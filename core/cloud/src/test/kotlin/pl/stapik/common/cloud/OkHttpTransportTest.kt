package pl.stapik.common.cloud

import org.junit.Test
import org.junit.Assert.assertEquals
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OkHttpTransportTest {
    @Test
    fun `sends method, headers and body and lower-cases response headers`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).addHeader("ETag", "\"x\"").setBody("{}"))
            server.start()
            val response = OkHttpTransport().execute(
                HttpRequest("PUT", server.url("/api/v1/documents/a.json").toString(), mapOf("x-api-key" to "k", "if-match" to "\"y\""), """{"content":"c"}"""),
            )
            val recorded = server.takeRequest()
            assertEquals("PUT", recorded.method)
            assertEquals("k", recorded.getHeader("x-api-key"))
            assertEquals("\"y\"", recorded.getHeader("If-Match"))
            assertEquals("""{"content":"c"}""", recorded.body.readUtf8())
            assertEquals(409, response.status)
            assertEquals("\"x\"", response.header("etag"))
        }
    }
}
