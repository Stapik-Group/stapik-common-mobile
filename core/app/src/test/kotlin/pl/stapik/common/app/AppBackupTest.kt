package pl.stapik.common.app

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument

class AppBackupTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `snapshot holds the document and every store`() = runBlocking {
        val envelope = DocumentEnvelope.parse("""{"lastUpdate":"2026-10-01T00:00:00Z","payload":{"a":1}}""")
        val backup = AppBackup("budget", MemoryDocumentStore(LocalDocument(envelope)), mapOf("s" to MemoryPort("""{"x":1}""")), clock)
        val snapshot = backup.snapshot()
        assertEquals("budget", snapshot.appId)
        assertEquals(clock.instant(), snapshot.createdAt)
        assertEquals(envelope.toJson(), snapshot.document)
        assertEquals(mapOf("s" to """{"x":1}"""), snapshot.mobileOnly)
    }

    @Test
    fun `no document gives null`() = runBlocking {
        assertNull(AppBackup("budget", MemoryDocumentStore(), emptyMap(), clock).snapshot().document)
    }
}
