package pl.stapik.common.presentation

import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.SyncOutcome
import pl.stapik.common.sync.SyncStatus
import pl.stapik.common.sync.UnreadableKind

enum class SyncBadge { LOCAL_ONLY, SYNCED, SYNCING, PENDING, OFFLINE, NEEDS_CHOICE, READ_ONLY, AUTH, UNREADABLE, NEWER_SCHEMA }

/** What the user can do about the current state; the UI turns each into a button. */
enum class SyncAction { RETRY, RECONNECT, ENTER_PASSPHRASE, CHOOSE_SOURCE, UPDATE_APP }

/**
 * What the sync indicator shows.
 *
 * @property messageKey key of the text to show (resolved by the i18n layer; not an Android resource id).
 * @property retryAfterSeconds when the server asked to wait, for the message.
 */
data class SyncUiState(
    val badge: SyncBadge,
    val messageKey: String,
    val actions: Set<SyncAction> = emptySet(),
    val retryAfterSeconds: Long? = null,
)

/** Turns the engine's state into [SyncUiState]. Pure. */
object SyncUiMapper {
    /**
     * @param status the engine's coarse status.
     * @param last how the last synchronisation ended, null if none ran yet in this session.
     * @param hasPendingChanges there are local changes not yet in the cloud.
     * @param connected a cloud is configured.
     */
    fun map(status: SyncStatus, last: SyncOutcome?, hasPendingChanges: Boolean, connected: Boolean): SyncUiState {
        if (status == SyncStatus.SYNCING) return SyncUiState(SyncBadge.SYNCING, "sync.syncing")
        if (!connected) return SyncUiState(SyncBadge.LOCAL_ONLY, "sync.local_only")
        return when (last) {
            null -> if (hasPendingChanges) SyncUiState(SyncBadge.PENDING, "sync.pending", setOf(SyncAction.RETRY))
            else SyncUiState(SyncBadge.SYNCED, "sync.synced")
            SyncOutcome.LocalOnly -> SyncUiState(SyncBadge.LOCAL_ONLY, "sync.local_only")
            SyncOutcome.Synchronized -> SyncUiState(SyncBadge.SYNCED, "sync.synced")
            SyncOutcome.ServerWon -> SyncUiState(SyncBadge.SYNCED, "sync.resolved.server")
            SyncOutcome.LocalWon -> SyncUiState(SyncBadge.SYNCED, "sync.resolved.local")
            SyncOutcome.KeptLocalUnsynced -> SyncUiState(SyncBadge.READ_ONLY, "sync.read_only", setOf(SyncAction.RECONNECT))
            is SyncOutcome.Offline -> offline(last.cause)
            is SyncOutcome.AuthProblem -> SyncUiState(
                SyncBadge.AUTH,
                if (last.cause == CloudError.Forbidden) "sync.auth.forbidden" else "sync.auth.unauthorized",
                setOf(SyncAction.RECONNECT),
            )
            is SyncOutcome.NeedsFirstConnectionChoice ->
                SyncUiState(SyncBadge.NEEDS_CHOICE, "sync.needs_choice", setOf(SyncAction.CHOOSE_SOURCE))
            is SyncOutcome.RemoteUnreadable -> unreadable(last.kind)
            is SyncOutcome.NewerSchema -> SyncUiState(SyncBadge.NEWER_SCHEMA, "sync.newer_schema", setOf(SyncAction.UPDATE_APP))
        }
    }

    private fun offline(cause: CloudError): SyncUiState {
        val key = when (cause) {
            is CloudError.RateLimited -> "sync.offline.rate_limited"
            is CloudError.ServerError -> "sync.offline.server"
            CloudError.Conflict -> "sync.offline.conflict"
            else -> "sync.offline.network"
        }
        return SyncUiState(SyncBadge.OFFLINE, key, setOf(SyncAction.RETRY), (cause as? CloudError.RateLimited)?.retryAfterSeconds)
    }

    private fun unreadable(kind: UnreadableKind): SyncUiState = when (kind) {
        UnreadableKind.PASSPHRASE_REQUIRED ->
            SyncUiState(SyncBadge.UNREADABLE, "sync.unreadable.passphrase_required", setOf(SyncAction.ENTER_PASSPHRASE))
        UnreadableKind.DECRYPTION_FAILED ->
            SyncUiState(SyncBadge.UNREADABLE, "sync.unreadable.wrong_passphrase", setOf(SyncAction.ENTER_PASSPHRASE))
        UnreadableKind.UNSUPPORTED_VERSION ->
            SyncUiState(SyncBadge.UNREADABLE, "sync.unreadable.newer_version", setOf(SyncAction.UPDATE_APP))
        UnreadableKind.OTHER ->
            SyncUiState(SyncBadge.UNREADABLE, "sync.unreadable.other", setOf(SyncAction.RETRY))
    }
}
