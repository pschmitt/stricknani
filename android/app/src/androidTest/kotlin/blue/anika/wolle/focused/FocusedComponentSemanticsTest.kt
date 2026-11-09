package blue.anika.wolle.focused

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.DESTRUCTIVE_DELETE_DIALOG_TAG
import blue.anika.wolle.ui.common.DESTRUCTIVE_DELETE_ICON_TAG
import blue.anika.wolle.ui.common.DestructiveDeleteDialog
import blue.anika.wolle.ui.common.DestructiveDeleteIcon
import blue.anika.wolle.ui.common.StickyNoteCard
import blue.anika.wolle.ui.home.SyncProblem
import blue.anika.wolle.ui.home.SyncProblemCard
import blue.anika.wolle.ui.theme.StricknaniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic Compose coverage for the states that are otherwise timing-sensitive in flows. */
@RunWith(AndroidJUnit4::class)
class FocusedComponentSemanticsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun syncProblemCardOffersRetryAndSaysWhatHappened() {
        var retried = false
        composeRule.setContent {
            StricknaniTheme {
                SyncProblemCard(problem = SyncProblem.Offline, onRetry = { retried = true })
            }
        }

        composeRule.onNodeWithText("You’re offline").assertIsDisplayed()
        composeRule.onNodeWithText("Here’s what’s on this phone.").assertIsDisplayed()
        composeRule.onNodeWithTag("home-sync-retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun syncProblemCardNamesAFailedRefresh() {
        composeRule.setContent {
            StricknaniTheme { SyncProblemCard(problem = SyncProblem.Failed, onRetry = {}) }
        }

        composeRule.onNodeWithText("Couldn’t check for news").assertIsDisplayed()
    }

    @Test
    fun emptyStateKeepsItsAccessibleTitleAndRecoveryHint() {
        composeRule.setContent {
            StricknaniTheme {
                blue.anika.wolle.ui.common.EmptyState(
                    icon = Icons.Filled.Folder,
                    title =
                        androidx.compose.ui.res.stringResource(R.string.projects_list_empty_title),
                    subtitle =
                        androidx.compose.ui.res.stringResource(
                            R.string.projects_list_empty_subtitle
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Nothing on the needles yet").assertIsDisplayed()
        composeRule
            .onNodeWithText("Start with one project to keep its pattern and progress together.")
            .assertIsDisplayed()
    }

    @Test
    fun destructiveDeleteDialogCancelDismissesWithoutConfirming() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            StricknaniTheme {
                DestructiveDeleteDialog(
                    title = "Delete project “Cable Cardigan”?",
                    text = "This can't be undone.",
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag(DESTRUCTIVE_DELETE_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Delete project “Cable Cardigan”?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertFalse(confirmed)
        assertTrue(dismissed)
    }

    @Test
    fun destructiveDeleteDialogConfirmsExplicitly() {
        var confirmed = false
        composeRule.setContent {
            StricknaniTheme {
                DestructiveDeleteDialog(
                    title = "Delete yarn “Merino”?",
                    text = "This can't be undone.",
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Delete").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun projectAndYarnTrashIconsExposeSharedDestructiveSemanticsAndErrorColor() {
        composeRule.setContent {
            StricknaniTheme {
                androidx.compose.foundation.layout.Row {
                    DestructiveDeleteIcon(contentDescription = "Delete project")
                    DestructiveDeleteIcon(contentDescription = "Delete yarn")
                }
            }
        }

        val icons = composeRule.onAllNodesWithTag(DESTRUCTIVE_DELETE_ICON_TAG)
        icons.assertCountEquals(2)
        icons[0].assertIsDisplayed()
    }

    @Test
    fun mutationFeedbackStringsAreAvailableInEnglishAndGerman() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val englishConfiguration =
            android.content.res.Configuration(context.resources.configuration)
        englishConfiguration.setLocale(java.util.Locale.ENGLISH)
        val english = context.createConfigurationContext(englishConfiguration)
        val germanConfiguration = android.content.res.Configuration(context.resources.configuration)
        germanConfiguration.setLocale(java.util.Locale.GERMAN)
        val german = context.createConfigurationContext(germanConfiguration)

        assertTrue(english.getString(R.string.mutation_project_created_queued).contains("Project"))
        assertTrue(german.getString(R.string.mutation_project_created_queued).contains("Projekt"))
        assertTrue(english.getString(R.string.mutation_favorite_offline).contains("offline"))
        assertTrue(german.getString(R.string.mutation_favorite_offline).contains("offline"))
    }

    @Test
    fun refreshFeedbackStringsAreAvailableInEnglishAndGerman() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val englishConfiguration =
            android.content.res.Configuration(context.resources.configuration)
        englishConfiguration.setLocale(java.util.Locale.ENGLISH)
        val english = context.createConfigurationContext(englishConfiguration)
        val germanConfiguration = android.content.res.Configuration(context.resources.configuration)
        germanConfiguration.setLocale(java.util.Locale.GERMAN)
        val german = context.createConfigurationContext(germanConfiguration)

        assertEquals("Already up to date", english.getString(R.string.refresh_no_changes))
        assertEquals("Bereits aktuell", german.getString(R.string.refresh_no_changes))
        assertEquals("Updated", english.getString(R.string.refresh_success))
        assertEquals("Aktualisiert", german.getString(R.string.refresh_success))
    }

    @Test
    fun notesCardKeepsItsTitleAndContentAccessibleInLightTheme() {
        composeRule.setContent {
            StricknaniTheme(darkTheme = false) {
                StickyNoteCard(title = "Notes") { Text("Add a sewn label after blocking.") }
            }
        }
        composeRule.onNodeWithTag("notes-card").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").assertIsDisplayed()
        composeRule.onNodeWithText("Add a sewn label after blocking.").assertIsDisplayed()
    }

    @Test
    fun notesCardKeepsItsContentAccessibleInDarkTheme() {
        composeRule.setContent {
            StricknaniTheme(darkTheme = true) {
                StickyNoteCard(title = "Notes") { Text("Add a sewn label after blocking.") }
            }
        }
        composeRule.onNodeWithTag("notes-card").assertIsDisplayed()
        composeRule.onNodeWithText("Add a sewn label after blocking.").assertIsDisplayed()
    }
}
