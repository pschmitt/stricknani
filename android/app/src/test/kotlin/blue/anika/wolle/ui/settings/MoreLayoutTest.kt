package blue.anika.wolle.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MoreLayoutTest {
    @Test
    fun `links start with Create, then Categories, the gauge calculator and the Trash`() {
        assertEquals(
            listOf(
                MoreLink.Create,
                MoreLink.Category(SettingsCategory.Categories),
                MoreLink.Gauge,
                MoreLink.Trash,
            ),
            moreLayout(isAdmin = true).links,
        )
    }

    @Test
    fun `Settings holds Language and User interface after Account, which the screen draws`() {
        val android = listOf(SettingsCategory.Language, SettingsCategory.Interface)
        assertEquals(android, moreLayout(isAdmin = true).settings)
        assertEquals(android, moreLayout(isAdmin = false).settings)
        assertFalse(SettingsCategory.Account in moreLayout(isAdmin = true).settings)
    }

    @Test
    fun `Data holds Admin for administrators, then keeping up to date and backup`() {
        assertEquals(
            listOf(SettingsCategory.Admin, SettingsCategory.Sync, SettingsCategory.Backup),
            moreLayout(isAdmin = true).data,
        )
        assertEquals(
            listOf(SettingsCategory.Sync, SettingsCategory.Backup),
            moreLayout(isAdmin = false).data,
        )
    }

    @Test
    fun `User interface opens Appearance, the bottom bar and detail card pages`() {
        assertEquals(
            listOf(
                SettingsCategory.Appearance,
                SettingsCategory.Navigation,
                SettingsCategory.DetailCards,
            ),
            moreLayout(isAdmin = false).interfacePages,
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
                layout.interfacePages +
                SettingsCategory.Account
        assertEquals(SettingsCategory.entries.toSet(), shown.toSet())
    }
}
