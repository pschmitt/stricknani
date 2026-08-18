package blue.anika.wolle.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Pulls delta-sync updates for categories, yarns, and projects into the Room cache. Never wipes
 * what's already cached on failure - a bad run just means the UI keeps showing the last
 * successfully synced state, never a blank screen. Categories are synced first since project/yarn
 * detail screens display category names sourced from this cache.
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.isConfigured.value) return Result.success()

        val failure =
            runSyncStep("categories") { categoryRepository.sync() }
                ?: runSyncStep("yarns") { yarnRepository.sync() }
                ?: runSyncStep("projects") { projectRepository.sync() }

        if (failure == null) return Result.success()

        Timber.w(failure, "Sync failed")
        return if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
    }

    private suspend fun runSyncStep(name: String, step: suspend () -> Unit): Throwable? =
        try {
            step()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Timber.w(failure, "Sync step '$name' failed")
            failure
        }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
