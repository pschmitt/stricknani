package blue.anika.wolle.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectEditorStepsTest {
    @Test
    fun movingAStepKeepsItsPositionRelativeToItsImages() {
        val first = ProjectEditorStep(title = "Cast on")
        val second = ProjectEditorStep(title = "Heel", images = emptyList())

        val moved = moveProjectEditorStep(listOf(first, second), 1, -1)

        assertEquals(listOf("Heel", "Cast on"), moved.map { it.title })
    }

    @Test
    fun movingBeyondTheListIsIgnored() {
        val steps = listOf(ProjectEditorStep(title = "Only"))

        assertEquals(steps, moveProjectEditorStep(steps, 0, -1))
        assertEquals(steps, moveProjectEditorStep(steps, 0, 1))
    }
}
