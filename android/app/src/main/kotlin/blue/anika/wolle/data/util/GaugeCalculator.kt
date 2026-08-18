package blue.anika.wolle.data.util

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
 * Python's builtin `round()` - what the backend uses - is round-half-to-even, and gauge ratios
 * land exactly on `.5` often enough (e.g. 121 cast-on sts at a 19/22 ratio = 104.5) that the two
 * would silently disagree with the web app on that boundary case otherwise.
 */
object GaugeCalculator {
    fun calculate(
        patternGaugeStitches: Int,
        patternGaugeRows: Int,
        userGaugeStitches: Int,
        userGaugeRows: Int,
        patternCastOnStitches: Int,
        patternRowCount: Int? = null,
    ): GaugeResult {
        val adjustedStitches =
            Math.rint(patternCastOnStitches * (userGaugeStitches.toDouble() / patternGaugeStitches))
                .toInt()
        val adjustedRows =
            patternRowCount?.let { rows ->
                Math.rint(rows * (userGaugeRows.toDouble() / patternGaugeRows)).toInt()
            }
        return GaugeResult(
            adjustedStitches = adjustedStitches,
            adjustedRows = adjustedRows,
            patternCastOnStitches = patternCastOnStitches,
            patternRowCount = patternRowCount,
        )
    }
}
