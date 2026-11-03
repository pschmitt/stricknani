package blue.anika.wolle.data.util

/** The area a gauge was measured over; patterns usually give it per 10 × 10 cm. */
data class SwatchSize(val widthCm: Double, val heightCm: Double) {
    val isDefault: Boolean
        get() = widthCm == DEFAULT.widthCm && heightCm == DEFAULT.heightCm

    companion object {
        val DEFAULT = SwatchSize(10.0, 10.0)
        const val MAX_CM = 100.0

        /** Parses "7,5" or "7.5"; null unless 0 < value <= [MAX_CM]. */
        fun parseCm(text: String): Double? =
            text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= MAX_CM }
    }
}

/** "10", "7.5" - centimetres without a trailing ".0". */
fun formatCm(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')

/** Mirrors `stricknani/utils/gauge.py`'s `GaugeResult`. */
data class GaugeResult(
    val adjustedStitches: Int,
    val adjustedRows: Int?,
    val patternCastOnStitches: Int,
    val patternRowCount: Int?,
)

/**
 * Pure port of `stricknani/utils/gauge.py`'s `calculate_gauge` - no network call needed, so the
 * gauge calculator screen works fully offline (see `android/TODO.md` SNA-11).
 *
 * Rounds via [Math.rint] (round-half-to-even), not [kotlin.math.roundToInt] (round-half-up):
 * Python's builtin `round()` - what the backend uses - is round-half-to-even, and gauge ratios land
 * exactly on `.5` often enough (e.g. 121 cast-on sts at a 19/22 ratio = 104.5) that the two would
 * silently disagree with the web app on that boundary case otherwise.
 */
object GaugeCalculator {
    fun calculate(
        patternGaugeStitches: Int,
        patternGaugeRows: Int,
        userGaugeStitches: Int,
        userGaugeRows: Int,
        patternCastOnStitches: Int,
        patternRowCount: Int? = null,
        patternSwatch: SwatchSize = SwatchSize.DEFAULT,
        userSwatch: SwatchSize = SwatchSize.DEFAULT,
    ): GaugeResult {
        // Compare densities (stitches per cm). Written as (user × patternWidth) / (pattern ×
        // userWidth) so the default 10 × 10 cm gives exactly the same doubles - and therefore the
        // same half-even rounding - as the plain user/pattern ratio the server uses.
        val stitchRatio =
            (userGaugeStitches * patternSwatch.widthCm) /
                (patternGaugeStitches * userSwatch.widthCm)
        val rowRatio =
            (userGaugeRows * patternSwatch.heightCm) / (patternGaugeRows * userSwatch.heightCm)
        val adjustedStitches = Math.rint(patternCastOnStitches * stitchRatio).toInt()
        val adjustedRows = patternRowCount?.let { rows -> Math.rint(rows * rowRatio).toInt() }
        return GaugeResult(
            adjustedStitches = adjustedStitches,
            adjustedRows = adjustedRows,
            patternCastOnStitches = patternCastOnStitches,
            patternRowCount = patternRowCount,
        )
    }
}
