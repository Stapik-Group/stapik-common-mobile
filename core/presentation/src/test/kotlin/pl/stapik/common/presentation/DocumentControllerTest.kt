package pl.stapik.common.presentation

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.stapik.common.document.DocumentCorruptedException
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.DocumentCodec
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SchemaGate
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.CloudGateway
import pl.stapik.common.sync.ConflictCopy
import pl.stapik.common.sync.DocumentStore
import pl.stapik.common.sync.RemoteDocument
import pl.stapik.common.sync.SyncEngine
import pl.stapik.common.sync.SyncOutcome

class DocumentControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC)
    private val codec = DocumentCodec(JsonObject.serializer()) { (it["items"] as? JsonArray)?.isEmpty() ?: true }

    private class Store : DocumentStore {
        var document: LocalDocument<DocumentEnvelope>? = null
        var failLoad: Exception? = null
        override suspend fun load(): LocalDocument<DocumentEnvelope>? {
            failLoad?.let { throw it }
            return document
        }
        override suspend fun save(document: LocalDocument<DocumentEnvelope>) { this.document = document }
        override suspend fun saveConflictCopy(copy: ConflictCopy) = Unit
    }

    private class Gateway : CloudGateway {
        var failure: CloudError? = null
        var document: RemoteDocument? = null
        override suspend fun info(): CloudInfo {
            failure?.let { throw CloudException(it) }
            return CloudInfo(CloudScope.READ_WRITE, "cloud-1")
        }
        override suspend fun read(slot: SlotKey): RemoteDocument? { failure?.let { throw CloudException(it) }; return document }
        override suspend fun write(slot: SlotKey, content: String, baseline: String): RemoteDocument {
            failure?.let { throw CloudException(it) }
            return RemoteDocument(content, "2026-10-18T10:00:01Z").also { document = it }
        }
    }

    private class Fixture(codec: DocumentCodec<JsonObject>, clock: Clock, gate: SchemaGate? = null) {
        val store = Store()
        val gateway = Gateway()
        var connected = true
        val engine = SyncEngine(SlotKey("a.json"), codec, store, { if (connected) gateway else null }, clock, gate)
        val controller = DocumentController(engine) { connected }
    }

    private fun fixture(gate: SchemaGate? = null) = Fixture(codec, clock, gate)
    private fun items(vararg v: String) = JsonObject(mapOf("items" to JsonArray(v.map { JsonPrimitive(it) })))

    @Test
    fun `starts loading, then shows the local document`() = runBlocking {
        val f = fixture()
        assertEquals(DocumentUiState.Loading, f.controller.document.value)
        f.controller.load()
        assertTrue(f.controller.document.value is DocumentUiState.Success)
        assertEquals(SyncBadge.SYNCED, f.controller.sync.value.badge)
    }

    @Test
    fun `an edit is shown and pushed`() = runBlocking {
        val f = fixture()
        f.controller.load()
        assertEquals(SyncOutcome.Synchronized, f.controller.edit { items("a") })
        assertEquals(DocumentUiState.Success(items("a")), f.controller.document.value)
        assertEquals(SyncBadge.SYNCED, f.controller.sync.value.badge)
    }

    @Test
    fun `an edit while offline is kept and shown as pending`() = runBlocking {
        val f = fixture()
        f.controller.load()
        f.gateway.failure = CloudError.Network("down")
        f.controller.edit { items("a") }
        assertEquals(DocumentUiState.Success(items("a")), f.controller.document.value)
        val sync = f.controller.sync.value
        assertEquals(SyncBadge.OFFLINE, sync.badge)
        assertEquals(setOf(SyncAction.RETRY), sync.actions)
    }

    @Test
    fun `without a cloud the indicator says local only`() = runBlocking {
        val f = fixture().apply { connected = false }
        f.controller.load()
        f.controller.edit { items("a") }
        assertEquals(SyncBadge.LOCAL_ONLY, f.controller.sync.value.badge)
    }

    @Test
    fun `unreadable storage is a corrupted error`() = runBlocking {
        val f = fixture().apply { store.failLoad = DocumentCorruptedException(emptyList()) }
        f.controller.load()
        assertEquals(DocumentUiState.Error(LoadErrorKind.CORRUPTED), f.controller.document.value)
    }

    @Test
    fun `a refused edit keeps the shown document and reports the newer schema`() = runBlocking {
        val gate = SchemaGate(1) { env -> (env.payload["schema"] as? JsonPrimitive)?.content?.toIntOrNull() }
        val f = fixture(gate)
        f.store.document = LocalDocument(DocumentEnvelope.parse("""{"lastUpdate":"2026-10-01T00:00:00Z","payload":{"schema":2,"items":["a"]}}"""))
        f.controller.load()
        assertEquals(SyncOutcome.NewerSchema(2, 1), f.controller.edit { items("b") })
        assertEquals(SyncBadge.NEWER_SCHEMA, f.controller.sync.value.badge)
        assertTrue(f.controller.document.value is DocumentUiState.Success)
    }

    @Test
    fun `sync after the connection is back clears the offline state`() = runBlocking {
        val f = fixture()
        f.controller.load()
        f.gateway.failure = CloudError.Network("down")
        f.controller.edit { items("a") }
        f.gateway.failure = null
        assertEquals(SyncOutcome.Synchronized, f.controller.sync())
        assertEquals(SyncBadge.SYNCED, f.controller.sync.value.badge)
    }
}
