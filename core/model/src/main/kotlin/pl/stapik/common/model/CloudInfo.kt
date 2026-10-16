package pl.stapik.common.model

/**
 * What the app knows about the cloud it is connected to (from `GET me`).
 *
 * @property scope what the API key may do.
 * @property identity identifies the cloud account (base URL + extensionId); two different values
 *   mean two different clouds, so a baseline taken from one is meaningless for the other.
 */
data class CloudInfo(val scope: CloudScope, val identity: String)
