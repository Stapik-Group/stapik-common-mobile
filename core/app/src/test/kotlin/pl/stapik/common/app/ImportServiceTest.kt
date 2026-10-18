package pl.stapik.common.app

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.stapik.common.document.AesGcmCipher
import pl.stapik.common.document.BackupContents
import pl.stapik.common.document.SafetyCopies
import pl.stapik.common.model.DocumentDecodeException
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SchemaGate

class ImportServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun doc(items: String, schema: Int = 1) =
        """{"lastUpdate":"2026-10-01T00:00:00Z","payload":{"schema":$schema,"items":$items}}"""

    private inner class Fixture(currentDoc: String? = doc("[\"mine\"]")) {
        val store = MemoryDocumentStore(currentDoc?.let { LocalDocument(DocumentEnvelope.parse(it)) })
        val replacer = RecordingReplacer(store)
        val archive = TableArchiveFormat()
        val settings = MemoryPort("""{"v":1,"data":"mine"}""")
        val ports = mapOf("settings" to settings)
        val backup = AppBackup("budget", store, ports, clock)
        val safety = SafetyCopies(Files.createTempDirectory("imp"), AesGcmCipher { key }, 5, clock)
        val gate = SchemaGate(1) { env -> (env.payload["schema"] as? JsonPrimitive)?.content?.toIntOrNull() }
        var validator: (DocumentEnvelope) -> Unit = {}
        val service by lazy {
            ImportService("budget", backup, archive, safety, replacer, ports, gate) { validator(it) }
        }
        fun contents(document: String? = doc("[\"theirs\"]"), appId: String = "budget", stores: Map<String, String> = mapOf("settings" to """{"v":1,"data":"theirs"}""")) =
            BackupContents(appId, Instant.parse("2026-10-17T00:00:00Z"), document, stores)
    }

    @Test
    fun `import replaces data and keeps the old state as a safety copy`() = runBlocking {
        val f = Fixture()
        val result = f.service.import(f.contents()) as ImportResult.Imported
        assertTrue(f.store.document!!.content.toJson().contains("theirs"))
        assertEquals("""{"v":1,"data":"theirs"}""", f.settings.json)
        val before = f.archive.read(f.safety.read(result.safetyCopy))
        assertTrue(before.document!!.contains("mine"))
        assertEquals("""{"v":1,"data":"mine"}""", before.mobileOnly["settings"])
    }

    @Test
    fun `restoring the safety copy brings the old state back`() = runBlocking {
        val f = Fixture()
        val imported = f.service.import(f.contents()) as ImportResult.Imported
        f.service.restoreSafetyCopy(imported.safetyCopy)
        assertTrue(f.store.document!!.content.toJson().contains("mine"))
        assertEquals("""{"v":1,"data":"mine"}""", f.settings.json)
        assertEquals(2, f.safety.list().size)
    }

    @Test
    fun `a backup from another app is refused and nothing changes`() = runBlocking {
        val f = Fixture()
        assertEquals(ImportResult.Rejected(ImportRejection.WrongApp("calendar")), f.service.import(f.contents(appId = "calendar")))
        assertTrue(f.safety.list().isEmpty())
        assertTrue(f.replacer.replaced.isEmpty())
        assertEquals(0, f.settings.imported.size)
    }

    @Test
    fun `a document with a newer schema is refused`() = runBlocking {
        val f = Fixture()
        val result = f.service.import(f.contents(document = doc("[]", schema = 2)))
        assertEquals(ImportResult.Rejected(ImportRejection.NewerSchema(2, 1)), result)
        assertTrue(f.safety.list().isEmpty())
    }

    @Test
    fun `a document that is not an envelope is refused`() = runBlocking {
        val f = Fixture()
        val result = f.service.import(f.contents(document = "[1,2]"))
        assertTrue((result as ImportResult.Rejected).reason is ImportRejection.DocumentInvalid)
    }

    @Test
    fun `a document that does not fit the model is refused`() = runBlocking {
        val f = Fixture().apply { validator = { throw DocumentDecodeException("no") } }
        val result = f.service.import(f.contents())
        assertEquals(ImportResult.Rejected(ImportRejection.DocumentInvalid("no")), result)
        assertTrue(f.replacer.replaced.isEmpty())
    }

    @Test
    fun `an unknown store is refused before anything is written`() = runBlocking {
        val f = Fixture()
        val result = f.service.import(f.contents(stores = mapOf("settings" to "{}", "extra" to "{}")))
        assertEquals(ImportResult.Rejected(ImportRejection.UnknownStore("extra")), result)
        assertEquals(0, f.settings.imported.size)
    }

    @Test
    fun `a backup without a document leaves the document alone`() = runBlocking {
        val f = Fixture()
        val result = f.service.import(f.contents(document = null)) as ImportResult.Imported
        assertEquals(null, result.sync)
        assertTrue(f.replacer.replaced.isEmpty())
        assertEquals("""{"v":1,"data":"theirs"}""", f.settings.json)
    }

    @Test
    fun `an app with no data yet can import`() = runBlocking {
        val f = Fixture(currentDoc = null)
        val result = f.service.import(f.contents()) as ImportResult.Imported
        assertEquals(null, f.archive.read(f.safety.read(result.safetyCopy)).document)
        assertEquals(1, f.replacer.replaced.size)
    }

    @Test
    fun `failure of the document write puts the stores back`() = runBlocking {
        val f = Fixture().apply { replacer.fail = true }
        val result = f.service.import(f.contents())
        assertTrue(result is ImportResult.Failed)
        assertEquals("""{"v":1,"data":"mine"}""", f.settings.json)
        assertTrue(f.store.document!!.content.toJson().contains("mine"))
    }

    @Test
    fun `failure in a store is reported and the document is not touched`() = runBlocking {
        val f = Fixture().apply { settings.failOnImport = true }
        val result = f.service.import(f.contents())
        assertTrue(result is ImportResult.Failed)
        assertTrue(f.replacer.replaced.isEmpty())
    }

    @Test
    fun `garbage bytes are not a backup`() = runBlocking {
        val f = Fixture()
        val result = f.service.importArchive("nonsense".toByteArray())
        assertTrue((result as ImportResult.Rejected).reason is ImportRejection.NotABackup)
    }

    @Test
    fun `a protected archive needs the right passphrase`() = runBlocking {
        val f = Fixture()
        val bytes = f.archive.write(f.contents(), "secret")
        assertEquals(ImportResult.Rejected(ImportRejection.PassphraseRequired), f.service.importArchive(bytes))
        assertEquals(ImportResult.Rejected(ImportRejection.WrongPassphrase), f.service.importArchive(bytes, "nope"))
        assertTrue(f.replacer.replaced.isEmpty())
        assertTrue(f.service.importArchive(bytes, "secret") is ImportResult.Imported)
        assertTrue(f.store.document!!.content.toJson().contains("theirs"))
    }
}
