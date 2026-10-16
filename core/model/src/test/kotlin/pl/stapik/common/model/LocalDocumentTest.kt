package pl.stapik.common.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentTest {

    private val t1 = Instant.parse("2026-10-16T10:00:00Z")

    @Test
    fun `new document is clean and never synchronised`() {
        val doc = LocalDocument(content = "a")
        assertFalse(doc.dirty)
        assertFalse(doc.everSynchronized)
        assertNull(doc.modifiedAt)
    }

    @Test
    fun `edit marks dirty, stores time and keeps the baseline`() {
        val synced = LocalDocument("a").synchronized("a", "2026-10-15T08:00:00Z", CloudScope.READ_WRITE, "cloud-1")
        val edited = synced.edited("b", t1)
        assertEquals("b", edited.content)
        assertTrue(edited.dirty)
        assertEquals(t1, edited.modifiedAt)
        assertEquals("2026-10-15T08:00:00Z", edited.cloudBaseline)
    }

    @Test
    fun `synchronized clears dirty and records baseline, scope and identity`() {
        val doc = LocalDocument("a").edited("b", t1)
            .synchronized("b", "2026-10-16T10:00:01Z", CloudScope.READ_WRITE, "cloud-1")
        assertFalse(doc.dirty)
        assertTrue(doc.everSynchronized)
        assertEquals("2026-10-16T10:00:01Z", doc.cloudBaseline)
        assertEquals(CloudScope.READ_WRITE, doc.scope)
        assertEquals("cloud-1", doc.cloudIdentity)
    }

    @Test
    fun `synchronized rejects an empty updatedAt`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalDocument("a").synchronized("a", "", null, null)
        }
    }

    @Test
    fun `detaching keeps content and dirty flag but forgets the cloud`() {
        val doc = LocalDocument("a")
            .synchronized("a", "2026-10-15T08:00:00Z", CloudScope.READ_WRITE, "cloud-1")
            .edited("b", t1)
            .detachedFromCloud()
        assertEquals("b", doc.content)
        assertTrue(doc.dirty)
        assertFalse(doc.everSynchronized)
        assertNull(doc.scope)
        assertNull(doc.cloudIdentity)
    }
}
