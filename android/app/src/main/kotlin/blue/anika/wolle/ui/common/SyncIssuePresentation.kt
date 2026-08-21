package blue.anika.wolle.ui.common

import androidx.annotation.StringRes
import blue.anika.wolle.R
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.MutationOperation
import blue.anika.wolle.data.db.entity.PendingMutationEntity

/** The stable, localized copy used to explain a queued mutation that needs attention. */
data class SyncIssuePresentation(
    @StringRes val operationLabelResId: Int,
    @StringRes val headlineResId: Int,
    @StringRes val detailResId: Int,
)

/**
 * Maps persisted outbox state to UI copy rather than exposing exception messages directly. The
 * latter are implementation/server details and are not consistently localized.
 */
fun PendingMutationEntity.syncIssuePresentation(): SyncIssuePresentation {
    val operationLabelResId =
        when (entityType to operation) {
            MutationEntityType.PROJECT to MutationOperation.CREATE ->
                R.string.sync_issue_project_create
            MutationEntityType.PROJECT to MutationOperation.UPDATE ->
                R.string.sync_issue_project_update
            MutationEntityType.PROJECT to MutationOperation.DELETE ->
                R.string.sync_issue_project_delete
            MutationEntityType.YARN to MutationOperation.CREATE -> R.string.sync_issue_yarn_create
            MutationEntityType.YARN to MutationOperation.UPDATE -> R.string.sync_issue_yarn_update
            MutationEntityType.YARN to MutationOperation.DELETE -> R.string.sync_issue_yarn_delete
            else -> R.string.sync_issue_unknown_change
        }
    return SyncIssuePresentation(
        operationLabelResId = operationLabelResId,
        headlineResId =
            if (isConflict) R.string.sync_issue_conflict_headline
            else R.string.sync_issue_failure_headline,
        detailResId =
            if (isConflict) R.string.sync_issue_conflict_detail
            else R.string.sync_issue_failure_detail,
    )
}

fun List<PendingMutationEntity>.hasRetryableSyncIssues(): Boolean = any { !it.isConflict }
