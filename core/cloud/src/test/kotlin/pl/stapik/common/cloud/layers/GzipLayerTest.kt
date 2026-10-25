package pl.stapik.common.cloud.layers

import org.junit.Test
import org.junit.Assert.assertEquals
import pl.stapik.common.cloud.assertFailsWith
import org.junit.Assert.assertTrue
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.CloudException

class GzipLayerTest {
    private val big = """{"items":[""" + (0 until 4000).joinToString(",") { """{"id":$it,"name":"item number $it"}""" } + "]}"

    @Test
    fun `small text is left alone`() {
        assertEquals("""{"a":1}""", GzipLayer.seal("""{"a":1}"""))
    }

    @Test
    fun `large text is compressed and restored`() {
        val sealed = GzipLayer.seal(big)
        assertTrue(sealed.length < big.length)
        assertEquals("stapik-gzip", JsonProbe.string(JsonProbe.parseObject(sealed)!!, "format"))
        assertEquals(big, GzipLayer.open(sealed))
    }

    @Test
    fun `plain text passes through open`() {
        assertEquals("""{"x":"stapik-gzip"}""", GzipLayer.open("""{"x":"stapik-gzip"}"""))
    }

    @Test
    fun `damaged data is unreadable`() {
        val damaged = """{"format":"stapik-gzip","version":1,"data":"AAAA"}"""
        assertFailsWith<CloudException> { GzipLayer.open(damaged) }
    }

    @Test
    fun `decompression stops at the limit`() {
        val sealed = GzipLayer.seal(big)
        assertEquals(big, GzipLayer.open(sealed, big.length.toLong()))
        assertFailsWith<CloudException> { GzipLayer.open(sealed, big.length - 1L) }
    }

    @Test
    fun `decompression limit is below what a phone can hold`() {
        assertTrue(GzipLayer.MAX_DECOMPRESSED_BYTES <= 64L * 1024 * 1024)
    }

    @Test
    fun `gzip then e2e round trip`() {
        val layers = ContentLayers(SlotKey("planner.json"), "pw")
        val sealed = layers.seal(big)
        assertTrue(E2eBlob.parse(sealed) != null)
        assertEquals(big, layers.open(sealed))
    }
}
