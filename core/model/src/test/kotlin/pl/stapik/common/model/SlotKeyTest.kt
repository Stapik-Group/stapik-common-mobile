package pl.stapik.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotKeyTest {

    @Test
    fun `a plain slot has no partition`() {
        val slot = SlotKey("calendar.json")

        assertNull(slot.partition)
        assertFalse(slot.isPartition)
        assertEquals("calendar.json", slot.toString())
    }

    @Test
    fun `a partition is a different address than its slot`() {
        val slot = SlotKey("calendar.json")
        val year = slot.inPartition("2026")

        assertTrue(year.isPartition)
        assertEquals("calendar.json", year.value)
        assertEquals("2026", year.partition)
        assertEquals("calendar.json/2026", year.toString())
        assertNotEquals(slot, year)
        assertNotEquals(year, slot.inPartition("2025"))
        assertEquals(year, SlotKey("calendar.json", "2026"))
    }

    @Test
    fun `a partition cannot have partitions`() {
        assertThrows(IllegalArgumentException::class.java) { SlotKey("calendar.json", "2026").inPartition("12") }
    }

    @Test
    fun `slot and partition must be single path segments`() {
        listOf("", "a/b", "a\\b", "a b", "a\nb").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { SlotKey(bad) }
            assertThrows(IllegalArgumentException::class.java) { SlotKey("calendar.json", bad) }
        }
    }
}
