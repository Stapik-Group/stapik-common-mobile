package pl.stapik.common.document

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.ConflictCopy
import pl.stapik.common.sync.ConflictSource

class FileDocumentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val slot = SlotKey("notes.json")
    private val dir: Path get() = tmp.root.toPath()

    /** Marks encrypted data and refuses to decrypt anything without the mark, like a wrong key would. */
    private object MarkedCipher : PayloadCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(0x45) + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(encrypted: ByteArray): ByteArray {
            require(encrypted.isNotEmpty() && encrypted[0] == 0x45.toByte()) { "wrong key" }
            return ByteArray(encrypted.size - 1) { (encrypted[it + 1].toInt() xor 0x5A).toByte() }
        }
    }

    private fun store(cipher: PayloadCipher = PayloadCipher.None, backups: Int = 3) =
        FileDocumentStore(dir, slot, cipher, backups)

    private fun doc(item: String, dirty: Boolean = true) = LocalDocument(
        content = DocumentEnvelope.parse("""{"lastUpdate":"t","payload":{"items":["$item"]}}"""),
        dirty = dirty,
    )

    private fun text(document: LocalDocument<DocumentEnvelope>?) = document!!.content.toJson()

    private fun file(name: String): Path = dir.resolve(name)

    private fun corrupt(path: Path) = Files.write(path, "garbage".toByteArray())

    @Test
    fun `nothing stored gives null`() = runBlocking {
        assertNull(store().load())
    }

    @Test
    fun `a saved document comes back with all its fields`() = runBlocking {
        val original = LocalDocument(
            content = DocumentEnvelope.parse("""{"lastUpdate":"t","extra":1,"payload":{"items":["a"]}}"""),
            cloudBaseline = "2026-10-15T08:00:00Z",
            dirty = true,
            modifiedAt = Instant.parse("2026-10-16T12:00:00Z"),
            scope = CloudScope.READ_WRITE,
            cloudIdentity = "cloud-1",
        )
        store().save(original)
        val loaded = store().load()!!
        assertEquals(original.content.toJson(), loaded.content.toJson())
        assertEquals(original.copy(content = loaded.content), loaded)
    }

    @Test
    fun `no temporary file is left behind`() = runBlocking {
        store().save(doc("a"))
        assertFalse(Files.exists(file("notes.json.doc.tmp")))
    }

    @Test
    fun `backups rotate and are capped`() = runBlocking {
        val s = store(backups = 3)
        listOf("v1", "v2", "v3", "v4", "v5").forEach { s.save(doc(it)) }
        assertTrue(text(s.load()).contains("v5"))
        assertTrue(text(StoredDocument.decode(Files.readAllBytes(file("notes.json.doc.bak")))).contains("v4"))
        assertTrue(text(StoredDocument.decode(Files.readAllBytes(file("notes.json.doc.bak.2")))).contains("v3"))
        assertTrue(text(StoredDocument.decode(Files.readAllBytes(file("notes.json.doc.bak.3")))).contains("v2"))
        assertFalse(Files.exists(file("notes.json.doc.bak.4")))
    }

    @Test
    fun `a corrupted current file is recovered from the newest backup`() = runBlocking {
        val s = store()
        s.save(doc("v1"))
        s.save(doc("v2"))
        corrupt(file("notes.json.doc"))
        assertTrue(text(s.load()).contains("v1"))
        assertTrue(s.lastLoadRecoveredFromBackup)
    }

    @Test
    fun `saving over a corrupted file keeps the good backup and then loads normally`() = runBlocking {
        val s = store()
        s.save(doc("v1"))
        s.save(doc("v2"))
        corrupt(file("notes.json.doc"))
        s.save(doc("v3"))
        assertTrue(text(s.load()).contains("v3"))
        assertFalse(s.lastLoadRecoveredFromBackup)
        assertTrue(text(StoredDocument.decode(Files.readAllBytes(file("notes.json.doc.bak")))).contains("v1"))
    }

    @Test
    fun `a missing current file is recovered from a backup`() = runBlocking {
        val s = store()
        s.save(doc("v1"))
        s.save(doc("v2"))
        Files.delete(file("notes.json.doc"))
        assertTrue(text(s.load()).contains("v1"))
        assertTrue(s.lastLoadRecoveredFromBackup)
    }

    @Test
    fun `when nothing is readable it throws instead of returning an empty document`() = runBlocking {
        val s = store()
        s.save(doc("v1"))
        s.save(doc("v2"))
        corrupt(file("notes.json.doc"))
        corrupt(file("notes.json.doc.bak"))
        assertThrows(DocumentCorruptedException::class.java) { runBlocking { s.load() } }
        Unit
    }

    @Test
    fun `an unsupported format version counts as unreadable`() = runBlocking {
        Files.write(file("notes.json.doc"), """{"version":99,"content":"{}"}""".toByteArray())
        assertThrows(DocumentCorruptedException::class.java) { runBlocking { store().load() } }
        Unit
    }

    @Test
    fun `data on disk is encrypted and unreadable without the right cipher`() = runBlocking {
        store(MarkedCipher).save(doc("secret-item"))
        val raw = String(Files.readAllBytes(file("notes.json.doc")), Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret-item"))
        assertTrue(text(store(MarkedCipher).load()).contains("secret-item"))
        assertThrows(DocumentCorruptedException::class.java) { runBlocking { store(PayloadCipher.None).load() } }
        Unit
    }

    @Test
    fun `conflict copies are stored, listed newest first and encrypted`() = runBlocking {
        val s = store(MarkedCipher)
        val older = ConflictCopy(slot, ConflictSource.LOCAL, Instant.ofEpochMilli(1_000), """{"lastUpdate":"old"}""")
        val newer = ConflictCopy(slot, ConflictSource.CLOUD, Instant.ofEpochMilli(2_000), """{"lastUpdate":"new"}""")
        s.saveConflictCopy(older)
        s.saveConflictCopy(newer)
        assertEquals(listOf(newer, older), s.listConflictCopies())
        val onDisk = Files.newDirectoryStream(dir.resolve("conflicts")).use { it.toList() }
        assertEquals(2, onDisk.size)
        onDisk.forEach { assertFalse(String(Files.readAllBytes(it), Charsets.ISO_8859_1).contains("lastUpdate")) }
    }

    @Test
    fun `conflict copies of other slots are not listed`() = runBlocking {
        val other = FileDocumentStore(dir, SlotKey("calendar.json"))
        other.saveConflictCopy(ConflictCopy(SlotKey("calendar.json"), ConflictSource.LOCAL, Instant.ofEpochMilli(5), "{}"))
        assertTrue(store().listConflictCopies().isEmpty())
        assertNotNull(other.listConflictCopies().singleOrNull())
    }
}
