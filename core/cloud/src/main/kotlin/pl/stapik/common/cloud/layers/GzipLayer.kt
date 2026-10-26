package pl.stapik.common.cloud.layers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.UnreadableKind

/**
 * The `stapik-gzip` layer of the desktop format:
 * `{"format":"stapik-gzip","version":1,"data":"<base64 of a gzip stream>"}`.
 *
 * A document whose text is at least [thresholdBytes] long (UTF-8) is compressed; one that would not
 * get smaller is sent as it is. Reading accepts both forms. Older desktop versions cannot read
 * compressed documents, so the threshold must stay identical to the desktop's.
 */
internal object GzipLayer {
    const val FORMAT = "stapik-gzip"
    const val VERSION = 1
    const val DEFAULT_THRESHOLD_BYTES = 32 * 1024
    const val MAX_DECOMPRESSED_BYTES = 32L * 1024 * 1024

    fun seal(text: String, thresholdBytes: Int = DEFAULT_THRESHOLD_BYTES): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size < thresholdBytes) return text
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        val encoded = Base64.getEncoder().encodeToString(out.toByteArray())
        if (encoded.length >= bytes.size) return text
        return JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT),
                "version" to JsonPrimitive(VERSION),
                "data" to JsonPrimitive(encoded),
            ),
        ).toString()
    }

    /** Returns the original text; text that is not a gzip blob is returned unchanged. */
    fun open(text: String, maxDecompressedBytes: Long = MAX_DECOMPRESSED_BYTES): String {
        if (!text.contains(FORMAT)) return text
        val root = JsonProbe.parseObject(text) ?: return text
        if (JsonProbe.string(root, "format") != FORMAT) return text

        val version = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (version != VERSION) {
            throw unreadable("The cloud data was compressed by a different version of the application", UnreadableKind.UNSUPPORTED_VERSION)
        }
        val data = JsonProbe.string(root, "data") ?: throw unreadable("The compressed cloud data is damaged")
        val compressed = try {
            Base64.getDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            throw unreadable("The compressed cloud data is damaged")
        }
        return try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { readLimited(it, maxDecompressedBytes) }.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw unreadable("Cannot decompress the cloud data")
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw unreadable("The decompressed cloud data is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun unreadable(reason: String, kind: UnreadableKind = UnreadableKind.OTHER) =
        CloudException(CloudError.Unreadable(reason, kind))
}
