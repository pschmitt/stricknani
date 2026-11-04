package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.UpdateCheckResult

/** What the About screen's version section says about updates. */
sealed interface AboutUpdateState {
    /** A check is running. */
    data object Checking : AboutUpdateState

    data object UpToDate : AboutUpdateState

    /** A newer build exists; [AppUpdate.downloadUrl] is the same link the Home card opens. */
    data class Available(val update: AppUpdate) : AboutUpdateState

    data object Failed : AboutUpdateState

    /** Newer than the latest stable release (e.g. a rolling build on the stable channel). */
    data object AheadOfStable : AboutUpdateState

    /** Local or dirty build: there is no release to compare against, so nothing is claimed. */
    data object DevBuild : AboutUpdateState

    /** Play installs update through Google Play, not GitHub. */
    data object Play : AboutUpdateState
}

/**
 * Maps the checker outcome to the row state. [checking] wins over a stale [result] so a manual
 * re-check shows progress; a build without self-update never reaches the checker.
 */
fun aboutUpdateState(
    selfUpdateEnabled: Boolean,
    checking: Boolean,
    result: UpdateCheckResult?,
): AboutUpdateState =
    when {
        !selfUpdateEnabled -> AboutUpdateState.Play
        checking || result == null -> AboutUpdateState.Checking
        else ->
            when (result) {
                UpdateCheckResult.UpToDate -> AboutUpdateState.UpToDate
                is UpdateCheckResult.Available -> AboutUpdateState.Available(result.update)
                UpdateCheckResult.AheadOfStable -> AboutUpdateState.AheadOfStable
                UpdateCheckResult.NotEligible -> AboutUpdateState.DevBuild
                UpdateCheckResult.Failed -> AboutUpdateState.Failed
            }
    }
