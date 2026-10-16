package pl.stapik.common.model

/**
 * Name of a document slot in Stapik Cloud, for example `budget.json` or `calendar.json`.
 *
 * The key ends up as a single URL path segment (`documents/{slotKey}`), so the constructor rejects
 * values that could not be one: empty strings, path separators, whitespace and control characters.
 * This is a client-side sanity check, not a copy of the server's own rules.
 */
@JvmInline
value class SlotKey(val value: String) {
    init {
        require(value.isNotEmpty()) { "Slot key must not be empty" }
        require(value.none { it == '/' || it == '\\' || it.isWhitespace() || it.isISOControl() }) {
            "Slot key must not contain path separators, whitespace or control characters: '$value'"
        }
    }

    override fun toString(): String = value
}
