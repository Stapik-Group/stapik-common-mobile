package pl.stapik.common.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import pl.stapik.common.i18n.Messages
import pl.stapik.common.presentation.SyncAction
import pl.stapik.common.presentation.SyncTone
import pl.stapik.common.presentation.SyncUiState
import pl.stapik.common.presentation.isQuiet
import pl.stapik.common.presentation.orderedActions
import pl.stapik.common.presentation.tone
import pl.stapik.common.ui.components.BannerAction
import pl.stapik.common.ui.components.StapikBanner
import pl.stapik.common.ui.theme.StapikTheme

/**
 * The sync indicator: a coloured dot, the message and a button for each thing the user can do.
 * Nothing is drawn while everything is synced unless [showWhenSynced] is set.
 */
@Composable
fun SyncStatusBanner(
    state: SyncUiState,
    onAction: (SyncAction) -> Unit,
    modifier: Modifier = Modifier,
    showWhenSynced: Boolean = false,
) {
    if (state.isQuiet && !showWhenSynced) return
    val context = LocalContext.current
    val colors = StapikTheme.colors
    val dot: Color = when (state.badge.tone) {
        SyncTone.MUTED -> colors.textMuted
        SyncTone.ACTIVE -> colors.syncSyncing
        SyncTone.WARNING -> colors.syncConflict
        SyncTone.ERROR -> colors.syncError
    }
    StapikBanner(
        text = Messages.text(context, state.messageKey),
        dotColor = dot,
        modifier = modifier,
        actions = state.orderedActions.map { action ->
            BannerAction(Messages.text(context, "action.${action.name.lowercase()}")) { onAction(action) }
        },
    )
}
