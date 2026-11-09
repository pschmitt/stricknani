package blue.anika.wolle.ui.imports

import android.content.ContentResolver
import blue.anika.wolle.data.imports.ImportFile
import blue.anika.wolle.data.imports.readImportBytes
import blue.anika.wolle.ui.common.ImportFailure
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The outcome of reading a picked document: the file, or why there is none. */
sealed interface PickedFileResult {
    data class Read(val file: ImportFile) : PickedFileResult

    data class Failed(val failure: ImportFailure) : PickedFileResult
}

/**
 * Reads the document [picked] in the system picker, up to the import limit (5 MiB, as on the web):
 * a larger one is [ImportFailure.TooLarge], one that cannot be opened [ImportFailure.Unreadable].
 */
suspend fun readPickedImportFile(
    resolver: ContentResolver,
    picked: PickedImportFile,
): PickedFileResult =
    withContext(Dispatchers.IO) {
        try {
            val stream =
                resolver.openInputStream(picked.uri)
                    ?: return@withContext PickedFileResult.Failed(ImportFailure.Unreadable)
            val bytes =
                stream.use { readImportBytes(it) }
                    ?: return@withContext PickedFileResult.Failed(ImportFailure.TooLarge)
            PickedFileResult.Read(
                ImportFile(
                    name = picked.name.ifBlank { "upload" },
                    mimeType = resolver.getType(picked.uri) ?: "application/octet-stream",
                    bytes = bytes,
                )
            )
        } catch (_: IOException) {
            PickedFileResult.Failed(ImportFailure.Unreadable)
        } catch (_: SecurityException) {
            PickedFileResult.Failed(ImportFailure.Unreadable)
        }
    }
