package pl.stapik.common.cloud

import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.stapik.common.cloud.layers.ContentLayers
import pl.stapik.common.cloud.layers.JsonProbe
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.CloudGateway
import pl.stapik.common.sync.RemoteDocument

/**
 * [CloudGateway] over the Stapik Cloud REST API (`/api/v1/documents/{slot}`, `/api/v1/me`).
 *
 * Conflicts: the server (>= 1.2.0) rejects a PUT whose `If-Match` is not the ETag of the stored
 * document. The gateway remembers the ETag from the last read or write of each slot; when it does
 * not match the caller's baseline it reads the document once, and a different `updatedAt` is a
 * conflict without sending anything. `clientLastKnownUpdate` is sent as well for older servers.
 *
 * A 429 with `Retry-After` of up to [MAX_AUTO_WAIT_SECONDS] is waited out once; anything longer is
 * reported as [CloudError.RateLimited].
 *
 * @param onApiKeyExpiry called with the expiry time announced by the server (`X-Api-Key-Expires-At`).
 * @param sleeper waits for the given milliseconds; replaced in tests.
 */
class HttpCloudGateway(
    private val config: CloudConfig,
    private val transport: HttpTransport,
    private val onApiKeyExpiry: (Instant) -> Unit = {},
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gzipThresholdBytes: Int = 32 * 1024,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    private val infoTtlMillis: Long = DEFAULT_INFO_TTL_MILLIS,
    private val uptimeMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : CloudGateway {

    private class Known(val updatedAt: String, val etag: String?)

    private class CachedInfo(val info: CloudInfo, val takenAt: Long)

    private val layers = HashMap<SlotKey, ContentLayers>()
    private val known = HashMap<SlotKey, Known>()
    private var cachedInfo: CachedInfo? = null

    @Synchronized
    private fun layersFor(slot: SlotKey): ContentLayers =
        layers.getOrPut(slot) { ContentLayers(slot, config.passphrase, gzipThresholdBytes) }

    @Synchronized
    private fun remember(slot: SlotKey, value: Known?) {
        if (value == null) known.remove(slot) else known[slot] = value
    }

    @Synchronized
    private fun knownFor(slot: SlotKey): Known? = known[slot]

    @Synchronized
    private fun freshInfo(): CloudInfo? =
        cachedInfo?.takeIf { uptimeMillis() - it.takenAt < infoTtlMillis }?.info

    @Synchronized
    private fun rememberInfo(info: CloudInfo) {
        cachedInfo = CachedInfo(info, uptimeMillis())
    }

    @Synchronized
    private fun forgetInfo() {
        cachedInfo = null
    }

    override suspend fun info(): CloudInfo {
        freshInfo()?.let { return it }
        val response = send(newRequest("GET", config.baseUrl + "me"))
        expectOk(response)
        val root = parseObject(response.body)
        val scope = CloudScope.fromWire(JsonProbe.string(root, "scope"))
        val extensionId = JsonProbe.string(root, "extensionId").orEmpty()
        return CloudInfo(scope, "${config.normalizedApiUrl}|$extensionId").also(::rememberInfo)
    }

    override suspend fun read(slot: SlotKey): RemoteDocument? {
        val response = send(newRequest("GET", documentUrl(slot)))
        if (response.status == 404) {
            remember(slot, null)
            return null
        }
        expectOk(response)
        val document = parseDocument(response)
        remember(slot, Known(document.updatedAt, response.header("etag")))
        val content = runOnWorker { layersFor(slot).open(document.content) }
        return RemoteDocument(content, document.updatedAt)
    }

    override suspend fun write(slot: SlotKey, content: String, baseline: String): RemoteDocument {
        val etag = etagFor(slot, baseline)
        val body = runOnWorker { requestBody(slot, content, baseline) }
        val extra = if (etag != null) mapOf("if-match" to etag) else emptyMap()
        val response = send(newRequest("PUT", documentUrl(slot), extra, body))
        if (response.status == 409) {
            remember(slot, null)
            throw CloudException(CloudError.Conflict)
        }
        expectOk(response)
        val saved = parseDocument(response)
        remember(slot, Known(saved.updatedAt, response.header("etag")))
        return RemoteDocument(content, saved.updatedAt)
    }

    /**
     * The ETag to put in `If-Match`, or null when creating a document. Throws
     * [CloudError.Conflict] when the server's document is not the one the caller based on.
     */
    private suspend fun etagFor(slot: SlotKey, baseline: String): String? {
        if (baseline.isEmpty()) return null
        val remembered = knownFor(slot)
        if (remembered != null && remembered.updatedAt == baseline && remembered.etag != null) return remembered.etag

        val response = send(newRequest("GET", documentUrl(slot)))
        if (response.status == 404) {
            remember(slot, null)
            throw CloudException(CloudError.Conflict)
        }
        expectOk(response)
        val current = parseDocument(response)
        val etag = response.header("etag")
        remember(slot, Known(current.updatedAt, etag))
        if (current.updatedAt != baseline) throw CloudException(CloudError.Conflict)
        return etag
    }

    private fun requestBody(slot: SlotKey, content: String, baseline: String): String =
        JsonObject(
            buildMap {
                put("content", JsonPrimitive(layersFor(slot).seal(content)))
                if (baseline.isNotEmpty()) put("clientLastKnownUpdate", JsonPrimitive(baseline))
            },
        ).toString()

    private class Wire(val content: String, val updatedAt: String)

    private suspend fun parseDocument(response: HttpResponse): Wire = runOnWorker {
        val root = parseObject(response.body)
        val content = JsonProbe.string(root, "content") ?: throw unreadable("The server sent a document without content")
        val updatedAt = JsonProbe.string(root, "updatedAt") ?: throw unreadable("The server sent a document without updatedAt")
        Wire(content, updatedAt)
    }

    private fun parseObject(text: String): JsonObject =
        JsonProbe.parseObject(text) ?: throw unreadable("The server sent an unexpected answer")

    private fun unreadable(reason: String) = CloudException(CloudError.Unreadable(reason))

    private fun documentUrl(slot: SlotKey): String = config.baseUrl + "documents/" + slot.value

    private fun newRequest(method: String, url: String, extraHeaders: Map<String, String> = emptyMap(), body: String? = null) =
        HttpRequest(method, url, headers() + extraHeaders, body, maxResponseBytes)

    private fun headers(): Map<String, String> =
        mapOf("x-api-key" to config.apiKey, "accept" to "application/json", "content-type" to "application/json")

    private fun expectOk(response: HttpResponse) {
        if (response.status in 200..299) return
        if (response.status == 401 || response.status == 403) forgetInfo()
        throw CloudException(
            when (response.status) {
                401 -> CloudError.Unauthorized
                403 -> CloudError.Forbidden
                409 -> CloudError.Conflict
                429 -> CloudError.RateLimited(retryAfter(response))
                else -> CloudError.ServerError(response.status)
            },
        )
    }

    private fun retryAfter(response: HttpResponse): Long? = response.header("retry-after")?.trim()?.toLongOrNull()

    private suspend fun send(request: HttpRequest): HttpResponse {
        var response = execute(request)
        if (response.status == 429) {
            val wait = retryAfter(response)
            if (wait != null && wait in 0..MAX_AUTO_WAIT_SECONDS) {
                sleeper(wait * 1000)
                response = execute(request)
            }
        }
        return response
    }

    private suspend fun execute(request: HttpRequest): HttpResponse {
        val response = try {
            runOnWorker { transport.execute(request) }
        } catch (e: ResponseTooLargeException) {
            throw CloudException(CloudError.Unreadable(TOO_LARGE_REASON), e)
        } catch (e: IOException) {
            throw CloudException(CloudError.Network(e.message), e)
        } catch (e: IllegalArgumentException) {
            throw CloudException(CloudError.Network(e.message), e)
        }
        response.header("x-api-key-expires-at")?.let { text ->
            try {
                onApiKeyExpiry(Instant.parse(text.trim()))
            } catch (_: DateTimeParseException) {
            }
        }
        return response
    }

    private suspend fun <T> runOnWorker(block: () -> T): T =
        withContext(dispatcher) {
            try {
                block()
            } catch (e: OutOfMemoryError) {
                throw CloudException(CloudError.Unreadable(TOO_LARGE_REASON), e)
            }
        }

    companion object {
        const val MAX_AUTO_WAIT_SECONDS = 30L
        const val DEFAULT_MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        const val DEFAULT_INFO_TTL_MILLIS = 5L * 60 * 1000
        private const val TOO_LARGE_REASON = "The document is too large to process on this device"
    }
}
