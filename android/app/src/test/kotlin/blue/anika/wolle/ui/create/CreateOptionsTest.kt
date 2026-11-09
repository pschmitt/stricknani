package blue.anika.wolle.ui.create

import org.junit.Assert.assertEquals
import org.junit.Test

class CreateOptionsTest {
    @Test
    fun `two tiles in the website order, no separate imports`() {
        assertEquals(
            listOf(CreateKind.NewProject, CreateKind.NewYarn),
            createOptions().map { it.kind },
        )
    }
}
