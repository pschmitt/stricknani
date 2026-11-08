package blue.anika.wolle.sync

import android.os.Build
import blue.anika.wolle.ui.common.shouldRequestNotificationPermission
import blue.anika.wolle.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun project(id: Int, name: String = "Blanket $id") =
        SyncedItem(SyncedKind.Project, id, name)

    private fun yarn(id: Int, name: String = "Yarn $id") = SyncedItem(SyncedKind.Yarn, id, name)

    @Test
    fun `one changed item is titled with its name and opens it`() {
        val summary = SyncSummary(projects = listOf(project(7, "Heirloom Baby Blanket")))

        val headline = syncHeadline(summary)

        assertEquals(
            SyncHeadline.Single(SyncedKind.Project, project(7, "Heirloom Baby Blanket")),
            headline,
        )
        assertEquals(Route.ProjectDetail(7), syncedRoute(summary.changes.name, projectId = 7))
        assertEquals(Route.YarnDetail(3), syncedRoute(SyncChanges.Yarns.name, yarnId = 3))
    }

    @Test
    fun `several changed items get a counted headline and open the list`() {
        val summary =
            SyncSummary(
                projects = listOf(project(1), project(2), project(3)),
                yarns = listOf(yarn(4)),
            )

        assertEquals(SyncHeadline.Multiple(projects = 3, yarns = 1), syncHeadline(summary))
        assertEquals(
            listOf("Blanket 1", "Blanket 2", "Blanket 3", "Yarn 4"),
            summary.items().map { it.name },
        )
        assertEquals(SyncChanges.ProjectsAndYarns, summary.changes)
        assertEquals(Route.Home, syncedRoute(summary.changes.name))
    }

    @Test
    fun `a single change with removals is not titled as one item`() {
        val summary = SyncSummary(projects = listOf(project(1)), removedYarns = 2)

        assertEquals(SyncHeadline.Multiple(projects = 1, yarns = 0), syncHeadline(summary))
        assertEquals(2, summary.removedTotal)
    }

    @Test
    fun `only removals say what went away, nothing at all says nothing`() {
        val removed = SyncSummary(removedProjects = 2, removedYarns = 1)

        assertEquals(SyncHeadline.Removed(projects = 2, yarns = 1), syncHeadline(removed))
        assertEquals(SyncChanges.ProjectsAndYarns, removed.changes)
        assertNull(syncHeadline(SyncSummary()))
        assertEquals(SyncChanges.None, SyncSummary().changes)
    }

    @Test
    fun `an invalid item id falls back to the list`() {
        assertEquals(Route.Projects, syncedRoute(SyncChanges.Projects.name, projectId = 0))
        assertEquals(
            Route.Yarns,
            syncedRoute(SyncChanges.Yarns.name, projectId = null, yarnId = -4),
        )
    }
}
