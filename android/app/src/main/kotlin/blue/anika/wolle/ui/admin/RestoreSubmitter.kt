package blue.anika.wolle.ui.admin

import blue.anika.wolle.R
import blue.anika.wolle.data.backups.RestoreEvent
import blue.anika.wolle.data.backups.RestoreState
import blue.anika.wolle.data.backups.canRestore
import blue.anika.wolle.ui.common.DangerousActionGuard

/**
 * The last step of the restore ("Restore now", also reached from "Undo last restore"): after the
 * typed backup name matches, the device unlock guard must pass before the flow moves on and the
 * request is sent. A refused or cancelled unlock changes nothing: the dialog stays on the typed
 * confirmation and nothing is sent. Web has no device unlock; this is the Android-only step of
 * "Dangerous actions" in `android/AGENTS.md`.
 */
class RestoreSubmitter(private val guard: DangerousActionGuard) {
    /**
     * Runs [send] with the confirmed state once the unlock passed, after [dispatch]ing the
     * submission; does nothing when the state cannot be restored yet.
     */
    fun submit(
        state: RestoreState,
        dispatch: (RestoreEvent) -> Unit,
        send: (RestoreState.Confirm) -> Unit,
    ) {
        val confirm = state as? RestoreState.Confirm ?: return
        if (!confirm.canRestore()) return
        guard.confirm(R.string.unlock_reason_restore_database) {
            dispatch(RestoreEvent.Submit)
            send(confirm)
        }
    }
}
