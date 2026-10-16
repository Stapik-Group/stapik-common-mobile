package pl.stapik.common.sync

/** What the cloud currently holds for a document slot, as seen by the sync logic. */
sealed interface RemoteState {
    /** The slot has no document yet. */
    data object Absent : RemoteState

    /**
     * @property updatedAt server timestamp of the stored document.
     * @property hasData whether the document holds user data (the app's `isEmpty` said no).
     */
    data class Present(val updatedAt: String, val hasData: Boolean) : RemoteState
}

/** The user's answer to the one question the library asks: what to do on the first connection. */
enum class FirstConnectionChoice { USE_CLOUD, SEND_LOCAL }

/** The decision of [SyncPlanner]; carrying it out (I/O, conflict copies) is the engine's job. */
sealed interface SyncPlan {
    /** Local and cloud already agree; nothing to do. */
    data object UpToDate : SyncPlan

    /** There is something local that cannot be sent now (read-only key, or no write possible). */
    data object KeepLocalUnsynced : SyncPlan

    /** Replace the local document with the cloud one; optionally keep the local one as a conflict copy. */
    data class UseRemote(val keepLocalAsConflictCopy: Boolean) : SyncPlan

    /** Send the local document; optionally keep the cloud one as a conflict copy first. */
    data class PushLocal(val keepRemoteAsConflictCopy: Boolean) : SyncPlan

    /** First connection with data on both sides: the user must choose. */
    data class NeedsFirstConnectionChoice(val canKeepLocal: Boolean) : SyncPlan
}
