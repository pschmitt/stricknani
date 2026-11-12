package blue.anika.wolle.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Device-unlock guard for dangerous (destructive or irreversible) actions.
 *
 * Every new destructive action goes through [DangerousActionGuard.confirm], after the action's own
 * confirmation dialog:
 * ```
 * guard.confirm(R.string.unlock_reason_purge) { performAction() }
 * ```
 * In a composable get the guard with [rememberDangerousActionGuard]; in a ViewModel inject
 * [DangerousActionGuard]. See `android/AGENTS.md` ("Dangerous actions").
 */
interface DangerousActionGuard {
    /**
     * Runs [action] once the user passed the device unlock (fingerprint, face or screen lock), or
     * at once when the "Unlock to confirm dangerous actions" setting is off or the device has no
     * secure lock screen. A cancelled prompt runs nothing and shows nothing. [reason] names the
     * action in the prompt title ("Confirm: delete permanently").
     */
    fun confirm(@StringRes reason: Int, action: () -> Unit)

    /** Like [confirm] but also asks when the setting is off (turning the setting itself off). */
    fun confirmAlways(@StringRes reason: Int, action: () -> Unit)
}

/** Without a provider (previews, focused UI tests) the action just runs. */
private object PassThroughGuard : DangerousActionGuard {
    override fun confirm(reason: Int, action: () -> Unit) = action()

    override fun confirmAlways(reason: Int, action: () -> Unit) = action()
}

val LocalDangerousActionGuard = compositionLocalOf<DangerousActionGuard> { PassThroughGuard }

/** The app's guard, provided by `MainActivity`; tests can provide a fake. */
@Composable fun rememberDangerousActionGuard(): DangerousActionGuard = LocalDangerousActionGuard.current

/** Whether the device has a secure lock screen (or biometrics) the unlock prompt can use. */
enum class DeviceLockState {
    Available,
    NotSecured,
}

/** The outcome of one unlock prompt. */
sealed interface UnlockResult {
    data object Success : UnlockResult

    /** The user backed out; nothing happens and nothing is shown. */
    data object Cancelled : UnlockResult

    /** The prompt failed (lockout, hardware error); [message] is the system text, if any. */
    data class Failed(val message: String?) : UnlockResult
}

/** The platform prompt behind the gate; a fake in unit tests. */
interface DeviceAuthenticator {
    fun lockState(): DeviceLockState

    fun authenticate(title: String, subtitle: String, onResult: (UnlockResult) -> Unit)
}

/**
 * The decision logic of the guard, free of Android types:
 * - setting off (and not [enforce]d): run the action;
 * - no authenticator (no activity to host the prompt): do nothing, report the failure;
 * - no secure lock screen: run the action (the action's own dialog already confirmed it);
 * - otherwise prompt; only [UnlockResult.Success] runs the action.
 *
 * One prompt at a time: a second request while one is showing is dropped.
 */
class DangerousActionGate(
    private val isEnabled: () -> Boolean,
    private val authenticator: () -> DeviceAuthenticator?,
) {
    private var inFlight = false

    fun confirm(
        title: String,
        subtitle: String,
        enforce: Boolean = false,
        onFailure: (String?) -> Unit = {},
        action: () -> Unit,
    ) {
        if (!enforce && !isEnabled()) {
            action()
            return
        }
        if (inFlight) return
        val auth = authenticator()
        if (auth == null) {
            onFailure(null)
            return
        }
        if (auth.lockState() == DeviceLockState.NotSecured) {
            action()
            return
        }
        inFlight = true
        auth.authenticate(title, subtitle) { result ->
            inFlight = false
            when (result) {
                UnlockResult.Success -> action()
                UnlockResult.Cancelled -> Unit
                is UnlockResult.Failed -> onFailure(result.message)
            }
        }
    }
}
