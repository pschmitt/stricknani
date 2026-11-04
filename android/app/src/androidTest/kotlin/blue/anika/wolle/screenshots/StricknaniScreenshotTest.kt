package blue.anika.wolle.screenshots

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review-only screenshot coverage for the manual Android screenshot workflow.
 *
 * This intentionally lives outside the disposable E2E package: the screenshots are a stable, named
 * visual inventory, while the E2E tests are responsible for journey assertions. The fixture
 * contract is the same as [blue.anika.wolle.e2e.StricknaniE2eTest].
 */
@RunWith(AndroidJUnit4::class)
class StricknaniScreenshotTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var device: UiDevice
    private val arguments
        get() = InstrumentationRegistry.getArguments()

    private val baseUrl: String
        get() = requiredArgument("e2e_base_url")

    private val token: String
        get() = requiredArgument("e2e_token")

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @After
    fun captureFinalState() {
        // A final frame is useful when a later named state fails. Best effort is deliberate: a
        // broken emulator must not hide the instrumentation failure that produced the report.
        capture("final-state")
    }

    @Test
    fun captureNamedProductStates() {
        capture("onboarding")

        connectToFixture()
        // Home: greeting, mascot, Continue card. Let the greeting settle and the activity feed
        // load.
        // The first sync fills Home asynchronously; wait for the Continue card, then let the
        // greeting and the activity feed settle.
        composeRule.waitUntil(180_000) { hasTag("home-continue-card") }
        Thread.sleep(15_000)
        capture("home")

        // Recent activity sits further down Home.
        composeRule.onNodeWithText(string(R.string.activity_title)).performScrollTo()
        Thread.sleep(1_000)
        capture("activity")

        composeRule.onNodeWithText(string(R.string.destination_label_projects)).performClick()
        waitForText("Heirloom Baby Blanket", timeoutMillis = 120_000)
        Thread.sleep(1_500)
        capture("project-list")

        composeRule.onNodeWithText("Heirloom Baby Blanket", useUnmergedTree = true).performClick()
        composeRule.waitUntil(120_000) { hasTag("e2e-project-detail") }
        Thread.sleep(2_000)
        capture("project")

        composeRule.onNodeWithTag("e2e-project-detail").performTouchInput { swipeUp() }
        Thread.sleep(1_000)
        capture("project-details")

        composeRule.onNodeWithContentDescription(string(R.string.settings_nav_back)).performClick()
        composeRule.onNodeWithText(string(R.string.destination_label_home)).performClick()
        composeRule.onNodeWithTag("home-stash-card").performScrollTo().performClick()
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
        Thread.sleep(1_500)
        capture("yarn-list")

        composeRule.onNodeWithText("Riverbend Merino DK").performClick()
        composeRule.waitUntil(120_000) { hasTag("e2e-yarn-detail") }
        Thread.sleep(2_000)
        capture("yarn")

        composeRule.onNodeWithContentDescription(string(R.string.settings_nav_back)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.settings_nav_back)).performClick()
        composeRule.onNodeWithText(string(R.string.destination_label_home)).performClick()
        composeRule
            .onNodeWithContentDescription(string(R.string.home_gauge_calculator_description))
            .performClick()
        waitForText(string(R.string.gauge_calculator_title))
        Thread.sleep(1_000)
        capture("gauge")
    }

    private fun connectToFixture() {
        // A test method can start with the persisted configured state from an earlier method.
        // Home itself only shows favorites/recent projects, so use the Projects nav label as the
        // configured-state marker and assert fixture rows only after navigating to their lists.
        composeRule.waitUntil(120_000) {
            hasTag("e2e-onboarding-server-url") ||
                hasText(string(R.string.destination_label_projects))
        }
        if (hasTag("e2e-onboarding-server-url")) {
            composeRule
                .onNodeWithTag("onboarding-mode-manual")
                .performSemanticsAction(SemanticsActions.OnClick)
            // The form cross-fades between modes; typing into a field that is still fading out
            // fails with "Failed to perform text input", so wait for the token field first.
            composeRule.waitUntil(30_000) { hasTag("e2e-onboarding-api-token") }
            composeRule.waitForIdle()
            Thread.sleep(1_000)
            composeRule.onNodeWithTag("e2e-onboarding-server-url").performTextInput(baseUrl)
            composeRule.onNodeWithTag("e2e-onboarding-api-token").performClick()
            composeRule.onNodeWithTag("e2e-onboarding-api-token").performTextInput(token)
            grantNotificationPermission()
            composeRule.onNodeWithText(string(R.string.onboarding_connect_button)).performClick()
        }
        waitForText(string(R.string.destination_label_projects), timeoutMillis = 120_000)
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand(
                    "pm grant ${composeRule.activity.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
                )
                .close()
        }
    }

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun waitForText(text: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) { hasText(text) }
    }

    private fun hasTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(text: String): Boolean =
        composeRule
            .onAllNodesWithText(text, substring = false, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun capture(name: String) {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("touch $CAPTURE_MARKER_PREFIX$safeName")
            .use {}
        // The host-side harness captures the current emulator frame with adb. Keeping the bitmap
        // out of the instrumentation process avoids Binder-size and app-specific storage limits
        // on tablet profiles.
        Thread.sleep(1_000)
    }

    private fun requiredArgument(name: String): String =
        arguments.getString(name)?.takeIf(String::isNotBlank)
            ?: error("$name instrumentation argument is required")

    private companion object {
        const val CAPTURE_MARKER_PREFIX = "/data/local/tmp/stricknani-screenshot-capture-"
    }
}
