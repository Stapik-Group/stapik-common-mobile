package pl.stapik.common.cloud

import org.junit.Test
import org.junit.Assert.assertEquals
import okhttp3.OkHttpClient
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

    @Test
    fun `a body within the limit is read`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100)))
            server.start()
            val response = OkHttpTransport().execute(HttpRequest("GET", server.url("/a").toString(), maxResponseBytes = 100))
            assertEquals(100, response.body.length)
        }
    }

    @Test
    fun `a body over the limit is refused`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(101)))
            server.start()
            val error = assertFailsWith<ResponseTooLargeException> {
                OkHttpTransport().execute(HttpRequest("GET", server.url("/a").toString(), maxResponseBytes = 100))
            }
            assertEquals(100L, error.limitBytes)
        }
    }

    @Test
    fun `a chunked body over the limit is refused`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(5000), 512))
            server.start()
            assertFailsWith<ResponseTooLargeException> {
                OkHttpTransport().execute(HttpRequest("GET", server.url("/a").toString(), maxResponseBytes = 1000))
            }
        }
    }

    @Test
    fun `without a limit the whole body is read`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(5000)))
            server.start()
            assertEquals(5000, OkHttpTransport().execute(HttpRequest("GET", server.url("/a").toString())).body.length)
        }
    }

    @Test
    fun `does not follow a redirect to another host`() {
        MockWebServer().use { target ->
            MockWebServer().use { origin ->
                target.start()
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/stolen").toString()))
                origin.start()
                val response = OkHttpTransport().execute(
                    HttpRequest("GET", origin.url("/api/v1/me").toString(), mapOf("x-api-key" to "k")),
                )
                assertEquals(302, response.status)
                assertEquals(target.url("/stolen").toString(), response.header("location"))
                assertEquals(0, target.requestCount)
            }
        }
    }

    @Test
    fun `does not follow a redirect even when the supplied client would`() {
        MockWebServer().use { target ->
            MockWebServer().use { origin ->
                target.start()
                origin.enqueue(MockResponse().setResponseCode(307).addHeader("Location", target.url("/stolen").toString()))
                origin.start()
                val following = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
                val response = OkHttpTransport(following).execute(
                    HttpRequest("PUT", origin.url("/api/v1/documents/a.json").toString(), mapOf("x-api-key" to "k"), """{"content":"c"}"""),
                )
                assertEquals(307, response.status)
                assertEquals(0, target.requestCount)
            }
        }
    }
}
