package pl.stapik.common.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import pl.stapik.common.sync.SyncOutcome
import pl.stapik.common.sync.SyncRetryPolicy

/**
 * Background retry of a synchronisation that could not reach the cloud. The app subclasses it and
 * runs its [SyncEngine][pl.stapik.common.sync.SyncEngine] in [runSync]; use the engine, not the
 * screen's controller, so that a finished sync does not cancel the work that is running it.
 */
abstract class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    protected abstract suspend fun runSync(): SyncOutcome

    protected open val maxAttempts: Int = SyncRetryPolicy.DEFAULT_MAX_ATTEMPTS

    final override suspend fun doWork(): Result {
        val outcome = try {
            runSync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if (runAttemptCount + 1 < maxAttempts) Result.retry() else Result.failure()
        }
        return when {
            SyncRetryPolicy.shouldTryAgain(outcome, runAttemptCount + 1, maxAttempts) -> Result.retry()
            SyncRetryPolicy.isWorthRetrying(outcome) -> Result.failure()
            else -> Result.success()
        }
    }
}
