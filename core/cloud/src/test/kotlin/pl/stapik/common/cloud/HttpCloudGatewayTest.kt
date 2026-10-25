package pl.stapik.common.cloud

import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.Assert.assertEquals
import pl.stapik.common.cloud.assertFailsWith
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.CloudError
import kotlin.coroutines.CoroutineContext

class HttpCloudGatewayTest {
    private val slot = SlotKey("budget.json")
    private val server = FakeServer()
    private val sleeps = mutableListOf<Long>()
    private val expiries = mutableListOf<Instant>()

    private fun gateway(passphrase: String? = null, url: String = " https://cloud.example/ ") = HttpCloudGateway(
        CloudConfig(url, "key-1", passphrase),
        server,
        onApiKeyExpiry = { expiries += it },
        sleeper = { sleeps += it },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun gatewayOn(dispatcher: CoroutineDispatcher, passphrase: String? = null) = HttpCloudGateway(
        CloudConfig("https://cloud.example", "key-1", passphrase),
        server,
        sleeper = { sleeps += it },
        dispatcher = dispatcher,
    )

    private fun countingDispatcher(counter: AtomicInteger) = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            counter.incrementAndGet()
            block.run()
        }
    }

    private fun errorOf(block: suspend () -> Unit): CloudError =
        assertFailsWith<pl.stapik.common.sync.CloudException> { runBlocking { block() } }.error

    @Test
    fun `normalizes the url and sends the key`() = runBlocking {
        gateway().info()
        val request = server.requests.single()
        assertEquals("https://cloud.example/api/v1/me", request.url)
        assertEquals("key-1", request.headers["x-api-key"])
    }

    @Test
    fun `info reports scope and identity`() = runBlocking {
        val info = gateway().info()
        assertEquals(CloudScope.READ_WRITE, info.scope)
        assertEquals("https://cloud.example|ext-1", info.identity)
        server.scope = "READ_ONLY"
        assertEquals(CloudScope.READ_ONLY, gateway().info().scope)
    }

    @Test
    fun `missing document is null`() = runBlocking { assertNull(gateway().read(slot)) }

    @Test
    fun `create then update through etags without extra reads`() = runBlocking {
        val g = gateway()
        val first = g.write(slot, """{"v":1}""", "")
        assertEquals("PUT", server.requests.last().method)
        assertNull(server.requests.last().headers["if-match"])
        val second = g.write(slot, """{"v":2}""", first.updatedAt)
        assertEquals(listOf("PUT", "PUT"), server.requests.map { it.method })
        assertTrue(server.requests.last().headers["if-match"] != null)
        assertEquals("""{"v":2}""", g.read(slot)?.content)
        assertEquals(second.updatedAt, g.read(slot)?.updatedAt)
    }

    @Test
    fun `fresh gateway reads once to get the etag`() = runBlocking {
        val first = gateway().write(slot, """{"v":1}""", "")
        val g = gateway()
        g.write(slot, """{"v":2}""", first.updatedAt)
        assertEquals(listOf("PUT", "GET", "PUT"), server.requests.map { it.method })
    }

    @Test
    fun `stale baseline is a conflict without a PUT`() {
        runBlocking { gateway().write(slot, """{"v":1}""", "") }
        val before = server.requests.size
        val error = errorOf { gateway().write(slot, """{"v":2}""", "2000-01-01T00:00:00Z") }
        assertEquals(CloudError.Conflict, error)
        assertEquals(listOf("GET"), server.requests.drop(before).map { it.method })
    }

    @Test
    fun `server side change after the read is a conflict from 409`() {
        val g = gateway()
        val first = runBlocking { g.write(slot, """{"v":1}""", "") }
        server.content = """{"other":true}"""
        server.version++
        assertEquals(CloudError.Conflict, errorOf { g.write(slot, """{"v":2}""", first.updatedAt) })
    }

    @Test
    fun `baseline for a deleted document is a conflict`() {
        assertEquals(CloudError.Conflict, errorOf { gateway().write(slot, "{}", "2026-01-01T00:00:00Z") })
    }

    @Test
    fun `legacy client last known update is sent`() = runBlocking {
        val g = gateway()
        val first = g.write(slot, "{}", "")
        g.write(slot, "{}", first.updatedAt)
        val body = Json.parseToJsonElement(server.requests.last().body!!) as JsonObject
        assertEquals(first.updatedAt, (body["clientLastKnownUpdate"] as kotlinx.serialization.json.JsonPrimitive).content)
        val firstBody = Json.parseToJsonElement(server.requests.first().body!!) as JsonObject
        assertTrue("clientLastKnownUpdate" !in firstBody)
    }

    @Test
    fun `encrypted content on the server is not plaintext and round trips`() = runBlocking {
        val g = gateway(passphrase = "pw")
        g.write(slot, """{"secret":"value"}""", "")
        assertTrue(!server.content!!.contains("secret"))
        assertEquals("""{"secret":"value"}""", g.read(slot)?.content)
    }

    @Test
    fun `large content is compressed on the wire`() = runBlocking {
        val big = """{"items":[""" + (0 until 4000).joinToString(",") { """{"id":$it}""" } + "]}"
        val g = gateway()
        g.write(slot, big, "")
        assertTrue(server.content!!.length < big.length)
        assertEquals(big, g.read(slot)?.content)
    }

    @Test
    fun `empty server content reads as an empty document`() = runBlocking {
        server.content = ""
        assertEquals("{}", gateway().read(slot)?.content)
    }

    @Test
    fun `status codes map to typed errors`() {
        server.forcedStatus = 401
        assertEquals(CloudError.Unauthorized, errorOf { gateway().read(slot) })
        server.forcedStatus = 403
        assertEquals(CloudError.Forbidden, errorOf { gateway().read(slot) })
        server.forcedStatus = 503
        assertEquals(CloudError.ServerError(503), errorOf { gateway().read(slot) })
    }

    @Test
    fun `a redirect is a server error`() {
        server.forcedStatus = 302
        assertEquals(CloudError.ServerError(302), errorOf { gateway().read(slot) })
    }

    @Test
    fun `io failure is a network error`() {
        server.failWith = IOException("down")
        assertEquals(CloudError.Network("down"), errorOf { gateway().read(slot) })
    }

    @Test
    fun `running out of memory is an unreadable document`() {
        server.failWithError = OutOfMemoryError()
        val error = errorOf { gateway().read(slot) }
        assertTrue(error is CloudError.Unreadable)
    }

    @Test
    fun `reading hands the transport, parsing and decoding to the dispatcher`() {
        server.content = """{"v":1}"""
        val dispatches = AtomicInteger()
        runBlocking { gatewayOn(countingDispatcher(dispatches), passphrase = "pw").read(slot) }
        assertEquals(3, dispatches.get())
    }

    @Test
    fun `writing hands sealing, the transport and parsing to the dispatcher`() {
        val dispatches = AtomicInteger()
        runBlocking { gatewayOn(countingDispatcher(dispatches), passphrase = "pw").write(slot, """{"v":1}""", "") }
        assertEquals(3, dispatches.get())
    }

    @Test
    fun `a response over the limit is an unreadable document`() {
        server.failWith = ResponseTooLargeException(1024)
        assertTrue(errorOf { gateway().read(slot) } is CloudError.Unreadable)
    }

    @Test
    fun `every request carries the response limit`() = runBlocking {
        val g = gateway()
        val first = g.write(slot, """{"v":1}""", "")
        g.read(slot)
        g.info()
        g.write(slot, """{"v":2}""", first.updatedAt)
        assertTrue(server.requests.all { it.maxResponseBytes == HttpCloudGateway.DEFAULT_MAX_RESPONSE_BYTES })
    }

    @Test
    fun `short rate limit is waited out`() = runBlocking {
        server.rateLimits += "2"
        assertNull(gateway().read(slot))
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun `long rate limit is reported`() {
        server.rateLimits += "120"
        assertEquals(CloudError.RateLimited(120), errorOf { gateway().read(slot) })
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `api key expiry header is reported`() = runBlocking {
        server.extraHeaders = mapOf("x-api-key-expires-at" to "2026-12-01T00:00:00Z")
        gateway().info()
        assertEquals(listOf(Instant.parse("2026-12-01T00:00:00Z")), expiries)
    }

    @Test
    fun `url status`() {
        assertEquals(ApiUrlStatus.EMPTY, CloudConfig(" ", "k").urlStatus)
        assertEquals(ApiUrlStatus.SECURE, CloudConfig("https://a.b/", "k").urlStatus)
        assertEquals(ApiUrlStatus.INSECURE, CloudConfig("http://a.b", "k").urlStatus)
        assertEquals(ApiUrlStatus.INVALID, CloudConfig("a.b", "k").urlStatus)
        assertEquals("https://a.b/api/v1/", CloudConfig("https://a.b//", "k").baseUrl)
    }
}
