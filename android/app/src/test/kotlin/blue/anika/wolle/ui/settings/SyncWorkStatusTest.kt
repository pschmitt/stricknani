package blue.anika.wolle.ui.settings

import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkStatusTest {
    private fun info(state: WorkInfo.State, attempts: Int = 0) =
        WorkInfo(
            id = UUID.randomUUID(),
            state = state,
            tags = emptySet(),
            runAttemptCount = attempts,
        )

    @Test
    fun `empty chain is idle`() {
        assertEquals(SyncWorkStatus.IDLE, syncWorkStatus(emptyList()))
    }

    @Test
    fun `running takes precedence over waiting and retrying work`() {
        assertEquals(
            SyncWorkStatus.RUNNING,
            syncWorkStatus(
                listOf(
                    info(WorkInfo.State.ENQUEUED, attempts = 2),
                    info(WorkInfo.State.BLOCKED),
                    info(WorkInfo.State.RUNNING),
                )
            ),
        )
    }

    @Test
    fun `enqueued work with a prior attempt means retry even beside blocked work`() {
        assertEquals(
            SyncWorkStatus.RETRY_SCHEDULED,
            syncWorkStatus(
                listOf(
                    info(WorkInfo.State.ENQUEUED, attempts = 1),
                    info(WorkInfo.State.BLOCKED),
                )
            ),
        )
    }

    @Test
    fun `enqueued work without attempts and blocked work are waiting`() {
        assertEquals(
            SyncWorkStatus.WAITING,
            syncWorkStatus(listOf(info(WorkInfo.State.ENQUEUED), info(WorkInfo.State.BLOCKED))),
        )
    }

    @Test
    fun `failed work is failed`() {
        assertEquals(
            SyncWorkStatus.FAILED,
            syncWorkStatus(listOf(info(WorkInfo.State.FAILED))),
        )
    }

    @Test
    fun `all successful work is succeeded`() {
        assertEquals(
            SyncWorkStatus.SUCCEEDED,
            syncWorkStatus(listOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.SUCCEEDED))),
        )
    }

    @Test
    fun `cancelled historical work does not mask successful current work`() {
        assertEquals(
            SyncWorkStatus.SUCCEEDED,
            syncWorkStatus(
                listOf(
                    info(WorkInfo.State.CANCELLED),
                    info(WorkInfo.State.SUCCEEDED),
                    info(WorkInfo.State.SUCCEEDED),
                )
            ),
        )
    }

    @Test
    fun `all cancelled work is idle`() {
        assertEquals(
            SyncWorkStatus.IDLE,
            syncWorkStatus(listOf(info(WorkInfo.State.CANCELLED), info(WorkInfo.State.CANCELLED))),
        )
    }
}
