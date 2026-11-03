package blue.anika.wolle.ui.home

import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger

/**
 * What Home's greeting says about a sync. There is no status card any more: the greeting subtitle
 * and the mascot carry it, and only a failed refresh adds a card (see [SyncProblemCard]).
 */
sealed interface GreetingStatus {
    /** Nothing to report: the usual "What are we knitting today?". */
    data object Question : GreetingStatus

    /** A refresh (pulled or automatic) is running. */
    data object Checking : GreetingStatus

    /** A refresh finished and nothing was new. */
    data object CaughtUp : GreetingStatus

    /** A refresh finished and brought news; [count] is how many, when it is known. */
    data class Updated(val count: Int?) : GreetingStatus
}

/** Why the last refresh could not check for news; shown by [SyncProblemCard]. */
enum class SyncProblem {
    /** The device could not reach the server. */
    Offline,
    /** The server was reached but the sync failed (for example a rejected sign-in). */
    Failed,
}

/** How long a success message stays in the subtitle before the question comes back. */
const val GREETING_OUTCOME_MILLIS = 3_000L

/**
 * The greeting status for a refresh [state]. While signed out the panel above the greeting speaks,
 * so the subtitle never says anything about syncing. An automatic refresh that found nothing is
 * silent, as it runs on every visit; one that found news, or any pull, reports back.
 */
fun greetingStatus(
    state: RefreshState,
    signedOut: Boolean,
    updateCount: Int? = null,
): GreetingStatus =
    when {
        signedOut -> GreetingStatus.Question
        state is RefreshState.Refreshing -> GreetingStatus.Checking
        state is RefreshState.Finished && state.changed -> GreetingStatus.Updated(updateCount)
        state is RefreshState.Finished && state.trigger == RefreshTrigger.UserInitiated ->
            GreetingStatus.CaughtUp
        else -> GreetingStatus.Question
    }

/** A finished refresh's message, which the subtitle holds on to for a moment. */
fun GreetingStatus.isOutcome(): Boolean =
    this is GreetingStatus.CaughtUp || this is GreetingStatus.Updated

/**
 * The problem card after [state]: a failure shows it, a finished refresh clears it, and everything
 * else (idle, a refresh in flight) leaves it as it was, so it does not flicker during a retry.
 */
fun problemAfter(previous: SyncProblem?, state: RefreshState, signedOut: Boolean): SyncProblem? =
    when {
        signedOut -> null
        state == RefreshState.Offline -> SyncProblem.Offline
        state == RefreshState.Error -> SyncProblem.Failed
        state is RefreshState.Finished -> null
        else -> previous
    }

/** Rotation keyframes in degrees for a head-shake (the signed-out panel's icon); ends at rest. */
val SYNC_SHAKE_ANGLES = listOf(-6f, 6f, -5f, 4f, -2f, 0f)
