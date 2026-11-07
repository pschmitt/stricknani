package blue.anika.wolle.data.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloads the data export into a file the user picked (a Storage Access Framework Uri). */
@Singleton
class DataExportRepository
@Inject
constructor(private val api: DataExportApi, @ApplicationContext private val context: Context) {

    suspend fun download(
        target: Uri,
        format: ExportFormat,
        onProgress: (Long) -> Unit,
    ): ExportResult =
        withContext(Dispatchers.IO) {
            val result =
                try {
                    val response = api.export(format.archiveParam)
                    streamExport(
                        response,
                        openOutput = { context.contentResolver.openOutputStream(target, "wt") },
                        onProgress = onProgress,
                    )
                } catch (e: CancellationException) {
                    discard(target)
                    throw e
                } catch (_: IOException) {
                    ExportResult.Failure(ExportFailure.Failed)
                } catch (_: SecurityException) {
                    ExportResult.Failure(ExportFailure.Failed)
                }
            if (result is ExportResult.Failure) discard(target)
            result
        }

    /** A half-written archive is worse than none: remove the file the picker created. */
    private fun discard(target: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
    }
}
