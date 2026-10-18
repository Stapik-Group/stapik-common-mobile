package pl.stapik.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.stapik.common.ui.theme.StapikTheme

/** Title bar with a back button, in the look of the theme. [backDescription] is the accessibility text of the button. */
@Composable
fun StapikScreenHeader(title: String, onBack: () -> Unit, backDescription: String, modifier: Modifier = Modifier) {
    val colors = StapikTheme.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(scheme.background)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .themedSurface(colors, colors.button, raised = true)
                .semantics { contentDescription = backDescription }
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("◀", color = scheme.onBackground)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .themedSurface(colors, scheme.primary, raised = false)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(title, color = scheme.onPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

/** A full screen with a header and a scrolling, padded body. */
@Composable
fun StapikScreen(
    title: String,
    onBack: () -> Unit,
    backDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        StapikScreenHeader(title, onBack, backDescription)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A row the user can pick; the selected one is drawn in the accent colour. */
@Composable
fun StapikOptionRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StapikTheme.colors
    val scheme = MaterialTheme.colorScheme
    val background = if (selected) scheme.primary else colors.button
    Box(
        modifier = modifier
            .fillMaxWidth()
            .themedSurface(colors, background, raised = !selected)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, color = if (selected) scheme.onPrimary else scheme.onBackground, fontWeight = FontWeight.Bold)
    }
}

/** A coloured dot and a message, with optional buttons: the building block of the sync indicator. */
data class BannerAction(val label: String, val onClick: () -> Unit)

@Composable
fun StapikBanner(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    actions: List<BannerAction> = emptyList(),
) {
    val colors = StapikTheme.colors
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .themedSurface(colors, scheme.surface, raised = false)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.padding(top = 5.dp).size(10.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(text, color = scheme.onSurface)
        }
        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEachIndexed { index, action ->
                    StapikButton(action.label, action.onClick, emphasized = index == 0)
                }
            }
        }
    }
}
