package blue.anika.wolle.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MoreLayoutTest {
    @Test
    fun `links follow the website order, Create first`() {
        assertEquals(
            listOf(
                MoreLink.Create,
                MoreLink.Category(SettingsCategory.Categories),
                MoreLink.Gauge,
                MoreLink.Category(SettingsCategory.About),
            ),
            moreLayout(isAdmin = false).links,
        )
    }

    @Test
    fun `administrators get Admin right before About`() {
        val links = moreLayout(isAdmin = true).links
        assertEquals(MoreLink.Category(SettingsCategory.Admin), links[links.size - 2])
        assertEquals(MoreLink.Category(SettingsCategory.About), links.last())
    }

    @Test
    fun `the Android-only settings sit in their own group without Account`() {
        val settings = moreLayout(isAdmin = true).settings
        assertEquals(
            listOf(
                SettingsCategory.Appearance,
                SettingsCategory.Navigation,
                SettingsCategory.DetailCards,
                SettingsCategory.Sync,
                SettingsCategory.Backup,
            ),
            settings,
        )
        assertFalse(SettingsCategory.Account in settings)
    }

    @Test
    fun `every category is reachable from More`() {
        val layout = moreLayout(isAdmin = true)
        val shown =
            layout.links.filterIsInstance<MoreLink.Category>().map { it.category } +
                layout.settings +
                SettingsCategory.Account
        assertEquals(SettingsCategory.entries.toSet(), shown.toSet())
    }
}
