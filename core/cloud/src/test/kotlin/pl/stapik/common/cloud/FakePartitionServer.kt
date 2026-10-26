package pl.stapik.common.cloud

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FakePartitionServer : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    val contents = linkedMapOf<String, String>()
    var partitionListBody: String? = null
    private var version = 0
    private val updatedAts = mutableMapOf<String, String>()

    fun plant(path: String, content: String) {
        contents[path] = content
        updatedAts[path] = "2026-10-01T00:00:00Z"
    }

    private fun etag(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(contents.getValue(path).toByteArray())
        return "\"" + digest.joinToString("") { "%02x".format(it) } + "\""
    }

    private fun body(path: String) = JsonObject(
        mapOf(
            "slotKey" to JsonPrimitive(path),
            "content" to JsonPrimitive(contents.getValue(path)),
            "updatedAt" to JsonPrimitive(updatedAts.getValue(path)),
        ),
    ).toString()

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val path = request.url.substringAfter("/api/v1/documents/")
        if (path.endsWith("/partitions")) {
            return partitionListBody?.let { HttpResponse(200, emptyMap(), it) } ?: HttpResponse(404)
        }
        return when (request.method) {
            "GET" -> if (path in contents) HttpResponse(200, mapOf("etag" to etag(path)), body(path)) else HttpResponse(404)
            "PUT" -> {
                if (path in contents && request.headers["if-match"] != etag(path)) return HttpResponse(409)
                contents[path] = ((Json.parseToJsonElement(request.body!!) as JsonObject)["content"] as JsonPrimitive).content
                updatedAts[path] = "2026-10-15T08:00:%02dZ".format(++version)
                HttpResponse(200, mapOf("etag" to etag(path)), body(path))
            }
            else -> HttpResponse(405)
        }
    }
}
