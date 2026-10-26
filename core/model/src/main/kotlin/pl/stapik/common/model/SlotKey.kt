package pl.stapik.common.model

/**
 * Address of a document in Stapik Cloud: a slot such as `budget.json` or `calendar.json`, or one
 * partition of a slot (`documents/{slot}/partitions/{partition}`), for example the year `2026` of a
 * calendar. A partition is a document of its own: it has its own version, baseline and conflicts.
 *
 * Both parts end up as a single URL path segment, so the constructor rejects values that could not
 * be one: empty strings, path separators, whitespace and control characters. This is a client-side
 * sanity check, not a copy of the server's own rules.
 */
data class SlotKey(val value: String, val partition: String? = null) {
    init {
        requireSegment(value, "Slot key")
        partition?.let { requireSegment(it, "Partition key") }
    }

    val isPartition: Boolean get() = partition != null

    fun inPartition(key: String): SlotKey {
        require(!isPartition) { "A partition cannot have partitions: '$this'" }
        return SlotKey(value, key)
    }

    override fun toString(): String = if (partition == null) value else "$value/$partition"

    private companion object {
        fun requireSegment(text: String, what: String) {
            require(text.isNotEmpty()) { "$what must not be empty" }
            require(text.none { it == '/' || it == '\\' || it.isWhitespace() || it.isISOControl() }) {
                "$what must not contain path separators, whitespace or control characters: '$text'"
            }
        }
    }
}
