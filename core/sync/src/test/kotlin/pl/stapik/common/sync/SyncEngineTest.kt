package pl.stapik.common.sync

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.DocumentCodec
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.model.SchemaGate
import pl.stapik.common.model.SlotKey

class SyncEngineTest {

    @Serializable
    data class Notes(val items: List<String> = emptyList())

    private val slot = SlotKey("notes.json")
    private val codec = DocumentCodec(Notes.serializer()) { it.items.isEmpty() }

    private class Fixture(codec: DocumentCodec<Notes>, slot: SlotKey) {
        val clock = TestClock(Instant.parse("2026-10-16T12:00:00Z"))
        val gateway = FakeCloudGateway()
        val store = InMemoryDocumentStore()
        var connected = true
        val engine = SyncEngine(slot, codec, store, { if (connected) gateway else null }, clock)
    }

    private fun fixture() = Fixture(codec, slot)

    private fun envelopeJson(updatedAt: String, items: List<String>, extra: String = "") =
        """{"lastUpdate":"$updatedAt",$extra"payload":{"items":[${items.joinToString(",") { "\"$it\"" }}]}}"""

    private fun remote(updatedAt: String, items: List<String>, extra: String = "") =
        RemoteDocument(envelopeJson(updatedAt, items, extra), updatedAt)

    private fun decode(document: RemoteDocument?): Notes =
        codec.decode(DocumentEnvelope.parse(checkNotNull(document).content))

    private val t0 = "2026-10-10T08:00:00Z"

    @Test
    fun `without a cloud, edits stay local`() = runBlocking {
        val f = fixture().apply { connected = false }
        assertEquals(SyncOutcome.LocalOnly, f.engine.edit { Notes(listOf("a")) })
        assertEquals(Notes(listOf("a")), f.engine.load())
        assertTrue(f.store.document!!.dirty)
        assertEquals(SyncStatus.IDLE, f.engine.status.value)
    }

    @Test
    fun `a local edit does not touch the cloud`() = runBlocking {
        val f = fixture()
        assertEquals(null, f.engine.editLocally { Notes(listOf("a")) })
        assertEquals(Notes(listOf("a")), f.engine.load())
        assertTrue(f.engine.hasPendingChanges())
        assertEquals(null, f.gateway.document)
        assertEquals(SyncStatus.IDLE, f.engine.status.value)
        assertEquals(SyncOutcome.Synchronized, f.engine.sync())
        assertEquals(Notes(listOf("a")), decode(f.gateway.document))
    }

    @Test
    fun `first connection with no local data takes the cloud`() = runBlocking {
        val f = fixture().apply { gateway.document = remote(t0, listOf("cloud")) }
        assertEquals(SyncOutcome.Synchronized, f.engine.sync())
        assertEquals(Notes(listOf("cloud")), f.engine.load())
        val stored = f.store.document!!
        assertFalse(stored.dirty)
        assertEquals(t0, stored.cloudBaseline)
        assertEquals("cloud-1", stored.cloudIdentity)
    }

    @Test
    fun `first connection with data on both sides asks, and changes nothing until answered`() = runBlocking {
        val f = fixture().apply {
            connected = false
            engine.edit { Notes(listOf("a")) }
            connected = true
            gateway.document = remote(t0, listOf("cloud"))
        }
        assertEquals(SyncOutcome.NeedsFirstConnectionChoice(true), f.engine.sync())
        assertEquals(SyncStatus.NEEDS_CHOICE, f.engine.status.value)
        assertEquals(Notes(listOf("a")), f.engine.load())
        assertTrue(f.store.document!!.dirty)
        assertTrue(f.gateway.writes.isEmpty())
        assertTrue(f.store.copies.isEmpty())
    }

    @Test
    fun `answering USE_CLOUD takes the cloud and keeps the local data as a conflict copy`() = runBlocking {
        val f = fixture().apply {
            connected = false
            engine.edit { Notes(listOf("a")) }
            connected = true
            gateway.document = remote(t0, listOf("cloud"))
        }
        assertEquals(SyncOutcome.ServerWon, f.engine.sync(FirstConnectionChoice.USE_CLOUD))
        assertEquals(Notes(listOf("cloud")), f.engine.load())
        val copy = f.store.copies.single()
        assertEquals(ConflictSource.LOCAL, copy.source)
        assertEquals(Notes(listOf("a")), codec.decode(DocumentEnvelope.parse(copy.content)))
    }

