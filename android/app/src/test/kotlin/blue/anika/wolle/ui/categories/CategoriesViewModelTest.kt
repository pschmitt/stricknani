package blue.anika.wolle.ui.categories

import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.ui.common.RefreshState
import blue.anika.wolle.ui.common.RefreshTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesViewModelTest {
    private val category = CategoryEntity(id = 1, name = "Sweaters")

    @Test
    fun `empty cache shows loading during the first refresh`() {
        assertEquals(
            CategoriesContentState.Loading,
            categoriesContentState(
                emptyList(),
                initialSyncComplete = false,
                RefreshState.Refreshing(RefreshTrigger.Automatic),
            ),
        )
    }

    @Test
    fun `empty cache exposes offline and error states after failed first refresh`() {
        assertEquals(
            CategoriesContentState.Offline,
            categoriesContentState(emptyList(), false, RefreshState.Offline),
        )
        assertEquals(
            CategoriesContentState.Error,
            categoriesContentState(emptyList(), false, RefreshState.Error),
        )
    }

    @Test
    fun `successful empty sync shows the empty state`() {
        assertEquals(
            CategoriesContentState.Empty,
            categoriesContentState(
                emptyList(),
                initialSyncComplete = true,
                RefreshState.Finished(false),
            ),
        )
    }

    @Test
    fun `cached categories remain visible while refreshing or after failure`() {
        val expected = CategoriesContentState.Data(listOf(category))

        assertEquals(
            expected,
            categoriesContentState(
                listOf(category),
                false,
                RefreshState.Refreshing(RefreshTrigger.Automatic),
            ),
        )
        assertEquals(expected, categoriesContentState(listOf(category), true, RefreshState.Offline))
        assertEquals(expected, categoriesContentState(listOf(category), true, RefreshState.Error))
    }
}
