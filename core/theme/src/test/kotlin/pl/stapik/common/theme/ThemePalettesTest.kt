package pl.stapik.common.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePalettesTest {

    @Test
    fun `contrast of black and white is 21`() {
        assertEquals(21.0, Contrast.ratio(0x000000, 0xFFFFFF), 0.001)
        assertEquals(1.0, Contrast.ratio(0x123456, 0x123456), 0.001)
    }

    @Test
    fun `ids match the desktop and round-trip`() {
        assertEquals(listOf("classic", "classic-pink", "modern", "dark", "neoclassic"), ThemeId.entries.map { it.id })
        ThemeId.entries.forEach { assertEquals(it, ThemeId.fromId(it.id)) }
        assertEquals(ThemeId.MODERN, ThemeId.fromId("nope"))
        assertEquals(ThemeId.CLASSIC, ThemeId.fromId(null, ThemeId.CLASSIC))
    }

    @Test
    fun `every theme has every category and its own id`() {
        for (id in ThemeId.entries) {
            val p = ThemePalettes.of(id)
            assertEquals(id, p.id)
            assertEquals(CategoryColor.entries.toSet(), p.categories.keys)
        }
    }

    @Test
    fun `themes without a bevel have one border colour`() {
        for (p in ThemePalettes.all.filter { !it.usesBevel && it.id != ThemeId.NEOCLASSIC }) {
            assertEquals(p.border, p.borderLight)
            assertEquals(p.border, p.borderDark)
        }
    }

    @Test
    fun `main text is readable`() {
        for (p in ThemePalettes.all) {
            assertTrue("${p.id} text/background", Contrast.ratio(p.text, p.background) >= 4.5)
            assertTrue("${p.id} text/surface", Contrast.ratio(p.text, p.surface) >= 4.5)
            // 3.0 is the WCAG limit for large text and controls: the desktop's Modern accent (white on 007AFF) is 4.0.
            assertTrue("${p.id} onAccent/accent", Contrast.ratio(p.onAccent, p.accent) >= 3.0)
        }
    }

    @Test
    fun `report contrast of everything else`() {
        // Not asserted: these are the desktop's colours, and some pairs are below 4.5. Printed so a
        // change of a palette shows up in the test output.
        for (p in ThemePalettes.all) {
            println("${p.id}: muted/background ${"%.2f".format(Contrast.ratio(p.textMuted, p.background))}")
            for ((c, pair) in p.categories) {
                val r = Contrast.ratio(pair.foreground, pair.background)
                println("${p.id}: $c ${"%.2f".format(r)}")
            }
        }
    }
}
