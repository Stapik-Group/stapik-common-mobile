package pl.stapik.common.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveTest {

    private val createdAt = Instant.parse("2026-10-18T10:00:00Z")
    private val document = """{"lastUpdate":"t","future":1,"payload":{"items":["a"]}}"""

    private fun contents(doc: String? = document, mobile: Map<String, String> = mapOf("prefs" to """{"version":1,"data":{"n":1}}""")) =
        BackupContents("budgeting", createdAt, doc, mobile)

    private fun archive(contents: BackupContents = contents()): ByteArray =
        ByteArrayOutputStream().also { BackupArchive.write(it, contents) }.toByteArray()

    private fun read(bytes: ByteArray, max: Long = BackupArchive.DEFAULT_MAX_ENTRY_BYTES) =
        BackupArchive.read(ByteArrayInputStream(bytes), max)

    /** Builds a zip from raw entries, so tests can make broken or tampered archives. */
    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entriesOf(bytes: ByteArray): MutableMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                result[e.name] = z.readBytes()
            }
        }
        return result
    }

    private fun assertRejected(bytes: ByteArray, max: Long = BackupArchive.DEFAULT_MAX_ENTRY_BYTES) {
        assertThrows(BackupFormatException::class.java) { read(bytes, max) }
    }

    @Test
    fun `a backup round-trips`() {
        assertEquals(contents(), read(archive()))
    }

    @Test
    fun `a backup without a document round-trips`() {
        val result = read(archive(contents(doc = null)))
        assertNull(result.document)
        assertEquals(mapOf("prefs" to """{"version":1,"data":{"n":1}}"""), result.mobileOnly)
    }

    @Test
    fun `a backup with no mobile-only data round-trips`() {
        assertEquals(contents(mobile = emptyMap()), read(archive(contents(mobile = emptyMap()))))
    }

    @Test
    fun `the document is stored as plain envelope text`() {
        assertEquals(document, String(entriesOf(archive())["document.json"]!!, Charsets.UTF_8))
    }

    @Test
    fun `a file changed after export is rejected`() {
        val entries = entriesOf(archive())
        entries["document.json"] = document.replace("\"a\"", "\"b\"").toByteArray()
        assertRejected(zip(entries))
    }

    @Test
    fun `a file removed from the archive is rejected`() {
        val entries = entriesOf(archive())
        entries.remove("document.json")
        assertRejected(zip(entries))
    }

    @Test
    fun `an extra unlisted file is rejected`() {
        val entries = entriesOf(archive())
        entries["mobile-only/extra.json"] = "{}".toByteArray()
        assertRejected(zip(entries))
    }

    @Test
    fun `entries with paths are rejected`() {
        listOf("../evil.json", "/etc/passwd", "mobile-only/../x.json", "other/doc.json", "mobile-only/").forEach { name ->
            val entries = entriesOf(archive())
            entries[name] = "{}".toByteArray()
            assertRejected(zip(entries))
        }
    }

    @Test
    fun `a missing manifest is rejected`() {
        val entries = entriesOf(archive())
        entries.remove("manifest.json")
        assertRejected(zip(entries))
    }

    @Test
    fun `an unsupported format version is rejected`() {
        val entries = entriesOf(archive())
        val manifest = String(entries["manifest.json"]!!, Charsets.UTF_8).replace("\"formatVersion\":1", "\"formatVersion\":2")
        entries["manifest.json"] = manifest.toByteArray()
        assertRejected(zip(entries))
    }

    @Test
    fun `bytes that are not a zip are rejected`() {
        assertRejected("this is not a zip".toByteArray())
        assertRejected(ByteArray(0))
    }

    @Test
    fun `an invalid document is rejected even when the hashes match`() {
        assertRejected(archive(contents(doc = "not json")))
    }

    @Test
    fun `an oversized entry is rejected`() {
        assertRejected(archive(), max = 20)
    }

    @Test
    fun `store names with path characters cannot be exported`() {
        assertThrows(IllegalArgumentException::class.java) { archive(contents(mobile = mapOf("../x" to "{}"))) }
    }

    @Test
    fun `app name, version and time are in the desktop manifest`() {
        val manifest = String(entriesOf(archive(contents().copy(appVersion = "1.4.0")))["manifest.json"]!!, Charsets.UTF_8)
        assertTrue(manifest.contains("\"format\":\"stapik-archive\""))
        assertTrue(manifest.contains("\"app\":\"budgeting\""))
        assertTrue(manifest.contains("\"appVersion\":\"1.4.0\""))
        assertTrue(manifest.contains("\"createdAt\":\"2026-10-18T10:00:00Z\""))
        assertTrue(manifest.contains("\"assets\":[]"))
        assertEquals("1.4.0", read(archive(contents().copy(appVersion = "1.4.0"))).appVersion)
    }

    @Test
    fun `an archive made by the desktop is read for its document`() {
        val manifest = """{
          "format": "stapik-archive", "formatVersion": 1, "app": "budgeting", "appVersion": "2.3.0",
          "createdAt": "2026-10-17T20:15:00Z",
          "assets": [{"filename":"receipt.png","mimeType":"image/png","sizeBytes":3,"checksumSha256":"00"}]
        }"""
        val bytes = zip(
            linkedMapOf(
                "document.json" to document.toByteArray(),
                "settings.json" to """{"theme":"dark"}""".toByteArray(),
                "assets/receipt.png" to byteArrayOf(1, 2, 3),
                "manifest.json" to manifest.toByteArray(),
            ),
        )
        val result = read(bytes)
        assertEquals("budgeting", result.appId)
        assertEquals("2.3.0", result.appVersion)
        assertEquals(Instant.parse("2026-10-17T20:15:00Z"), result.createdAt)
        assertEquals(document, result.document)
        assertEquals(emptyMap<String, String>(), result.mobileOnly)
    }

    @Test
    fun `a desktop archive with many large assets is not rejected for its assets`() {
        val entries = linkedMapOf<String, ByteArray>(
            "manifest.json" to """{"format":"stapik-archive","formatVersion":1,"app":"x","appVersion":"1","createdAt":"2026-10-17T20:15:00Z","assets":[]}""".toByteArray(),
            "document.json" to document.toByteArray(),
        )
        repeat(250) { entries["assets/file$it.bin"] = ByteArray(100) }
        assertEquals(document, read(zip(entries), max = 50_000).document)
    }

    @Test
    fun `a desktop archive without a document is rejected`() {
        val manifest = """{"format":"stapik-archive","formatVersion":1,"app":"x","appVersion":"1","createdAt":"2026-10-17T20:15:00Z","assets":[]}"""
        assertRejected(zip(mapOf("manifest.json" to manifest.toByteArray())))
    }

    @Test
    fun `a zip with another format name is rejected`() {
        val manifest = """{"format":"other","formatVersion":1,"app":"x","appVersion":"1","createdAt":"2026-10-17T20:15:00Z","assets":[]}"""
        assertRejected(zip(mapOf("manifest.json" to manifest.toByteArray(), "document.json" to document.toByteArray())))
    }

    @Test
    fun `a desktop manifest cannot smuggle mobile-only files in`() {
        val manifest = """{"format":"stapik-archive","formatVersion":1,"app":"x","appVersion":"1","createdAt":"2026-10-17T20:15:00Z","assets":[]}"""
        assertRejected(zip(mapOf("manifest.json" to manifest.toByteArray(), "document.json" to document.toByteArray(), "mobile-only/p.json" to "{}".toByteArray())))
    }

    @Test
    fun `fractional seconds are dropped from the creation time`() {
        val result = read(archive(contents().copy(createdAt = Instant.parse("2026-10-18T10:00:00.789Z"))))
        assertEquals(Instant.parse("2026-10-18T10:00:00Z"), result.createdAt)
    }
}
