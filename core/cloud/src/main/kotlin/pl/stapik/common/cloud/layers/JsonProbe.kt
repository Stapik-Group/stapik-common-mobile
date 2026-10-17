package pl.stapik.common.cloud.layers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object JsonProbe {
    /** The text as a JSON object, or null if it is anything else. */
    fun parseObject(text: String): JsonObject? =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        }

    /** A string field, or null if missing or not a string. */
    fun string(obj: JsonObject, name: String): String? =
        (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
