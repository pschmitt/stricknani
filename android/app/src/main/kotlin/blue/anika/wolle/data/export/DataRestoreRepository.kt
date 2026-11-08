package blue.anika.wolle.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

/** Uploads a chosen archive to `POST /api/v2/account/import`. */
@Singleton
class DataRestoreRepository
@Inject
constructor(private val api: DataRestoreApi, @ApplicationContext private val context: Context) {

    /** The size in bytes the provider reports, or 0 when it does not say. */
    fun sizeOf(source: Uri): Long =
        runCatching {
                context.contentResolver
                    .query(source, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
                    }
            }
            .getOrNull() ?: 0L

    suspend fun restore(
        source: Uri,
        passphrase: String? = null,
        onProgress: (Long) -> Unit,
    ): RestoreResult =
        withContext(Dispatchers.IO) {
            try {
                val head =
                    (context.contentResolver.openInputStream(source) ?: throw RestoreInputMissing())
                        .use { readHead(it) }
                val body =
                    StreamingRequestBody(
                        mediaType = restoreMediaType(head),
                        openInput = {
                            context.contentResolver.openInputStream(source)
                                ?: throw RestoreInputMissing()
                        },
                        onProgress = onProgress,
                    )
                resultOf(api.import(body, passphrase?.let(::encodePassphraseHeader)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                ioFailure()
            } catch (_: SecurityException) {
                ioFailure()
            } catch (_: RuntimeException) {
                ioFailure()
            }
        }

    private fun resultOf(response: Response<ResponseBody>): RestoreResult {
        if (response.isSuccessful) {
            val text = response.body()?.use { it.string() }.orEmpty()
            val summary = parseRestoreSummary(text) ?: return ioFailure()
            return RestoreResult.Success(summary)
        }
        val code = response.errorBody()?.use { parseRestoreErrorCode(it.string()) }
        return RestoreResult.Failure(restoreFailureFor(response.code(), code))
    }
}
