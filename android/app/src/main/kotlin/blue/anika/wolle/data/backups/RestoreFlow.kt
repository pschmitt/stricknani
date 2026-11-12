package blue.anika.wolle.data.backups

import blue.anika.wolle.data.oidc.ReauthProof

/**
 * State machine of the multi-step restore: preview -> re-authentication -> type the backup name ->
 * Restore now -> "the server is restarting" -> success or failure. Pure: the view model performs
 * the requests and feeds their results back as [RestoreEvent]s. Mirrors `restore-flow.ts`.
 *
 * Unlike the browser (which only stamps its session), a native client holds a one-use proof; it
 * travels with the state as [ReauthGrant] and is spent by the restore request.
 */

/** How long a confirmed re-authentication stays valid on the server (10 minutes). */
const val REAUTH_WINDOW_MS: Long = 10 * 60 * 1000

/** Margin so a grant is not offered right before the server would refuse it. */
private const val REAUTH_MARGIN_MS: Long = 15_000

class ReauthGrant(val at: Long, val proof: ReauthProof)

enum class RestoreErrorKind {
    NEWER_SCHEMA,
    UNREADABLE,
    BUSY,
    PRE_RESTORE_FAILED,
    STAGE_FAILED,
    CONFIRMATION,
    MEDIA,
    UNAVAILABLE,
    OFFLINE,
    UNKNOWN,
}

sealed interface RestoreState {
    data object Closed : RestoreState

    data class Loading(val backup: BackupEntryDto, val grant: ReauthGrant?) : RestoreState

    data class Preview(
        val backup: BackupEntryDto,
        val preview: RestorePreviewDto,
        val media: MediaOption,
        val grant: ReauthGrant?,
    ) : RestoreState

    data class Reauth(
        val backup: BackupEntryDto,
        val preview: RestorePreviewDto,
        val media: MediaOption,
        val grant: ReauthGrant?,
    ) : RestoreState

    data class Confirm(
        val backup: BackupEntryDto,
        val preview: RestorePreviewDto,
        val media: MediaOption,
        val grant: ReauthGrant,
        val typed: String = "",
        val error: RestoreErrorKind? = null,
    ) : RestoreState

    data class Restoring(
        val backup: BackupEntryDto,
        val preview: RestorePreviewDto,
        val media: MediaOption,
        val grant: ReauthGrant,
        val typed: String,
    ) : RestoreState

    data class Waiting(
        val backup: BackupEntryDto,
        val accepted: RestoreAcceptedDto,
        val requestedAt: Long,
    ) : RestoreState

    data class ManualRestart(val backup: BackupEntryDto, val accepted: RestoreAcceptedDto) :
        RestoreState

    data class Succeeded(val backup: BackupEntryDto, val accepted: RestoreAcceptedDto) :
        RestoreState

    data class Failed(
        val backup: BackupEntryDto,
        val accepted: RestoreAcceptedDto,
        val error: String?,
    ) : RestoreState

    data class Timeout(val backup: BackupEntryDto, val accepted: RestoreAcceptedDto) : RestoreState

    data class Error(val backup: BackupEntryDto, val error: RestoreErrorKind) : RestoreState
}

sealed interface RestoreEvent {
    data class Open(val backup: BackupEntryDto, val grant: ReauthGrant? = null) : RestoreEvent

    data class PreviewLoaded(val preview: RestorePreviewDto) : RestoreEvent

    data class PreviewFailed(val error: RestoreErrorKind) : RestoreEvent

    data class SetMedia(val media: MediaOption) : RestoreEvent

    data class Continue(val now: Long) : RestoreEvent

    data class ReauthConfirmed(val now: Long, val proof: ReauthProof) : RestoreEvent

    data object ReauthCancelled : RestoreEvent

    data class Typed(val value: String) : RestoreEvent

    data object Submit : RestoreEvent

    data class Accepted(val accepted: RestoreAcceptedDto, val now: Long) : RestoreEvent

    data class Rejected(val error: RestoreErrorKind, val reauthRequired: Boolean = false) :
        RestoreEvent

    data class ServerBack(val result: RestartResult, val error: String? = null) : RestoreEvent

    data object Back : RestoreEvent

