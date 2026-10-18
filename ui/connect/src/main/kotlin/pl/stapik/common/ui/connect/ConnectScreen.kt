package pl.stapik.common.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.stapik.common.i18n.Messages
import pl.stapik.common.i18n.R
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.CloudScope
import pl.stapik.common.presentation.ConnectController
import pl.stapik.common.presentation.ConnectFormState
import pl.stapik.common.presentation.ConnectOutcome
import pl.stapik.common.ui.components.StapikButton
import pl.stapik.common.ui.components.StapikDialog
import pl.stapik.common.ui.components.StapikScreen
import pl.stapik.common.ui.components.StapikTextField
import pl.stapik.common.ui.theme.StapikTheme

/**
 * The connect screen wired to a [ConnectController].
 *
 * @param connected a cloud is configured now; shows the disconnect button.
 * @param onConnected the check passed and the settings were saved; the app should start a synchronisation.
 * @param onDisconnected the saved connection was forgotten.
 */
@Composable
fun ConnectRoute(
    controller: ConnectController,
    connected: Boolean,
    onConnected: (CloudInfo) -> Unit,
    onDisconnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<CloudInfo?>(null) }
    ConnectScreen(
        form = form,
        info = info,
        connected = connected,
        onApiUrl = controller::setApiUrl,
        onApiKey = controller::setApiKey,
        onPassphrase = controller::setPassphrase,
        onToggleKey = controller::toggleKeyVisible,
        onConnect = {
            scope.launch {
                val outcome = controller.connect()
                if (outcome is ConnectOutcome.Connected) {
                    info = outcome.info
                    onConnected(outcome.info)
                }
            }
        },
        onDisconnect = {
            scope.launch {
                controller.disconnect()
                info = null
                onDisconnected()
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

/** The stateless connect form; [info] is shown after a successful check. */
@Composable
fun ConnectScreen(
    form: ConnectFormState,
    info: CloudInfo?,
    connected: Boolean,
    onApiUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onPassphrase: (String) -> Unit,
    onToggleKey: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }

    if (confirmDisconnect) {
        StapikDialog(
            title = stringResource(R.string.connect_disconnect_title),
            confirmLabel = stringResource(R.string.connect_disconnect),
            onConfirm = {
                confirmDisconnect = false
                onDisconnect()
            },
            onDismiss = { confirmDisconnect = false },
            dismissLabel = stringResource(R.string.action_cancel),
        ) {
            Text(stringResource(R.string.connect_disconnect_message))
        }
    }

    StapikScreen(
        title = stringResource(R.string.connect_title),
        onBack = onBack,
        backDescription = stringResource(R.string.action_back),
        modifier = modifier,
    ) {
        val urlError = if (form.showInvalidUrl) stringResource(R.string.connect_invalid_url) else null
        val urlHint = if (form.showInsecureWarning) stringResource(R.string.connect_insecure_warning) else null
        StapikTextField(
            value = form.apiUrl,
            onValueChange = onApiUrl,
            label = stringResource(R.string.connect_url),
            modifier = Modifier.fillMaxWidth(),
            error = urlError,
            hint = urlHint,
            keyboardType = KeyboardType.Uri,
            enabled = !form.busy,
        )
        StapikTextField(
            value = form.apiKey,
            onValueChange = onApiKey,
            label = stringResource(R.string.connect_key),
            modifier = Modifier.fillMaxWidth(),
            secret = true,
            revealed = form.keyVisible,
            enabled = !form.busy,
        )
        StapikTextField(
            value = form.passphrase,
            onValueChange = onPassphrase,
            label = stringResource(R.string.connect_passphrase),
            modifier = Modifier.fillMaxWidth(),
            secret = true,
            revealed = form.keyVisible,
            hint = stringResource(R.string.connect_passphrase_hint),
            enabled = !form.busy,
        )
        StapikButton(
            text = stringResource(if (form.keyVisible) R.string.connect_key_hide else R.string.connect_key_show),
            onClick = onToggleKey,
        )
        form.errorKey?.let { key ->
            Text(Messages.text(context, key), color = StapikTheme.colors.syncError)
        }
        info?.let {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.connect_connected, form.apiUrl),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(
                        if (it.scope == CloudScope.READ_WRITE) R.string.connect_scope_read_write else R.string.connect_scope_read_only,
                    ),
                    color = StapikTheme.colors.textMuted,
                )
            }
        }
        StapikButton(
            text = stringResource(R.string.connect_submit),
            onClick = onConnect,
            enabled = form.canSubmit,
            busy = form.busy,
            emphasized = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (connected) {
            StapikButton(
                text = stringResource(R.string.connect_disconnect),
                onClick = { confirmDisconnect = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
