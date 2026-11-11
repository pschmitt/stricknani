package blue.anika.wolle.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedByRouteTest {

    @Test
    fun `the admin users menu opens the project list filtered by that owner`() {
        assertEquals(Route.ProjectsFiltered(owner = "42"), projectsOwnedByRoute(42))
    }

    @Test
    fun `the admin users menu opens the yarn list filtered by that owner`() {
        assertEquals(Route.YarnsByOwner(owner = "42"), yarnsOwnedByRoute(42))
    }
}
