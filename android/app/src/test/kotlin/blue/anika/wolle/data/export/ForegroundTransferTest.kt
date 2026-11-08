package blue.anika.wolle.data.export

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTransferTest {
    @Test
    fun `api 29 to 33 use the data sync type`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundTypeFor(29))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundTypeFor(33))
    }

    @Test
    fun `api 26 to 28 have no service type`() {
        assertEquals(0, foregroundTypeFor(26))
        assertEquals(0, foregroundTypeFor(28))
    }

    @Test
    fun `api 34 and newer never use a foreground worker`() {
        assertNull(foregroundTypeFor(34))
        assertNull(foregroundTypeFor(36))
    }

    @Test
    fun `the foreground ids differ from the result ids`() {
        val results = setOf(DataExportNotifier.NOTIFICATION_ID, DataRestoreNotifier.NOTIFICATION_ID)
        assertEquals(false, EXPORT_FOREGROUND_ID in results)
        assertEquals(false, RESTORE_FOREGROUND_ID in results)
        assertEquals(false, EXPORT_FOREGROUND_ID == RESTORE_FOREGROUND_ID)
    }
}
