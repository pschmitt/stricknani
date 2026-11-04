package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.PrivilegedState

/** What the Shizuku row explains under its switch. */
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

/** How the "Install updates silently with Shizuku" row looks: shown, switchable, on, and why. */
data class ShizukuToggleUi(
    val visible: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val hint: ShizukuHint?,
)

/**
 * The row exists only in developer mode and in builds that carry Shizuku. The switch is usable only
 * while the app could ask for, or already has, the permission; in every other state it is off and
 * disabled with a hint, whatever was stored before.
 */
fun shizukuToggleUi(
    developerMode: Boolean,
    supported: Boolean,
    preferenceOn: Boolean,
    state: PrivilegedState,
): ShizukuToggleUi {
    if (!developerMode || !supported) return ShizukuToggleUi(false, false, false, null)
    return when (state) {
        PrivilegedState.UNAVAILABLE ->
            ShizukuToggleUi(true, false, false, ShizukuHint.NOT_INSTALLED)
        PrivilegedState.NOT_RUNNING -> ShizukuToggleUi(true, false, false, ShizukuHint.NOT_RUNNING)
        PrivilegedState.PERMISSION_DENIED ->
            ShizukuToggleUi(true, false, false, ShizukuHint.PERMISSION_DENIED)
        PrivilegedState.PERMISSION_NEEDED ->
            ShizukuToggleUi(true, true, preferenceOn, ShizukuHint.PERMISSION_NEEDED)
        PrivilegedState.READY -> ShizukuToggleUi(true, true, preferenceOn, ShizukuHint.READY)
    }
}
