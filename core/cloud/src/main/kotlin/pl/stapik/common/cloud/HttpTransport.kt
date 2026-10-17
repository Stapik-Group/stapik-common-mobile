package pl.stapik.common.cloud

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** @property headers response headers with lower-case names. */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * The only thing the cloud client needs from an HTTP library. Blocking; throws
 * [java.io.IOException] when there is no answer (no connection, timeout, ...). Any status,
 * including 4xx and 5xx, is a normal return value.
 */
interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}