    @Test
    fun `answering SEND_LOCAL pushes local data and keeps the cloud version as a conflict copy`() = runBlocking {
        val f = fixture().apply {
            connected = false
            engine.edit { Notes(listOf("a")) }
            connected = true
            gateway.document = remote(t0, listOf("cloud"))
        }
        val original = f.gateway.document!!.content
        assertEquals(SyncOutcome.LocalWon, f.engine.sync(FirstConnectionChoice.SEND_LOCAL))
        assertEquals(Notes(listOf("a")), decode(f.gateway.document))
        val copy = f.store.copies.single()
        assertEquals(ConflictSource.CLOUD, copy.source)
        assertEquals(original, copy.content)
        val stored = f.store.document!!
        assertFalse(stored.dirty)
        assertEquals(f.gateway.document!!.updatedAt, stored.cloudBaseline)
    }

    @Test
    fun `edits are pushed when connected`() = runBlocking {
        val f = fixture()
        assertEquals(SyncOutcome.Synchronized, f.engine.edit { Notes(listOf("a")) })
        assertEquals(SyncOutcome.Synchronized, f.engine.edit { Notes(it.items + "b") })
        assertEquals(Notes(listOf("a", "b")), decode(f.gateway.document))
        assertEquals(2, f.gateway.writes.size)
        assertFalse(f.store.document!!.dirty)
    }

    @Test
    fun `offline edits are kept and sent later`() = runBlocking {
        val f = fixture().apply { gateway.failure = CloudError.Network("down") }
        assertEquals(SyncOutcome.Offline(CloudError.Network("down")), f.engine.edit { Notes(listOf("a")) })
        assertEquals(SyncStatus.OFFLINE, f.engine.status.value)
        assertTrue(f.store.document!!.dirty)
        assertEquals(Notes(listOf("a")), f.engine.load())

        f.gateway.failure = null
        assertEquals(SyncOutcome.Synchronized, f.engine.sync())
        assertEquals(SyncStatus.IDLE, f.engine.status.value)
        assertEquals(Notes(listOf("a")), decode(f.gateway.document))
    }

    @Test
    fun `rate limiting is reported as offline with the retry hint`() = runBlocking {
        val f = fixture().apply { gateway.failure = CloudError.RateLimited(30) }
        assertEquals(SyncOutcome.Offline(CloudError.RateLimited(30)), f.engine.edit { Notes(listOf("a")) })
    }

    @Test
    fun `a rejected key is reported and the edit is kept`() = runBlocking {
        val f = fixture().apply { gateway.failure = CloudError.Unauthorized }
        assertEquals(SyncOutcome.AuthProblem(CloudError.Unauthorized), f.engine.edit { Notes(listOf("a")) })
        assertEquals(SyncStatus.ERROR, f.engine.status.value)
        assertEquals(Notes(listOf("a")), f.engine.load())
    }

    @Test
    fun `an unreadable cloud document stops everything and writes nothing`() = runBlocking {
        val f = fixture().apply {
            connected = false
            engine.edit { Notes(listOf("a")) }
            connected = true
            gateway.document = RemoteDocument("this is not json", t0)
        }
        val before = f.store.document
        assertTrue(f.engine.sync() is SyncOutcome.RemoteUnreadable)
        assertEquals(SyncStatus.ERROR, f.engine.status.value)
        assertEquals(before, f.store.document)
        assertTrue(f.gateway.writes.isEmpty())
        assertTrue(f.store.copies.isEmpty())
    }

    @Test
    fun `unknown fields in the cloud document survive an edit`() = runBlocking {
        val f = fixture().apply { gateway.document = remote(t0, emptyList(), extra = """"futureField":{"x":1},""") }
        assertEquals(SyncOutcome.Synchronized, f.engine.sync())
        assertEquals(SyncOutcome.Synchronized, f.engine.edit { Notes(listOf("a")) })
        assertTrue(f.gateway.document!!.content.contains(""""futureField":{"x":1}"""))
        assertEquals(Notes(listOf("a")), decode(f.gateway.document))
    }

    @Test
    fun `a lost race gives the win to the server and keeps the local edit as a conflict copy`() = runBlocking {
        val f = fixture().apply { gateway.document = remote(t0, emptyList()) }
        f.engine.sync()
        f.gateway.onBeforeWrite = { f.gateway.document = remote("2026-10-16T09:00:00Z", listOf("server")) }
        assertEquals(SyncOutcome.ServerWon, f.engine.edit { Notes(listOf("a")) })
        assertEquals(Notes(listOf("server")), f.engine.load())
        val copy = f.store.copies.single()
        assertEquals(ConflictSource.LOCAL, copy.source)
        assertEquals(Notes(listOf("a")), codec.decode(DocumentEnvelope.parse(copy.content)))
        assertFalse(f.store.document!!.dirty)
    }

