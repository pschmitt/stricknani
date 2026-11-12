package blue.anika.wolle.data.backups

import android.content.Context
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.timer.ProjectTimerReminderScheduler
import blue.anika.wolle.data.timer.RunningTimers
import blue.anika.wolle.ui.common.MutationFeedback
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compares the server's data epoch (`GET /api/v2/meta`) with the one this install last saw. After
 * an administrator restored a backup it drops everything bound to the old data: the Room cache, the
 * queued offline writes and sync cursors (all in the database), queued uploads and running timers.
 * The next sync then loads everything again. Changes on the device that were not synced are lost; a
 * snackbar says so. Credentials stay: the account is the same.
 */
@Singleton
class DataEpochGuard
@Inject
constructor(
    private val metaApi: MetaV2Api,
    settings: SettingsRepository,
    private val database: AppDatabase,
    private val runningTimers: RunningTimers,
    private val reminders: ProjectTimerReminderScheduler,
    private val feedback: MutationFeedback,
    @ApplicationContext private val context: Context,
) {
    private val tracker =
        DataEpochTracker(settings) {
            reminders.cancelAll()
            reminders.clearSavedDeadlines()
            runningTimers.clearAll()
            withContext(Dispatchers.IO) {
                database.clearAllTables()
                runCatching { File(context.filesDir, "pending-uploads").deleteRecursively() }
            }
        }

    /**
     * Asks the server for its epoch and applies [decideEpoch]. Offline or failing: does nothing.
     */
    suspend fun check(): EpochDecision {
        val epoch =
            try {
                metaApi.getMeta().data.dataEpoch
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return EpochDecision.UNCHANGED
            }
        val decision = tracker.observe(epoch)
        if (decision == EpochDecision.CHANGED) feedback.show(R.string.backups_restored_notice)
        return decision
    }
}
