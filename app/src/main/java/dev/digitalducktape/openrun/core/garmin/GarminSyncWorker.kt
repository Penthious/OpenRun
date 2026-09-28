package dev.digitalducktape.openrun.core.garmin

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.digitalducktape.openrun.OpenRunApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class GarminSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val manager = (applicationContext as OpenRunApplication).garmin
        if (manager.sync()) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun enqueue(context: Context) {
            scheduleRecovery(context)
            WorkManager.getInstance(context).enqueueUniqueWork("garmin-upload", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GarminSyncWorker>().setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        }

        fun scheduleRecovery(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("garmin-recovery", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GarminSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
        }
    }
}
