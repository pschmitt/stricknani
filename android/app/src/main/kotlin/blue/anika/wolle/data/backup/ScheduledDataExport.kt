package blue.anika.wolle.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import blue.anika.wolle.data.export.DataExportRepository
import blue.anika.wolle.data.export.ExportFailure
import blue.anika.wolle.data.export.ExportFormat
import blue.anika.wolle.data.export.ExportResult
import blue.anika.wolle.data.export.exportFileName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** How many data downloads a scheduled backup keeps in the folder. */
const val SCHEDULED_DATA_EXPORTS_KEPT = 3

private val DATA_EXPORT_NAME = Regex("""stricknani-export-\d{4}-\d{2}-\d{2}\.tar\.gz""")

/** `stricknani-export-YYYY-MM-DD.tar.gz` for the UTC date of [now]. */
fun scheduledDataExportName(now: Instant): String =
    exportFileName(now.atZone(ZoneOffset.UTC).toLocalDate(), ExportFormat.TarGz)

/** Whether [name] looks like a data download of the app. The pattern is the only thing matched. */
fun isDataExportName(name: String): Boolean = DATA_EXPORT_NAME.matches(name)

/**
 * The names to delete so only the newest [keep] data downloads stay: only names that match
 * [isDataExportName] are ever considered, so no other file in the folder is touched. The date in
 * the name orders them.
 */
fun dataExportsToDelete(names: Collection<String>, keep: Int = SCHEDULED_DATA_EXPORTS_KEPT) =
    names.filter(::isDataExportName).sortedDescending().drop(keep.coerceAtLeast(0))

/**
 * Writes the data download into the folder of the scheduled backups, streaming through the same
 * repository as the manual download, and prunes the old ones. A scheduled run is plain background
 * work, so it keeps WorkManager's ten minute limit (see [BackupWorker]).
 */
@Singleton
class ScheduledDataExport
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: DataExportRepository,
) {
    private data class Child(val documentId: String, val name: String)

    /**
     * Replaces today's file if it exists, downloads, and on success keeps the newest
     * [SCHEDULED_DATA_EXPORTS_KEPT]. A failed or cancelled download leaves no partial file (the
     * repository removes it).
     */
    suspend fun write(treeUri: Uri, now: Instant = Instant.now()): ExportResult {
        val resolver = context.contentResolver
        val parent =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        val name = scheduledDataExportName(now)
        children(treeUri).filter { it.name == name }.forEach { delete(treeUri, it) }
        val target =
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
                ?: return ExportResult.Failure(ExportFailure.Failed)
        val result = repository.download(target, ExportFormat.TarGz) {}
        if (result is ExportResult.Success) prune(treeUri)
        return result
    }

    private fun prune(treeUri: Uri) {
        val children = children(treeUri)
        val doomed = dataExportsToDelete(children.map { it.name }).toSet()
        children.filter { it.name in doomed }.forEach { delete(treeUri, it) }
    }

    private fun children(treeUri: Uri): List<Child> {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        val found = mutableListOf<Child>()
        runCatching {
            context.contentResolver
                .query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        found += Child(cursor.getString(0), cursor.getString(1).orEmpty())
                    }
                }
        }
        return found
    }

    private fun delete(treeUri: Uri, child: Child) {
        runCatching {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId),
            )
        }
    }
}
