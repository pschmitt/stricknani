package blue.anika.wolle.data.imports

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import blue.anika.wolle.data.export.foregroundInfoFor
import blue.anika.wolle.data.repository.ApiProjectImporter
import blue.anika.wolle.data.repository.ApiYarnImporter
import blue.anika.wolle.di.SDK_INT_NAME
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Named
import kotlinx.coroutines.CancellationException

/**
 * Imports one link (see [BackgroundImports]). It runs as expedited work: on Android 12 and newer
 * without a notification of its own; on 8 to 11 WorkManager makes it a short `dataSync` foreground
 * service with [ImportNotifier.progressNotification].
 */
@HiltWorker
class ImportWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val imports: BackgroundImports,
    private val projectImporter: ApiProjectImporter,
    private val yarnImporter: ApiYarnImporter,
    private val notifier: ImportNotifier,
    private val visibility: AppVisibility,
    @Named(SDK_INT_NAME) private val sdkInt: Int,
) : CoroutineWorker(context, params) {

    private val kind: ImportKind
        get() =
            ImportKind.entries.firstOrNull { it.name == inputData.getString(KEY_KIND) }
                ?: ImportKind.Project

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfoFor(sdkInt, FOREGROUND_ID, notifier.progressNotification(kind))
            ?: ForegroundInfo(FOREGROUND_ID, notifier.progressNotification(kind))

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val kind = kind
        val result =
            try {
                when (kind) {
                    ImportKind.Project ->
                        PendingImport.ProjectReady(url, projectImporter.importFromUrl(url))
                    ImportKind.Yarn -> PendingImport.YarnReady(url, yarnImporter.importFromUrl(url))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                PendingImport.Failed.of(url, failure)
            }
        // A newer import or a cancel replaced this one: drop the result quietly. Otherwise tell
        // about it, unless the import dialog is on screen right now.
        val onScreen = visibility.visible && imports.isWatched(kind)
        if (imports.complete(kind, result) && !onScreen) {
            notifier.finished(kind, result)
        }
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_URL = "url"
        const val FOREGROUND_ID = 1040
    }
}
