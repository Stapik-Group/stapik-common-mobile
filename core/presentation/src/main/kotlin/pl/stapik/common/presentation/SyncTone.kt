package pl.stapik.common.presentation

/** How loud the sync indicator is; the UI maps each tone to a colour of the current theme. */
enum class SyncTone { MUTED, ACTIVE, WARNING, ERROR }

val SyncBadge.tone: SyncTone
    get() = when (this) {
        SyncBadge.LOCAL_ONLY, SyncBadge.SYNCED -> SyncTone.MUTED
        SyncBadge.SYNCING -> SyncTone.ACTIVE
        SyncBadge.PENDING, SyncBadge.OFFLINE, SyncBadge.NEEDS_CHOICE, SyncBadge.READ_ONLY -> SyncTone.WARNING
        SyncBadge.AUTH, SyncBadge.UNREADABLE, SyncBadge.NEWER_SCHEMA -> SyncTone.ERROR
    }

/** Buttons of an indicator in a fixed order: the most useful first (it is the emphasised one). */
val SyncUiState.orderedActions: List<SyncAction>
    get() = SyncAction.values().filter { it in actions }

/** Nothing worth showing: the indicator can be hidden. */
val SyncUiState.isQuiet: Boolean
    get() = badge == SyncBadge.SYNCED
