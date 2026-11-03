package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class NotesFabPlacementTest {
    private val w = 400f
    private val fab = 56f

    @Test
    fun `insets are 9_6 on phones and 20 from 600dp`() {
        assertEquals(9.6f, NotesFabMath.insetDp(411f))
        assertEquals(9.6f, NotesFabMath.insetDp(599f))
        assertEquals(20f, NotesFabMath.insetDp(600f))
    }

    @Test
    fun `rest position is mirrored on the left`() {
        assertEquals(400f - 10f - 56f, NotesFabMath.restX(NotesFabSide.Right, w, fab, 10f))
        assertEquals(10f, NotesFabMath.restX(NotesFabSide.Left, w, fab, 10f))
    }

    @Test
    fun `drop snaps to the nearest edge`() {
        assertEquals(NotesFabSide.Left, NotesFabMath.nearestSide(100f, w, fab))
        assertEquals(NotesFabSide.Right, NotesFabMath.nearestSide(250f, w, fab))
        // Centre exactly on the middle goes right.
        assertEquals(NotesFabSide.Right, NotesFabMath.nearestSide(w / 2f - fab / 2f, w, fab))
    }

    @Test
    fun `vertical band keeps the margins and never inverts`() {
        assertEquals(16f, NotesFabMath.bandTop(16f))
        assertEquals(800f - 16f - 56f, NotesFabMath.bandBottom(800f, fab, 16f))
        assertEquals(16f, NotesFabMath.bandBottom(60f, fab, 16f))
    }

    @Test
    fun `fraction maps to y and back with clamping`() {
        val top = 16f
        val bottom = 728f
        assertEquals(bottom, NotesFabMath.yFromFraction(1f, top, bottom))
        assertEquals(top, NotesFabMath.yFromFraction(0f, top, bottom))
        assertEquals(372f, NotesFabMath.yFromFraction(0.5f, top, bottom))
        assertEquals(top, NotesFabMath.yFromFraction(-3f, top, bottom))
        assertEquals(0f, NotesFabMath.fractionFromY(-50f, top, bottom))
        assertEquals(1f, NotesFabMath.fractionFromY(9999f, top, bottom))
        assertEquals(0.5f, NotesFabMath.fractionFromY(372f, top, bottom))
        assertEquals(1f, NotesFabMath.fractionFromY(10f, 50f, 50f))
    }

    @Test
    fun `drag tilt follows velocity within 12 degrees on top of the resting tilt`() {
        assertEquals(4f, NotesFabMath.dragTilt(0f, NotesFabSide.Right))
        assertEquals(-4f, NotesFabMath.dragTilt(0f, NotesFabSide.Left))
        assertEquals(4f + 5f, NotesFabMath.dragTilt(250f, NotesFabSide.Right), 0.001f)
        assertEquals(4f + 12f, NotesFabMath.dragTilt(5000f, NotesFabSide.Right), 0.001f)
        assertEquals(4f - 12f, NotesFabMath.dragTilt(-5000f, NotesFabSide.Right), 0.001f)
    }

    @Test
    fun `drop yields side and fraction`() {
        val p = NotesFabMath.drop(40f, 372f, w, fab, 16f, 728f)
        assertEquals(NotesFabSide.Left, p.side)
        assertEquals(0.5f, p.yFraction)
    }

    @Test
    fun `accessibility steps stay inside the band and flip the side`() {
        val bottom = NotesFabPlacement(NotesFabSide.Right, 1f)
        assertEquals(1f, NotesFabMath.step(bottom, +1).yFraction)
        assertEquals(0.75f, NotesFabMath.step(bottom, -1).yFraction)
        assertEquals(
            0f,
            NotesFabMath.step(NotesFabPlacement(NotesFabSide.Left, 0.1f), -1).yFraction,
        )
        assertEquals(NotesFabSide.Left, NotesFabMath.flipSide(bottom).side)
        assertEquals(1f, NotesFabMath.flipSide(bottom).yFraction)
    }

    @Test
    fun `storage mapping round trips and tolerates garbage`() {
        val p = NotesFabPlacement(NotesFabSide.Left, 0.25f)
        assertEquals(p, NotesFabPlacement.fromStorage(p.side.storageName, p.yFraction))
        assertEquals(NotesFabPlacement.Default, NotesFabPlacement.fromStorage(null, null))
        assertEquals(NotesFabPlacement.Default, NotesFabPlacement.fromStorage("up", Float.NaN))
        assertEquals(1f, NotesFabPlacement.fromStorage("right", 7f).yFraction)
        assertEquals(0f, NotesFabPlacement.fromStorage("left", -1f).yFraction)
    }
}
