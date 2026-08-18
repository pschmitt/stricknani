package blue.anika.wolle.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues [SyncWorker] (pulls server changes in) and [WriteReplayWorker] (pushes queued local
 * edits out, SNA-8) periodically and on-demand (app launch, pull-to-refresh, right after a local
 * write).
 */
@Singleton
class SyncScheduler @Inject constructor(private val workManager: WorkManager) {

    private val constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val syncRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(SYNC_PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()
        workManager.enqueueUniquePeriodicWork(
            SYNC_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest,
        )

        // More frequent than the pull sync - a queued local edit should flush promptly once
        // connectivity returns, not wait up to SYNC_PERIODIC_INTERVAL_HOURS.
        val replayRequest =
            PeriodicWorkRequestBuilder<WriteReplayWorker>(
                    REPLAY_PERIODIC_INTERVAL_MINUTES,
                    TimeUnit.MINUTES,
                )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()
        workManager.enqueueUniquePeriodicWork(
            REPLAY_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            replayRequest,
        )
    }

    /** Queues an immediate one-off sync without making the caller wait for it. */
    fun syncNow() {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()
        workManager.enqueueUniqueWork(ONE_OFF_SYNC_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Queues an immediate replay-then-sync pass without making the caller wait for it - replay
     * first so a just-made local edit reaches the server before the resulting sync pulls fresh data
     * back down. Called right after every `ProjectRepository`/`YarnRepository` write.
     */
    fun replayThenSyncNow() {
        val replayRequest =
            OneTimeWorkRequestBuilder<WriteReplayWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()
        val syncRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()
        workManager
            .beginUniqueWork(ONE_OFF_REPLAY_WORK_NAME, ExistingWorkPolicy.REPLACE, replayRequest)
            .then(syncRequest)
            .enqueue()
    }

    private companion object {
        const val SYNC_PERIODIC_WORK_NAME = "stricknani_periodic_sync"
        const val REPLAY_PERIODIC_WORK_NAME = "stricknani_periodic_replay"
        const val ONE_OFF_SYNC_WORK_NAME = "stricknani_one_off_sync"
        const val ONE_OFF_REPLAY_WORK_NAME = "stricknani_one_off_replay"
        const val SYNC_PERIODIC_INTERVAL_HOURS = 6L
        const val REPLAY_PERIODIC_INTERVAL_MINUTES = 15L
        const val BACKOFF_DELAY_MINUTES = 15L
    }
}
