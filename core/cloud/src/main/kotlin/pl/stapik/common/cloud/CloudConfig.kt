package pl.stapik.common.cloud

enum class ApiUrlStatus { EMPTY, INVALID, INSECURE, SECURE }

/**
 * Connection settings of one Stapik Cloud account.
 *
 * @property passphrase when not empty, documents are end-to-end encrypted with it.
 */
data class CloudConfig(
    val apiUrl: String,
    val apiKey: String,
    val passphrase: String? = null,
) {
    val isConfigured: Boolean get() = normalizedApiUrl.isNotEmpty() && apiKey.isNotEmpty()

    val isEndToEndEncrypted: Boolean get() = !passphrase.isNullOrEmpty()

    /** The URL without surrounding whitespace and without trailing slashes. */
    val normalizedApiUrl: String get() = apiUrl.trim().trimEnd('/')

    /** Base of all API calls, ending with a slash: `<url>/api/v1/`. */
    val baseUrl: String get() = "$normalizedApiUrl/api/v1/"

    override fun toString(): String =
        "CloudConfig(apiUrl=$apiUrl, apiKey=$MASK, passphrase=${passphrase?.let { MASK }})"

    val urlStatus: ApiUrlStatus
        get() {
            val url = normalizedApiUrl
            return when {
                url.isEmpty() -> ApiUrlStatus.EMPTY
                hasSchemeAndHost(url, "https://") -> ApiUrlStatus.SECURE
                hasSchemeAndHost(url, "http://") -> ApiUrlStatus.INSECURE
                else -> ApiUrlStatus.INVALID
            }
        }

    private fun hasSchemeAndHost(url: String, scheme: String): Boolean =
        url.length > scheme.length && url.regionMatches(0, scheme, 0, scheme.length, ignoreCase = true)

    private companion object {
        const val MASK = "***"
    }
}
