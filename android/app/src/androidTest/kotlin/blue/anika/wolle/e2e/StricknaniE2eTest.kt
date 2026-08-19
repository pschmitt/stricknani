package blue.anika.wolle.e2e

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import blue.anika.wolle.MainActivity
import java.io.Closeable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared fixture contract and wait helpers for the PR smoke and manual full lanes. */
abstract class StricknaniE2eTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    protected val baseUrl: String
        get() = requiredArgument("e2e_base_url")

    protected val token: String
        get() = requiredArgument("e2e_token")

    protected fun connectToFixture() {
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

    protected fun waitForText(text: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule
                .onAllNodesWithText(text, substring = false, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    protected fun openProjects() {
        composeRule.onNodeWithText("Projects").performClick()
        waitForText("Heirloom Baby Blanket", timeoutMillis = 120_000)
    }

    protected fun openSettingsAccount() {
        composeRule.onNodeWithText("Settings").performClick()
        waitForText("Settings")
        composeRule
            .onNodeWithContentDescription("Account: Server connection and sign-out")
            .performClick()
        waitForText("Server URL")
        waitForText(baseUrl)
    }

    protected fun enterSearchTerm(term: String) {
        composeRule.onNodeWithText("Search").performClick()
        waitForText("Search projects and yarns")
        composeRule
            .onNodeWithText("Search projects and yarns", useUnmergedTree = true)
            .performClick()
        composeRule
            .onNodeWithText("Search projects and yarns", useUnmergedTree = true)
            .performTextInput(term)
        waitForText("Riverbend Merino DK")
    }

    private fun requiredArgument(name: String): String =
        androidx.test.platform.app.InstrumentationRegistry
            .getArguments()
            .getString(name)
            ?.takeIf(String::isNotBlank)
            ?: error("$name instrumentation argument is required")
}

@RunWith(AndroidJUnit4::class)
class StricknaniSmokeTest : StricknaniE2eTest() {
    @Test
    fun onboardingSyncDetailAndSettings() {
        connectToFixture()
        captureE2eScreenshot("smoke-01-home")

        openProjects()
        composeRule.onNodeWithText("Heirloom Baby Blanket").performClick()
        waitForText("Description")
        captureE2eScreenshot("smoke-02-project-detail")

        composeRule.onNodeWithContentDescription("Back").performClick()
        openSettingsAccount()
        captureE2eScreenshot("smoke-03-settings-account")
    }
}

@RunWith(AndroidJUnit4::class)
class StricknaniFullE2eTest : StricknaniE2eTest() {
    @Test
    fun cachedBrowsingSearchAndSettings() {
        connectToFixture()
        captureE2eScreenshot("full-01-home")

        openProjects()
        composeRule.onNodeWithText("Heirloom Baby Blanket").performClick()
        waitForText("Description")
        composeRule.onNodeWithContentDescription("Back").performClick()

        enterSearchTerm("Riverbend")
        captureE2eScreenshot("full-02-search")

        composeRule.onNodeWithText("Settings").performClick()
        waitForText("Settings")
        captureE2eScreenshot("full-03-settings")
    }

    @Test
    fun cachedBrowsingAndQueuedEditSurviveOfflineMode() {
        connectToFixture()
        openProjects()
        composeRule.onNodeWithText("Heirloom Baby Blanket").performClick()
        waitForText("Description")

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        setAirplaneMode(device, enabled = true)
        try {
            // The detail remains available from Room while the server is unreachable.
            composeRule.onNodeWithContentDescription("More actions").performClick()
            composeRule.onNodeWithText("Edit").performClick()
            waitForText("Edit project")
            composeRule
                .onNodeWithText("Heirloom Baby Blanket", useUnmergedTree = true)
                .performTextReplacement("Offline queued project")
            composeRule.onNodeWithContentDescription("Save").performClick()
            waitForText("Offline queued project")

            // Returning to the cached list and Home proves both the read path and pending-write
            // status survive without a network response.
            composeRule.onNodeWithText("Projects").performClick()
            waitForText("Offline queued project")
            composeRule.onNodeWithText("Home").performClick()
            waitForText("Changes pending")
            captureE2eScreenshot("full-04-offline-queued-edit")
        } finally {
            setAirplaneMode(device, enabled = false)
        }
    }

    private fun setAirplaneMode(device: UiDevice, enabled: Boolean) {
        val command =
            if (enabled) "cmd connectivity airplane-mode enable"
            else "cmd connectivity airplane-mode disable"
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .closeQuietly()
    }

    private fun Closeable.closeQuietly() {
        runCatching { close() }
    }
}
