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
}
