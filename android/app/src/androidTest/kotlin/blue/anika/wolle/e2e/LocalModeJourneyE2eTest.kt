package blue.anika.wolle.e2e

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.localserver.LocalServerNative
import blue.anika.wolle.sync.RustReplayTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local-only mode (T130, `docs/local-only.md`) as a user journey: onboarding "Only on this phone",
 * a project created in the editor, the project still there after the activity is recreated (and
 * on the in-app server itself), and an Account page that says "Only on this phone" and shows none
 * of the server-only cards. Needs a build with the server library (`android/local-server-libs`);
 * skipped without one. Runs in the E2E workflow's `backups` lane, which builds that library.
 */
@RunWith(AndroidJUnit4::class)
class LocalModeJourneyE2eTest : StricknaniE2eTest() {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val graph: RustReplayTestEntryPoint
        get() = EntryPointAccessors.fromApplication(context, RustReplayTestEntryPoint::class.java)

    @Test
    fun onlyOnThisPhoneProjectSurvivesRecreateAndAccountShowsLocalMode() {
        assumeTrue("this build has no in-app server library", LocalServerNative.available)
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodesWithTag("onboarding-local").fetchSemanticsNodes().isNotEmpty()
        }
        grantNotificationPermission()
        composeRule
            .onNodeWithTag("onboarding-local")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Projects", timeoutMillis = 120_000)

        val name = "Local journey ${UUID.randomUUID().toString().take(8)}"
        composeRule.onNodeWithText("Projects").performClick()
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithTag("e2e-new-project").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("e2e-new-project").performClick()
        waitForText("New project")
        composeRule.onNodeWithTag("e2e-project-editor-name").performTextReplacement(name)
        clickTextWithAction("Save")
        waitForText(name, timeoutMillis = 60_000)

        // "Restart": the whole activity (and its view models) is rebuilt; data must come back
        // from the local database and the in-app server.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(120_000) { projectRowShown(name) }
        val onServer =
            runBlocking {
                graph.projectsApi().listProjects(page = 1).items.count { it.name == name }
            }
        assertEquals("the in-app server lost the project", 1, onServer)

        // Account: reached through More, says where the data lives, hides server-only cards.
        composeRule.onNodeWithText("More").performClick()
        waitForText("Settings")
        composeRule.onNodeWithTag("more-account").performScrollTo().performClick()
        composeRule
            .onNodeWithTag("account-settings-list")
            .performScrollToNode(hasText("Use a server instead"))
        waitForText("Only on this phone")
        // The Data card is the last one in local mode: scrolled to it, nothing server-only is left.
        composeRule
            .onNodeWithTag("account-settings-list")
            .performScrollToNode(hasText("Backups", substring = true))
        for (serverOnly in listOf("Server URL", "Sign out", "Profile", "Sign-in and security")) {
            assertTrue(
                "'$serverOnly' is shown in local mode",
                composeRule.onAllNodesWithText(serverOnly).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    private fun projectRowShown(name: String): Boolean =
        runCatching {
                composeRule.onNodeWithText("Projects").performClick()
                composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
            }
            .getOrDefault(false)
}