    data object Close : RestoreEvent
}

/** A confirmation counts when it equals the backup's exact file name. */
fun confirmationMatches(typed: String, name: String): Boolean = typed == name

fun grantIsFresh(grant: ReauthGrant?, now: Long): Boolean =
    grant != null && now >= grant.at && now - grant.at < REAUTH_WINDOW_MS - REAUTH_MARGIN_MS

/** "Restore now" is only enabled with the exact name. */
fun RestoreState.canRestore(): Boolean =
    this is RestoreState.Confirm && confirmationMatches(typed, backup.name)

/** The dialog cannot be dismissed while the server works on the restore. */
fun RestoreState.isLocked(): Boolean =
    this is RestoreState.Restoring || this is RestoreState.Waiting

fun reduceRestore(state: RestoreState, event: RestoreEvent): RestoreState =
    when (event) {
        is RestoreEvent.Open ->
            if (state is RestoreState.Closed && event.backup.restorable)
                RestoreState.Loading(event.backup, event.grant)
            else state
        is RestoreEvent.PreviewLoaded ->
            if (state is RestoreState.Loading)
                RestoreState.Preview(state.backup, event.preview, MediaOption.NONE, state.grant)
            else state
        is RestoreEvent.PreviewFailed ->
            if (state is RestoreState.Loading) RestoreState.Error(state.backup, event.error)
            else state
        is RestoreEvent.SetMedia ->
            when {
                state is RestoreState.Preview && event.media in state.preview.options ->
                    state.copy(media = event.media)
                state is RestoreState.Confirm && event.media in state.preview.options ->
                    state.copy(media = event.media)
                else -> state
            }
        is RestoreEvent.Continue ->
            if (state is RestoreState.Preview) {
                val grant = state.grant
                if (grant != null && grantIsFresh(grant, event.now))
                    RestoreState.Confirm(state.backup, state.preview, state.media, grant)
                else RestoreState.Reauth(state.backup, state.preview, state.media, state.grant)
            } else state
        is RestoreEvent.ReauthConfirmed ->
            if (state is RestoreState.Reauth)
                RestoreState.Confirm(
                    state.backup,
                    state.preview,
                    state.media,
                    ReauthGrant(event.now, event.proof),
                )
            else state
        RestoreEvent.ReauthCancelled ->
            if (state is RestoreState.Reauth)
                RestoreState.Preview(state.backup, state.preview, state.media, state.grant)
            else state
        is RestoreEvent.Typed ->
            if (state is RestoreState.Confirm) state.copy(typed = event.value, error = null)
            else state
        RestoreEvent.Submit ->
            if (state is RestoreState.Confirm && state.canRestore())
                RestoreState.Restoring(
                    state.backup,
                    state.preview,
                    state.media,
                    state.grant,
                    state.typed,
                )
            else state
        is RestoreEvent.Accepted ->
            if (state is RestoreState.Restoring) {
                if (event.accepted.restarting)
                    RestoreState.Waiting(state.backup, event.accepted, event.now)
                else RestoreState.ManualRestart(state.backup, event.accepted)
            } else state
        is RestoreEvent.Rejected ->
            if (state is RestoreState.Restoring) {
                if (event.reauthRequired)
                    // The server's window closed or the proof was used up: ask again.
                    RestoreState.Reauth(state.backup, state.preview, state.media, null)
                else
                    RestoreState.Confirm(
                        state.backup,
                        state.preview,
                        state.media,
                        state.grant,
                        state.typed,
                        event.error,
                    )
            } else state
        is RestoreEvent.ServerBack ->
            if (state is RestoreState.Waiting)
                when (event.result) {
                    RestartResult.SUCCEEDED -> RestoreState.Succeeded(state.backup, state.accepted)
                    RestartResult.TIMEOUT -> RestoreState.Timeout(state.backup, state.accepted)
                    RestartResult.FAILED ->
                        RestoreState.Failed(state.backup, state.accepted, event.error)
                }
            else state
        RestoreEvent.Back ->
            if (state is RestoreState.Confirm)
                RestoreState.Preview(state.backup, state.preview, state.media, state.grant)
            else state
        RestoreEvent.Close -> if (state.isLocked()) state else RestoreState.Closed
    }
