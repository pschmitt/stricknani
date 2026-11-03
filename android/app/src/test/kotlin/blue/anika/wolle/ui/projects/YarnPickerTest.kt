package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.db.entity.YarnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YarnPickerTest {

    private fun yarn(id: Int, name: String, brand: String? = null, colorway: String? = null) =
        YarnEntity(
            id = id,
            name = name,
            brand = brand,
            colorway = colorway,
            weightCategory = null,
            isFavorite = false,
            updatedAt = 0L,
            previewUrl = null,
            detailJson = "{}",
        )

    private val stash =
        listOf(
            yarn(1, "Merino Soft", brand = "Drops", colorway = "Plum"),
            yarn(2, "alpaca cloud", brand = "Lana Grossa"),
            yarn(3, "Cotton Breeze", colorway = "Sea green"),
        )

    @Test
    fun `chosen yarns come first, then alphabetical`() {
        assertEquals(
            listOf(3, 2, 1),
            filterYarnsForPicker(stash, "", selected = setOf(3)).map { it.id },
        )
    }

    @Test
    fun `search matches name, brand and colorway, ignoring case`() {
        assertEquals(listOf(1), filterYarnsForPicker(stash, "drops", emptySet()).map { it.id })
        assertEquals(listOf(3), filterYarnsForPicker(stash, "SEA", emptySet()).map { it.id })
        assertEquals(listOf(2), filterYarnsForPicker(stash, " alpaca ", emptySet()).map { it.id })
    }

    @Test
    fun `subtitle joins brand and colorway and skips blanks`() {
        assertEquals("Drops, Plum", yarnSubtitle(stash[0]))
        assertEquals("Lana Grossa", yarnSubtitle(stash[1]))
        assertNull(yarnSubtitle(yarn(9, "Plain", brand = " ")))
    }
}
