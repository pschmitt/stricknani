package blue.anika.wolle.screenshots

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
        waitForText("Heirloom Baby Blanket", timeoutMillis = 120_000)
        capture("project-list")

        composeRule
            .onNodeWithText("Heirloom Baby Blanket", useUnmergedTree = true)
            .performClick()
        waitForText("Description", timeoutMillis = 120_000)
        capture("project")

        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Yarns").performClick()
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
        capture("yarn-list")

        composeRule.onNodeWithText("Riverbend Merino DK").performClick()
        waitForText("Brand", timeoutMillis = 120_000)
        capture("yarn")

        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        waitForText("Settings")
        capture("settings")

        captureOfflineState()
    }

    private fun connectToFixture() {
        composeRule.onNodeWithTag("e2e-onboarding-server-url").performTextInput(baseUrl)
        composeRule.onNodeWithTag("e2e-onboarding-api-token").performTextInput(token)
        grantNotificationPermission()
        composeRule.onNodeWithText("Connect").performClick()
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
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
        waitForText("Riverbend Merino DK")
        setAirplaneMode(enabled = true)
        try {
            // Home is at the top after navigating from Settings. Pulling far enough to pass the
            // Material 3 threshold exercises the real refresh/offline feedback path.
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight / 3,
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                20,
            )
            waitForText("You’re offline - showing cached data.", timeoutMillis = 30_000)
            capture("offline")
        } finally {
            setAirplaneMode(enabled = false)
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule
                .onAllNodesWithText(text, substring = false, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

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
