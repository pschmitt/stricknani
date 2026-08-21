package blue.anika.wolle.sync

import android.os.Build
import blue.anika.wolle.ui.common.shouldRequestNotificationPermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncNotificationPolicyTest {
    @Test
    fun `only periodic changed syncs request a notification`() {
        assertTrue(shouldNotifySyncCompletion(notifyOnChange = true, hasChanges = true))
        assertFalse(shouldNotifySyncCompletion(notifyOnChange = true, hasChanges = false))
        assertFalse(shouldNotifySyncCompletion(notifyOnChange = false, hasChanges = true))
        assertFalse(shouldNotifySyncCompletion(notifyOnChange = false, hasChanges = false))
    }

    @Test
    fun `notification permission is requested only when eligible`() {
        assertTrue(
            shouldRequestNotificationPermission(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = false,
                requestAlreadyMade = false,
            )
        )
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = true,
                requestAlreadyMade = false,
            )
        )
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = false,
                requestAlreadyMade = true,
            )
        )
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = Build.VERSION_CODES.S,
                permissionGranted = false,
                requestAlreadyMade = false,
            )
        )
    }

    @Test
    fun `notification permission policy also respects app-level notification switch`() {
        assertTrue(
            canPostNotifications(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = true,
                notificationsEnabled = true,
            )
        )
        assertFalse(
            canPostNotifications(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = false,
                notificationsEnabled = true,
            )
        )
        assertFalse(
            canPostNotifications(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                permissionGranted = true,
                notificationsEnabled = false,
            )
        )
        assertTrue(
            canPostNotifications(
                sdkInt = Build.VERSION_CODES.S,
                permissionGranted = false,
                notificationsEnabled = true,
            )
        )
    }
}
