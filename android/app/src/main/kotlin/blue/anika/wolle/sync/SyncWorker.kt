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
import blue.anika.wolle.data.imports.AppVisibility
import blue.anika.wolle.data.imports.ImportDrafts
import blue.anika.wolle.data.imports.ImportNotifier
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ItemSyncResult
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
    private val mediaUrlResolver: MediaUrlResolver,
    private val errorReportingController: ErrorReportingController,
    private val importDrafts: ImportDrafts,
    private val importNotifier: ImportNotifier,
    private val appVisibility: AppVisibility,
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

        var yarnResult = ItemSyncResult.None
        var projectResult = ItemSyncResult.None
        val failure =
            runSyncStep("categories") { categoryRepository.sync() }
                ?: runSyncStep("yarns") { yarnResult = yarnRepository.syncDetailed() }
                ?: runSyncStep("projects") { projectResult = projectRepository.syncDetailed() }
        // An import the server finished while the app was away (safety net for ImportDraftsWorker).
        runCatching { importDrafts.announceFinished(importNotifier, appVisibility.visible) }
        val changes = SyncChanges.of(projects = projectResult.changed, yarns = yarnResult.changed)

        if (failure != null) {
            return if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }

        if (
            shouldNotifySyncCompletion(
                notifyOnChange = inputData.getBoolean(KEY_NOTIFY_ON_CHANGE, false),
                hasChanges = changes != SyncChanges.None,
            )
        ) {
            syncNotifier.notifySyncFoundChanges(summarize(projectResult, yarnResult))
        }
        return Result.success()
    }

    /** Names (and preview images) of what changed, from the cache that was just updated. */
    private suspend fun summarize(
        projectResult: ItemSyncResult,
        yarnResult: ItemSyncResult,
    ): SyncSummary =
        SyncSummary(
            projects =
                projectRepository.cachedByIds(projectResult.upsertedIds).map {
                    SyncedItem(
                        SyncedKind.Project,
                        it.id,
                        it.name,
                        mediaUrlResolver.resolve(it.previewUrl),
                    )
                },
            yarns =
                yarnRepository.cachedByIds(yarnResult.upsertedIds).map {
                    SyncedItem(
                        SyncedKind.Yarn,
                        it.id,
                        it.name,
                        mediaUrlResolver.resolve(it.previewUrl),
                    )
                },
            removedProjects = projectResult.deletedCount,
            removedYarns = yarnResult.deletedCount,
        )

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
