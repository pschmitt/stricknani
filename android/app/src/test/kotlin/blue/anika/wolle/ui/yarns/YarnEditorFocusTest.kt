package blue.anika.wolle.ui.yarns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YarnEditorFocusTest {

    @Test
    fun `sections map to their fixed editor positions`() {
        assertEquals(0, yarnEditorItemIndex(YarnEditorFocus.DETAILS))
        assertEquals(3, yarnEditorItemIndex(YarnEditorFocus.DESCRIPTION))
        assertEquals(3, yarnEditorItemIndex(YarnEditorFocus.NOTES))
        assertNull(yarnEditorItemIndex("used_in"))
    }
}
