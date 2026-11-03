package blue.anika.wolle.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.settings.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The screen edge the notes note is stuck to. */
internal enum class NotesFabSide(val storageName: String) {
    Left("left"),
    Right("right");

    val other: NotesFabSide
        get() = if (this == Left) Right else Left

    /** The resting tilt of the little note: +4 on the right, mirrored to -4 on the left. */
    val restTilt: Float
        get() = if (this == Left) -NotesFabMath.REST_TILT else NotesFabMath.REST_TILT

    /** +1 on the right, -1 on the left: mirrors the sway keyframes. */
    val sign: Float
        get() = if (this == Left) -1f else 1f
}

/**
 * Where the little note rests: the [side] it is snapped to and [yFraction] (0..1) of the allowed
 * vertical band, 1 being the bottom, which is where it sat before it could be moved.
 */
internal data class NotesFabPlacement(
    val side: NotesFabSide = NotesFabSide.Right,
    val yFraction: Float = 1f,
) {
    companion object {
        val Default = NotesFabPlacement()

        /** Maps the stored values back; anything unknown or out of range falls back safely. */
        fun fromStorage(side: String?, yFraction: Float?): NotesFabPlacement =
            NotesFabPlacement(
                NotesFabSide.entries.firstOrNull { it.storageName == side } ?: Default.side,
                yFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: Default.yFraction,
            )
    }
}

/** Snap and clamp rules of the movable notes note, in plain functions so they can be tested. */
internal object NotesFabMath {
    const val REST_TILT = 4f
    const val MAX_VELOCITY_TILT = 12f
    const val TILT_PER_DP_S = 0.02f
    const val INSET_PHONE_DP = 9.6f
    const val INSET_WIDE_DP = 20f
    const val WIDE_BREAKPOINT_DP = 600f
    const val EDGE_MARGIN_DP = 16f
    const val MOVE_STEP_FRACTION = 0.25f

    fun insetDp(widthDp: Float): Float =
        if (widthDp >= WIDE_BREAKPOINT_DP) INSET_WIDE_DP else INSET_PHONE_DP

    /** The left edge of the note when it rests on [side]. */
    fun restX(side: NotesFabSide, width: Float, fab: Float, inset: Float): Float =
        if (side == NotesFabSide.Right) width - inset - fab else inset

    /** The nearer edge to a note whose left edge is at [x]. */
    fun nearestSide(x: Float, width: Float, fab: Float): NotesFabSide =
        if (x + fab / 2f < width / 2f) NotesFabSide.Left else NotesFabSide.Right

    /** The top of the note may range over this band (the bottom limit is the one used so far). */
    fun bandTop(margin: Float): Float = margin

    fun bandBottom(height: Float, fab: Float, margin: Float): Float =
        maxOf(bandTop(margin), height - margin - fab)

    fun yFromFraction(fraction: Float, top: Float, bottom: Float): Float =
        top + (bottom - top) * fraction.coerceIn(0f, 1f)

    fun fractionFromY(y: Float, top: Float, bottom: Float): Float =
        if (bottom <= top) 1f else ((y - top) / (bottom - top)).coerceIn(0f, 1f)

    /** Tilt while dragging: the velocity tilt on top of the resting tilt of the [side]. */
    fun dragTilt(vxDpPerSecond: Float, side: NotesFabSide): Float =
        (vxDpPerSecond * TILT_PER_DP_S).coerceIn(-MAX_VELOCITY_TILT, MAX_VELOCITY_TILT) +
            side.restTilt

    /** The placement after dropping the note with its top left corner at ([x], [y]). */
    fun drop(x: Float, y: Float, width: Float, fab: Float, top: Float, bottom: Float) =
        NotesFabPlacement(nearestSide(x, width, fab), fractionFromY(y, top, bottom))

    /** TalkBack: one step up (-1) or down (+1) inside the band. */
    fun step(placement: NotesFabPlacement, direction: Int) =
        placement.copy(
            yFraction = (placement.yFraction + direction * MOVE_STEP_FRACTION).coerceIn(0f, 1f)
        )

    /** TalkBack: the same height on the opposite edge. */
    fun flipSide(placement: NotesFabPlacement) = placement.copy(side = placement.side.other)
}

/** Keeps the placement of the notes note per device, shared by the project and yarn screens. */
@HiltViewModel
internal class NotesFabViewModel
@Inject
constructor(private val preferences: AppPreferencesRepository) : ViewModel() {
    /** Null until the stored value is read. */
    val placement: StateFlow<NotesFabPlacement?> =
        preferences.notesFabPlacement
            .map { (side, y) -> NotesFabPlacement.fromStorage(side, y) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(placement: NotesFabPlacement) {
        viewModelScope.launch {
            preferences.setNotesFabPlacement(placement.side.storageName, placement.yFraction)
        }
    }
}
