package blue.anika.wolle.ui.home

import androidx.compose.foundation.lazy.LazyListState

/**
 * The maths behind Home's flying mascot: the hero mascot shrinks and travels into the logo slot of
 * the compact top bar as the list scrolls, and back again. Everything here is pure, in pixels, so
 * it can be unit tested; the composables only feed it scroll state from inside draw/layout lambdas.
 */

/** Where the mascot is drawn: its top-left corner and the scale applied about that corner. */
data class MorphPlacement(val x: Float, val y: Float, val scale: Float)

/**
 * How far the hero has travelled towards the top bar, 0 to 1. [scrolledPx] is how far the greeting
 * has scrolled up; [rangePx] is the scroll distance that completes the flight. With [instant]
 * (animations off) there is no flight: the logo just takes over once the hero has scrolled off the
 * top, i.e. past [instantThresholdPx].
 */
fun morphProgress(
    scrolledPx: Float,
    rangePx: Float,
    instant: Boolean,
    instantThresholdPx: Float,
): Float =
    if (instant) {
        if (scrolledPx >= instantThresholdPx) 1f else 0f
    } else if (rangePx <= 0f) {
        if (scrolledPx > 0f) 1f else 0f
    } else {
        (scrolledPx / rangePx).coerceIn(0f, 1f)
    }

fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/**
 * The mascot's placement at [progress]. At 0 it rides along with the list ([restX], [restY] is
 * where the hero sits, already scrolled); at 1 it sits in the logo slot ([slotX], [slotY]) scaled
 * to [slotScale]; in between it blends the two, so it keeps moving with the finger the whole way.
 */
fun morphPlacement(
    progress: Float,
    restX: Float,
    restY: Float,
    slotX: Float,
    slotY: Float,
    slotScale: Float,
): MorphPlacement =
    MorphPlacement(
        x = lerp(restX, slotX, progress),
        y = lerp(restY, slotY, progress),
        scale = lerp(1f, slotScale, progress),
    )

/** The width of the logo slot at [progress]: it opens from 0 and pushes the title right. */
fun morphSlotWidth(progress: Float, fullWidthPx: Float): Float = fullWidthPx * progress

/**
 * The top bar's container opacity: transparent while the hero is still around, filled once the
 * mascot has nearly arrived (like a collapsing top bar).
 */
fun morphBarOpacity(progress: Float): Float =
    ((progress - BAR_FILL_START) / (1f - BAR_FILL_START)).coerceIn(0f, 1f)

private const val BAR_FILL_START = 0.6f

/**
 * Reads Home's scroll state to place the flying mascot. Its functions are meant to be called from
 * `graphicsLayer {}` / `layout {}` lambdas, so a scroll only invalidates the layer, never the
 * composition.
 */
internal class HeroFlight(
    private val listState: LazyListState,
    private val greetingIndex: Int,
    /** Where the hero sits inside the greeting item. */
    private val restLeftPx: Float,
    private val restTopPx: Float,
    private val heroPx: Float,
    /** The logo slot in the top bar. */
    private val slotLeftPx: Float,
    private val slotTopPx: Float,
    private val slotPx: Float,
    private val instant: Boolean,
) {
    private fun greetingTop(): Float {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == greetingIndex }
        return when {
            info != null -> info.offset.toFloat()
            listState.firstVisibleItemIndex > greetingIndex -> -FAR
            else -> FAR
        }
    }

    fun progress(): Float =
        morphProgress(
            scrolledPx = -greetingTop(),
            rangePx = heroPx,
            instant = instant,
            instantThresholdPx = restTopPx + heroPx,
        )

    fun placement(): MorphPlacement =
        morphPlacement(
            progress = progress(),
            restX = restLeftPx,
            restY = greetingTop() + restTopPx,
            slotX = slotLeftPx,
            slotY = slotTopPx,
            slotScale = slotPx / heroPx,
        )

    private companion object {
        const val FAR = 100_000f
    }
}
