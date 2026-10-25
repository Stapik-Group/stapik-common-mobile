package pl.stapik.common.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import pl.stapik.common.cloud.ApiUrlStatus
import pl.stapik.common.cloud.CloudConfig
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.settings.ConnectionConfigStore
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.CloudGateway

/** State of the connect form. */
data class ConnectFormState(
    val apiUrl: String = "",
    val apiKey: String = "",
    val passphrase: String = "",
    val keyVisible: Boolean = false,
    val busy: Boolean = false,
    /** Key of the message about the last failed attempt, if any. */
    val errorKey: String? = null,
) {
    private val config get() = CloudConfig(apiUrl, apiKey, passphrase.ifEmpty { null })

    val urlStatus: ApiUrlStatus get() = config.urlStatus

    /** http:// is allowed but the key then travels unencrypted; the UI shows a warning. */
    val showInsecureWarning: Boolean get() = urlStatus == ApiUrlStatus.INSECURE

    val showInvalidUrl: Boolean get() = apiUrl.isNotBlank() && urlStatus == ApiUrlStatus.INVALID

    val canSubmit: Boolean
        get() = !busy && config.isConfigured && (urlStatus == ApiUrlStatus.SECURE || urlStatus == ApiUrlStatus.INSECURE)

    fun toConfig(): CloudConfig = config

    override fun toString(): String =
        "ConnectFormState(apiUrl=$apiUrl, apiKey=***, passphrase=***, keyVisible=$keyVisible, busy=$busy, errorKey=$errorKey)"
}

sealed interface ConnectOutcome {
    data class Connected(val info: CloudInfo) : ConnectOutcome
    data class Failed(val messageKey: String) : ConnectOutcome
}

/**
 * The connect screen's logic: edits the form, checks the key against the cloud (`GET me`), and
 * only then saves the settings, so a wrong key never replaces a working connection.
 *
 * @param gatewayFactory builds a client for a configuration (an [pl.stapik.common.cloud.HttpCloudGateway] in the app).
 * @param initial the saved configuration, to prefill the form.
 */
class ConnectController(
    private val store: ConnectionConfigStore,
    private val gatewayFactory: (CloudConfig) -> CloudGateway,
    initial: CloudConfig? = null,
) {
    private val mutableState = MutableStateFlow(
        ConnectFormState(initial?.apiUrl.orEmpty(), initial?.apiKey.orEmpty(), initial?.passphrase.orEmpty()),
    )
    val state: StateFlow<ConnectFormState> = mutableState.asStateFlow()

    fun setApiUrl(value: String) = mutableState.update { it.copy(apiUrl = value, errorKey = null) }
    fun setApiKey(value: String) = mutableState.update { it.copy(apiKey = value, errorKey = null) }
    fun setPassphrase(value: String) = mutableState.update { it.copy(passphrase = value, errorKey = null) }
    fun toggleKeyVisible() = mutableState.update { it.copy(keyVisible = !it.keyVisible) }

    suspend fun connect(): ConnectOutcome {
        val form = mutableState.value
        if (!form.canSubmit) return ConnectOutcome.Failed(INVALID_FORM)
        mutableState.update { it.copy(busy = true, errorKey = null) }
        val config = form.toConfig()
        return try {
            val info = gatewayFactory(config).info()
            withContext(Dispatchers.IO) { store.save(config) }
            mutableState.update { it.copy(busy = false) }
            ConnectOutcome.Connected(info)
        } catch (e: CloudException) {
            fail(errorKey(e.error))
        } catch (e: CancellationException) {
            mutableState.update { it.copy(busy = false) }
            throw e
        } catch (e: Exception) {
            fail(UNEXPECTED)
        }
    }

    /** Forgets the saved connection; local data stays. */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) { store.clear() }
        mutableState.update { ConnectFormState() }
    }

    private fun fail(key: String): ConnectOutcome {
        mutableState.update { it.copy(busy = false, errorKey = key) }
        return ConnectOutcome.Failed(key)
    }

    private fun errorKey(error: CloudError): String = when (error) {
        CloudError.Unauthorized -> "connect.error.unauthorized"
        CloudError.Forbidden -> "connect.error.forbidden"
        is CloudError.Network -> "connect.error.network"
        is CloudError.ServerError -> "connect.error.server"
        is CloudError.RateLimited -> "connect.error.rate_limited"
        CloudError.Conflict, is CloudError.Unreadable -> UNEXPECTED
    }

    private companion object {
        const val INVALID_FORM = "connect.error.invalid_form"
        const val UNEXPECTED = "connect.error.unexpected"
    }
}
