package pl.stapik.common.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import pl.stapik.common.sync.SyncRetry

/**
 * [SyncRetry] on WorkManager: one pending retry at a time, run when the network is back, with
 * exponential backoff between failed attempts.
 *
 * @param workerClass the app's [SyncWorker] subclass.
 */
class WorkManagerSyncRetry(
    context: Context,
    private val workerClass: Class<out SyncWorker>,
    private val uniqueName: String = DEFAULT_NAME,
    private val initialBackoffSeconds: Long = DEFAULT_BACKOFF_SECONDS,
) : SyncRetry {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule() {
        val request = OneTimeWorkRequest.Builder(workerClass)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, initialBackoffSeconds, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(uniqueName)
    }

    companion object {
        const val DEFAULT_NAME = "stapik-sync-retry"
        const val DEFAULT_BACKOFF_SECONDS = 30L
    }
}
