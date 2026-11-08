package blue.anika.wolle.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import blue.anika.wolle.data.export.DataExportNotifier
import blue.anika.wolle.data.export.ExportResult
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Writes one scheduled backup (SNA-15) into the SAF folder tree the user picked in Settings, using
 * platform `DocumentsContract` directly (no `androidx.documentfile` dependency needed for a single
 * create-and-write).
 *
 * With "Include your data" on, the run also writes `stricknani-export-YYYY-MM-DD.tar.gz` (see
 * [ScheduledDataExport]; `.tar.gz.age`, encrypted with the backup password, when one is set, and no
 * data file at all when that password is too short to encrypt with) after the settings backup, so a
 * failing data download never loses the settings backup. Unlike the user-started download, a
 * scheduled run is background work and keeps WorkManager's ten minute limit; a very large account
 * may not finish in time. That is acceptable: the run then reports the failure (notification that
 * opens Backups) and the partial file is removed.
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
    private val dataExport: ScheduledDataExport,
    private val notifier: DataExportNotifier,
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
            if (appPreferencesRepository.scheduledBackupIncludeData.first()) {
                writeData(treeUri, password)
            } else {
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Scheduled backup failed")
            Result.retry()
        }
    }

    /**
     * The data part, after the settings backup is safe. Any failure is reported once through a
     * notification that opens Backups; the run then ends as failed (no retry, which would write a
     * second settings backup) and the next period tries again.
     */
    private suspend fun writeData(treeUri: Uri, password: String?): Result {
        val plan = scheduledDataPlan(password)
        if (plan == ScheduledDataPlan.PasswordTooShort) {
            notifier.scheduledDataPasswordTooShort()
            return Result.failure()
        }
        val passphrase = password.takeIf { plan == ScheduledDataPlan.Encrypted }
        return try {
            when (dataExport.write(treeUri, passphrase)) {
                is ExportResult.Success -> Result.success()
                is ExportResult.Failure -> dataFailed()
            }
        } catch (e: CancellationException) {
            // The ten minute limit (or a stop): the repository already removed the partial file.
            notifier.scheduledDataFailed()
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Scheduled data export failed")
            dataFailed()
        }
    }

    private fun dataFailed(): Result {
        notifier.scheduledDataFailed()
        return Result.failure()
    }
}
