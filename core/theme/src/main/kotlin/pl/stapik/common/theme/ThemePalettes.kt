package pl.stapik.common.theme

import pl.stapik.common.theme.CategoryColor.*

/** The five desktop themes. Values are copied from the desktop CSS; see each block for the source variable. */
object ThemePalettes {

    fun of(id: ThemeId): ThemePalette = when (id) {
        ThemeId.CLASSIC -> classic
        ThemeId.CLASSIC_PINK -> classicPink
        ThemeId.MODERN -> modern
        ThemeId.DARK -> dark
        ThemeId.NEOCLASSIC -> neoclassic
    }

    val all: List<ThemePalette> get() = ThemeId.entries.map(::of)

    private fun cats(vararg pairs: Triple<CategoryColor, Rgb, Rgb>): Map<CategoryColor, ColorPair> =
        pairs.associate { (c, bg, fg) -> c to ColorPair(bg, fg) }

    private const val WHITE = 0xFFFFFF

    val classic = ThemePalette(
        id = ThemeId.CLASSIC, isDark = false, usesBevel = true, cornerRadiusDp = 0,
        background = 0xC0C0C0, surface = WHITE, text = 0x000000, textMuted = 0x404040,
        border = 0x808080, borderLight = 0xFFFFFF, borderDark = 0x808080,
        accent = 0x000080, onAccent = WHITE,
        syncSyncing = 0x000080, syncConflict = 0x804000, syncError = 0x800000,
        categories = cats(
            Triple(RED, 0x800000, WHITE), Triple(GREEN, 0x008000, WHITE), Triple(BLUE, 0x000080, WHITE),
            Triple(YELLOW, 0x808000, 0x000000), Triple(PURPLE, 0x800080, WHITE), Triple(ORANGE, 0xFF8000, 0x000000),
            Triple(BROWN, 0x804000, WHITE), Triple(PINK, 0xC080A0, 0x000000), Triple(TEAL, 0x008080, WHITE),
            Triple(GRAY, 0x808080, WHITE),
        ),
    )

    val classicPink = ThemePalette(
        id = ThemeId.CLASSIC_PINK, isDark = false, usesBevel = true, cornerRadiusDp = 0,
        background = 0xF4B6DD, surface = 0xF9CBE6, text = 0x4A0E4E, textMuted = 0x8C3A72,
        border = 0x8C3A72, borderLight = 0xFFE0F3, borderDark = 0x8C3A72,
        accent = 0x7B2FF7, onAccent = WHITE,
        syncSyncing = 0x7B2FF7, syncConflict = 0xB5836B, syncError = 0xA0003C,
        categories = cats(
            Triple(RED, 0xFF6EC7, WHITE), Triple(GREEN, 0x00E5A0, 0x1A1A2E), Triple(BLUE, 0x00C8FF, 0x1A1A2E),
            Triple(YELLOW, 0xFFF65E, 0x4A0E4E), Triple(PURPLE, 0x7B2FF7, WHITE), Triple(ORANGE, 0xFF9F6E, 0x4A0E4E),
            Triple(BROWN, 0xB5836B, WHITE), Triple(PINK, 0xFF2FD0, WHITE), Triple(TEAL, 0x00E5FF, 0x1A1A2E),
            Triple(GRAY, 0xB7A3B3, 0x4A0E4E),
        ),
    )

    val modern = ThemePalette(
        id = ThemeId.MODERN, isDark = false, usesBevel = false, cornerRadiusDp = 12,
        background = 0xF5F5F7, surface = WHITE, text = 0x1C1C1E, textMuted = 0x6E6E73,
        border = 0xE2E2E5, borderLight = 0xE2E2E5, borderDark = 0xE2E2E5,
        accent = 0x007AFF, onAccent = WHITE,
        syncSyncing = 0x007AFF, syncConflict = 0xFF9500, syncError = 0xFF3B30,
        categories = cats(
            Triple(RED, 0xFF453A, WHITE), Triple(GREEN, 0x34C759, WHITE), Triple(BLUE, 0x007AFF, WHITE),
            Triple(YELLOW, 0xFFCC00, 0x1C1C1E), Triple(PURPLE, 0xAF52DE, WHITE), Triple(ORANGE, 0xFF9500, WHITE),
            Triple(BROWN, 0xA2845E, WHITE), Triple(PINK, 0xFF2D55, WHITE), Triple(TEAL, 0x30B0C7, WHITE),
            Triple(GRAY, 0x8E8E93, WHITE),
        ),
    )

    val dark = ThemePalette(
        id = ThemeId.DARK, isDark = true, usesBevel = false, cornerRadiusDp = 12,
        background = 0x1C1C1E, surface = 0x2C2C2E, text = 0xF2F2F7, textMuted = 0x98989F,
        border = 0x3A3A3C, borderLight = 0x3A3A3C, borderDark = 0x3A3A3C,
        accent = 0x0A84FF, onAccent = WHITE,
        syncSyncing = 0x0A84FF, syncConflict = 0xFF9F0A, syncError = 0xFF453A,
        categories = cats(
            Triple(RED, 0xD62F33, WHITE), Triple(GREEN, 0x34C759, WHITE), Triple(BLUE, 0x0A84FF, WHITE),
            Triple(YELLOW, 0xFFD60A, 0x1C1C1E), Triple(PURPLE, 0xAF52DE, WHITE), Triple(ORANGE, 0xFF9500, WHITE),
            Triple(BROWN, 0xA2845E, WHITE), Triple(PINK, 0xF06EB0, WHITE), Triple(TEAL, 0x30B0C7, WHITE),
            Triple(GRAY, 0x8E8E93, WHITE),
        ),
    )

    val neoclassic = ThemePalette(
        id = ThemeId.NEOCLASSIC, isDark = false, usesBevel = false, cornerRadiusDp = 3,
        background = 0xECE9D8, surface = 0xFBFAF4, text = 0x000000, textMuted = 0x5A5A52,
        border = 0x7F9DB9, borderLight = 0x7F9DB9, borderDark = 0x003C74,
        accent = 0x316AC5, onAccent = WHITE,
        syncSyncing = 0x316AC5, syncConflict = 0xB86E00, syncError = 0xC8321E,
        categories = cats(
            Triple(RED, 0xC8321E, WHITE), Triple(GREEN, 0x3C9B3C, WHITE), Triple(BLUE, 0x316AC5, WHITE),
            Triple(YELLOW, 0xF2C200, 0x000000), Triple(PURPLE, 0x7A3FA8, WHITE), Triple(ORANGE, 0xE8860C, WHITE),
            Triple(BROWN, 0x8A5A2B, WHITE), Triple(PINK, 0xD9619A, WHITE), Triple(TEAL, 0x1F9A9A, WHITE),
            Triple(GRAY, 0x808078, WHITE),
        ),
    )
}
