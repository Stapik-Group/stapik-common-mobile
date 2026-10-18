package pl.stapik.common.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import pl.stapik.common.ui.theme.StapikTheme

/** A button in the look of the theme. [emphasized] is the main action of a screen; [busy] shows a spinner and ignores taps. */
@Composable
fun StapikButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    emphasized: Boolean = false,
) {
    val colors = StapikTheme.colors
    val scheme = MaterialTheme.colorScheme
    val background = if (emphasized) scheme.primary else colors.button
    val content = if (emphasized) scheme.onPrimary else scheme.onBackground
    Box(
        modifier = modifier
            .themedSurface(colors, background, raised = true)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = content, strokeWidth = 2.dp)
        } else {
            Text(text, color = content, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A text field with a label. [secret] hides the text unless [revealed]; [error] is shown under the field
 * and [hint] when there is no error.
 */
@Composable
fun StapikTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    revealed: Boolean = false,
    error: String? = null,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    val message = error ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = if (message != null) ({ Text(message) }) else null,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
    )
}
