package blue.anika.wolle.focused

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.R
import blue.anika.wolle.ui.home.HomeSyncStatusCard
import blue.anika.wolle.ui.theme.StricknaniTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic Compose coverage for the states that are otherwise timing-sensitive in flows. */
@RunWith(AndroidJUnit4::class)
class FocusedComponentSemanticsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun syncStatusExposesLoadingAndErrorFeedback() {
        composeRule.setContent {
            StricknaniTheme {
                HomeSyncStatusCard(
                    isRefreshing = true,
                    lastSyncedMillis = null,
                    pendingChangesCount = 0,
                    hasSyncFailures = false,
                )
            }
        }

        composeRule.onNodeWithText("Syncing…").assertIsDisplayed()
        composeRule
            .onNodeWithText("Pulling the latest projects and yarns.")
            .assertIsDisplayed()

        composeRule.setContent {
            StricknaniTheme {
                HomeSyncStatusCard(
                    isRefreshing = false,
                    lastSyncedMillis = null,
                    pendingChangesCount = 1,
                    hasSyncFailures = true,
                )
            }
        }

        composeRule.onNodeWithText("Sync issue").assertIsDisplayed()
        composeRule
            .onNodeWithText("1 change couldn’t reach the server yet.")
            .assertIsDisplayed()
    }

    @Test
    fun emptyStateKeepsItsAccessibleTitleAndRecoveryHint() {
        composeRule.setContent {
            StricknaniTheme {
                blue.anika.wolle.ui.common.EmptyState(
                    icon = Icons.Filled.Folder,
                    title =
                        androidx.compose.ui.res.stringResource(
                            R.string.projects_list_empty_title
                        ),
                    subtitle =
                        androidx.compose.ui.res.stringResource(
                            R.string.projects_list_empty_subtitle
                        ),
                )
            }
        }

        composeRule.onNodeWithText("No projects yet").assertIsDisplayed()
        composeRule
            .onNodeWithText("Pull to refresh, or adjust your filters.")
            .assertIsDisplayed()
    }
}
