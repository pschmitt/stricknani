package blue.anika.wolle.focused

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import blue.anika.wolle.ui.common.SyncIssueBanner
import blue.anika.wolle.ui.theme.StricknaniTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncIssueBannerTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun banner_exposes_issue_count_details_and_retry_action() {
        var openedDetails = false
        var retried = false
        val issues =
            listOf(
                mutation(MutationEntityType.PROJECT, MutationOperation.UPDATE, isConflict = true),
                mutation(MutationEntityType.YARN, MutationOperation.CREATE),
            )

        composeRule.setContent {
            StricknaniTheme {
                SyncIssueBanner(
                    issues = issues,
                    onRetry = { retried = true },
                    onOpenDetails = { openedDetails = true },
                )
            }
        }

        composeRule.onNodeWithText("Sync needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("2 queued changes need attention.").assertIsDisplayed()
        composeRule.onNodeWithText("Update project: Conflict resolved").assertIsDisplayed()
        composeRule.onNodeWithText("View details").performClick()
        composeRule.onNodeWithText("Retry").performClick()

        assertTrue(openedDetails)
        assertTrue(retried)
    }

    @Test
    fun conflict_only_banner_does_not_offer_retry() {
        composeRule.setContent {
            StricknaniTheme {
                SyncIssueBanner(
                    issues =
                        listOf(
                            mutation(
                                MutationEntityType.PROJECT,
                                MutationOperation.DELETE,
                                isConflict = true,
                            )
                        ),
                    onRetry = {},
                    onOpenDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("Sync needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("View details").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    private fun mutation(entityType: String, operation: String, isConflict: Boolean = false) =
        PendingMutationEntity(
            entityType = entityType,
            operation = operation,
            localId = 7,
            payloadJson = null,
            createdAt = 0,
            lastErrorMessage = "server said no",
            isConflict = isConflict,
        )
}
