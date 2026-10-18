package pl.stapik.common.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.stapik.common.document.DocumentCorruptedException
import pl.stapik.common.model.DocumentDecodeException
import pl.stapik.common.sync.FirstConnectionChoice
import pl.stapik.common.sync.SyncEngine
import pl.stapik.common.sync.SyncOutcome

enum class LoadErrorKind {
    /** The stored file and its backups cannot be read; offer restoring a safety copy or an import. */
    CORRUPTED,

    /** The data does not fit the app's model (for example written by a newer version). */
    MODEL_MISMATCH,

    UNKNOWN,
}

/** The common Loading / Success / Error shape every app's screen had separately. */
sealed interface DocumentUiState<out T> {
    data object Loading : DocumentUiState<Nothing>
    data class Success<T>(val value: T) : DocumentUiState<T>
    data class Error(val kind: LoadErrorKind) : DocumentUiState<Nothing>
}

/**
 * The state of one synchronised document for a screen: the value, and the sync indicator.
 * An Android ViewModel only owns one of these and forwards its flows.
 *
 * @param connected whether a cloud is configured now.
 */
class DocumentController<T>(
    private val engine: SyncEngine<T>,
    private val connected: suspend () -> Boolean,
) {
    private val mutableDocument = MutableStateFlow<DocumentUiState<T>>(DocumentUiState.Loading)
    private val mutableSync = MutableStateFlow(SyncUiMapper.map(engine.status.value, null, false, false))
    private var lastOutcome: SyncOutcome? = null

    val document: StateFlow<DocumentUiState<T>> = mutableDocument.asStateFlow()
    val sync: StateFlow<SyncUiState> = mutableSync.asStateFlow()

    /** Reads the local document and refreshes the indicator. Never touches the cloud. */
    suspend fun load() {
        mutableDocument.value = DocumentUiState.Loading
        mutableDocument.value = try {
            DocumentUiState.Success(engine.load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: DocumentCorruptedException) {
            DocumentUiState.Error(LoadErrorKind.CORRUPTED)
        } catch (e: DocumentDecodeException) {
            DocumentUiState.Error(LoadErrorKind.MODEL_MISMATCH)
        } catch (e: Exception) {
            DocumentUiState.Error(LoadErrorKind.UNKNOWN)
        }
        refreshIndicator()
    }

    /** Applies the edit; the new value is shown even when sync fails. */
    suspend fun edit(transform: (T) -> T): SyncOutcome {
        val outcome = engine.edit(transform)
        finish(outcome)
        return outcome
    }

    suspend fun sync(choice: FirstConnectionChoice? = null): SyncOutcome {
        mutableSync.value = SyncUiState(SyncBadge.SYNCING, "sync.syncing")
        val outcome = engine.sync(choice)
        finish(outcome)
        return outcome
    }

    private suspend fun finish(outcome: SyncOutcome) {
        lastOutcome = outcome
        if (outcome !is SyncOutcome.NewerSchema) {
            mutableDocument.value = try {
                DocumentUiState.Success(engine.load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DocumentUiState.Error(LoadErrorKind.UNKNOWN)
            }
        }
        refreshIndicator()
    }

    private suspend fun refreshIndicator() {
        val pending = try {
            engine.hasPendingChanges()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // the document is unreadable; the document state already says so
        }
        mutableSync.value = SyncUiMapper.map(engine.status.value, lastOutcome, pending, connected())
    }
}
