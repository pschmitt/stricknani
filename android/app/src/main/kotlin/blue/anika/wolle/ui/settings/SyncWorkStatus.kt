package blue.anika.wolle.ui.settings

import androidx.work.WorkInfo

enum class SyncWorkStatus {
    IDLE,
    RUNNING,
    WAITING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    FAILED,
}

/** Summarizes the work items for the sync chain into one user-facing status. */
fun syncWorkStatus(infos: List<WorkInfo>): SyncWorkStatus {
    if (infos.any { it.state == WorkInfo.State.RUNNING }) return SyncWorkStatus.RUNNING
    if (
        infos.any {
            it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0
        }
    ) {
        return SyncWorkStatus.RETRY_SCHEDULED
    }
    if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
        return SyncWorkStatus.WAITING
    }
    if (infos.any { it.state == WorkInfo.State.FAILED }) return SyncWorkStatus.FAILED
    val nonCancelledInfos = infos.filter { it.state != WorkInfo.State.CANCELLED }
    if (
        nonCancelledInfos.isNotEmpty() &&
            nonCancelledInfos.all { it.state == WorkInfo.State.SUCCEEDED }
    ) {
        return SyncWorkStatus.SUCCEEDED
    }
    return SyncWorkStatus.IDLE
}
