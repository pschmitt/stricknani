package blue.anika.wolle.ui.common

import blue.anika.wolle.data.api.v2.PhotoAltRequest
import blue.anika.wolle.data.api.v2.PhotoCropDto
import blue.anika.wolle.data.api.v2.PhotoEditRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditMathTest {
    private val minW = 0.1f
    private val minH = 0.1f

    private fun assertBox(expected: CropBox, actual: CropBox) {
        val eps = 1e-5f
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    // (x, y, w, h) = (0.1, 0.2, 0.3, 0.4)
    private val sample = CropBox(0.1f, 0.2f, 0.4f, 0.6f)

    @Test
    fun rotateCropClockwise() {
        // (0.4, 0.1, 0.4, 0.3)
        assertBox(CropBox(0.4f, 0.1f, 0.8f, 0.4f), PhotoEditMath.rotateCrop(sample, 1))
    }

    @Test
    fun rotateCropCounterClockwise() {
        // (0.2, 0.6, 0.4, 0.3)
        assertBox(CropBox(0.2f, 0.6f, 0.6f, 0.9f), PhotoEditMath.rotateCrop(sample, -1))
    }

    @Test
    fun rotateCropHalfTurn() {
        // (0.6, 0.4, 0.3, 0.4)
        assertBox(CropBox(0.6f, 0.4f, 0.9f, 0.8f), PhotoEditMath.rotateCrop(sample, 2))
    }

    @Test
    fun rotateCropFourTurnsReturnsTheOriginal() {
        assertBox(sample, PhotoEditMath.rotateCrop(sample, 4))
        var turned = sample
        repeat(4) { turned = PhotoEditMath.rotateCrop(turned, 1) }
        assertBox(sample, turned)
        assertBox(sample, PhotoEditMath.rotateCrop(PhotoEditMath.rotateCrop(sample, 1), -1))
    }

    @Test
    fun rotateCropKeepsAWholeBoxWholeAndClamps() {
        assertBox(CropBox.Whole, PhotoEditMath.rotateCrop(CropBox.Whole, 1))
        val out = PhotoEditMath.rotateCrop(CropBox(0.8f, 0.8f, 1.2f, 1.2f), 1)
        assertTrue(out.left >= 0f && out.top >= 0f && out.right <= 1f && out.bottom <= 1f)
    }

    @Test
    fun rotationFoldsIntoQuarterTurns() {
        assertEquals(270, PhotoEditMath.rotateBy(0, -1))
        assertEquals(90, PhotoEditMath.rotateBy(0, 1))
        assertEquals(0, PhotoEditMath.rotateBy(270, 1))
        assertEquals(180, PhotoEditMath.rotateBy(90, 1))
        assertEquals(0, PhotoEditMath.rotateBy(PhotoEditMath.rotateBy(0, 1), -1))
        assertEquals(90, PhotoEditMath.normalizeRotation(-270))
        assertEquals(0, PhotoEditMath.normalizeRotation(720))
    }

    @Test
    fun rotatedSizeSwapsSidesOnQuarterTurns() {
        assertEquals(400 to 300, PhotoEditMath.rotatedSize(400, 300, 0))
        assertEquals(300 to 400, PhotoEditMath.rotatedSize(400, 300, 90))
        assertEquals(400 to 300, PhotoEditMath.rotatedSize(400, 300, 180))
        assertEquals(300 to 400, PhotoEditMath.rotatedSize(400, 300, 270))
    }

    @Test
    fun descriptionChangeIgnoresSurroundingBlanks() {
        assertFalse(PhotoEditMath.altChanged("Cover", " Cover "))
        assertTrue(PhotoEditMath.altChanged("Cover", "Back"))
        assertTrue(PhotoEditMath.altChanged("Cover", "  "))
        assertFalse(PhotoEditMath.altChanged("", "   "))
    }

    @Test
    fun descriptionRequestUsesTheServerFieldName() {
        val body = Json.encodeToString(PhotoAltRequest("Sleeve detail"))
        assertEquals("""{"alt_text":"Sleeve detail"}""", body)
    }

    @Test
    fun minFractionKeepsSixteenPixels() {
        assertEquals(0.08f, PhotoEditMath.minFraction(1000), 1e-6f)
        assertEquals(0.1f, PhotoEditMath.minFraction(200), 1e-6f)
        assertEquals(1f, PhotoEditMath.minFraction(10), 1e-6f)
        assertEquals(PhotoEditMath.MIN_CROP_FRACTION, PhotoEditMath.minFraction(0), 1e-6f)
        assertTrue(PhotoEditMath.minFraction(150) * 150 >= 16f)
    }

    @Test
    fun cornerDragResizesAndKeepsOppositeCorner() {
        val box = CropBox(0.2f, 0.2f, 0.8f, 0.8f)
        val moved = PhotoEditMath.moveCorner(box, CropCorner.TopLeft, 0.1f, 0.05f, minW, minH)
        assertEquals(CropBox(0.3f, 0.25f, 0.8f, 0.8f), moved)
        val other = PhotoEditMath.moveCorner(box, CropCorner.BottomRight, -0.1f, -0.1f, minW, minH)
        assertEquals(CropBox(0.2f, 0.2f, 0.7f, 0.7f), other)
    }

    @Test
    fun cornerDragIsClampedToImageAndMinimumSize() {
        val box = CropBox(0.2f, 0.2f, 0.8f, 0.8f)
        val outside = PhotoEditMath.moveCorner(box, CropCorner.TopLeft, -1f, -1f, minW, minH)
        assertEquals(CropBox(0f, 0f, 0.8f, 0.8f), outside)
        val tiny = PhotoEditMath.moveCorner(box, CropCorner.TopLeft, 1f, 1f, minW, minH)
        assertEquals(0.7f, tiny.left, 1e-6f)
        assertEquals(0.7f, tiny.top, 1e-6f)
        assertEquals(minW, tiny.width, 1e-6f)
        val far = PhotoEditMath.moveCorner(box, CropCorner.BottomRight, 1f, 1f, minW, minH)
        assertEquals(1f, far.right, 1e-6f)
        assertEquals(1f, far.bottom, 1e-6f)
        val squeezed = PhotoEditMath.moveCorner(box, CropCorner.BottomRight, -1f, -1f, minW, minH)
        assertEquals(0.3f, squeezed.right, 1e-6f)
        assertEquals(0.3f, squeezed.bottom, 1e-6f)
    }

    @Test
    fun boxMoveKeepsSizeAndStaysInside() {
        val box = CropBox(0.2f, 0.2f, 0.6f, 0.5f)
        val moved = PhotoEditMath.moveBox(box, 0.1f, 0.1f)
        assertEquals(0.4f, moved.width, 1e-6f)
        assertEquals(0.3f, moved.height, 1e-6f)
        assertEquals(0.3f, moved.left, 1e-6f)
        val clamped = PhotoEditMath.moveBox(box, 5f, -5f)
        assertEquals(CropBox(0.6f, 0f, 1f, 0.3f).right, clamped.right, 1e-6f)
        assertEquals(0f, clamped.top, 1e-6f)
        assertEquals(0.4f, clamped.width, 1e-6f)
        assertEquals(0.3f, clamped.height, 1e-6f)
    }

    @Test
    fun hitTestPrefersCornersThenBoxThenNothing() {
        val box = CropBox(0.25f, 0.25f, 0.75f, 0.75f)
        // 400 x 200 px image: top-left corner at (100, 50).
        assertEquals(
            CropDrag.Corner(CropCorner.TopLeft),
            PhotoEditMath.hitTest(box, 110f, 60f, 400f, 200f, 30f),
        )
        assertEquals(
            CropDrag.Corner(CropCorner.BottomRight),
            PhotoEditMath.hitTest(box, 295f, 155f, 400f, 200f, 30f),
        )
        assertEquals(CropDrag.Move, PhotoEditMath.hitTest(box, 200f, 100f, 400f, 200f, 30f))
        assertNull(PhotoEditMath.hitTest(box, 5f, 5f, 400f, 200f, 30f))
    }

    @Test
    fun hitTestOnCornerOfWholeImageGrabsTheCorner() {
        assertEquals(
            CropDrag.Corner(CropCorner.TopRight),
            PhotoEditMath.hitTest(CropBox.Whole, 395f, 4f, 400f, 200f, 30f),
        )
    }

    @Test
    fun noChangeMeansNoRequest() {
        assertNull(PhotoEditMath.request(0, CropBox.Whole))
        assertNull(PhotoEditMath.request(360, CropBox(0.001f, 0f, 1f, 0.999f)))
    }

    @Test
    fun rotationOnlyOmitsTheCrop() {
        assertEquals(PhotoEditRequest(90, null), PhotoEditMath.request(90, CropBox.Whole))
        assertEquals(PhotoEditRequest(270, null), PhotoEditMath.request(-90, CropBox.Whole))
    }

    @Test
    fun cropIsSentAsFractionsOfTheRotatedImage() {
        val request = PhotoEditMath.request(180, CropBox(0.1f, 0.2f, 0.6f, 0.9f))!!
        assertEquals(180, request.rotate)
        val crop = request.crop!!
        assertEquals(0.1f, crop.x, 1e-6f)
        assertEquals(0.2f, crop.y, 1e-6f)
        assertEquals(0.5f, crop.width, 1e-6f)
        assertEquals(0.7f, crop.height, 1e-6f)
        val cropOnly = PhotoEditMath.request(0, CropBox(0f, 0f, 0.5f, 1f))!!
        assertEquals(0, cropOnly.rotate)
        assertEquals(PhotoCropDto(0f, 0f, 0.5f, 1f), cropOnly.crop)
    }

    @Test
    fun requestBodyJsonOmitsNullCrop() {
        val json = Json { explicitNulls = false }
        assertEquals("""{"rotate":90}""", json.encodeToString(PhotoEditRequest(90)))
        assertTrue(
            json
                .encodeToString(PhotoEditRequest(0, PhotoCropDto(0.1f, 0.2f, 0.5f, 0.5f)))
                .contains(""""crop":{"x":0.1,"y":0.2,"width":0.5,"height":0.5}""")
        )
        assertFalse(CropBox(0.1f, 0f, 1f, 1f).isWhole)
        assertTrue(CropBox.Whole.isWhole)
    }
}
