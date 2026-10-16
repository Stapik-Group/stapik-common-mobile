package pl.stapik.common.sync

import java.time.Instant
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.LocalDocument

/**
 * The conflict policy (plan 4.4) as a pure function: no I/O, no clock, fully testable.
 *
 * The user is asked only on the first connection to a cloud, and only when both sides hold data.
 * In every other case the decision is automatic and the losing version is never dropped: the plan
 * says when it must be kept as a conflict copy.
 */
object SyncPlanner {

    /**
     * @param localHasData whether the local document holds user data (the app's `isEmpty` said no).
     * @param choice the user's answer to a previous [SyncPlan.NeedsFirstConnectionChoice], if any.
     */
    fun plan(
        local: LocalDocument<*>,
        localHasData: Boolean,
        remote: RemoteState,
        cloud: CloudInfo,
        choice: FirstConnectionChoice? = null,
    ): SyncPlan {
        val canWrite = cloud.scope.canWrite
        return if (isFirstConnection(local, cloud)) {
            planFirstConnection(localHasData, remote, canWrite, choice)
        } else {
            planKnownCloud(local, localHasData, remote, canWrite)
        }
    }

    /** No baseline yet, or a baseline taken from a different cloud. */
    private fun isFirstConnection(local: LocalDocument<*>, cloud: CloudInfo): Boolean =
        !local.everSynchronized || (local.cloudIdentity != null && local.cloudIdentity != cloud.identity)

    private fun planFirstConnection(
        localHasData: Boolean,
        remote: RemoteState,
        canWrite: Boolean,
        choice: FirstConnectionChoice?,
    ): SyncPlan {
        val remoteHasData = remote is RemoteState.Present && remote.hasData
        return when {
            !remoteHasData -> when {
                localHasData && canWrite -> SyncPlan.PushLocal(keepRemoteAsConflictCopy = false)
                localHasData -> SyncPlan.KeepLocalUnsynced
                // Both sides empty: adopt the cloud document (gives us a baseline) or do nothing.
                remote is RemoteState.Present -> SyncPlan.UseRemote(keepLocalAsConflictCopy = false)
                else -> SyncPlan.UpToDate
            }
            !localHasData -> SyncPlan.UseRemote(keepLocalAsConflictCopy = false)
            else -> when (choice) {
                null -> SyncPlan.NeedsFirstConnectionChoice(canKeepLocal = canWrite)
                FirstConnectionChoice.USE_CLOUD -> SyncPlan.UseRemote(keepLocalAsConflictCopy = true)
                FirstConnectionChoice.SEND_LOCAL ->
                    if (canWrite) {
                        SyncPlan.PushLocal(keepRemoteAsConflictCopy = true)
                    } else {
                        SyncPlan.NeedsFirstConnectionChoice(canKeepLocal = false)
                    }
            }
        }
    }

    private fun planKnownCloud(
        local: LocalDocument<*>,
        localHasData: Boolean,
        remote: RemoteState,
        canWrite: Boolean,
    ): SyncPlan = when (remote) {
        RemoteState.Absent -> when {
            localHasData && canWrite -> SyncPlan.PushLocal(keepRemoteAsConflictCopy = false)
            localHasData -> SyncPlan.KeepLocalUnsynced
            else -> SyncPlan.UpToDate
        }
        is RemoteState.Present -> {
            val remoteChanged = remote.updatedAt != local.cloudBaseline
            when {
                !remoteChanged && !local.dirty -> SyncPlan.UpToDate
                !remoteChanged ->
                    if (canWrite) SyncPlan.PushLocal(keepRemoteAsConflictCopy = false) else SyncPlan.KeepLocalUnsynced
                !local.dirty -> SyncPlan.UseRemote(keepLocalAsConflictCopy = false)
                // Both sides changed: the newer one wins, the other becomes a conflict copy.
                canWrite && localIsNewer(local, remote) -> SyncPlan.PushLocal(keepRemoteAsConflictCopy = true)
                else -> SyncPlan.UseRemote(keepLocalAsConflictCopy = true)
            }
        }
    }

    /** Local wins when we cannot compare the times (missing or unparseable), like the calendar app does. */
    private fun localIsNewer(local: LocalDocument<*>, remote: RemoteState.Present): Boolean {
        val localTime = local.modifiedAt ?: return true
        val remoteTime = runCatching { Instant.parse(remote.updatedAt) }.getOrNull() ?: return true
        return localTime.isAfter(remoteTime)
    }
}
