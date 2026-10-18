package pl.stapik.common.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog with a title, a body and up to two buttons. With [dismissible] false only the buttons close it
 * (for decisions that must be made).
 */
@Composable
fun StapikDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = null,
    dismissible: Boolean = true,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
        title = { Text(title) },
        text = content,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = if (dismissLabel != null) ({ TextButton(onClick = onDismiss) { Text(dismissLabel) } }) else null,
    )
}
