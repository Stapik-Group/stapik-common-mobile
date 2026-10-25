package pl.stapik.common.model

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

object CloudTimestamp {
    fun sameInstant(first: String, second: String?): Boolean {
        if (second == null) return false
        if (first == second) return true
        val a = parse(first) ?: return false
        val b = parse(second) ?: return false
        return a.isEqual(b)
    }

    private fun parse(text: String): OffsetDateTime? =
        try {
            OffsetDateTime.parse(text.trim())
        } catch (_: DateTimeParseException) {
            null
        }
}
