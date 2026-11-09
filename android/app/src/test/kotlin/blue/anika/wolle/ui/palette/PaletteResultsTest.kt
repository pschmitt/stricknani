package blue.anika.wolle.ui.palette

import blue.anika.wolle.R
import blue.anika.wolle.ui.navigation.Route
import blue.anika.wolle.ui.settings.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteResultsTest {
    private val names =
        mapOf(
            R.string.destination_label_home to "Home",
            R.string.destination_label_projects to "Projects",
            R.string.destination_label_yarns to "Yarns",
            R.string.destination_label_categories to "Categories",
            R.string.gauge_calculator_title to "Gauge calculator",
        )

    private fun statics(url: String? = null, isAdmin: Boolean = false) =
        PaletteEntries.static(isAdmin, url) { names[it] ?: "string $it" }

    private fun project(id: Int, name: String, category: String? = null) =
        PaletteEntry(
            id = "project:$id",
            group = PaletteGroup.Projects,
            title = name,
            sub = category,
            icon = PaletteIcon.Projects,
            target = PaletteTarget.Go(Route.ProjectDetail(id)),
        )

    private fun ids(
        query: String,
        entries: List<PaletteEntry>,
        expanded: Set<PaletteGroup> = emptySet(),
        url: String? = null,
    ) =
        PaletteResults.buildGroups(query, entries, expanded, url).map { group ->
            group.group to group.rows.map { it.entry.id }
        }

    @Test
    fun `an empty query shows actions, recent items and the main pages`() {
        val entries = statics() + project(1, "Socks")
        val groups = ids("", entries).map { it.first }
        assertEquals(
            listOf(PaletteGroup.Actions, PaletteGroup.Projects, PaletteGroup.Pages),
            groups,
        )
    }

    @Test
    fun `admin is a page only for administrators`() {
        fun settingsIds(admin: Boolean) =
            statics(isAdmin = admin).filter { it.id == "page:settings-Admin" }
        assertTrue(settingsIds(false).isEmpty())
        assertEquals(1, settingsIds(true).size)
    }

    @Test
    fun `settings come from the real settings categories`() {
        val ids = statics().filter { it.group == PaletteGroup.Settings }.map { it.id }
        assertTrue("setting:${SettingsCategory.Language.name}" in ids)
        assertTrue("setting:${SettingsCategory.Appearance.name}" in ids)
        assertFalse("setting:${SettingsCategory.About.name}" in ids)
    }

    @Test
    fun `groups come in the fixed order`() {
        val entries =
            statics() +
                project(1, "Pages of yarn") +
                PaletteEntry(
                    "category:1",
                    PaletteGroup.Categories,
                    "Yarn bombs",
                    icon = PaletteIcon.Categories,
                    target = PaletteTarget.Go(Route.Categories),
                )
        val groups = ids("yarn", entries).map { it.first }
        assertEquals(groups.sortedBy { it.ordinal }, groups)
        assertTrue(PaletteGroup.Projects in groups && PaletteGroup.Categories in groups)
    }

    @Test
    fun `umlaut free typing finds the project`() {
        val entries = listOf(project(7, "Müller Pullover", "Sweaters"))
        assertEquals(listOf(PaletteGroup.Projects to listOf("project:7")), ids("muller", entries))
    }

    @Test
    fun `exact and prefix matches come before fuzzy ones`() {
        val pages = ids("yarn", statics()).first { it.first == PaletteGroup.Pages }.second
        assertEquals(
            "page:${blue.anika.wolle.ui.navigation.TopLevelDestination.YARNS.name}",
            pages.first(),
        )
    }

    @Test
    fun `a group is limited and lists everything once expanded`() {
        val entries = (1..12).map { project(it, "Sock $it") }
        val limited = PaletteResults.buildGroups("sock", entries, emptySet(), null).single()
        assertEquals(PaletteMatcher.GROUP_LIMIT, limited.rows.size)
        assertEquals(12, limited.total)
        val all =
            PaletteResults.buildGroups("sock", entries, setOf(PaletteGroup.Projects), null).single()
        assertEquals(12, all.rows.size)
    }

    @Test
    fun `equal scores keep their order`() {
        val entries = listOf(project(1, "Sock A"), project(2, "Sock B"), project(3, "Sock C"))
        assertEquals(
            listOf("project:1", "project:2", "project:3"),
            ids("sock", entries).single().second,
        )
    }

    @Test
    fun `a pasted link offers both imports first, linked to the import route`() {
        val url = "https://example.com/patterns/socks"
        val entries = statics(url) + project(1, "Socks")
        val groups = PaletteResults.buildGroups(url, entries, emptySet(), url)
        assertEquals(1, groups.size)
        val rows = groups.single().rows.map { it.entry }
        assertEquals(listOf("action:import-project", "action:import-yarn"), rows.map { it.id })
        assertEquals(
            Route.ProjectEditor(importUrl = url),
            (rows[0].target as PaletteTarget.Go).route,
        )
        assertEquals(
            Route.YarnEditor(importUrl = url),
            (rows[1].target as PaletteTarget.Go).route,
        )
    }
}
