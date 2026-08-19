package blue.anika.wolle.screenshots

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText as hasTextMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import blue.anika.wolle.MainActivity
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review-only screenshot coverage for the manual Android screenshot workflow.
 *
 * This intentionally lives outside the disposable E2E package: the screenshots are a stable,
 * named visual inventory, while the E2E tests are responsible for journey assertions. The fixture
 * contract is the same as [blue.anika.wolle.e2e.StricknaniE2eTest].
 */
@RunWith(AndroidJUnit4::class)
class StricknaniScreenshotTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var device: UiDevice
    private lateinit var screenshotPrefix: String

    private val arguments
        get() = InstrumentationRegistry.getArguments()

    private val baseUrl: String
        get() = requiredArgument("e2e_base_url")

    private val token: String
        get() = requiredArgument("e2e_token")

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        screenshotPrefix =
            "${requiredArgument("screenshot_device")}_${requiredArgument("screenshot_theme")}"
    }

    @After
    fun captureFinalState() {
        // A final frame is useful when a later named state fails. Best effort is deliberate: a
        // broken emulator must not hide the instrumentation failure that produced the report.
        capture("final-state")
        setAirplaneMode(enabled = false)
    }

    @Test
    fun captureNamedProductStates() {
        capture("onboarding")

        connectToFixture()
        capture("home")

        composeRule.onNodeWithText("Projects").performClick()
        waitForText("Search projects")
        composeRule
            .onNodeWithTag("e2e-projects-list")
            .performScrollToNode(hasTextMatcher("Heirloom Baby Blanket"))
        waitForText("Heirloom Baby Blanket", timeoutMillis = 120_000)
        capture("project-list")

        composeRule
            .onNodeWithText("Heirloom Baby Blanket", useUnmergedTree = true)
            .performClick()
        composeRule
            .onNodeWithTag("e2e-project-detail")
            .performScrollToNode(hasTextMatcher("Description"))
        waitForText("Description", timeoutMillis = 120_000)
        capture("project")

        composeRule
            .onNodeWithTag("e2e-project-detail")
            .performScrollToNode(hasTextMatcher("Stitch sample"))
        waitForText("Stitch sample", timeoutMillis = 120_000)
        capture("project-stitch-sample")

        composeRule
            .onNodeWithTag("e2e-project-detail")
            .performScrollToNode(hasTextMatcher("Notes"))
        waitForText("Notes", timeoutMillis = 120_000)
        capture("project-notes")

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Yarns").performClick()
        waitForText("Search yarns")
        composeRule
            .onNodeWithTag("e2e-yarns-list")
            .performScrollToNode(hasTextMatcher("Riverbend Merino DK"))
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
        capture("yarn-list")

        composeRule.onNodeWithText("Riverbend Merino DK").performClick()
        composeRule
            .onNodeWithTag("e2e-yarn-detail")
            .performScrollToNode(hasTextMatcher("Brand"))
        waitForText("Brand", timeoutMillis = 120_000)
        capture("yarn")

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        waitForText("Settings")
        capture("settings")

        captureOfflineState()
    }

    private fun connectToFixture() {
        // A test method can start with the persisted configured state from an earlier method.
        // Home itself only shows favorites/recent projects, so use the Projects nav label as the
        // configured-state marker and assert fixture rows only after navigating to their lists.
        composeRule.waitUntil(120_000) {
            hasTag("e2e-onboarding-server-url") || hasText("Projects")
        }
        if (hasTag("e2e-onboarding-server-url")) {
            composeRule.onNodeWithTag("e2e-onboarding-server-url").performTextInput(baseUrl)
            composeRule.onNodeWithTag("e2e-onboarding-api-token").performTextInput(token)
            grantNotificationPermission()
            composeRule.onNodeWithText("Connect").performClick()
        }
        waitForText("Projects", timeoutMillis = 120_000)
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand(
                    "pm grant blue.anika.wolle.debug ${Manifest.permission.POST_NOTIFICATIONS}",
                )
                .close()
        }
    }

    private fun captureOfflineState() {
        composeRule.onNodeWithText("Home").performClick()
        waitForText("Home")
        setAirplaneMode(enabled = true)
        try {
            // Target the actual refresh container instead of relying on emulator coordinates;
            // the latter can land on the bottom navigation bar on smaller screenshot devices.
            composeRule
                .onNodeWithTag("e2e-home-refresh")
                .performTouchInput {
                    swipe(center.copy(y = top + height * 0.25f), center.copy(y = top + height * 0.75f))
                }
            waitForText("You’re offline - showing cached data.", timeoutMillis = 30_000)
            capture("offline")
        } finally {
            setAirplaneMode(enabled = false)
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) {
            hasText(text)
        }
    }

    private fun hasTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(text: String): Boolean =
        composeRule
            .onAllNodesWithText(text, substring = false, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun capture(name: String) {
        runCatching {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                val directory =
                    instrumentation.targetContext
                        .getExternalFilesDir("screenshot-captures")
                        ?.apply(File::mkdirs) ?: return
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                File(directory, "$screenshotPrefix-$safeName.png").outputStream().use { output ->
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                screenshot.recycle()
            }
            .onFailure { error ->
                System.err.println("Unable to capture screenshot $name: ${error.message}")
            }
    }

    private fun setAirplaneMode(enabled: Boolean) {
        runCatching {
                val command =
                    if (enabled) "cmd connectivity airplane-mode enable"
                    else "cmd connectivity airplane-mode disable"
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {}
            }
            .onFailure { error ->
                System.err.println("Unable to set airplane mode to $enabled: ${error.message}")
            }
    }

    private fun requiredArgument(name: String): String =
        arguments.getString(name)?.takeIf(String::isNotBlank)
            ?: error("$name instrumentation argument is required")
}
