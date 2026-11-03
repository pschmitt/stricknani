package blue.anika.wolle.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectEditorFocusTest {

    @Test
    fun `step targets round-trip through the route string`() {
        assertEquals(42, ProjectEditorFocus.stepId(ProjectEditorFocus.step(42)))
        assertNull(ProjectEditorFocus.stepId(ProjectEditorFocus.NOTES))
        assertNull(ProjectEditorFocus.stepId("step:not-a-number"))
    }

    @Test
    fun `sections map to their fixed editor positions`() {
        assertEquals(0, projectEditorItemIndex(ProjectEditorFocus.DETAILS, null))
        assertEquals(2, projectEditorItemIndex(ProjectEditorFocus.ATTACHMENTS, null))
        assertEquals(3, projectEditorItemIndex(ProjectEditorFocus.STITCH_SAMPLE, null))
        assertEquals(4, projectEditorItemIndex(ProjectEditorFocus.DESCRIPTION, null))
        assertEquals(4, projectEditorItemIndex(ProjectEditorFocus.NOTES, null))
        assertEquals(5, projectEditorItemIndex(ProjectEditorFocus.LINKED_YARNS, null))
        assertNull(projectEditorItemIndex("unknown", null))
    }

    @Test
    fun `steps land on their own item, unknown steps on the steps label`() {
        assertEquals(7, projectEditorItemIndex(ProjectEditorFocus.step(9), 0))
        assertEquals(9, projectEditorItemIndex(ProjectEditorFocus.step(9), 2))
        assertEquals(6, projectEditorItemIndex(ProjectEditorFocus.step(9), -1))
        assertEquals(6, projectEditorItemIndex(ProjectEditorFocus.step(9), null))
    }
}
