package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.PrivilegedState

/** What the Shizuku row says under its title. */
enum class ShizukuHint {
    /** Shizuku (or Sui) is not on the device, or is too old. */
    NOT_INSTALLED,
    NOT_RUNNING,

    /** The app may still ask; the allow button does that. */
    PERMISSION_NEEDED,

    /** Denied for good: only the Shizuku app can grant it again. */
    PERMISSION_DENIED,
    READY,
}

/** Whether the Shizuku status row shows, and the hint under it. */
data class ShizukuStatusUi(val visible: Boolean, val hint: ShizukuHint?)

/**
 * The row exists only in builds that carry Shizuku. Updates use Shizuku whenever it is ready, so
 * the row only reports where it stands. A missing Shizuku is mentioned in developer mode only; in
 * every other state the row is shown, since the user has installed or started it.
 */
fun shizukuStatusUi(
    developerMode: Boolean,
    supported: Boolean,
    state: PrivilegedState,
): ShizukuStatusUi {
    if (!supported) return ShizukuStatusUi(false, null)
    return when (state) {
        PrivilegedState.UNAVAILABLE ->
            ShizukuStatusUi(developerMode, if (developerMode) ShizukuHint.NOT_INSTALLED else null)
        PrivilegedState.NOT_RUNNING -> ShizukuStatusUi(true, ShizukuHint.NOT_RUNNING)
        PrivilegedState.PERMISSION_NEEDED -> ShizukuStatusUi(true, ShizukuHint.PERMISSION_NEEDED)
        PrivilegedState.PERMISSION_DENIED -> ShizukuStatusUi(true, ShizukuHint.PERMISSION_DENIED)
        PrivilegedState.READY -> ShizukuStatusUi(true, ShizukuHint.READY)
    }
}
