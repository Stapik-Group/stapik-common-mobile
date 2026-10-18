package pl.stapik.common.app

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.stapik.common.cloud.SealedBackupException
import pl.stapik.common.sync.UnreadableKind

class ExportServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC)
    private val archive = TableArchiveFormat()
    private val service = ExportService(AppBackup("budget", MemoryDocumentStore(), mapOf("s" to MemoryPort("{}")), clock), archive)

    @Test
    fun `plain export can be read without a passphrase`() = runBlocking {
        val contents = archive.read(service.export())
        assertEquals("budget", contents.appId)
        assertEquals(mapOf("s" to "{}"), contents.mobileOnly)
    }

    @Test
    fun `protected export needs the passphrase`() = runBlocking {
        val bytes = service.export("secret")
        val e = org.junit.Assert.assertThrows(SealedBackupException::class.java) { archive.read(bytes) }
        assertEquals(UnreadableKind.PASSPHRASE_REQUIRED, e.kind)
        assertEquals("budget", archive.read(bytes, "secret").appId)
    }
}
