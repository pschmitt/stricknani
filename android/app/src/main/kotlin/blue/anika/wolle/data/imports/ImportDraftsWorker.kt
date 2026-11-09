package blue.anika.wolle.data.imports

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * A light look at the server's import drafts while the app is in the background: announces the ones
 * that finished ([ImportNotifier]) and, while another still runs, looks again soon. Started when an
 * import starts ([ImportDrafts.schedulePoll]); the periodic sync does the same as a safety net.
 */
@HiltWorker
class ImportDraftsWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val drafts: ImportDrafts,
    private val notifier: ImportNotifier,
    private val visibility: AppVisibility,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            if (drafts.announceFinished(notifier, visibility.visible)) drafts.schedulePoll()
            Result.success()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Offline or a server hiccup: the next look (or the periodic sync) tries again.
            Result.retry()
        }
}
