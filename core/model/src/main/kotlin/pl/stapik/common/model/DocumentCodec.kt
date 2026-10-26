package pl.stapik.common.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The envelope is well-formed but its payload does not fit the app's model. */
class DocumentDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Typed view of a document's `payload` for one app: turns [DocumentEnvelope] into the app's model
 * [T] and back.
 *
 * Reading ignores unknown fields. Writing keeps unknown **top-level payload keys** from the
 * previous envelope, so a field added to the payload by a newer desktop survives. Unknown fields
 * *nested inside* known structures (for example an extra key on a list item) are not preserved:
 * the typed model cannot carry them. Callers that must not lose such data have to refuse to
 * write a document with a newer schema than they understand ([SchemaGate]).
 *
 * @param serializer serializer of the payload model; it must serialise to a JSON object.
 * @param isEmptyContent whether the model holds no user data (used by the first-connection rule).
 */
class DocumentCodec<T>(
    private val serializer: KSerializer<T>,
    private val isEmptyContent: (T) -> Boolean,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val knownKeys: Set<String> = serializer.descriptor.elementNames.toSet()

    fun decode(envelope: DocumentEnvelope): T =
        try {
            json.decodeFromJsonElement(serializer, envelope.payload)
        } catch (e: SerializationException) {
            throw DocumentDecodeException("Payload does not match the expected model", e)
        } catch (e: IllegalArgumentException) {
            throw DocumentDecodeException("Payload does not match the expected model", e)
        }

    fun encode(content: T, previous: DocumentEnvelope, lastUpdate: String): DocumentEnvelope {
        val encoded = json.encodeToJsonElement(serializer, content) as? JsonObject
            ?: error("Payload model must serialise to a JSON object")
        val preserved = previous.payload.filterKeys { it !in knownKeys }
        return previous.withPayload(JsonObject(encoded + preserved), lastUpdate)
    }

    fun isEmpty(content: T): Boolean = isEmptyContent(content)
}
