package blue.anika.wolle.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MoreLayoutTest {
    @Test
    fun `links start with Create, then Categories and the gauge calculator`() {
        assertEquals(
            listOf(MoreLink.Create, MoreLink.Category(SettingsCategory.Categories), MoreLink.Gauge),
            moreLayout(isAdmin = true).links,
        )
    }

    @Test
    fun `the Android-only settings follow Account, which the screen draws first`() {
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
    fun `Admin and About close the screen, About last`() {
        assertEquals(
            listOf(SettingsCategory.Admin, SettingsCategory.About),
            moreLayout(isAdmin = true).footer,
        )
        assertEquals(listOf(SettingsCategory.About), moreLayout(isAdmin = false).footer)
    }

    @Test
    fun `every category is reachable from More`() {
        val layout = moreLayout(isAdmin = true)
        val shown =
            layout.links.filterIsInstance<MoreLink.Category>().map { it.category } +
                layout.settings +
                layout.footer +
                SettingsCategory.Account
        assertEquals(SettingsCategory.entries.toSet(), shown.toSet())
    }
}
