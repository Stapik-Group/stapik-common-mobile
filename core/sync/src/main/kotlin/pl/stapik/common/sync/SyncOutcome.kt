package pl.stapik.common.sync

/** What a synchronisation attempt ended with. */
sealed interface SyncOutcome {
    /** No cloud is configured; the data stays on this device. */
    data object LocalOnly : SyncOutcome

    /** Local and cloud agree now (nothing to do, pulled, or pushed). */
    data object Synchronized : SyncOutcome

    /** Both sides had changes; the cloud version won and the local one is kept as a conflict copy. */
    data object ServerWon : SyncOutcome

    /** Both sides had changes; the local version won and the cloud one is kept as a conflict copy. */
    data object LocalWon : SyncOutcome

    /** There are local changes that cannot be sent (the key is read-only). */
    data object KeptLocalUnsynced : SyncOutcome

    /** The cloud is unreachable or busy; local changes wait for the next attempt. */
    data class Offline(val cause: CloudError) : SyncOutcome

    /** The key was rejected; the user has to reconnect. */
    data class AuthProblem(val cause: CloudError) : SyncOutcome

    /** First connection with data on both sides; call `sync` again with the user's choice. */
    data class NeedsFirstConnectionChoice(val canKeepLocal: Boolean) : SyncOutcome

    /** The cloud document cannot be read; nothing was written anywhere. */
    data class RemoteUnreadable(val reason: String, val kind: UnreadableKind = UnreadableKind.OTHER) : SyncOutcome
}

/** Coarse state for the UI. */
enum class SyncStatus { IDLE, SYNCING, OFFLINE, NEEDS_CHOICE, ERROR }
