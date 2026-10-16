package pl.stapik.common.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The text of a cloud document is not a valid envelope (not JSON, wrong shape). */
class EnvelopeFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The desktop-compatible document envelope `{ "lastUpdate": "...", "payload": { ... } }`.
 *
 * It keeps the **whole parsed JSON tree**, not a typed model, so that a field this app does not
 * know about (for example one added by a newer desktop version) survives a read-modify-write
 * cycle. [withPayload] replaces only `lastUpdate` and `payload`; every other top-level key, and
 * the key order, stays as it was. Typed access to the payload is layered on top of [payload].
 */
class DocumentEnvelope private constructor(private val root: JsonObject) {

    val lastUpdate: String
        get() = (root[LAST_UPDATE] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** The `payload` object; empty if the document has none. */
    val payload: JsonObject
        get() = root[PAYLOAD] as? JsonObject ?: JsonObject(emptyMap())

    /** Returns a copy with new `lastUpdate` and `payload`; all other top-level keys are untouched. */
    fun withPayload(newPayload: JsonObject, newLastUpdate: String): DocumentEnvelope {
        val updated = LinkedHashMap<String, JsonElement>(root)
        updated[LAST_UPDATE] = JsonPrimitive(newLastUpdate)
        updated[PAYLOAD] = newPayload
        return DocumentEnvelope(JsonObject(updated))
    }

    fun toJson(): String = json.encodeToString(JsonElement.serializer(), root)

    companion object {
        private const val LAST_UPDATE = "lastUpdate"
        private const val PAYLOAD = "payload"
        private val json = Json

        /** An envelope with an empty payload, for a brand-new document. */
        fun empty(lastUpdate: String): DocumentEnvelope =
            DocumentEnvelope(
                JsonObject(
                    linkedMapOf(
                        LAST_UPDATE to JsonPrimitive(lastUpdate),
                        PAYLOAD to JsonObject(emptyMap()),
                    ),
                ),
            )

        /**
         * Parses [text]. Throws [EnvelopeFormatException] when it is not a JSON object, when
         * `lastUpdate` is present but not a string, or when `payload` is present but not an object.
         */
        fun parse(text: String): DocumentEnvelope {
            val element = try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw EnvelopeFormatException("Document is not valid JSON", e)
            }
            val obj = element as? JsonObject
                ?: throw EnvelopeFormatException("Document root is not a JSON object")
            val lastUpdate = obj[LAST_UPDATE]
            if (lastUpdate != null && !(lastUpdate is JsonPrimitive && lastUpdate.isString)) {
                throw EnvelopeFormatException("'lastUpdate' is not a string")
            }
            val payload = obj[PAYLOAD]
            if (payload != null && payload !is JsonObject) {
                throw EnvelopeFormatException("'payload' is not an object")
            }
            return DocumentEnvelope(obj)
        }
    }
}
