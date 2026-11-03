package blue.anika.wolle.ui.settings

internal sealed interface DeveloperModeTapAction {
    /** Taps 1 and 2: nothing to show yet. */
    data object Silent : DeveloperModeTapAction

    data object AlreadyDeveloper : DeveloperModeTapAction

    /** Taps 3 to 6: [stepsLeft] more taps (4, 3, 2, 1) to go. */
    data class Countdown(val stepsLeft: Int) : DeveloperModeTapAction

    data object Unlock : DeveloperModeTapAction
}

/**
 * Taps on the Build row. The countdown starts after the 3rd tap; the 7th unlocks. A gap of more
 * than [tapWindowMillis] between two taps starts the count over.
 */
internal class DeveloperModeTapState(
    private val requiredTaps: Int = 7,
    private val silentTaps: Int = 2,
    private val tapWindowMillis: Long = 2_000L,
) {
    private var tapCount = 0
    private var lastTapAt = 0L

    fun onTap(now: Long, developerModeEnabled: Boolean): DeveloperModeTapAction {
        if (developerModeEnabled) return DeveloperModeTapAction.AlreadyDeveloper
        if (tapCount > 0 && now - lastTapAt > tapWindowMillis) tapCount = 0
        lastTapAt = now
        tapCount++
        return when {
            tapCount >= requiredTaps -> {
                tapCount = 0
                DeveloperModeTapAction.Unlock
            }
            tapCount <= silentTaps -> DeveloperModeTapAction.Silent
            else -> DeveloperModeTapAction.Countdown(requiredTaps - tapCount)
        }
    }

    fun reset() {
        tapCount = 0
    }
}
