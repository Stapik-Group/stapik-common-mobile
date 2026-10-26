package pl.stapik.common.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.stapik.common.document.DocumentCorruptedException
import pl.stapik.common.model.DocumentDecodeException
import pl.stapik.common.sync.FirstConnectionChoice
import pl.stapik.common.sync.SyncEngine
import pl.stapik.common.sync.SyncOutcome
import pl.stapik.common.sync.SyncRetry
import pl.stapik.common.sync.SyncRetryPolicy

enum class LoadErrorKind {
    /** The stored file and its backups cannot be read; offer restoring a safety copy or an import. */
    CORRUPTED,

    /** The data is intact but cannot be decrypted: the device key is gone (restore on another device, reset of the key store). */
    UNDECRYPTABLE,

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
 * @param scope runs the delayed sync of [editDebounced]; without it every edit syncs at once.
 * @param debounceMillis how long [editDebounced] waits for the next edit before syncing.
 * @param waiter waits for the given milliseconds; replaced in tests.
 * @param retry scheduled after a sync that could not reach the cloud, cancelled by any other outcome.
 * @param connected whether a cloud is configured now.
 */
class DocumentController<T>(
    private val engine: SyncEngine<T>,
    private val scope: CoroutineScope? = null,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val waiter: suspend (Long) -> Unit = { delay(it) },
    private val retry: SyncRetry = SyncRetry.None,
    private val connected: suspend () -> Boolean,
) {
    private val mutableDocument = MutableStateFlow<DocumentUiState<T>>(DocumentUiState.Loading)
    private val mutableSync = MutableStateFlow(SyncUiMapper.map(engine.status.value, null, false, false))
    private var lastOutcome: SyncOutcome? = null
    private var resolvedNotice: SyncOutcome? = null
    private var debounce: Job? = null
    private var debounceWaiting = false

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
            DocumentUiState.Error(if (e.undecryptable) LoadErrorKind.UNDECRYPTABLE else LoadErrorKind.CORRUPTED)
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

    /**
     * Saves the edit at once and syncs [debounceMillis] after the last one, so a burst of edits is
     * one round trip. Returns null while the sync is still waiting, or the outcome when the edit was
     * refused or no [scope] was given (then it is the same as [edit]).
     */
    suspend fun editDebounced(transform: (T) -> T): SyncOutcome? {
        val scope = scope ?: return edit(transform)
        val refused = engine.editLocally(transform)
        if (refused != null) {
            finish(refused)
            return refused
        }
        cancelWaiting()
        debounceWaiting = true
        debounce = scope.launch {
            waiter(debounceMillis)
            debounceWaiting = false
            sync()
        }
        reloadDocument()
        refreshIndicator()
        return null
    }

    /** Syncs now if an edit is waiting for [editDebounced]'s delay; call it when the screen goes away. */
    suspend fun flushPendingSync(): SyncOutcome? {
        if (!debounceWaiting) return null
        cancelWaiting()
        return sync()
    }

    private fun cancelWaiting() {
        if (debounceWaiting) debounce?.cancel()
        debounceWaiting = false
    }

    suspend fun sync(choice: FirstConnectionChoice? = null): SyncOutcome {
        mutableSync.value = SyncUiState(SyncBadge.SYNCING, "sync.syncing")
        val outcome = engine.sync(choice)
        finish(outcome)
        return outcome
    }

    suspend fun dismissResolvedNotice() {
        resolvedNotice = null
        refreshIndicator()
    }

    private suspend fun finish(outcome: SyncOutcome) {
        if (SyncRetryPolicy.isWorthRetrying(outcome)) retry.schedule() else retry.cancel()
        if (outcome.isConflictResolution()) {
            resolvedNotice = outcome
            lastOutcome = SyncOutcome.Synchronized
        } else {
            lastOutcome = outcome
        }
        if (outcome !is SyncOutcome.NewerSchema) reloadDocument()
        refreshIndicator()
    }

    private suspend fun reloadDocument() {
        mutableDocument.value = try {
            DocumentUiState.Success(engine.load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DocumentUiState.Error(LoadErrorKind.UNKNOWN)
        }
    }

    private suspend fun refreshIndicator() {
        val pending = try {
            engine.hasPendingChanges()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // the document is unreadable; the document state already says so
        }
        val current = SyncUiMapper.map(engine.status.value, lastOutcome, pending, connected())
        val notice = resolvedNotice
        mutableSync.value =
            if (current.isQuiet && notice != null) SyncUiMapper.map(engine.status.value, notice, pending, connected()) else current
    }

    private fun SyncOutcome.isConflictResolution(): Boolean = this == SyncOutcome.ServerWon || this == SyncOutcome.LocalWon

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 2_000L
    }
}
