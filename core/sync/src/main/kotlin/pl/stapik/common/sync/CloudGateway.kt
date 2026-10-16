package pl.stapik.common.sync

import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.SlotKey

/** Why a call to the cloud failed. Typed, so the engine can react differently to each case. */
sealed interface CloudError {
    /** The API key is missing, wrong or expired. */
    data object Unauthorized : CloudError

    /** The key is valid but not allowed to do this (scope). */
    data object Forbidden : CloudError

    /** The write was rejected because the document changed on the server in the meantime (409). */
    data object Conflict : CloudError

    data class RateLimited(val retryAfterSeconds: Long?) : CloudError

    data class ServerError(val httpCode: Int) : CloudError

    /** No connection, timeout, DNS failure and the like. */
    data class Network(val message: String? = null) : CloudError

    /** The stored document cannot be understood (unknown encoding, encrypted without a passphrase). */
    data class Unreadable(val reason: String) : CloudError
}

/** Thrown by a [CloudGateway] to report a [CloudError]. */
class CloudException(val error: CloudError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** A document as stored in the cloud: its text and the server's version stamp. */
data class RemoteDocument(val content: String, val updatedAt: String)

/**
 * The engine's view of Stapik Cloud. The real implementation (HTTP, gzip, E2E) comes later in
 * `core:cloud`; the engine only depends on this interface. Every method throws [CloudException].
 */
interface CloudGateway {
    /** Scope and identity of the connected cloud (`GET me`). */
    suspend fun info(): CloudInfo

    /** The document in [slot], or null when the slot has no document yet. */
    suspend fun read(slot: SlotKey): RemoteDocument?

    /**
     * Stores [content]. [baseline] is the `updatedAt` the client last saw (empty when creating);
     * if the server's version differs, this throws [CloudError.Conflict].
     */
    suspend fun write(slot: SlotKey, content: String, baseline: String): RemoteDocument
}
