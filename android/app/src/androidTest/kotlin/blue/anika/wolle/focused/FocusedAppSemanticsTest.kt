package blue.anika.wolle.focused

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText as hasTextMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import blue.anika.wolle.MainActivity
import blue.anika.wolle.ui.common.DESTRUCTIVE_DELETE_DIALOG_TAG
import blue.anika.wolle.ui.common.DESTRUCTIVE_DELETE_ICON_TAG
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focused accessibility and state journeys, kept separate from the disposable T80 E2E package.
 *
 * Configured journeys use the same disposable fixture arguments as T80. The test class is also
 * useful against a freshly installed APK: the onboarding test needs no server at all, while the
 * configured tests skip with an explicit assumption when those arguments are absent.
 */
@RunWith(AndroidJUnit4::class)
class FocusedAppSemanticsTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingValidationAndCredentialControlsAreAccessible() {
        ensureOnboarding()

        composeRule.onNodeWithText("Connect to Stricknani").assertIsDisplayed()
        composeRule.onNodeWithText("Server URL", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("API token", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show token").assertHasClickAction()

        composeRule.onNodeWithText("Connect").performClick()
        waitForText("Enter both the server URL and the API token")

        composeRule.onNodeWithTag("e2e-onboarding-server-url").performTextInput("not-a-server-url")
        composeRule.onNodeWithTag("e2e-onboarding-api-token").performTextInput("focused-test-token")
        composeRule.onNodeWithContentDescription("Show token").performClick()
        composeRule.onNodeWithContentDescription("Hide token").assertIsDisplayed()
        composeRule.onNodeWithText("Connect").performClick()
        waitForText("Enter a valid server URL, e.g. https://stricknani.example.com")
    }

    @Test
    fun navigationAndFilteredEmptyStatesRemainDiscoverable() {
        ensureConfigured()

        listOf("Home", "Projects", "Yarns", "Search", "Gauge", "Settings").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }

        openProjects()
        composeRule.onNodeWithContentDescription("Search projects").performClick()
        composeRule
            .onNodeWithContentDescription("Search projects")
            .performTextInput("focused-no-project-match")
        waitForText("No projects yet")

        openYarns()
        composeRule.onNodeWithContentDescription("Search yarns").performClick()
        composeRule
            .onNodeWithContentDescription("Search yarns")
            .performTextInput("focused-no-yarn-match")
        waitForText("No yarns yet")

        composeRule.onNodeWithText("Search").performClick()
        waitForText("Search your stash")
        composeRule.onNodeWithContentDescription("Search projects and yarns").performClick()
        composeRule
            .onNodeWithContentDescription("Search projects and yarns")
            .performTextInput("focused-no-search-match")
        waitForText("No matches")
    }

    @Test
    fun settingsCategoriesAndSignOutDialogExposeAccessibleActions() {
        ensureConfigured()
        composeRule.onNodeWithText("Settings").performClick()
        waitForText("Settings")

        listOf(
                "Account: Server connection and sign-out",
                "Appearance: Theme",
                "Navigation: Choose destinations and their order",
                "Sync: Background refresh status",
                "Backup: Export, restore, and scheduled backups",
                "About: Application and server information",
            )
            .forEach { description ->
                composeRule.onNodeWithContentDescription(description).assertHasClickAction()
            }

        composeRule
            .onNodeWithContentDescription("Account: Server connection and sign-out")
            .performClick()
        waitForText("Server URL")
        clickSignOutButton()
        waitForText("Sign out?")
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("e2e-sign-out-confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(!hasText("Sign out?"))
    }

    @Test
    fun cachedContentSurvivesAnOfflineRefresh() {
        ensureConfigured()
        openProjects()
        composeRule.onNodeWithTag("e2e-projects-list").performScrollToIndex(0)

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        setAirplaneMode(device, enabled = true)
        try {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight / 3,
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                20,
            )
            waitForText("You’re offline - showing cached data.")
            composeRule.onNodeWithText("Heirloom Baby Blanket").assertIsDisplayed()
        } finally {
            setAirplaneMode(device, enabled = false)
        }
    }

