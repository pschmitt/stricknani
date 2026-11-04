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
    fun `Settings holds the Android-only settings after Account, which the screen draws`() {
        val android =
            listOf(
                SettingsCategory.Appearance,
                SettingsCategory.Navigation,
                SettingsCategory.DetailCards,
            )
        assertEquals(android, moreLayout(isAdmin = true).settings)
        assertEquals(android, moreLayout(isAdmin = false).settings)
        assertFalse(SettingsCategory.Account in moreLayout(isAdmin = true).settings)
    }

    @Test
    fun `Data holds keeping up to date, backup and Admin for administrators`() {
        assertEquals(
            listOf(SettingsCategory.Sync, SettingsCategory.Backup, SettingsCategory.Admin),
            moreLayout(isAdmin = true).data,
        )
        assertEquals(
            listOf(SettingsCategory.Sync, SettingsCategory.Backup),
            moreLayout(isAdmin = false).data,
        )
    }

    @Test
    fun `About stands alone at the bottom`() {
        assertEquals(listOf(SettingsCategory.About), moreLayout(isAdmin = true).footer)
        assertEquals(listOf(SettingsCategory.About), moreLayout(isAdmin = false).footer)
    }

    @Test
    fun `every category is reachable from More`() {
        val layout = moreLayout(isAdmin = true)
        val shown =
            layout.links.filterIsInstance<MoreLink.Category>().map { it.category } +
                layout.settings +
                layout.data +
                layout.footer +
                SettingsCategory.Account
        assertEquals(SettingsCategory.entries.toSet(), shown.toSet())
    }
}
