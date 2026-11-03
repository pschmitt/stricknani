package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.UpdateCheckResult
import org.junit.Assert.assertEquals
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
}
