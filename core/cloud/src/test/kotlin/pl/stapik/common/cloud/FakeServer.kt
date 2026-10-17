package pl.stapik.common.cloud

import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/** A stateful stand-in for the cloud: ETag = quoted SHA-256 of the content, 409 on stale If-Match. */
class FakeServer : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var content: String? = null
    var version = 0
    var failWith: IOException? = null
    var forcedStatus: Int? = null
    var extraHeaders: Map<String, String> = emptyMap()
    var scope = "READ_WRITE"
    /** Responses to return for 429 before behaving normally. */
    val rateLimits = ArrayDeque<String?>()

    fun etag(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((content ?: "").toByteArray())
        return "\"" + digest.joinToString("") { "%02x".format(it) } + "\""
    }

    fun updatedAt() = "2026-10-15T08:00:%02dZ".format(version)

    private fun doc(slot: String) = JsonObject(
        mapOf(
            "slotKey" to JsonPrimitive(slot),
            "content" to JsonPrimitive(content ?: ""),
            "contentHash" to JsonPrimitive(etag().trim('"')),
            "updatedAt" to JsonPrimitive(updatedAt()),
        ),
    ).toString()

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        failWith?.let { throw it }
        if (rateLimits.isNotEmpty()) {
            val retry = rateLimits.removeFirst()
            return HttpResponse(429, if (retry != null) mapOf("retry-after" to retry) else emptyMap())
        }
        forcedStatus?.let { return HttpResponse(it) }
        val path = request.url.substringAfter("/api/v1/")
        if (path == "me") {
            return HttpResponse(200, extraHeaders, """{"scope":"$scope","extensionId":"ext-1"}""")
        }
        val slot = path.removePrefix("documents/")
        return when (request.method) {
            "GET" -> if (content == null) HttpResponse(404) else HttpResponse(200, mapOf("etag" to etag()) + extraHeaders, doc(slot))
            "PUT" -> {
                val body = Json.parseToJsonElement(request.body!!) as JsonObject
                val ifMatch = request.headers["if-match"]
                if (content != null && ifMatch != etag()) return HttpResponse(409)
                content = (body["content"] as JsonPrimitive).content
                version++
                HttpResponse(200, mapOf("etag" to etag()), doc(slot))
            }
            else -> HttpResponse(405)
        }
    }
}
