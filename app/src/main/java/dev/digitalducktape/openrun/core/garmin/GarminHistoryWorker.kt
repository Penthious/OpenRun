package dev.digitalducktape.openrun.core.garmin

import android.content.Context
import androidx.work.*
import dev.digitalducktape.openrun.OpenRunApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class GarminHistoryWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val app=applicationContext as OpenRunApplication
        return try {
            var success=true
            app.garmin.state.value.accounts.filterNot { it.needsLogin }.forEach { if(!app.refreshPaces(it.profileId)) success=false }
            if(success) Result.success() else Result.retry()
        } catch(e:CancellationException) { throw e } catch(_:Exception) { Result.retry() }
    }
    companion object {
        fun schedule(context:Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("garmin-pace-history",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GarminHistoryWorker>(7,TimeUnit.DAYS)
                    .setInitialDelay(1,TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
