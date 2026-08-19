package blue.anika.wolle.ui.navigation

import blue.anika.wolle.data.settings.NavbarItemPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class NavbarCustomizationTest {

    @Test
    fun `null input falls back to every destination visible in declared order`() {
        val sanitized = NavbarCustomization.sanitize(null)

        assertEquals(
            TopLevelDestination.entries.toList(),
            NavbarCustomization.toDestinations(sanitized),
        )
        assertEquals(sanitized.size, sanitized.count { it.visible })
    }

    @Test
    fun `unknown ids are dropped`() {
        val sanitized =
            NavbarCustomization.sanitize(
                listOf(NavbarItemPreference("NOT_A_REAL_DESTINATION", visible = true))
            )

        assertEquals(
            TopLevelDestination.entries.toList(),
            NavbarCustomization.toDestinations(sanitized),
        )
    }

    @Test
    fun `settings is forced visible even if stored hidden`() {
        val sanitized =
            NavbarCustomization.sanitize(
                TopLevelDestination.entries.map { NavbarItemPreference(it.name, visible = false) }
            )

        val settingsEntry = sanitized.first { it.id == TopLevelDestination.SETTINGS.name }
        assertEquals(true, settingsEntry.visible)
    }

    @Test
    fun `missing destinations are appended as visible`() {
        val sanitized =
            NavbarCustomization.sanitize(
                listOf(NavbarItemPreference(TopLevelDestination.HOME.name))
            )

        assertEquals(TopLevelDestination.entries.size, sanitized.size)
        assertEquals(true, sanitized.all { it.visible })
    }

    @Test
    fun `visibleDestinations filters out hidden entries`() {
        val sanitized =
            NavbarCustomization.sanitize(
                listOf(
                    NavbarItemPreference(TopLevelDestination.HOME.name, visible = true),
                    NavbarItemPreference(TopLevelDestination.PROJECTS.name, visible = false),
                )
            )

        val visible = NavbarCustomization.visibleDestinations(sanitized)
        assert(TopLevelDestination.PROJECTS !in visible)
        assert(TopLevelDestination.HOME in visible)
    }

    @Test
    fun `nested routes keep their top-level destination selected`() {
        assert(TopLevelDestination.PROJECTS.routeTypes.contains(Route.ProjectDetail::class))
        assert(TopLevelDestination.PROJECTS.routeTypes.contains(Route.ProjectEditor::class))
        assert(TopLevelDestination.YARNS.routeTypes.contains(Route.YarnDetail::class))
        assert(TopLevelDestination.YARNS.routeTypes.contains(Route.YarnEditor::class))
        assert(TopLevelDestination.SETTINGS.routeTypes.contains(Route.SettingsCategoryRoute::class))
        assert(TopLevelDestination.SETTINGS.routeTypes.contains(Route.Libraries::class))
    }

    @Test
    fun `top-level tap is a no-op when already at its root`() {
        assertEquals(false, shouldNavigateToTopLevelRoot(isAlreadyAtRoot = true))
        assertEquals(true, shouldNavigateToTopLevelRoot(isAlreadyAtRoot = false))
    }

    @Test
    fun `fresh root is only opened when target root was not in the back stack`() {
        assertEquals(false, shouldOpenFreshTopLevelRoot(didPopToRoot = true))
        assertEquals(true, shouldOpenFreshTopLevelRoot(didPopToRoot = false))
    }
}
