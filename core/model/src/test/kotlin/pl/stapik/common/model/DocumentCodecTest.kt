package pl.stapik.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCodecTest {

    @Serializable
    data class Item(val id: Int, val title: String)

    @Serializable
    data class Sample(
        val items: List<Item> = emptyList(),
        @SerialName("currency_code") val currency: String = "PLN",
    )

    private val codec = DocumentCodec(Sample.serializer()) { it.items.isEmpty() }

    private val stored =
        """{"lastUpdate":"t1","payload":{"items":[{"id":1,"title":"a"}],"currency_code":"EUR","newInDesktop":{"x":1}}}"""

    @Test
    fun `decode reads known fields and ignores unknown ones`() {
        val model = codec.decode(DocumentEnvelope.parse(stored))
        assertEquals(Sample(listOf(Item(1, "a")), "EUR"), model)
    }

    @Test
    fun `decode of a missing payload gives the defaults`() {
        assertEquals(Sample(), codec.decode(DocumentEnvelope.parse("""{"lastUpdate":"t"}""")))
    }

    @Test
    fun `encode keeps unknown top-level payload keys and unknown envelope keys`() {
        val previous = DocumentEnvelope.parse(
            """{"lastUpdate":"t1","other":true,"payload":{"items":[],"newInDesktop":{"x":1}}}""",
        )
        val result = codec.encode(Sample(listOf(Item(2, "b")), "PLN"), previous, "t2")
        assertEquals(
            """{"lastUpdate":"t2","other":true,"payload":{"items":[{"id":2,"title":"b"}],"currency_code":"PLN","newInDesktop":{"x":1}}}""",
            result.toJson(),
        )
    }

    @Test
    fun `encode drops unknown fields nested in a known structure`() {
        val previous = DocumentEnvelope.parse(
            """{"lastUpdate":"t1","payload":{"items":[{"id":1,"title":"a","addedByNewerDesktop":true}],"currency_code":"EUR"}}""",
        )
        val result = codec.encode(codec.decode(previous), previous, "t2")
        assertEquals(
            """{"lastUpdate":"t2","payload":{"items":[{"id":1,"title":"a"}],"currency_code":"EUR"}}""",
            result.toJson(),
        )
    }

    @Test
    fun `encode keeps an unknown top-level key whole, including what is nested in it`() {
        val previous = DocumentEnvelope.parse(
            """{"lastUpdate":"t1","payload":{"items":[],"newInDesktop":{"list":[{"deep":{"x":1}}]}}}""",
        )
        val result = codec.encode(Sample(), previous, "t2")
        assertTrue(result.toJson().contains(""""newInDesktop":{"list":[{"deep":{"x":1}}]}"""))
    }

    @Test
    fun `encode then decode returns the same model`() {
        val model = Sample(listOf(Item(3, "c")), "USD")
        val envelope = codec.encode(model, DocumentEnvelope.empty("t0"), "t1")
        assertEquals(model, codec.decode(DocumentEnvelope.parse(envelope.toJson())))
    }

    @Test
    fun `a payload of the wrong shape is a DocumentDecodeException`() {
        val bad = DocumentEnvelope.parse("""{"lastUpdate":"t","payload":{"items":"nope"}}""")
        assertThrows(DocumentDecodeException::class.java) { codec.decode(bad) }
    }

    @Test
    fun `isEmpty delegates to the app predicate`() {
        assertTrue(codec.isEmpty(Sample()))
        assertFalse(codec.isEmpty(Sample(listOf(Item(1, "a")))))
    }
}
