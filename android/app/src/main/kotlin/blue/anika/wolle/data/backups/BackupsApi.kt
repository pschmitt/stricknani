package blue.anika.wolle.data.backups

import blue.anika.wolle.data.api.v2.V2Envelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * `src/api_v2/admin_backups.rs`: administrator-only, online-only. Restoring needs a one-use
 * `restore_backup` proof in `X-Reauth-Token` (403 `reauth_required` without it) and the exact
 * backup name as confirmation. Downloading backups is deliberately not part of the API.
 */
interface BackupsApi {
    @GET("api/v2/admin/backups") suspend fun list(): V2Envelope<BackupListDto>

    @GET("api/v2/admin/backups/{name}/restore-preview")
    suspend fun preview(@Path("name") name: String): V2Envelope<RestorePreviewDto>

    @POST("api/v2/admin/backups/{name}/restore")
    suspend fun restore(
        @Path("name") name: String,
        @Header("X-Reauth-Token") reauthToken: String,
        @Body request: RestoreRequestDto,
    ): V2Envelope<RestoreAcceptedDto>
}

/** `GET /healthz`: 200 once the (restarted) server listens. The body is not read. */
interface HealthApi {
    @GET("healthz") suspend fun healthz(): retrofit2.Response<okhttp3.ResponseBody>
}

enum class BackupKind(val wire: String) {
    NIGHTLY("nightly"),
    PREDEPLOY("predeploy"),
    PRE_MIGRATE("pre_migrate"),
    PRE_RESTORE("pre_restore"),
    MANUAL("manual");

    companion object {
        /** Unknown kinds read as manual, like the web client. */
        fun fromWire(value: String?): BackupKind =
            entries.firstOrNull { it.wire == value } ?: MANUAL
    }
}

/** `none` keeps the current media files; `add_missing` only adds files that do not exist. */
enum class MediaOption(val wire: String) {
    NONE("none"),
    ADD_MISSING("add_missing");

    companion object {
        fun fromWire(value: String?): MediaOption? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class BackupCountsDto(
    val users: Long? = null,
    val projects: Long? = null,
    val yarns: Long? = null,
    val trashed: Long? = null,
)

@Serializable
data class BackupEntryDto(
    val name: String,
    val kind: String = "manual",
    /** RFC 3339, UTC. */
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("has_media") val hasMedia: Boolean = false,
    @SerialName("schema_version") val schemaVersion: Long? = null,
    val counts: BackupCountsDto? = null,
    val readable: Boolean = false,
    @SerialName("newer_schema") val newerSchema: Boolean = false,
    val restorable: Boolean = false,
    val error: String? = null,
) {
    val kindOrManual: BackupKind
        get() = BackupKind.fromWire(kind)
}

@Serializable
data class PendingRestoreDto(
    val backup: String = "",
    @SerialName("pre_restore") val preRestore: String = "",
    @SerialName("requested_at") val requestedAt: String = "",
)

@Serializable
data class RestoreOutcomeDto(
    val status: String = "failed",
    val backup: String = "",
    @SerialName("pre_restore") val preRestore: String = "",
    val media: String = "none",
    @SerialName("finished_at") val finishedAt: String = "",
    @SerialName("requested_by_email") val requestedByEmail: String = "",
    val error: String? = null,
    @SerialName("media_added") val mediaAdded: Long? = null,
    @SerialName("media_error") val mediaError: String? = null,
) {
    val succeeded: Boolean
        get() = status == "succeeded"
}

@Serializable
data class BackupListDto(
    val available: Boolean = false,
    @SerialName("server_schema_version") val serverSchemaVersion: Long = 0,
    val backups: List<BackupEntryDto> = emptyList(),
    /** The newest restorable `pre-restore-*` file: what "Undo last restore" restores. */
    @SerialName("latest_pre_restore") val latestPreRestore: String? = null,
    @SerialName("pending_restore") val pendingRestore: PendingRestoreDto? = null,
    @SerialName("last_restore") val lastRestore: RestoreOutcomeDto? = null,
    @SerialName("data_epoch") val dataEpoch: String? = null,
    /** The server stops itself after staging a restore and relies on a supervisor. */
    @SerialName("auto_restart") val autoRestart: Boolean = false,
)

@Serializable
data class RestoreSnapshotDto(
    @SerialName("schema_version") val schemaVersion: Long? = null,
    val counts: BackupCountsDto? = null,
)

@Serializable
data class RestorePreviewDto(
    val backup: BackupEntryDto,
    val current: RestoreSnapshotDto = RestoreSnapshotDto(),
    @SerialName("backup_snapshot") val backupSnapshot: RestoreSnapshotDto = RestoreSnapshotDto(),
    /** Audit events recorded after the backup was taken; null when unknown. */
    @SerialName("changes_since_backup") val changesSinceBackup: Long? = null,
    @SerialName("media_options") val mediaOptions: List<String> = listOf("none"),
    @SerialName("restarts_server") val restartsServer: Boolean = false,
) {
    /** Only the options this client knows; always at least [MediaOption.NONE]. */
    val options: List<MediaOption>
        get() = mediaOptions.mapNotNull(MediaOption::fromWire).ifEmpty { listOf(MediaOption.NONE) }
}

@Serializable data class RestoreRequestDto(val confirmation: String, val media: String)

@Serializable
data class RestoreAcceptedDto(
    val backup: String = "",
    @SerialName("pre_restore") val preRestore: String = "",
    val restarting: Boolean = false,
)
