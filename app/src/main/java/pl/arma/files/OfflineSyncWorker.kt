package pl.arma.files

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Okresowa synchronizacja kopii offline w tle, także gdy aplikacja jest zamknięta. */
class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        applicationContext.armaApp.offline.syncAllNow()
        return Result.success()
    }

    companion object {
        private const val NAME = "offline-sync"

        /** [minutes] 0 wyłącza pracę w tle; Android pozwala najczęściej co 15 minut. */
        fun schedule(context: Context, minutes: Int, wifiOnly: Boolean) {
            // Bez inicjalizacji WorkManagera (np. w testach) planowania po prostu nie ma.
            val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            if (minutes <= 0) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build()
                )
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
