package blue.anika.wolle.ui.common

import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncIssuePresentationTest {
    @Test
    fun `transient project update is labeled as retryable sync failure`() {
        val issue = mutation(MutationEntityType.PROJECT, MutationOperation.UPDATE)

        val presentation = issue.syncIssuePresentation()

        assertEquals(R.string.sync_issue_project_update, presentation.operationLabelResId)
        assertEquals(R.string.sync_issue_failure_headline, presentation.headlineResId)
        assertEquals(R.string.sync_issue_failure_detail, presentation.detailResId)
        assertTrue(listOf(issue).hasRetryableSyncIssues())
    }

    @Test
    fun `conflict is labeled resolved and is not retryable`() {
        val issue =
            mutation(MutationEntityType.YARN, MutationOperation.DELETE).copy(isConflict = true)

        val presentation = issue.syncIssuePresentation()

        assertEquals(R.string.sync_issue_yarn_delete, presentation.operationLabelResId)
        assertEquals(R.string.sync_issue_conflict_headline, presentation.headlineResId)
        assertEquals(R.string.sync_issue_conflict_detail, presentation.detailResId)
        assertFalse(listOf(issue).hasRetryableSyncIssues())
    }

    @Test
    fun `a mixed issue list remains retryable while preserving conflicts`() {
        val issues =
            listOf(
                mutation(MutationEntityType.PROJECT, MutationOperation.UPDATE).copy(isConflict = true),
                mutation(MutationEntityType.YARN, MutationOperation.CREATE),
            )

        assertTrue(issues.hasRetryableSyncIssues())
        assertEquals(
            R.string.sync_issue_conflict_headline,
            issues.first().syncIssuePresentation().headlineResId,
        )
        assertEquals(
            R.string.sync_issue_failure_headline,
            issues.last().syncIssuePresentation().headlineResId,
        )
    }

    private fun mutation(entityType: String, operation: String) =
        PendingMutationEntity(
            entityType = entityType,
            operation = operation,
            localId = 7,
            payloadJson = null,
            createdAt = 0,
            lastErrorMessage = "server said no",
        )
}
