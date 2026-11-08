package blue.anika.wolle.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTransferTest {
    @Test
    fun `the path follows the api level`() {
        assertEquals(TransferPath.WorkManager, transferPathFor(26))
        assertEquals(TransferPath.WorkManager, transferPathFor(33))
        assertEquals(TransferPath.UserInitiatedJob, transferPathFor(34))
        assertEquals(TransferPath.UserInitiatedJob, transferPathFor(36))
    }

    @Test
    fun `old devices never touch the job path`() {
        var jobCalls = 0
        var workCalls = 0
        val path =
            startTransfer(
                33,
                viaJob = {
                    jobCalls++
                    true
                },
                viaWork = { workCalls++ },
            )
        assertEquals(TransferPath.WorkManager, path)
        assertEquals(0, jobCalls)
        assertEquals(1, workCalls)
    }

    @Test
    fun `new devices use a job and no worker`() {
        var workCalls = 0
        val path = startTransfer(34, viaJob = { true }, viaWork = { workCalls++ })
        assertEquals(TransferPath.UserInitiatedJob, path)
        assertEquals(0, workCalls)
    }

    @Test
    fun `a refused job falls back to the worker`() {
        var workCalls = 0
        val path = startTransfer(35, viaJob = { false }, viaWork = { workCalls++ })
        assertEquals(TransferPath.WorkManager, path)
        assertEquals(1, workCalls)
    }

    @Test
    fun `an idle store defers to WorkManager, a busy one wins`() {
        val failed = ExportJobStatus.Failed(ExportFailure.Failed)
        assertEquals(failed, mergeExportStatus(ExportJobStatus.Idle, failed))
        assertEquals(
            ExportJobStatus.Running(5),
            mergeExportStatus(ExportJobStatus.Running(5), ExportJobStatus.Succeeded),
        )
        assertEquals(
            RestoreJobStatus.Running(1, 2),
            mergeRestoreStatus(RestoreJobStatus.Running(1, 2), RestoreJobStatus.Idle),
        )
        assertEquals(
            RestoreJobStatus.Idle,
            mergeRestoreStatus(RestoreJobStatus.Idle, RestoreJobStatus.Idle),
        )
    }

    @Test
    fun `the store keeps both states apart`() {
        val store = DataTransferStatusStore()
        store.setExport(ExportJobStatus.Running(10))
        assertEquals(ExportJobStatus.Running(10), store.export.value)
        assertEquals(RestoreJobStatus.Idle, store.restore.value)
        store.setRestore(RestoreJobStatus.Failed(RestoreFailure.TooLarge))
        assertEquals(RestoreJobStatus.Failed(RestoreFailure.TooLarge), store.restore.value)
        assertEquals(ExportJobStatus.Running(10), store.export.value)
    }

    @Test
    fun `only the user or the app stopping a job is silent`() {
        assertTrue(stopWasDeliberate(1)) // STOP_REASON_CANCELLED_BY_APP
        assertTrue(stopWasDeliberate(13)) // STOP_REASON_USER
        assertFalse(stopWasDeliberate(7)) // STOP_REASON_CONSTRAINT_CONNECTIVITY
        assertFalse(stopWasDeliberate(10)) // STOP_REASON_QUOTA
        assertFalse(stopWasDeliberate(0)) // STOP_REASON_UNDEFINED
    }

    @Test
    fun `the estimate grows ahead of the transfer`() {
        assertEquals(100L, grownEstimate(50, 100))
        assertEquals(100L, grownEstimate(100, 100))
        assertEquals(101L + ESTIMATE_STEP_BYTES, grownEstimate(101, 100))
        assertEquals(10L + ESTIMATE_STEP_BYTES, grownEstimate(10, 0))
    }

    @Test
    fun `results use their own normal channel, progress stays on the silent one`() {
        val progress = DataExportNotifier.channelFor(results = false)
        val results = DataExportNotifier.channelFor(results = true)
        assertEquals("data_export", progress)
        assertEquals("data_transfer_results", results)
        assertTrue(progress != results)
    }

    @Test
    fun `every notification id has its own group, so Android does not bundle them`() {
        val download = DataExportNotifier.groupFor(DataExportNotifier.NOTIFICATION_ID)
        val restore = DataExportNotifier.groupFor(DataRestoreNotifier.NOTIFICATION_ID)
        assertTrue(download != restore)
        assertEquals(download, DataExportNotifier.groupFor(DataExportNotifier.NOTIFICATION_ID))
    }
}
