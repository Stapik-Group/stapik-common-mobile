package pl.stapik.common.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.stapik.common.theme.CategoryColor
import pl.stapik.common.theme.ColorPair
import pl.stapik.common.theme.Rgb
import pl.stapik.common.theme.ThemeId
import pl.stapik.common.theme.ThemePalette
import pl.stapik.common.theme.ThemePalettes

/** A background with the colour of the text that goes on it. */
@Immutable
data class ColorOnColor(val background: Color, val content: Color)

/**
 * Colours and shape of the current theme that Material 3 has no slot for. The ones Material has
 * (background, surface, primary, ...) are in [MaterialTheme.colorScheme].
 */
@Immutable
data class StapikColors(
    val palette: ThemePalette,
    val textMuted: Color,
    /** Background of buttons. */
    val button: Color,
    val borderLight: Color,
    val borderDark: Color,
    val syncSyncing: Color,
    val syncConflict: Color,
    val syncError: Color,
    val categories: Map<CategoryColor, ColorOnColor>,
) {
    val id: ThemeId get() = palette.id
    val usesBevel: Boolean get() = palette.usesBevel
    val cornerRadius: Dp get() = palette.cornerRadiusDp.dp
    val isDark: Boolean get() = palette.isDark
}

private fun Rgb.color(): Color = Color(0xFF000000.toInt() or this)

private fun ColorPair.color() = ColorOnColor(background.color(), foreground.color())

fun ThemePalette.toStapikColors(): StapikColors = StapikColors(
    palette = this,
    textMuted = textMuted.color(),
    button = button.color(),
    borderLight = borderLight.color(),
    borderDark = borderDark.color(),
    syncSyncing = syncSyncing.color(),
    syncConflict = syncConflict.color(),
    syncError = syncError.color(),
    categories = categories.mapValues { it.value.color() },
)

/** Maps the palette to Material 3 slots, so stock components (buttons, fields, dialogs) follow the theme. */
fun ThemePalette.toMaterialColorScheme(): ColorScheme {
    val bg = background.color()
    val fg = text.color()
    return if (isDark) {
        darkColorScheme(
            primary = accent.color(), onPrimary = onAccent.color(),
            background = bg, onBackground = fg,
            surface = surface.color(), onSurface = fg,
            surfaceVariant = surface.color(), onSurfaceVariant = textMuted.color(),
            outline = border.color(), outlineVariant = border.color(),
            error = syncError.color(),
        )
    } else {
        lightColorScheme(
            primary = accent.color(), onPrimary = onAccent.color(),
            background = bg, onBackground = fg,
            surface = surface.color(), onSurface = fg,
            surfaceVariant = surface.color(), onSurfaceVariant = textMuted.color(),
            outline = border.color(), outlineVariant = border.color(),
            error = syncError.color(),
        )
    }
}

private val DefaultColors = ThemePalettes.of(ThemeId.MODERN).toStapikColors()

val LocalStapikColors = staticCompositionLocalOf { DefaultColors }

/** Access to the theme parts that are not in Material: `StapikTheme.colors.syncError`. */
object StapikTheme {
    val colors: StapikColors
        @Composable @ReadOnlyComposable get() = LocalStapikColors.current
}

/** Applies a theme to [content]: Material colours plus [StapikTheme.colors]. */
@Composable
fun StapikTheme(themeId: ThemeId, content: @Composable () -> Unit) {
    val colors = remember(themeId) { ThemePalettes.of(themeId).toStapikColors() }
    val scheme = remember(themeId) { ThemePalettes.of(themeId).toMaterialColorScheme() }
    CompositionLocalProvider(LocalStapikColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
