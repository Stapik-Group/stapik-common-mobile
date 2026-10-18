package pl.stapik.common.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import pl.stapik.common.i18n.R
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.presentation.ConnectController
import pl.stapik.common.theme.ThemeId
import pl.stapik.common.ui.components.StapikScreen
import pl.stapik.common.ui.components.StapikButton
import pl.stapik.common.ui.connect.ConnectRoute

private enum class SettingsPage { HOME, CONNECT, THEME, ABOUT }

/**
 * The settings every Stapik app has: connect to the cloud, theme, about. It owns only the navigation between
 * them; the app owns the state (theme, connection) and is told about changes.
 *
 * @param extra extra rows of the home page for settings that only this app has (notifications, ...).
 * @param onClose leaves the settings (the back button of the home page and the system back).
 */
@Composable
fun SettingsFlow(
    app: AppDefinition,
    themeId: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
    connectController: ConnectController,
    connected: Boolean,
    onConnected: (CloudInfo) -> Unit,
    onDisconnected: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    BackHandler { if (page == SettingsPage.HOME) onClose() else page = SettingsPage.HOME }
    val back = { page = SettingsPage.HOME }
    when (page) {
        SettingsPage.HOME -> StapikScreen(
            title = stringResource(R.string.menu_settings),
            onBack = onClose,
            backDescription = stringResource(R.string.action_back),
            modifier = modifier,
        ) {
            StapikButton(stringResource(R.string.menu_connect), { page = SettingsPage.CONNECT }, Modifier.fillMaxWidth())
            StapikButton(stringResource(R.string.menu_theme), { page = SettingsPage.THEME }, Modifier.fillMaxWidth())
            extra()
            StapikButton(stringResource(R.string.menu_about), { page = SettingsPage.ABOUT }, Modifier.fillMaxWidth())
        }
        SettingsPage.CONNECT -> ConnectRoute(
            controller = connectController,
            connected = connected,
            onConnected = onConnected,
            onDisconnected = onDisconnected,
            onBack = back,
            modifier = modifier,
        )
        SettingsPage.THEME -> ThemeScreen(themeId, onThemeChange, back, modifier)
        SettingsPage.ABOUT -> AboutScreen(app, back, modifier)
    }
}
