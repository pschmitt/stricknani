package blue.anika.wolle.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import blue.anika.wolle.data.errorreporting.ErrorReportingController
import blue.anika.wolle.data.errorreporting.SyncFailureClassifier
import blue.anika.wolle.data.errorreporting.SyncFailureKind
import blue.anika.wolle.data.errorreporting.SyncFailureReport
import blue.anika.wolle.data.errorreporting.SyncFailureReportSanitizer
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Pulls delta-sync updates for categories, yarns, and projects into the Room cache. Never wipes
 * what's already cached on failure - a bad run just means the UI keeps showing the last
 * successfully synced state, never a blank screen. Categories are synced first since project/yarn
 * detail screens display category names sourced from this cache.
 *
 * SNA-14: fires a local notification when [KEY_NOTIFY_ON_CHANGE] is set (only true for
 * `SyncScheduler.schedulePeriodic()`'s periodic request - a manual pull-to-refresh or the on-launch
 * sync means the user is already looking at the fresh data, so notifying then would just be noise)
 * and a project/yarn sync actually found a change. Categories are excluded: they have no real delta
 * (`CategoryRepository.sync()`'s kdoc), so "changed" there is meaningless noise.
 */
@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val categoryRepository: CategoryRepository,
    private val yarnRepository: YarnRepository,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val syncNotifier: SyncNotifier,
    private val errorReportingController: ErrorReportingController,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.isConfigured.value) return Result.success()
        // An expired OIDC sign-in would only 401 every step. Nothing is lost: the user signs in
        // again from the card in the app, which queues a fresh sync.
        if (settingsRepository.sessionExpired.value) return Result.success()
        if (
            shouldSkipBackgroundWork(
                background = inputData.getBoolean(KEY_BACKGROUND, false),
                powerSaveMode = applicationContext.isPowerSaveMode(),
            )
        ) {
            return Result.success()
        }

        var hasChanges = false
        val failure =
            runSyncStep("categories") { categoryRepository.sync() }
                ?: runSyncStep("yarns") { hasChanges = yarnRepository.sync() || hasChanges }
                ?: runSyncStep("projects") { hasChanges = projectRepository.sync() || hasChanges }

        if (failure != null) {
            return if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }

        if (
            shouldNotifySyncCompletion(
                notifyOnChange = inputData.getBoolean(KEY_NOTIFY_ON_CHANGE, false),
                hasChanges = hasChanges,
            )
        ) {
            syncNotifier.notifySyncFoundChanges()
        }
        return Result.success()
    }

    private suspend fun runSyncStep(name: String, step: suspend () -> Unit): Throwable? =
        try {
            step()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val safeReport =
                SyncFailureReportSanitizer.sanitize(
                    SyncFailureReport(
                        kind = SyncFailureKind.SYNC,
                        operation = name,
                        failureClass = SyncFailureClassifier.classify(failure),
                        httpStatus = (failure as? retrofit2.HttpException)?.code() ?: 0,
                        stackTrace = failure.stackTrace.toList(),
                    )
                )
            Log.w(
                "SyncReplay",
                "Pull sync failed: operation=${safeReport.operation}, " +
                    "cause=${safeReport.failureClass.tagValue}, http=${safeReport.httpStatus}",
            )
            errorReportingController.reportSyncFailure(safeReport)
            failure
        }

    companion object {
        const val KEY_NOTIFY_ON_CHANGE = "notify_on_change"

        /** Set on the periodic request: skipped while the battery saver is on. */
        const val KEY_BACKGROUND = "background"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}

internal fun shouldNotifySyncCompletion(notifyOnChange: Boolean, hasChanges: Boolean): Boolean =
    notifyOnChange && hasChanges
