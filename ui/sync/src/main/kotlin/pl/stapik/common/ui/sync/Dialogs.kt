package pl.stapik.common.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.stapik.common.i18n.R
import pl.stapik.common.presentation.LoadErrorKind
import pl.stapik.common.sync.FirstConnectionChoice
import pl.stapik.common.ui.components.StapikButton
import pl.stapik.common.ui.components.StapikDialog

/**
 * The question asked once, when this device and the cloud both have data. It cannot be dismissed:
 * [onChoose] receives the answer, and [onCancel] is for the explicit "not now" button.
 */
@Composable
fun FirstConnectionDialog(
    canKeepLocal: Boolean,
    onChoose: (FirstConnectionChoice) -> Unit,
    onCancel: () -> Unit,
) {
    StapikDialog(
        title = stringResource(R.string.choice_title),
        confirmLabel = stringResource(R.string.choice_use_cloud),
        onConfirm = { onChoose(FirstConnectionChoice.USE_CLOUD) },
        onDismiss = onCancel,
        dismissLabel = stringResource(R.string.action_cancel),
        dismissible = false,
    ) {
        Column {
            Text(stringResource(R.string.choice_message))
            Spacer(Modifier.height(12.dp))
            if (canKeepLocal) {
                StapikButton(
                    text = stringResource(R.string.choice_send_local),
                    onClick = { onChoose(FirstConnectionChoice.SEND_LOCAL) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(stringResource(R.string.choice_read_only))
            }
        }
    }
}

/**
 * What to show when the document cannot be loaded. With [onDiscardLocal] and an error that is about
 * unreadable data, it also offers (after a confirmation) to delete that data and download the
 * document from the cloud again.
 */
@Composable
fun LoadErrorMessage(kind: LoadErrorKind, modifier: Modifier = Modifier, onDiscardLocal: (() -> Unit)? = null) {
    val text = when (kind) {
        LoadErrorKind.CORRUPTED -> stringResource(R.string.load_error_corrupted)
        LoadErrorKind.UNDECRYPTABLE -> stringResource(R.string.load_error_undecryptable)
        LoadErrorKind.MODEL_MISMATCH -> stringResource(R.string.load_error_model_mismatch)
        LoadErrorKind.UNKNOWN -> stringResource(R.string.load_error_unknown)
    }
    var confirming by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text)
        if (onDiscardLocal != null && kind.hasUnreadableData) {
            Spacer(Modifier.height(16.dp))
            StapikButton(text = stringResource(R.string.load_error_discard_action), onClick = { confirming = true })
        }
    }
    if (confirming && onDiscardLocal != null) {
        StapikDialog(
            title = stringResource(R.string.load_error_discard_title),
            confirmLabel = stringResource(R.string.load_error_discard_confirm),
            onConfirm = {
                confirming = false
                onDiscardLocal()
            },
            onDismiss = { confirming = false },
            dismissLabel = stringResource(R.string.action_cancel),
        ) {
            Text(stringResource(R.string.load_error_discard_message))
        }
    }
}

private val LoadErrorKind.hasUnreadableData: Boolean
    get() = this == LoadErrorKind.CORRUPTED || this == LoadErrorKind.UNDECRYPTABLE
