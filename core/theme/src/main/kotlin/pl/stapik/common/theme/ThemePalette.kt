package pl.stapik.common.theme

/** A colour as opaque 0xRRGGBB. Kept as a number so this module has no UI dependency. */
typealias Rgb = Int

/** Background and the text colour that goes on it. */
data class ColorPair(val background: Rgb, val foreground: Rgb)

/** The ten entry/category colours of the desktop themes. */
enum class CategoryColor { RED, GREEN, BLUE, YELLOW, PURPLE, ORANGE, BROWN, PINK, TEAL, GRAY }

/**
 * Identifiers are the ones the desktop stores in its settings (`Theme.cpp`), so a theme name can
 * travel between the desktop and the apps.
 */
enum class ThemeId(val id: String) {
    CLASSIC("classic"),
    CLASSIC_PINK("classic-pink"),
    MODERN("modern"),
    DARK("dark"),
    NEOCLASSIC("neoclassic");

    companion object {
        /** Unknown or missing id gives [default]. */
        fun fromId(id: String?, default: ThemeId = MODERN): ThemeId =
            entries.firstOrNull { it.id == id } ?: default
    }
}

/**
 * Colours of one theme, taken from the desktop `resources/themes (the CSS files)`.
 *
 * [border] is the single border colour; [borderLight] and [borderDark] are the two sides of a
 * bevel and are equal to [border] in themes without one ([usesBevel] false).
 */
data class ThemePalette(
    val id: ThemeId,
    val isDark: Boolean,
    val usesBevel: Boolean,
    val cornerRadiusDp: Int,
    val background: Rgb,
    val surface: Rgb,
    val text: Rgb,
    val textMuted: Rgb,
    /** Background of buttons (the desktop's `stapik_btn_*`; the window colour in the classic themes). */
    val button: Rgb,
    val border: Rgb,
    val borderLight: Rgb,
    val borderDark: Rgb,
    val accent: Rgb,
    val onAccent: Rgb,
    val syncSyncing: Rgb,
    val syncConflict: Rgb,
    val syncError: Rgb,
    val categories: Map<CategoryColor, ColorPair>,
)
