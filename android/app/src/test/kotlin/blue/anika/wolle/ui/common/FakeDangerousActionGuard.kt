package blue.anika.wolle.ui.common

/** A guard for ViewModel tests: [unlocked] says whether the "device unlock" succeeds. */
internal class FakeDangerousActionGuard(var unlocked: Boolean = true) : DangerousActionGuard {
    /** The reasons asked for, in order (a request counts even when the unlock was refused). */
    val requests = mutableListOf<Int>()

    override fun confirm(reason: Int, action: () -> Unit) {
        requests += reason
        if (unlocked) action()
    }

    override fun confirmAlways(reason: Int, action: () -> Unit) = confirm(reason, action)
}
