package pl.stapik.common.cloud

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

/** [HttpTransport] on OkHttp. Share one [OkHttpClient] across the app. */
class OkHttpTransport(
    client: OkHttpClient = defaultClient(),
) : HttpTransport {

    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun execute(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(JSON)
            ?: if (request.method == "GET" || request.method == "HEAD") null else ByteArray(0).toRequestBody(null)
        builder.method(request.method, body)
        client.newCall(builder.build()).execute().use { response ->
            val headers = response.headers.names().associate { name -> name.lowercase() to response.header(name).orEmpty() }
            return HttpResponse(response.code, headers, readBody(response.body, request.maxResponseBytes))
        }
    }

    private fun readBody(body: ResponseBody?, maxBytes: Long?): String {
        if (body == null) return ""
        if (maxBytes != null) {
            if (body.contentLength() > maxBytes || (maxBytes < Long.MAX_VALUE && body.source().request(maxBytes + 1))) {
                throw ResponseTooLargeException(maxBytes)
            }
        }
        return body.string()
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
