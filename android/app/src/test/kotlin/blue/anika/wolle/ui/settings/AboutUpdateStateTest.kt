package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.UpdateCheckResult
import blue.anika.wolle.data.update.UpdateFailure
import blue.anika.wolle.data.update.UpdateInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AboutUpdateStateTest {
    private val update = AppUpdate("1.2.0", "https://example.test/a.apk")

    private fun map(self: Boolean = true, checking: Boolean = false, r: UpdateCheckResult?) =
        aboutUpdateState(self, checking, r)

    @Test
    fun `no result yet means checking`() {
        assertEquals(AboutUpdateState.Checking, map(r = null))
    }

    @Test
    fun `running check wins over a previous result`() {
        assertEquals(AboutUpdateState.Checking, map(checking = true, r = UpdateCheckResult.Failed))
    }

    @Test
    fun `results map one to one`() {
        assertEquals(AboutUpdateState.UpToDate, map(r = UpdateCheckResult.UpToDate))
        assertEquals(
            AboutUpdateState.AheadOfStable,
            map(r = UpdateCheckResult.AheadOfStable),
        )
        assertEquals(
            AboutUpdateState.Available(update),
            map(r = UpdateCheckResult.Available(update)),
        )
        assertEquals(AboutUpdateState.Failed, map(r = UpdateCheckResult.Failed))
        assertEquals(AboutUpdateState.DevBuild, map(r = UpdateCheckResult.NotEligible))
    }

    @Test
    fun `play builds never show a github state`() {
        assertEquals(
            AboutUpdateState.Play,
            map(self = false, checking = true, r = UpdateCheckResult.Available(update)),
        )
    }

    @Test
    fun `an available idle update is offered`() {
        assertEquals(
            update,
            aboutUpdateOffer(AboutUpdateState.Available(update), UpdateInstallState.Idle),
        )
    }

    @Test
    fun `no offer while the install runs`() {
        assertNull(
            aboutUpdateOffer(
                AboutUpdateState.Available(update),
                UpdateInstallState.Downloading(10, 100),
            )
        )
    }

    @Test
    fun `no offer after a failed install`() {
        val failed = UpdateInstallState.Failed(UpdateFailure.entries.first())
        assertNull(aboutUpdateOffer(AboutUpdateState.Available(update), failed))
    }

    @Test
    fun `no offer when up to date`() {
        assertNull(aboutUpdateOffer(AboutUpdateState.UpToDate, UpdateInstallState.Idle))
    }

    @Test
    fun `trailing slot follows the state`() {
        val idle = UpdateInstallState.Idle
        fun trailing(state: AboutUpdateState) = aboutUpdateTrailing(state, idle)

        assertEquals(AboutUpdateTrailing.Checking, trailing(AboutUpdateState.Checking))
        assertEquals(AboutUpdateTrailing.UpToDate, trailing(AboutUpdateState.UpToDate))
        assertEquals(AboutUpdateTrailing.AheadOfStable, trailing(AboutUpdateState.AheadOfStable))
        assertEquals(AboutUpdateTrailing.Offer, trailing(AboutUpdateState.Available(update)))
        assertEquals(AboutUpdateTrailing.Failed, trailing(AboutUpdateState.Failed))
        assertEquals(AboutUpdateTrailing.None, trailing(AboutUpdateState.DevBuild))
        assertEquals(AboutUpdateTrailing.Play, trailing(AboutUpdateState.Play))
    }

    @Test
    fun `trailing slot is empty while an offered update installs or failed`() {
        val available = AboutUpdateState.Available(update)
        val failed = UpdateInstallState.Failed(UpdateFailure.entries.first())

        assertEquals(
            AboutUpdateTrailing.None,
            aboutUpdateTrailing(available, UpdateInstallState.Downloading(10, 100)),
        )
        assertEquals(AboutUpdateTrailing.None, aboutUpdateTrailing(available, failed))
    }
}
