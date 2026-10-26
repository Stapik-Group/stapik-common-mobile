package pl.stapik.common.document

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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

    private object LostKeyCipher : PayloadCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(encrypted: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException("key is gone")
    }

    @Test
    fun `data that fails only in the cipher is reported as undecryptable`() = runBlocking {
        store().save(doc("a"))
        store().save(doc("b"))
        val error = assertThrows(DocumentCorruptedException::class.java) { runBlocking { store(LostKeyCipher).load() } }
        assertTrue(error.undecryptable)
    }

    @Test
    fun `damaged content is not reported as undecryptable`() = runBlocking {
        store().save(doc("a"))
        corrupt(file("notes.json.doc"))
        val error = assertThrows(DocumentCorruptedException::class.java) { runBlocking { store().load() } }
        assertFalse(error.undecryptable)
    }

    @Test
    fun `discarding removes the document and its backups but keeps conflict copies`() = runBlocking {
        val s = store()
        s.save(doc("a"))
        s.save(doc("b"))
        s.saveConflictCopy(ConflictCopy(slot, ConflictSource.LOCAL, Instant.ofEpochMilli(1_000), "{}"))
        corrupt(file("notes.json.doc"))
        s.discard()
        assertFalse(Files.exists(file("notes.json.doc")))
        assertFalse(Files.exists(file("notes.json.doc.bak")))
        assertNull(s.load())
        assertEquals(1, s.listConflictCopies().size)
    }

    @Test
    fun `a document can be saved again after discarding unreadable data`() = runBlocking {
        store().save(doc("a"))
        corrupt(file("notes.json.doc"))
        store().discard()
        store().save(doc("fresh"))
        assertTrue(text(store().load()).contains("fresh"))
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

    private val now = Instant.parse("2026-10-26T12:00:00Z")

    private fun pruning(max: Int = 20, maxAge: Duration = Duration.ofDays(90)) =
        FileDocumentStore(dir, slot, PayloadCipher.None, 3, Clock.fixed(now, ZoneOffset.UTC), max, maxAge)

    private fun copy(at: Instant, source: ConflictSource = ConflictSource.LOCAL) = ConflictCopy(slot, source, at, "{}")

    private suspend fun FileDocumentStore.saveAll(vararg copies: ConflictCopy) = copies.forEach { saveConflictCopy(it) }

    @Test
    fun `only the newest copies are kept`() = runBlocking {
        val s = pruning(max = 3)
        val times = (1L..5L).map { now.minusSeconds(100 - it) }
        s.saveAll(*times.map { copy(it) }.toTypedArray())
        assertEquals(times.takeLast(3).reversed(), s.listConflictCopies().map { it.createdAt })
    }

    @Test
    fun `copies past the age limit are removed`() = runBlocking {
        val s = pruning(maxAge = Duration.ofDays(30))
        val fresh = (1L..3L).map { now.minusSeconds(it) }
        val old = now.minus(Duration.ofDays(40))
        s.saveAll(copy(old), *fresh.map { copy(it) }.toTypedArray())
        assertEquals(fresh, s.listConflictCopies().map { it.createdAt })
    }

    @Test
    fun `the newest few copies are kept even when all are old`() = runBlocking {
        val s = pruning(maxAge = Duration.ofDays(30))
        val old = (1L..5L).map { now.minus(Duration.ofDays(100)).plusSeconds(it) }
        s.saveAll(*old.map { copy(it) }.toTypedArray())
        assertEquals(old.takeLast(FileDocumentStore.PROTECTED_COPIES).reversed(), s.listConflictCopies().map { it.createdAt })
    }

    @Test
    fun `pruning leaves the copies of other slots alone`() = runBlocking {
        val other = FileDocumentStore(dir, SlotKey("calendar.json"), PayloadCipher.None, 3, Clock.fixed(now, ZoneOffset.UTC), 1)
        val foreign = ConflictCopy(SlotKey("calendar.json"), ConflictSource.CLOUD, now.minusSeconds(500), "{}")
        other.saveConflictCopy(foreign)
        pruning(max = 1).saveAll(copy(now.minusSeconds(3)), copy(now.minusSeconds(2)), copy(now.minusSeconds(1)))
        assertEquals(listOf(foreign), other.listConflictCopies())
        assertEquals(1, pruning().listConflictCopies().size)
    }

    @Test
    fun `copies that cannot be read are pruned too`() = runBlocking {
        val encrypted = FileDocumentStore(dir, slot, MarkedCipher, 3, Clock.fixed(now, ZoneOffset.UTC), 1)
        encrypted.saveAll(copy(now.minusSeconds(2)), copy(now.minusSeconds(1)))
        val files = Files.newDirectoryStream(dir.resolve("conflicts")).use { it.toList() }
        assertEquals(1, files.size)
    }

    @Test
    fun `conflict copies of other slots are not listed`() = runBlocking {
        val other = FileDocumentStore(dir, SlotKey("calendar.json"))
        other.saveConflictCopy(ConflictCopy(SlotKey("calendar.json"), ConflictSource.LOCAL, Instant.ofEpochMilli(5), "{}"))
        assertTrue(store().listConflictCopies().isEmpty())
        assertNotNull(other.listConflictCopies().singleOrNull())
    }

    private fun partitionStore(key: String) = FileDocumentStore(dir, slot.inPartition(key))

    @Test
    fun `partitions of a slot keep separate documents`() = runBlocking {
        partitionStore("2025").save(doc("old"))
        partitionStore("2026").save(doc("new"))
        store().save(doc("whole"))

        assertTrue(text(partitionStore("2025").load()).contains("old"))
        assertTrue(text(partitionStore("2026").load()).contains("new"))
        assertTrue(text(store().load()).contains("whole"))
    }

    @Test
    fun `discarding a partition leaves the other partitions and the slot`() = runBlocking {
        partitionStore("2025").save(doc("old"))
        partitionStore("2026").save(doc("new"))
        store().save(doc("whole"))

        partitionStore("2026").discard()

        assertNull(partitionStore("2026").load())
        assertNotNull(partitionStore("2025").load())
        assertNotNull(store().load())
    }

    @Test
    fun `conflict copies are listed per partition`() = runBlocking {
        val key = slot.inPartition("2026")
        val inYear = ConflictCopy(key, ConflictSource.CLOUD, Instant.ofEpochMilli(5), "{}")
        partitionStore("2026").saveConflictCopy(inYear)
        partitionStore("2025").saveConflictCopy(ConflictCopy(slot.inPartition("2025"), ConflictSource.LOCAL, Instant.ofEpochMilli(6), "{}"))
        store().saveConflictCopy(ConflictCopy(slot, ConflictSource.LOCAL, Instant.ofEpochMilli(7), "{}"))

        assertEquals(listOf(inYear), partitionStore("2026").listConflictCopies())
        assertEquals(1, partitionStore("2025").listConflictCopies().size)
        assertEquals(1, store().listConflictCopies().size)
    }

    @Test
    fun `pruning one partition leaves the copies of the others`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
        val year = FileDocumentStore(dir, slot.inPartition("2026"), PayloadCipher.None, 3, clock, 1)
        val other = partitionStore("2025")
        other.saveConflictCopy(ConflictCopy(slot.inPartition("2025"), ConflictSource.CLOUD, Instant.ofEpochMilli(1), "{}"))

        year.saveConflictCopy(ConflictCopy(slot.inPartition("2026"), ConflictSource.CLOUD, Instant.ofEpochMilli(2), "{}"))
        year.saveConflictCopy(ConflictCopy(slot.inPartition("2026"), ConflictSource.CLOUD, Instant.ofEpochMilli(3), "{}"))

        assertEquals(1, year.listConflictCopies().size)
        assertEquals(1, other.listConflictCopies().size)
    }
}
