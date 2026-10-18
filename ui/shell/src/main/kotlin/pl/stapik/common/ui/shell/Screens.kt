package pl.stapik.common.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import pl.stapik.common.i18n.R
import pl.stapik.common.theme.ThemeId
import pl.stapik.common.ui.components.StapikButton
import pl.stapik.common.ui.components.StapikOptionRow
import pl.stapik.common.ui.components.StapikScreen

/** Name of a theme in the current language. */
@Composable
fun themeLabel(id: ThemeId): String = stringResource(
    when (id) {
        ThemeId.CLASSIC -> R.string.theme_classic
        ThemeId.CLASSIC_PINK -> R.string.theme_classic_pink
        ThemeId.MODERN -> R.string.theme_modern
        ThemeId.DARK -> R.string.theme_dark
        ThemeId.NEOCLASSIC -> R.string.theme_neoclassic
    },
)

@Composable
fun ThemeScreen(selected: ThemeId, onSelect: (ThemeId) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    StapikScreen(
        title = stringResource(R.string.menu_theme),
        onBack = onBack,
        backDescription = stringResource(R.string.action_back),
        modifier = modifier,
    ) {
        ThemeId.entries.forEach { id ->
            StapikOptionRow(label = themeLabel(id), selected = id == selected, onClick = { onSelect(id) })
        }
    }
}

@Composable
fun AboutScreen(app: AppDefinition, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val color = MaterialTheme.colorScheme.onBackground
    StapikScreen(
        title = stringResource(R.string.menu_about),
        onBack = onBack,
        backDescription = stringResource(R.string.action_back),
        modifier = modifier,
    ) {
        Text(app.name, color = color, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.about_version, app.versionName), color = color)
        Text(stringResource(R.string.about_author, app.author), color = color)
        app.websiteUrl?.let { url ->
            StapikButton(
                text = stringResource(R.string.about_website),
                onClick = { uriHandler.openUri(url) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
