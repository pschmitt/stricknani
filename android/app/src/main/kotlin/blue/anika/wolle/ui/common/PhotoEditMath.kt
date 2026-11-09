package blue.anika.wolle.ui.common

import blue.anika.wolle.data.api.v2.PhotoCropDto
import blue.anika.wolle.data.api.v2.PhotoEditRequest
import kotlin.math.max
import kotlin.math.min

/** The crop rectangle as fractions (0..1) of the rotated, upright image. */
data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    /** True when the box covers the whole image (within [PhotoEditMath.WHOLE_EPSILON]). */
    val isWhole: Boolean
        get() =
            left <= PhotoEditMath.WHOLE_EPSILON &&
                top <= PhotoEditMath.WHOLE_EPSILON &&
                right >= 1f - PhotoEditMath.WHOLE_EPSILON &&
                bottom >= 1f - PhotoEditMath.WHOLE_EPSILON

    companion object {
        val Whole = CropBox(0f, 0f, 1f, 1f)
    }
}

enum class CropCorner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

/** What a drag that started at some point moves: one corner, or the whole box. */
sealed interface CropDrag {
    data class Corner(val corner: CropCorner) : CropDrag

    data object Move : CropDrag
}

/** Pure math of the photo editor: rotation, crop box gestures and the request body. */
object PhotoEditMath {
    /** The server refuses results under 16 px per side; keep a margin. */
    const val MIN_SIDE_PX = 20
    const val MIN_CROP_FRACTION = 0.08f
    const val WHOLE_EPSILON = 0.002f

    /** Longest description the editor accepts (the server allows 4096 bytes). */
    const val MAX_ALT_LENGTH = 1000

    /** The description differs from the stored one; surrounding blanks do not count. */
    fun altChanged(stored: String, current: String): Boolean = stored.trim() != current.trim()

    /** [degrees] folded into 0, 90, 180 or 270 (clockwise). */
    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** [steps] quarter turns clockwise (negative: counter-clockwise) from [current]. */
    fun rotateBy(current: Int, steps: Int): Int = normalizeRotation(current + steps * 90)

    /** Width and height of an image of [width] x [height] after [rotation] degrees. */
    fun rotatedSize(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (normalizeRotation(rotation) % 180 == 0) width to height else height to width

    /** Smallest allowed box side, as a fraction of an image side of [sidePx] pixels. */
    fun minFraction(sidePx: Int): Float {
        if (sidePx <= 0) return MIN_CROP_FRACTION
        return min(1f, max(MIN_CROP_FRACTION, MIN_SIDE_PX.toFloat() / sidePx))
    }

    /**
     * Moves one corner by ([dx], [dy]) (fractions); the opposite corner stays, the box stays inside
     * the image and at least [minW] x [minH].
     */
    fun moveCorner(
        box: CropBox,
        corner: CropCorner,
        dx: Float,
        dy: Float,
        minW: Float,
        minH: Float,
    ): CropBox {
        val moveLeft = corner == CropCorner.TopLeft || corner == CropCorner.BottomLeft
        val moveTop = corner == CropCorner.TopLeft || corner == CropCorner.TopRight
        var left = box.left
        var right = box.right
        var top = box.top
        var bottom = box.bottom
        if (moveLeft) left = (box.left + dx).coerceIn(0f, box.right - minW)
        else right = (box.right + dx).coerceIn(box.left + minW, 1f)
        if (moveTop) top = (box.top + dy).coerceIn(0f, box.bottom - minH)
        else bottom = (box.bottom + dy).coerceIn(box.top + minH, 1f)
        return CropBox(left, top, right, bottom)
    }

    /** Slides the box by ([dx], [dy]) without resizing it, keeping it inside the image. */
    fun moveBox(box: CropBox, dx: Float, dy: Float): CropBox {
        val x = dx.coerceIn(-box.left, 1f - box.right)
        val y = dy.coerceIn(-box.top, 1f - box.bottom)
        return CropBox(box.left + x, box.top + y, box.right + x, box.bottom + y)
    }

    /**
     * What a touch at ([x], [y]) pixels grabs in a [widthPx] x [heightPx] image: the nearest corner
     * within [cornerRadiusPx], else the box (when inside it), else nothing.
     */
    fun hitTest(
        box: CropBox,
        x: Float,
        y: Float,
        widthPx: Float,
        heightPx: Float,
        cornerRadiusPx: Float,
    ): CropDrag? {
        val corners =
            listOf(
                CropCorner.TopLeft to (box.left to box.top),
                CropCorner.TopRight to (box.right to box.top),
                CropCorner.BottomLeft to (box.left to box.bottom),
                CropCorner.BottomRight to (box.right to box.bottom),
            )
        val nearest =
            corners
                .map { (corner, point) ->
                    val dx = point.first * widthPx - x
                    val dy = point.second * heightPx - y
                    corner to (dx * dx + dy * dy)
                }
                .minByOrNull { it.second }
        if (nearest != null && nearest.second <= cornerRadiusPx * cornerRadiusPx) {
            return CropDrag.Corner(nearest.first)
        }
        val fx = x / widthPx
        val fy = y / heightPx
        return if (fx in box.left..box.right && fy in box.top..box.bottom) CropDrag.Move else null
    }

    /** The request for [rotation] and [box], or null when nothing changed (nothing to save). */
    fun request(rotation: Int, box: CropBox): PhotoEditRequest? {
        val rotate = normalizeRotation(rotation)
        if (rotate == 0 && box.isWhole) return null
        val crop =
            if (box.isWhole) null
            else
                PhotoCropDto(
                    x = box.left,
                    y = box.top,
                    width = box.width.coerceAtMost(1f - box.left),
                    height = box.height.coerceAtMost(1f - box.top),
                )
        return PhotoEditRequest(rotate, crop)
    }
}