    @Test
    fun `the winner does not depend on the device clock`() = runBlocking {
        val f = fixture().apply { gateway.document = remote(t0, emptyList()) }
        f.engine.sync()
        f.clock.now = Instant.parse("2099-01-01T00:00:00Z")
        f.gateway.onBeforeWrite = { f.gateway.document = remote("2026-10-16T09:00:00Z", listOf("server")) }
        assertEquals(SyncOutcome.ServerWon, f.engine.edit { Notes(listOf("a")) })
        assertEquals(Notes(listOf("server")), f.engine.load())
    }

    @Test
    fun `when every write is refused as a conflict, the server version wins after three attempts`() = runBlocking {
        val f = fixture().apply { gateway.document = remote(t0, listOf("cloud")) }
        f.engine.sync()
        f.gateway.onBeforeWrite = { throw CloudException(CloudError.Conflict) }
        assertEquals(SyncOutcome.ServerWon, f.engine.edit { Notes(listOf("a")) })
        assertEquals(Notes(listOf("cloud")), f.engine.load())
        assertEquals(1, f.store.copies.count { it.source == ConflictSource.LOCAL })
        assertFalse(f.store.document!!.dirty)
    }

    @Test
    fun `a read-only key keeps local changes unsynced`() = runBlocking {
        val f = fixture().apply {
            gateway.info = gateway.info.copy(scope = CloudScope.READ_ONLY)
            gateway.document = remote(t0, listOf("cloud"))
        }
        f.engine.sync()
        assertEquals(SyncOutcome.KeptLocalUnsynced, f.engine.edit { Notes(it.items + "a") })
        assertTrue(f.store.document!!.dirty)
        assertTrue(f.gateway.writes.isEmpty())
        assertEquals(CloudScope.READ_ONLY, f.store.document!!.scope)
    }

    private val gate = SchemaGate(1) { env -> (env.payload["schema"] as? JsonPrimitive)?.content?.toIntOrNull() }

    private fun gatedEngine(f: Fixture) = SyncEngine(slot, codec, f.store, { null }, f.clock, gate)

    private fun stored(schema: Int) = LocalDocument(
        DocumentEnvelope.parse("""{"lastUpdate":"$t0","payload":{"schema":$schema,"items":["a"]}}"""),
    )

    @Test
    fun `edit of a document with a newer schema is refused and changes nothing`() = runBlocking {
        val f = fixture().apply { store.document = stored(schema = 2) }
        val before = f.store.document
        assertEquals(SyncOutcome.NewerSchema(2, 1), gatedEngine(f).edit { Notes(listOf("b")) })
        assertEquals(before, f.store.document)
    }

    @Test
    fun `edit of a document with the supported schema works and keeps the schema key`() = runBlocking {
        val f = fixture().apply { store.document = stored(schema = 1) }
        assertEquals(SyncOutcome.LocalOnly, gatedEngine(f).edit { Notes(listOf("b")) })
        assertEquals(Notes(listOf("b")), codec.decode(f.store.document!!.content))
        assertEquals(JsonPrimitive(1), f.store.document!!.content.payload["schema"])
    }

    @Test
    fun `replaceDocument stamps the new content as the latest local change`() = runBlocking {
        val f = fixture().apply { connected = false }
        val imported = DocumentEnvelope.parse(envelopeJson("2020-01-01T00:00:00Z", listOf("x", "y")))
        assertEquals(SyncOutcome.LocalOnly, f.engine.replaceDocument(imported))
        val saved = f.store.document!!
        assertTrue(saved.dirty)
        assertEquals("2026-10-16T12:00:00Z", saved.content.lastUpdate)
        assertEquals(Notes(listOf("x", "y")), f.engine.load())
    }

    @Test
    fun `replaceDocument is pushed to the cloud like an edit`() = runBlocking {
        val f = fixture().apply { gateway.document = remote(t0, listOf("old")) }
        f.engine.sync()
        val imported = DocumentEnvelope.parse(envelopeJson("2020-01-01T00:00:00Z", listOf("new")))
        assertEquals(SyncOutcome.Synchronized, f.engine.replaceDocument(imported))
        assertEquals(Notes(listOf("new")), decode(f.gateway.document))
    }
}
