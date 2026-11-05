package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.UpdateCheckResult
import blue.anika.wolle.data.update.UpdateInstallState

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

/**
 * The update the version row offers a button for: an available update with no install running and
 * no failed install to report. Otherwise the version row shows the progress or failure.
 */
fun aboutUpdateOffer(state: AboutUpdateState, install: UpdateInstallState): AppUpdate? =
    (state as? AboutUpdateState.Available)?.update?.takeIf {
        !install.isBusy && install !is UpdateInstallState.Failed
    }

/** What the version row shows in its trailing slot. */
enum class AboutUpdateTrailing {
    /** Nothing: a development build, or an install whose progress or failure shows below. */
    None,
    Checking,
    UpToDate,
    AheadOfStable,
    /** The Update button. */
    Offer,
    Failed,
    /** The button that opens the Play listing. */
    Play,
}

/** Maps the update state and install state to the version row's trailing slot. */
fun aboutUpdateTrailing(state: AboutUpdateState, install: UpdateInstallState): AboutUpdateTrailing =
    when (state) {
        AboutUpdateState.Checking -> AboutUpdateTrailing.Checking
        AboutUpdateState.UpToDate -> AboutUpdateTrailing.UpToDate
        AboutUpdateState.AheadOfStable -> AboutUpdateTrailing.AheadOfStable
        is AboutUpdateState.Available ->
            if (aboutUpdateOffer(state, install) != null) AboutUpdateTrailing.Offer
            else AboutUpdateTrailing.None
        AboutUpdateState.Failed -> AboutUpdateTrailing.Failed
        AboutUpdateState.DevBuild -> AboutUpdateTrailing.None
        AboutUpdateState.Play -> AboutUpdateTrailing.Play
    }
