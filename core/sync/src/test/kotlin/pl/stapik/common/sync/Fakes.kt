package pl.stapik.common.sync

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SlotKey

/** A clock the test moves by hand. */
class TestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** An in-memory Stapik Cloud with the same conflict rule as the real server. */
class FakeCloudGateway : CloudGateway {
    var info = CloudInfo(CloudScope.READ_WRITE, "cloud-1")
    var document: RemoteDocument? = null

    /** When set, every call fails with this error. */
    var failure: CloudError? = null

    /** Runs at the start of every write, before the conflict check; lets a test change the server. */
    var onBeforeWrite: (() -> Unit)? = null

    /** When set, [info] waits for it; lets a test hold a synchronisation half way. */
    var gate: CompletableDeferred<Unit>? = null

    val writes = mutableListOf<String>()
    private var stamp = Instant.parse("2026-10-20T00:00:00Z")

    private fun failIfNeeded() {
        failure?.let { throw CloudException(it) }
    }

    override suspend fun info(): CloudInfo {
        gate?.await()
        failIfNeeded()
        return info
    }

    override suspend fun read(slot: SlotKey): RemoteDocument? {
        failIfNeeded()
        return document
    }

    override suspend fun write(slot: SlotKey, content: String, baseline: String): RemoteDocument {
        failIfNeeded()
        onBeforeWrite?.invoke()
        if (document?.updatedAt.orEmpty() != baseline) throw CloudException(CloudError.Conflict)
        stamp = stamp.plusSeconds(1)
        writes += content
        return RemoteDocument(content, stamp.toString()).also { document = it }
    }
}

class InMemoryDocumentStore : DocumentStore {
    var document: LocalDocument<DocumentEnvelope>? = null
    val copies = mutableListOf<ConflictCopy>()

    override suspend fun load() = document

    override suspend fun save(document: LocalDocument<DocumentEnvelope>) {
        this.document = document
    }

    override suspend fun saveConflictCopy(copy: ConflictCopy) {
        copies += copy
    }

    override suspend fun discard() {
        document = null
    }
}
