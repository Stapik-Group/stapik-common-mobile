package pl.stapik.common.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEnvelopeTest {

    private val sample = """{"lastUpdate":"2026-10-15T08:00:00Z","futureField":{"a":[1,2.0,3.5]},"payload":{"entries":[{"id":1,"newInDesktop":true}]}}"""

    @Test
    fun `reads lastUpdate and payload`() {
        val env = DocumentEnvelope.parse(sample)
        assertEquals("2026-10-15T08:00:00Z", env.lastUpdate)
        assertTrue(env.payload.containsKey("entries"))
    }

    @Test
    fun `unchanged document round-trips byte for byte`() {
        assertEquals(sample, DocumentEnvelope.parse(sample).toJson())
    }

    @Test
    fun `withPayload keeps unknown top-level keys and key order`() {
        val env = DocumentEnvelope.parse(sample)
        val updated = env.withPayload(JsonObject(mapOf("entries" to JsonArray(emptyList()))), "2026-10-16T10:00:00Z")
        assertEquals(
            """{"lastUpdate":"2026-10-16T10:00:00Z","futureField":{"a":[1,2.0,3.5]},"payload":{"entries":[]}}""",
            updated.toJson(),
        )
        // The original is immutable.
        assertEquals("2026-10-15T08:00:00Z", env.lastUpdate)
    }

    @Test
    fun `numbers keep their original spelling`() {
        val env = DocumentEnvelope.parse("""{"lastUpdate":"x","payload":{"n":1.0,"big":12345678901234567890}}""")
        assertEquals("""{"lastUpdate":"x","payload":{"n":1.0,"big":12345678901234567890}}""", env.toJson())
    }

    @Test
    fun `exponent notation is normalised but the value is preserved`() {
        // kotlinx.serialization re-spells 3e2 as 300.0; the number itself must not change.
        val env = DocumentEnvelope.parse("""{"lastUpdate":"x","payload":{"n":3e2}}""")
        val n = DocumentEnvelope.parse(env.toJson()).payload["n"] as JsonPrimitive
        assertEquals(300.0, n.content.toDouble(), 0.0)
    }

    @Test
    fun `missing payload reads as empty and missing lastUpdate as empty string`() {
        val env = DocumentEnvelope.parse("{}")
        assertEquals("", env.lastUpdate)
        assertTrue(env.payload.isEmpty())
    }

    @Test
    fun `empty envelope has the given lastUpdate and an empty payload`() {
        val env = DocumentEnvelope.empty("t")
        assertEquals("""{"lastUpdate":"t","payload":{}}""", env.toJson())
    }

    @Test
    fun `withPayload accepts a primitive replacement of lastUpdate`() {
        val env = DocumentEnvelope.empty("a").withPayload(JsonObject(mapOf("k" to JsonPrimitive("v"))), "b")
        assertEquals("""{"lastUpdate":"b","payload":{"k":"v"}}""", env.toJson())
    }

    @Test
    fun `malformed documents are rejected with EnvelopeFormatException`() {
        listOf("", "not json", "[]", "\"text\"", """{"lastUpdate":5}""", """{"payload":[]}""").forEach { text ->
            assertThrows("for: $text", EnvelopeFormatException::class.java) { DocumentEnvelope.parse(text) }
        }
    }
}
