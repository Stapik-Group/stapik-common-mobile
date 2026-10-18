package pl.stapik.common.sync

import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.DocumentCodec
import pl.stapik.common.model.DocumentDecodeException
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.EnvelopeFormatException
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SchemaGate
import pl.stapik.common.model.SlotKey

/**
 * Local-first synchronisation of one document slot.
 *
 * Every edit is saved on the device first and always succeeds; sending it to the cloud is a
 * separate, best-effort step. What to do when both sides changed is decided by [SyncPlanner];
 * this class performs the plan: reads, writes, conflict copies, retries after a 409.
 *
 * All operations on one engine are serialised by a mutex (single flight).
 *
 * @param cloudProvider the current cloud connection, or null when none is configured.
 */
class SyncEngine<T>(
    private val slot: SlotKey,
    private val codec: DocumentCodec<T>,
    private val store: DocumentStore,
    private val cloudProvider: suspend () -> CloudGateway?,
    private val clock: Clock,
    private val schemaGate: SchemaGate? = null,
) : DocumentReplacer {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(SyncStatus.IDLE)

    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    /** The local document; the app's defaults when nothing has been saved yet. */
    suspend fun load(): T = mutex.withLock { codec.decode(loadLocal().content) }

    /**
     * Applies [transform] to the local document, saves it locally, then tries to synchronise.
     * The edit is kept whatever the returned outcome is, except [SyncOutcome.NewerSchema]: a
     * document written with a newer schema than the app understands is never edited.
     */
    suspend fun edit(transform: (T) -> T): SyncOutcome {
        mutex.withLock {
            val local = loadLocal()
            if (schemaGate != null) {
                val newer = schemaGate.newerVersionOf(local.content)
                if (newer != null) return SyncOutcome.NewerSchema(newer, schemaGate.supported)
            }
            val updated = transform(codec.decode(local.content))
            val now = clock.instant()
            val envelope = codec.encode(updated, local.content, now.truncatedTo(ChronoUnit.SECONDS).toString())
            store.save(local.edited(envelope, now))
        }
        return sync()
    }

    /**
     * Replaces the whole document (import of a backup, restore). The new content is stamped as the
     * latest local change, saved, then synchronised like an edit.
     */
    override suspend fun replaceDocument(envelope: DocumentEnvelope): SyncOutcome {
        mutex.withLock {
            val local = loadLocal()
            val now = clock.instant()
            val stamped = envelope.withPayload(envelope.payload, now.truncatedTo(ChronoUnit.SECONDS).toString())
            store.save(local.edited(stamped, now))
        }
        return sync()
    }

    /** Synchronises with the cloud, if one is configured. [choice] answers [SyncOutcome.NeedsFirstConnectionChoice]. */
    suspend fun sync(choice: FirstConnectionChoice? = null): SyncOutcome {
        mutableStatus.value = SyncStatus.SYNCING
        val outcome = try {
            mutex.withLock { runSync(choice) }
        } catch (e: CancellationException) {
            mutableStatus.value = SyncStatus.IDLE
            throw e
        } catch (e: Exception) {
            mutableStatus.value = SyncStatus.ERROR
            throw e
        }
        mutableStatus.value = outcome.toStatus()
        return outcome
    }

    /** Whether there are local changes not yet sent to the cloud. */
    suspend fun hasPendingChanges(): Boolean = mutex.withLock { loadLocal().dirty }

    private suspend fun loadLocal(): LocalDocument<DocumentEnvelope> =
        store.load() ?: LocalDocument(DocumentEnvelope.empty(""))

    private fun hasData(envelope: DocumentEnvelope): Boolean = !codec.isEmpty(codec.decode(envelope))

    private suspend fun runSync(choice: FirstConnectionChoice?): SyncOutcome {
        val gateway = cloudProvider() ?: return SyncOutcome.LocalOnly
        return try {
            Run(gateway, gateway.info(), choice).execute()
        } catch (e: CloudException) {
            e.error.toOutcome()
        } catch (e: UnreadableRemote) {
            SyncOutcome.RemoteUnreadable(e.message.orEmpty())
        }
    }

    private class UnreadableRemote(reason: String, cause: Throwable? = null) : Exception(reason, cause)

    /** What the cloud holds right now, parsed. */
    private class Remote(val state: RemoteState, val document: RemoteDocument?, val envelope: DocumentEnvelope?)

    /** One synchronisation run against one connection. */
    private inner class Run(
        private val gateway: CloudGateway,
        private val info: CloudInfo,
        private val choice: FirstConnectionChoice?,
    ) {
        /** `updatedAt` values of cloud versions already saved as conflict copies in this run. */
        private val savedCloudCopies = mutableSetOf<String>()

        suspend fun execute(): SyncOutcome {
            repeat(MAX_ATTEMPTS) {
                val outcome = attempt()
                if (outcome != null) return outcome
            }
            // The server kept changing under us: stop competing, the server version wins.
            val local = loadLocal()
            val remote = readRemote()
            if (remote.document == null) return SyncOutcome.Offline(CloudError.Conflict)
            return applyRemote(local, remote, keepLocalAsCopy = true)
        }

        /** Returns null when the write lost a race (409) and the attempt should be repeated. */
        private suspend fun attempt(): SyncOutcome? {
            val local = loadLocal()
            val remote = readRemote()
            val plan = SyncPlanner.plan(local, hasData(local.content), remote.state, info, choice)
            return when (plan) {
                SyncPlan.UpToDate -> {
                    store.save(withCloudInfo(local))
                    SyncOutcome.Synchronized
                }
                SyncPlan.KeepLocalUnsynced -> {
                    store.save(withCloudInfo(local))
                    SyncOutcome.KeptLocalUnsynced
                }
                is SyncPlan.UseRemote -> applyRemote(local, remote, plan.keepLocalAsConflictCopy)
                is SyncPlan.PushLocal -> push(local, remote, plan.keepRemoteAsConflictCopy)
                is SyncPlan.NeedsFirstConnectionChoice -> SyncOutcome.NeedsFirstConnectionChoice(plan.canKeepLocal)
            }
        }

        private suspend fun readRemote(): Remote {
            val document = gateway.read(slot) ?: return Remote(RemoteState.Absent, null, null)
            val envelope = try {
                DocumentEnvelope.parse(document.content)
            } catch (e: EnvelopeFormatException) {
                throw UnreadableRemote("Cloud document is not a valid envelope: ${e.message}", e)
            }
            val present = try {
                hasData(envelope)
            } catch (e: DocumentDecodeException) {
                throw UnreadableRemote("Cloud document does not match this app's model", e)
            }
            return Remote(RemoteState.Present(document.updatedAt, present), document, envelope)
        }

        private suspend fun applyRemote(
            local: LocalDocument<DocumentEnvelope>,
            remote: Remote,
            keepLocalAsCopy: Boolean,
        ): SyncOutcome {
            val document = checkNotNull(remote.document)
            val envelope = checkNotNull(remote.envelope)
            if (keepLocalAsCopy) {
                // The copy is written before the local document is overwritten.
                store.saveConflictCopy(ConflictCopy(slot, ConflictSource.LOCAL, clock.instant(), local.content.toJson()))
            }
            store.save(LocalDocument(envelope).synchronized(envelope, document.updatedAt, info.scope, info.identity))
            return if (keepLocalAsCopy) SyncOutcome.ServerWon else SyncOutcome.Synchronized
        }

        private suspend fun push(
            local: LocalDocument<DocumentEnvelope>,
            remote: Remote,
            keepRemoteAsCopy: Boolean,
        ): SyncOutcome? {
            val remoteDocument = remote.document
            if (keepRemoteAsCopy && remoteDocument != null && savedCloudCopies.add(remoteDocument.updatedAt)) {
                store.saveConflictCopy(ConflictCopy(slot, ConflictSource.CLOUD, clock.instant(), remoteDocument.content))
            }
            val written = try {
                gateway.write(slot, local.content.toJson(), remoteDocument?.updatedAt.orEmpty())
            } catch (e: CloudException) {
                if (e.error == CloudError.Conflict) return null
                throw e
            }
            store.save(local.synchronized(local.content, written.updatedAt, info.scope, info.identity))
            return if (keepRemoteAsCopy) SyncOutcome.LocalWon else SyncOutcome.Synchronized
        }

        /** Records the scope seen now; a synchronised document without a recorded identity gets this cloud's. */
        private fun withCloudInfo(local: LocalDocument<DocumentEnvelope>): LocalDocument<DocumentEnvelope> =
            local.copy(
                scope = info.scope,
                cloudIdentity = if (local.everSynchronized) local.cloudIdentity ?: info.identity else local.cloudIdentity,
            )
    }

    private fun CloudError.toOutcome(): SyncOutcome = when (this) {
        CloudError.Unauthorized, CloudError.Forbidden -> SyncOutcome.AuthProblem(this)
        is CloudError.Unreadable -> SyncOutcome.RemoteUnreadable(reason, kind)
        CloudError.Conflict, is CloudError.RateLimited, is CloudError.ServerError, is CloudError.Network ->
            SyncOutcome.Offline(this)
    }

    private fun SyncOutcome.toStatus(): SyncStatus = when (this) {
        SyncOutcome.LocalOnly, SyncOutcome.Synchronized, SyncOutcome.ServerWon, SyncOutcome.LocalWon,
        SyncOutcome.KeptLocalUnsynced -> SyncStatus.IDLE
        is SyncOutcome.Offline -> SyncStatus.OFFLINE
        is SyncOutcome.NeedsFirstConnectionChoice -> SyncStatus.NEEDS_CHOICE
        is SyncOutcome.AuthProblem, is SyncOutcome.RemoteUnreadable, is SyncOutcome.NewerSchema -> SyncStatus.ERROR
    }

    private companion object {
        /** After this many lost races the server version wins (plan 4.4). */
        const val MAX_ATTEMPTS = 3
    }
}
