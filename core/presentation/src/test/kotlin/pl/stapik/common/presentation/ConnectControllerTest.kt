package pl.stapik.common.presentation

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.stapik.common.cloud.CloudConfig
import pl.stapik.common.document.AesGcmCipher
import pl.stapik.common.document.DocumentCorruptedException
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.DocumentCodec
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SlotKey
import pl.stapik.common.settings.ConfigLoad
import pl.stapik.common.settings.ConnectionConfigStore
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.CloudGateway
import pl.stapik.common.sync.ConflictCopy
import pl.stapik.common.sync.DocumentStore
import pl.stapik.common.sync.RemoteDocument
import pl.stapik.common.sync.SyncEngine

class ConnectControllerTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val dir = Files.createTempDirectory("connect")
    private fun store() = ConnectionConfigStore(dir, AesGcmCipher { key }, 1)

    private class FakeGateway(val error: CloudError? = null) : CloudGateway {
        override suspend fun info(): CloudInfo {
            error?.let { throw CloudException(it) }
            return CloudInfo(CloudScope.READ_WRITE, "cloud-1")
        }
        override suspend fun read(slot: SlotKey): RemoteDocument? = null
        override suspend fun write(slot: SlotKey, content: String, baseline: String): RemoteDocument = error("unused")
    }

    private class MemoryStore(var document: LocalDocument<DocumentEnvelope>? = null) : DocumentStore {
        override suspend fun load() = document
        override suspend fun save(document: LocalDocument<DocumentEnvelope>) { this.document = document }
        override suspend fun saveConflictCopy(copy: ConflictCopy) = Unit
        override suspend fun discard() { document = null }
    }

    private val codec = DocumentCodec(JsonObject.serializer()) { it.isEmpty() }
    private val clock = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC)

    private fun engineOver(store: MemoryStore) = SyncEngine(SlotKey("a.json"), codec, store, { null }, clock)

    private fun syncedDocument() = LocalDocument(DocumentEnvelope.parse("""{"lastUpdate":"t","payload":{"x":1}}"""))
        .synchronized(DocumentEnvelope.parse("""{"lastUpdate":"t","payload":{"x":1}}"""), "2026-10-26T11:00:00Z", CloudScope.READ_WRITE, "cloud-1")

    private fun controller(
        error: CloudError? = null,
        created: MutableList<CloudConfig> = mutableListOf(),
        documents: List<SyncEngine<*>> = emptyList(),
    ) = ConnectController(store(), { config -> created += config; FakeGateway(error) }, documents)

    @Test
    fun `form validation`() {
        val c = controller()
        assertFalse(c.state.value.canSubmit)
        c.setApiUrl("cloud.example"); c.setApiKey("k")
        assertTrue(c.state.value.showInvalidUrl)
        assertFalse(c.state.value.canSubmit)
        c.setApiUrl("http://cloud.example")
        assertTrue(c.state.value.showInsecureWarning)
        assertTrue(c.state.value.canSubmit)
        c.setApiUrl("https://cloud.example/")
        assertFalse(c.state.value.showInsecureWarning)
        c.setApiKey("")
        assertFalse(c.state.value.canSubmit)
    }

    @Test
    fun `key visibility toggles`() {
        val c = controller()
        c.toggleKeyVisible()
        assertTrue(c.state.value.keyVisible)
    }

    @Test
    fun `successful connect saves the configuration`() = runBlocking {
        val c = controller()
        c.setApiUrl("https://cloud.example/"); c.setApiKey("secret"); c.setPassphrase("phrase")
        val outcome = c.connect() as ConnectOutcome.Connected
        assertEquals("cloud-1", outcome.info.identity)
        val loaded = store().load() as ConfigLoad.Loaded
        assertEquals("secret", loaded.config.apiKey)
        assertEquals("phrase", loaded.config.passphrase)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `a rejected key saves nothing and keeps the previous connection`() = runBlocking {
        store().save(CloudConfig("https://old.example", "old-key"))
        val c = controller(error = CloudError.Unauthorized)
        c.setApiUrl("https://cloud.example"); c.setApiKey("wrong")
        assertEquals(ConnectOutcome.Failed("connect.error.unauthorized"), c.connect())
        assertEquals("connect.error.unauthorized", c.state.value.errorKey)
        assertEquals("old-key", (store().load() as ConfigLoad.Loaded).config.apiKey)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `errors map to their own messages`() = runBlocking {
        fun key(error: CloudError): String {
            val c = controller(error)
            c.setApiUrl("https://c.example"); c.setApiKey("k")
            return (runBlocking { c.connect() } as ConnectOutcome.Failed).messageKey
        }
        assertEquals("connect.error.forbidden", key(CloudError.Forbidden))
        assertEquals("connect.error.network", key(CloudError.Network("x")))
        assertEquals("connect.error.server", key(CloudError.ServerError(500)))
        assertEquals("connect.error.rate_limited", key(CloudError.RateLimited(5)))
        assertEquals("connect.error.unexpected", key(CloudError.Conflict))
    }

    @Test
    fun `an invalid form does not reach the network`() = runBlocking {
        val created = mutableListOf<CloudConfig>()
        val c = controller(created = created)
        assertEquals(ConnectOutcome.Failed("connect.error.invalid_form"), c.connect())
        assertTrue(created.isEmpty())
    }

    @Test
    fun `editing clears the error`() = runBlocking {
        val c = controller(error = CloudError.Unauthorized)
        c.setApiUrl("https://c.example"); c.setApiKey("k")
        c.connect()
        c.setApiKey("k2")
        assertEquals(null, c.state.value.errorKey)
    }

    @Test
    fun `disconnect forgets the saved connection and clears the form`() = runBlocking {
        val c = controller()
        c.setApiUrl("https://c.example"); c.setApiKey("k")
        c.connect()
        c.disconnect()
        assertEquals(ConfigLoad.None, store().load())
        assertEquals("", c.state.value.apiKey)
    }

    @Test
    fun `disconnect makes every document forget the cloud and keeps its content`() = runBlocking {
        val first = MemoryStore(syncedDocument())
        val second = MemoryStore(syncedDocument())
        val c = controller(documents = listOf(engineOver(first), engineOver(second)))
        c.disconnect()
        listOf(first, second).forEach {
            val stored = checkNotNull(it.document)
            assertFalse(stored.everSynchronized)
            assertEquals(null, stored.cloudIdentity)
            assertEquals("""{"lastUpdate":"t","payload":{"x":1}}""", stored.content.toJson())
        }
    }

    @Test
    fun `disconnect still forgets the connection when a document cannot be read`() = runBlocking {
        val unreadable = object : DocumentStore {
            override suspend fun load(): LocalDocument<DocumentEnvelope>? = throw DocumentCorruptedException(emptyList())
            override suspend fun save(document: LocalDocument<DocumentEnvelope>) = Unit
            override suspend fun saveConflictCopy(copy: ConflictCopy) = Unit
            override suspend fun discard() = Unit
        }
        val c = controller(documents = listOf(SyncEngine(SlotKey("a.json"), codec, unreadable, { null }, clock)))
        c.setApiUrl("https://c.example"); c.setApiKey("k")
        c.connect()
        c.disconnect()
        assertEquals(ConfigLoad.None, store().load())
    }

    @Test
    fun `the form is prefilled from the saved configuration`() {
        val c = ConnectController(store(), { FakeGateway() }, emptyList(), CloudConfig("https://c.example", "k", "p"))
        assertEquals("k", c.state.value.apiKey)
        assertEquals("p", c.state.value.passphrase)
    }
}
