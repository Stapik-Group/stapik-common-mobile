package pl.stapik.common.model

/** Access level of the API key, as reported by Stapik Cloud (`GET me`). */
enum class CloudScope {
    READ_ONLY,
    READ_WRITE;

    val canWrite: Boolean get() = this == READ_WRITE

    companion object {
        /**
         * Parses the scope string sent by the server. A missing or unrecognised value is treated
         * as [READ_ONLY]: it is the safe answer, because it never lets the app attempt a write.
         */
        fun fromWire(value: String?): CloudScope =
            if (value == "READ_WRITE") READ_WRITE else READ_ONLY
    }
}
