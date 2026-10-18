package pl.stapik.common.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.stapik.common.sync.UnreadableKind

class BackupSealingTest {
    private val zip = byteArrayOf(0x50, 0x4b, 3, 4, 0, -1, -2, 127, -128) + ByteArray(1000) { it.toByte() }

    @Test
    fun `round trip`() {
        val sealed = BackupSealing.seal(zip, "pass żółć")
        assertTrue(BackupSealing.isSealed(sealed))
        assertArrayEquals(zip, BackupSealing.open(sealed, "pass żółć"))
    }

    @Test
    fun `a plain archive is not sealed`() {
        assertFalse(BackupSealing.isSealed(zip))
        assertFalse(BackupSealing.isSealed(ByteArray(0)))
        assertFalse(BackupSealing.isSealed("""{"format":"other"}""".toByteArray()))
    }

    @Test
    fun `the archive does not appear in the sealed file`() {
        val text = String(BackupSealing.seal("PK-visible-marker".toByteArray(), "p"), Charsets.ISO_8859_1)
        assertFalse(text.contains("visible-marker"))
        val encoded = java.util.Base64.getEncoder().encodeToString("PK-visible-marker".toByteArray())
        assertFalse(text.contains(encoded.take(16)))
    }

    private fun kindOf(block: () -> Unit): UnreadableKind =
        assertFailsWith<SealedBackupException> { block() }.kind

    @Test
    fun `missing passphrase`() {
        val sealed = BackupSealing.seal(zip, "p")
        assertEquals(UnreadableKind.PASSPHRASE_REQUIRED, kindOf { BackupSealing.open(sealed, null) })
        assertEquals(UnreadableKind.PASSPHRASE_REQUIRED, kindOf { BackupSealing.open(sealed, "") })
    }

    @Test
    fun `wrong passphrase`() {
        val sealed = BackupSealing.seal(zip, "p")
        assertEquals(UnreadableKind.DECRYPTION_FAILED, kindOf { BackupSealing.open(sealed, "q") })
    }

    @Test
    fun `a cloud blob is not accepted as a backup`() {
        // Same format, different associated data: sealed for the cloud slot, not for a backup.
        val cloudBlob = pl.stapik.common.cloud.layers.ContentLayers(pl.stapik.common.model.SlotKey("backup"), "p").seal("{}")
        assertEquals(UnreadableKind.DECRYPTION_FAILED, kindOf { BackupSealing.open(cloudBlob.toByteArray(), "p") })
    }

    @Test
    fun `not a sealed file`() {
        assertEquals(UnreadableKind.OTHER, kindOf { BackupSealing.open(zip, "p") })
    }
}
