package blue.anika.wolle.sync

import android.os.Build
import blue.anika.wolle.ui.common.shouldRequestNotificationPermission
import blue.anika.wolle.ui.navigation.Route
import org.junit.Assert.assertEquals
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

    @Test
    fun `the notification names what changed and opens that list`() {
        assertEquals(SyncChanges.None, SyncChanges.of(projects = false, yarns = false))
        assertEquals(SyncChanges.Projects, SyncChanges.of(projects = true, yarns = false))
        assertEquals(SyncChanges.Yarns, SyncChanges.of(projects = false, yarns = true))
        assertEquals(SyncChanges.ProjectsAndYarns, SyncChanges.of(projects = true, yarns = true))
        assertEquals(Route.Projects, syncedRoute(SyncChanges.Projects.name))
        assertEquals(Route.Yarns, syncedRoute(SyncChanges.Yarns.name))
        assertEquals(Route.Home, syncedRoute(SyncChanges.ProjectsAndYarns.name))
        assertEquals(Route.Home, syncedRoute(null))
    }
}
