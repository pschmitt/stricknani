package blue.anika.wolle.ui.create

import blue.anika.wolle.R
import blue.anika.wolle.data.api.ImportAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateOptionsTest {
    private fun options(url: Boolean, ai: Boolean) =
        createOptions(ImportAvailability(url = url, ai = ai)).associateBy { it.kind }

    @Test
    fun `four tiles in the website order`() {
        assertEquals(
            listOf(
                CreateKind.NewProject,
                CreateKind.NewYarn,
                CreateKind.ImportProject,
                CreateKind.ImportYarn,
            ),
            createOptions(ImportAvailability()).map { it.kind },
        )
    }

    @Test
    fun `everything on means all tiles enabled and no notes`() {
        val all = createOptions(ImportAvailability(url = true, ai = true))
        assertTrue(all.all { it.enabled && it.noteRes == null })
    }

    @Test
    fun `link imports off disables only the yarn import`() {
        val o = options(url = false, ai = true)
        assertTrue(o.getValue(CreateKind.ImportProject).enabled)
        assertEquals(
            R.string.import_unavailable_url,
            o.getValue(CreateKind.ImportYarn).disabledReasonRes,
        )
        assertTrue(o.getValue(CreateKind.NewProject).enabled)
        assertTrue(o.getValue(CreateKind.NewYarn).enabled)
    }

    @Test
    fun `AI off adds a note to both imports but keeps them usable`() {
        val o = options(url = true, ai = false)
        assertEquals(R.string.import_unavailable_ai, o.getValue(CreateKind.ImportProject).noteRes)
        assertEquals(R.string.import_unavailable_ai, o.getValue(CreateKind.ImportYarn).noteRes)
        assertTrue(o.getValue(CreateKind.ImportYarn).enabled)
        assertNull(o.getValue(CreateKind.NewProject).noteRes)
    }
}
