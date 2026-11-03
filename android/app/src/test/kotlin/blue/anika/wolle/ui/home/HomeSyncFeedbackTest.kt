package blue.anika.wolle.ui.home

import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSyncFeedbackTest {
    private val pull = RefreshTrigger.UserInitiated
    private val auto = RefreshTrigger.Automatic

    @Test
    fun `idle is the usual question`() {
        assertEquals(GreetingStatus.Question, greetingStatus(RefreshState.Idle, signedOut = false))
    }

    @Test
    fun `any refresh in flight is checking, pulled or automatic`() {
        assertEquals(
            GreetingStatus.Checking,
            greetingStatus(RefreshState.Refreshing(pull), signedOut = false),
        )
        assertEquals(
            GreetingStatus.Checking,
            greetingStatus(RefreshState.Refreshing(auto), signedOut = false),
        )
    }

    @Test
    fun `success without news is caught up, with news it is updated`() {
        assertEquals(
            GreetingStatus.CaughtUp,
            greetingStatus(RefreshState.Finished(changed = false, trigger = pull), false),
        )
        assertEquals(
            GreetingStatus.Updated(null),
            greetingStatus(RefreshState.Finished(changed = true, trigger = pull), false),
        )
        assertEquals(
            GreetingStatus.Updated(3),
            greetingStatus(RefreshState.Finished(changed = true), false, updateCount = 3),
        )
    }

    @Test
    fun `an automatic refresh without news stays silent`() {
        assertEquals(
            GreetingStatus.Question,
            greetingStatus(RefreshState.Finished(changed = false, trigger = auto), false),
        )
        assertEquals(
            GreetingStatus.Updated(null),
            greetingStatus(RefreshState.Finished(changed = true, trigger = auto), false),
        )
    }

    @Test
    fun `errors leave the subtitle alone, the card carries them`() {
        assertEquals(GreetingStatus.Question, greetingStatus(RefreshState.Offline, false))
        assertEquals(GreetingStatus.Question, greetingStatus(RefreshState.Error, false))
    }

    @Test
    fun `signed out says nothing about syncing`() {
        for (state in
            listOf(
                RefreshState.Idle,
                RefreshState.Refreshing(pull),
                RefreshState.Finished(changed = true),
                RefreshState.Error,
            )) {
            assertEquals(GreetingStatus.Question, greetingStatus(state, signedOut = true))
        }
    }

    @Test
    fun `only finished messages are outcomes`() {
        assertEquals(true, GreetingStatus.CaughtUp.isOutcome())
        assertEquals(true, GreetingStatus.Updated(2).isOutcome())
        assertEquals(false, GreetingStatus.Checking.isOutcome())
        assertEquals(false, GreetingStatus.Question.isOutcome())
    }

    @Test
    fun `the problem card appears on failure and stays until a refresh succeeds`() {
        var problem: SyncProblem? = null
        problem = problemAfter(problem, RefreshState.Offline, false)
        assertEquals(SyncProblem.Offline, problem)
        // A retry in flight and the consumed Idle keep it, so it does not flicker.
        problem = problemAfter(problem, RefreshState.Refreshing(pull), false)
        assertEquals(SyncProblem.Offline, problem)
        problem = problemAfter(problem, RefreshState.Error, false)
        assertEquals(SyncProblem.Failed, problem)
        problem = problemAfter(problem, RefreshState.Idle, false)
        assertEquals(SyncProblem.Failed, problem)
        problem = problemAfter(problem, RefreshState.Finished(changed = false), false)
        assertEquals(null, problem)
    }

    @Test
    fun `signed out never shows the problem card`() {
        assertEquals(null, problemAfter(SyncProblem.Failed, RefreshState.Error, signedOut = true))
    }

    @Test
    fun `the top bar takes over as the greeting scrolls out`() {
        assertEquals(0f, greetingHandoff(0f, instant = false), 0f)
        assertEquals(0f, greetingHandoff(0.25f, instant = false), 0f)
        assertEquals(0.5f, greetingHandoff(0.475f, instant = false), 0.001f)
        assertEquals(1f, greetingHandoff(0.7f, instant = false), 0f)
        assertEquals(1f, greetingHandoff(3f, instant = false), 0f)
    }

    @Test
    fun `without animations the top bar just toggles`() {
        assertEquals(0f, greetingHandoff(0.3f, instant = true), 0f)
        assertEquals(1f, greetingHandoff(0.5f, instant = true), 0f)
    }
}
