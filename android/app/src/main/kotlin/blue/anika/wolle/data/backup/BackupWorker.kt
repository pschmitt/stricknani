package blue.anika.wolle.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Writes one scheduled backup (SNA-15) into the SAF folder tree the user picked in Settings, using
 * platform `DocumentsContract` directly (no `androidx.documentfile` dependency needed for a single
 * create-and-write).
 */
@HiltWorker
class BackupWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val folderUriString = appPreferencesRepository.scheduledBackupFolderUri.first()
        if (folderUriString.isNullOrBlank()) return Result.failure()
        val treeUri = Uri.parse(folderUriString)
        val password = settingsRepository.scheduledBackupPassword.first()
        return try {
            val bytes = backupManager.export(password)
            val resolver = applicationContext.contentResolver
            val parentDocumentUri =
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            val fileName =
                "stricknani-backup-${DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-')}.stricknanibackup"
            val fileUri =
                DocumentsContract.createDocument(
                    resolver,
                    parentDocumentUri,
                    "application/octet-stream",
                    fileName,
                ) ?: return Result.failure()
            resolver.openOutputStream(fileUri)?.use { it.write(bytes) } ?: return Result.failure()
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "Scheduled backup failed")
            Result.retry()
        }
    }
}
