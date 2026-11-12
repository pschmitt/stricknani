package blue.anika.wolle.data.backups

import blue.anika.wolle.data.account.httpFailure
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.ui.common.isOfflineFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** A failed backups request in terms the screens can explain. */
class BackupsException(
    val kind: RestoreErrorKind,
    /** 403 `reauth_required`: the proof was missing, used up or expired. */
    val reauthRequired: Boolean = false,
    /** 401/403 `admin_required`: not an administrator (or the session is gone). */
    val notAllowed: Boolean = false,
    cause: Throwable? = null,
) : Exception(kind.name, cause)

/** What the Backups UI needs from the server; faked in tests. */
interface BackupsGateway {
    suspend fun list(): BackupListDto

    suspend fun preview(name: String): RestorePreviewDto

    /** [proof] must be a `restore_backup` proof; it is sent once and never kept. */
    suspend fun restore(
        name: String,
        confirmation: String,
        media: MediaOption,
        proof: ReauthProof,
    ): RestoreAcceptedDto
}

/** Maps a thrown request error to a [BackupsException]. Pure, so it is unit-tested. */
fun Throwable.toBackupsException(): BackupsException {
    if (this is BackupsException) return this
    val http = (this as? HttpException)?.let { httpFailureOf(it) }
    if (http == null)
        return BackupsException(
            if (isOfflineFailure()) RestoreErrorKind.OFFLINE else RestoreErrorKind.UNKNOWN,
            cause = this,
        )
    return backupsExceptionFor(http.first, http.second, this)
}

private fun httpFailureOf(failure: HttpException): Pair<Int, String?>? =
    failure.httpFailure()?.let { it.status to it.code }

fun backupsExceptionFor(status: Int, code: String?, cause: Throwable? = null): BackupsException =
    when {
        status == 403 && code == "reauth_required" ->
            BackupsException(RestoreErrorKind.UNKNOWN, reauthRequired = true, cause = cause)
        status == 401 || (status == 403 && code == "admin_required") ->
            BackupsException(RestoreErrorKind.UNKNOWN, notAllowed = true, cause = cause)
        code == "backup_newer_schema" ->
            BackupsException(RestoreErrorKind.NEWER_SCHEMA, cause = cause)
        code == "backup_unreadable" -> BackupsException(RestoreErrorKind.UNREADABLE, cause = cause)
        code == "restore_in_progress" -> BackupsException(RestoreErrorKind.BUSY, cause = cause)
        code == "pre_restore_failed" ->
            BackupsException(RestoreErrorKind.PRE_RESTORE_FAILED, cause = cause)
        code == "stage_failed" -> BackupsException(RestoreErrorKind.STAGE_FAILED, cause = cause)
        code == "confirmation_mismatch" ->
            BackupsException(RestoreErrorKind.CONFIRMATION, cause = cause)
        code == "media_unavailable" -> BackupsException(RestoreErrorKind.MEDIA, cause = cause)
        code == "backups_unavailable" ->
            BackupsException(RestoreErrorKind.UNAVAILABLE, cause = cause)
        else -> BackupsException(RestoreErrorKind.UNKNOWN, cause = cause)
    }

@Singleton
class BackupsRepository @Inject constructor(private val api: BackupsApi) : BackupsGateway {
    override suspend fun list(): BackupListDto = call { api.list().data }

    override suspend fun preview(name: String): RestorePreviewDto = call {
        api.preview(name).data
    }

    override suspend fun restore(
        name: String,
        confirmation: String,
        media: MediaOption,
        proof: ReauthProof,
    ): RestoreAcceptedDto {
        if (proof.action != ReauthAction.RESTORE_BACKUP) {
            throw BackupsException(RestoreErrorKind.UNKNOWN, reauthRequired = true)
        }
        return call {
            api.restore(name, proof.token, RestoreRequestDto(confirmation, media.wire)).data
        }
    }

    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            throw failure.toBackupsException()
        }
}

/** The production [RestartProbe]: plain Retrofit calls, every failure reads as "not up". */
@Singleton
class RetrofitRestartProbe
@Inject
constructor(
    private val health: HealthApi,
    private val meta: MetaV2Api,
) : RestartProbe {
    override suspend fun healthy(): Boolean =
        try {
            health.healthz().isSuccessful
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    override suspend fun meta(): MetaProbe? =
        try {
            val data = meta.getMeta().data
            MetaProbe(
                epoch = data.dataEpoch,
                lastStatus = data.lastRestore?.status,
                lastFinishedMs = parseInstantOrNull(data.lastRestore?.finishedAt),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: HttpException) {
            if (failure.code() == 401) MetaProbe(null, null, null, unauthorized = true) else null
        } catch (_: Exception) {
            null
        }
}
