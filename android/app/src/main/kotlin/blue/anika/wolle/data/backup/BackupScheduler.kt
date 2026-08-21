package blue.anika.wolle.data.backup

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Enqueues/cancels [BackupWorker] for the scheduled-backup toggle in Settings (SNA-15). */
@Singleton
class BackupScheduler @Inject constructor(private val workManager: WorkManager) {

    fun schedule(frequency: BackupFrequency) {
        val request =
            PeriodicWorkRequestBuilder<BackupWorker>(frequency.intervalDays, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "stricknani_scheduled_backup"
    }
}