    @Test
    fun projectAndYarnDeleteMenusRequireConfirmationWithoutMutatingData() {
        ensureConfigured()

        composeRule.onNodeWithText("Projects").performClick()
        waitForText("Heirloom Baby Blanket", timeoutMillis = 120_000)
        composeRule.onNodeWithText("Heirloom Baby Blanket").performClick()
        waitForText("More actions")
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithTag(DESTRUCTIVE_DELETE_ICON_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithTag(DESTRUCTIVE_DELETE_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Heirloom Baby Blanket").assertIsDisplayed()

        composeRule.onNodeWithText("Yarns").performClick()
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
        composeRule.onNodeWithText("Riverbend Merino DK").performClick()
        waitForText("More actions")
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithTag(DESTRUCTIVE_DELETE_ICON_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithTag(DESTRUCTIVE_DELETE_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Riverbend Merino DK").assertIsDisplayed()
    }

    private fun ensureOnboarding() {
        waitForEither("Connect to Stricknani", "Settings")
        if (!hasText("Connect to Stricknani")) {
            composeRule.onNodeWithText("Settings").performClick()
            waitForText("Settings")
            composeRule
                .onNodeWithContentDescription("Account: Server connection and sign-out")
                .performClick()
            waitForText("Server URL")
            clickSignOutButton()
            waitForText("Sign out?")
            confirmSignOutButton()
            waitForText("Connect to Stricknani")
        }
    }

    private fun ensureConfigured() {
        waitForEither("Connect to Stricknani", "Home")
        if (hasText("Connect to Stricknani")) {
            val baseUrl = instrumentationArgument("e2e_base_url")
            val token = instrumentationArgument("e2e_token")
            assumeTrue(
                "configured focused journeys require e2e_base_url and e2e_token",
                !baseUrl.isNullOrBlank() && !token.isNullOrBlank(),
            )
            composeRule.onNodeWithTag("e2e-onboarding-server-url").performTextInput(baseUrl!!)
            composeRule.onNodeWithTag("e2e-onboarding-api-token").performTextInput(token!!)
            grantNotificationPermission()
            composeRule.onNodeWithText("Connect").performClick()
        }
        waitForText("Home", timeoutMillis = 120_000)
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand(
                    "pm grant blue.anika.wolle.debug ${Manifest.permission.POST_NOTIFICATIONS}"
                )
                .close()
        }
    }

    private fun waitForEither(first: String, second: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) { hasText(first) || hasText(second) }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 30_000) {
        composeRule.waitUntil(timeoutMillis) { hasText(text) }
    }

    private fun hasText(text: String): Boolean =
        composeRule
            .onAllNodesWithText(text, substring = false, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun instrumentationArgument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf(String::isNotBlank)

    private fun clickSignOutButton() {
        composeRule.onNodeWithTag("e2e-sign-out-button").performClick()
    }

    private fun confirmSignOutButton() {
        composeRule.onNodeWithTag("e2e-sign-out-confirm").performClick()
    }

    private fun openProjects() {
        composeRule.onNodeWithText("Projects").performClick()
        waitForText("Search projects")
        composeRule
            .onNodeWithTag("e2e-projects-list")
            .performScrollToNode(hasTextMatcher("Heirloom Baby Blanket"))
        waitForText("Heirloom Baby Blanket", timeoutMillis = 120_000)
    }

    private fun openYarns() {
        composeRule.onNodeWithText("Yarns").performClick()
        waitForText("Search yarns")
        composeRule
            .onNodeWithTag("e2e-yarns-list")
            .performScrollToNode(hasTextMatcher("Riverbend Merino DK"))
        waitForText("Riverbend Merino DK", timeoutMillis = 120_000)
    }

    private fun setAirplaneMode(device: UiDevice, enabled: Boolean) {
        val command =
            if (enabled) "cmd connectivity airplane-mode enable"
            else "cmd connectivity airplane-mode disable"
        runCatching {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand(command)
                .close()
        }
    }
}
