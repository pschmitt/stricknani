package blue.anika.wolle.e2e

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.MainActivity
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
        composeRule
            .onNodeWithText("Server URL", useUnmergedTree = true)
            .performTextInput(baseUrl)
        composeRule
            .onNodeWithText("API token", useUnmergedTree = true)
            .performTextInput(token)
        composeRule.onNodeWithText("Connect").performClick()
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
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
}
