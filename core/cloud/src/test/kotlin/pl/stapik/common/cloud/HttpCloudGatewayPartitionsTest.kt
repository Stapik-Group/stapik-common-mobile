package pl.stapik.common.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.PartitionInfo
import pl.stapik.common.sync.UnreadableKind

class HttpCloudGatewayPartitionsTest {
    private val slot = SlotKey("calendar.json")
    private val year = slot.inPartition("2026")
    private val server = FakePartitionServer()

    private fun gateway(passphrase: String? = null) = HttpCloudGateway(
        CloudConfig("https://cloud.example", "key-1", passphrase),
        server,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun errorOf(block: suspend () -> Unit): CloudError =
        assertFailsWith<CloudException> { runBlocking { block() } }.error

    @Test
    fun `a partition lives under the partitions path of its slot`() = runBlocking {
        val g = gateway()

        g.write(year, """{"v":1}""", "")
        g.read(year)

        assertEquals(
            listOf("https://cloud.example/api/v1/documents/calendar.json/partitions/2026"),
            server.requests.map { it.url }.distinct(),
        )
    }

    @Test
    fun `a missing partition reads as null`() = runBlocking {
        assertNull(gateway().read(year))
    }

    @Test
    fun `a partition and its slot are separate documents`() = runBlocking {
        val g = gateway()
        g.write(slot, """{"main":true}""", "")
        g.write(year, """{"year":2026}""", "")

        assertEquals("""{"main":true}""", g.read(slot)?.content)
        assertEquals("""{"year":2026}""", g.read(year)?.content)
    }

    @Test
    fun `partitions keep their own etags`() = runBlocking {
        val g = gateway()
        val first2026 = g.write(year, """{"v":1}""", "")
        val first2025 = g.write(slot.inPartition("2025"), """{"v":1}""", "")

        g.write(year, """{"v":2}""", first2026.updatedAt)
        g.write(slot.inPartition("2025"), """{"v":2}""", first2025.updatedAt)

        assertEquals(listOf("PUT", "PUT", "PUT", "PUT"), server.requests.map { it.method })
    }

    @Test
    fun `a stale partition is a conflict`() {
        val g = gateway()
        runBlocking { g.write(year, """{"v":1}""", "") }

        assertEquals(CloudError.Conflict, errorOf { g.write(year, """{"v":2}""", "2000-01-01T00:00:00Z") })
    }

    @Test
    fun `the partition key is escaped in the url`() = runBlocking {
        gateway().read(slot.inPartition("a?b#c"))

        assertTrue(server.requests.single().url.endsWith("/partitions/a%3Fb%23c"))
    }

    @Test
    fun `lists the partitions of a slot`() = runBlocking {
        server.partitionListBody = """
            {"partitions":[
              {"partition":"2025","sizeBytes":120,"contentHash":"aa","updatedAt":"2026-01-01T00:00:00Z"},
              {"partition":"2026","sizeBytes":340,"contentHash":"bb","updatedAt":"2026-10-01T00:00:00Z"}
            ]}
        """.trimIndent()

        val partitions = gateway().partitions(slot)

        assertEquals(
            listOf(PartitionInfo("2025", "2026-01-01T00:00:00Z"), PartitionInfo("2026", "2026-10-01T00:00:00Z")),
            partitions,
        )
        assertEquals("https://cloud.example/api/v1/documents/calendar.json/partitions", server.requests.single().url)
    }

    @Test
    fun `a slot without partitions lists none`() = runBlocking {
        assertEquals(emptyList<PartitionInfo>(), gateway().partitions(slot))
    }

    @Test
    fun `an unexpected partition list is an unreadable answer`() {
        listOf("""{"partitions":5}""", """{"partitions":[7]}""", """{"partitions":[{"partition":"2026"}]}""", "[]").forEach { body ->
            server.partitionListBody = body

            assertTrue(body, errorOf { gateway().partitions(slot) } is CloudError.Unreadable)
        }
    }

    @Test
    fun `a partition has no partitions of its own`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { gateway().partitions(year) } }
    }

    @Test
    fun `an encrypted partition round trips and hides its content`() = runBlocking {
        val g = gateway(passphrase = "pw")

        g.write(year, """{"secret":"value"}""", "")

        assertTrue(!server.contents.values.single().contains("secret"))
        assertEquals("""{"secret":"value"}""", g.read(year)?.content)
    }

    @Test
    fun `an encrypted partition cannot be passed off as another one`() {
        val g = gateway(passphrase = "pw")
        runBlocking { g.write(year, """{"year":2026}""", "") }
        server.plant("calendar.json/partitions/2025", server.contents.getValue("calendar.json/partitions/2026"))

        val error = errorOf { g.read(slot.inPartition("2025")) }

        assertEquals(UnreadableKind.DECRYPTION_FAILED, (error as CloudError.Unreadable).kind)
    }

    @Test
    fun `an encrypted slot cannot be passed off as a partition`() {
        val g = gateway(passphrase = "pw")
        runBlocking { g.write(slot, """{"main":true}""", "") }
        server.plant("calendar.json/partitions/2026", server.contents.getValue("calendar.json"))

        val error = errorOf { g.read(year) }

        assertEquals(UnreadableKind.DECRYPTION_FAILED, (error as CloudError.Unreadable).kind)
    }
}
