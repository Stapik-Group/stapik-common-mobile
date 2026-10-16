package pl.stapik.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SlotKeyTest {

    @Test
    fun `accepts the slot names used by the apps`() {
        listOf("calendar.json", "planner.json", "media.json", "budget.json").forEach { name ->
            assertEquals(name, SlotKey(name).value)
        }
    }

    @Test
    fun `toString returns the plain value`() {
        assertEquals("budget.json", SlotKey("budget.json").toString())
    }

    @Test
    fun `keys with the same value are equal`() {
        assertEquals(SlotKey("media.json"), SlotKey("media.json"))
    }

    @Test
    fun `rejects an empty key`() {
        assertThrows(IllegalArgumentException::class.java) { SlotKey("") }
    }

    @Test
    fun `rejects path separators`() {
        assertThrows(IllegalArgumentException::class.java) { SlotKey("a/b.json") }
        assertThrows(IllegalArgumentException::class.java) { SlotKey("a\\b.json") }
    }

    @Test
    fun `rejects whitespace and control characters`() {
        assertThrows(IllegalArgumentException::class.java) { SlotKey("my file.json") }
        assertThrows(IllegalArgumentException::class.java) { SlotKey("tab\there.json") }
        assertThrows(IllegalArgumentException::class.java) { SlotKey("nul\u0000.json") }
    }
}
