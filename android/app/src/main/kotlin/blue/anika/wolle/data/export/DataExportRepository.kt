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

    /**
     * Streams the export into [target]. With a [passphrase] the download is encrypted: the ticket
     * is requested first and the ticket URL streamed second (`docs/data-export.md`, "Encryption").
     */
    suspend fun download(
        target: Uri,
        format: ExportFormat,
        passphrase: String? = null,
        onProgress: (Long) -> Unit,
    ): ExportResult =
        withContext(Dispatchers.IO) {
            val result =
                try {
                    val response =
                        if (passphrase == null) api.export(format.archiveParam)
                        else
                            when (val ticket = requestTicket(format, passphrase)) {
                                is TicketResult.Ready -> api.downloadTicket(ticket.url)
                                is TicketResult.Refused ->
                                    return@withContext ExportResult.Failure(ticket.reason).also {
                                        discard(target)
                                    }
                            }
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

    private suspend fun requestTicket(format: ExportFormat, passphrase: String): TicketResult {
        val response = api.createEncryptedExport(encryptedExportBody(format, passphrase))
        val text =
            if (response.isSuccessful) response.body()?.use { it.string() }
            else {
                response.errorBody()?.close()
                null
            }
        return ticketResultFor(response.code(), text)
    }

    /** A half-written archive is worse than none: remove the file the picker created. */
    private fun discard(target: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
    }
}
