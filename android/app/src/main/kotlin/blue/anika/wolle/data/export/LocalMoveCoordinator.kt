package blue.anika.wolle.data.export

import android.content.Context
import androidx.core.net.toUri
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.settings.StricknaniCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app-wide wiring of [LocalDataMove] (T130.2): the in-app export of the local data, the restore
 * path for the upload and what Home offers after a sign-in. The rules live in [LocalDataMove]; this
 * only connects them to Android.
 */
@Singleton
class LocalMoveCoordinator
@Inject
constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
    exports: DataExportRepository,
    private val restores: DataRestoreScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val move =
        LocalDataMove(
            store = settings,
            dir = File(context.filesDir, LocalDataMove.DIR_NAME),
            export = { file ->
                exports.download(file.toUri(), ExportFormat.TarGz) {} is ExportResult.Success
            },
            // Uri.fromFile: the restore reads it like a picked file, also as a user-initiated job.
            upload = { file -> restores.startIfIdle(android.net.Uri.fromFile(file)) },
        )

    private val dismissedFor = MutableStateFlow<StricknaniCredentials?>(null)

    init {
        scope.launch { restores.status.collect(move::onRestoreStatus) }
    }

    /** What Home offers right now; null for nothing. */
    val offer: StateFlow<LocalMoveOffer?> =
        combine(
                settings.credentials,
                settings.sessionExpired,
                move.pending,
                move.uploading,
                dismissedFor,
            ) { credentials, expired, pending, uploading, dismissed ->
                move.offerFor(
                    credentials = credentials,
                    localMode = settings.isLocalMode,
                    sessionExpired = expired,
                    dismissedFor = dismissed,
                    pending = pending,
                    uploading = uploading,
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Exports the local data for the move; false when that failed (stay in local mode). */
    suspend fun prepare(): Boolean = move.prepare()

    /** The user chose not to bring the data along: drop an older export. */
    fun discard() = move.discard()

    /** "Upload", for the offer shown to [shownFor]; refused for any other account. */
    fun upload(shownFor: StricknaniCredentials): Boolean =
        move.startUpload(shownFor, settings.credentials.value)

    /** "Not now": the offer stays for later and comes back next time the app starts. */
    fun notNow(shownFor: StricknaniCredentials) {
        dismissedFor.value = shownFor
    }
}
