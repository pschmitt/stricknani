package blue.anika.wolle.data.util

import java.math.BigInteger

/** The area a gauge was measured over; patterns usually give it per 10 × 10 cm. */
data class SwatchSize(val widthCm: Double, val heightCm: Double) {
    val isDefault: Boolean
        get() = widthCm == DEFAULT.widthCm && heightCm == DEFAULT.heightCm

    companion object {
        val DEFAULT = SwatchSize(10.0, 10.0)
        const val MAX_CM = 100.0

        /**
         * Parses "7,5" or "7.5"; null unless 0 < value <= [MAX_CM]. Rounded to the server's three
         * decimals so what is shown is exactly what is calculated and sent.
         */
        fun parseCm(text: String): Double? =
            text
                .trim()
                .replace(',', '.')
                .toDoubleOrNull()
                ?.let { Math.round(it * 1000) / 1000.0 }
                ?.takeIf { it > 0 && it <= MAX_CM }
    }
}

/** "10", "7.5" - centimetres without a trailing ".0". */
fun formatCm(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')

/** The result of a gauge calculation. */
data class GaugeResult(
    val adjustedStitches: Int,
    val adjustedRows: Int?,
    val patternCastOnStitches: Int,
    val patternRowCount: Int?,
)

/**
 * Pure port of the Rust server's gauge calculation (`POST /api/v2/tools/gauge`) - no network call
 * needed, so the gauge calculator screen works fully offline (see `android/TODO.md` SNA-11).
 *
 * Rounds half UP, exactly like the server: gauge ratios land on `.5` often enough (e.g. 121 cast-on
 * sts at a 19/22 ratio = 104.5, which becomes 105) that the rounding mode is visible. The legacy
 * Python backend rounded half-to-even; the Rust server (the only one that is kept) does not. To
 * keep ties deterministic the swatch sizes are fixed-point thousandths of a centimetre and the
 * whole formula is a single exact integer division, never floating point.
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
        // Compare densities (stitches per cm): count × (user / userSize) / (pattern / patternSize).
        val adjustedStitches =
            adjustedCount(
                patternCastOnStitches,
                userGaugeStitches,
                patternSwatch.widthCm,
                patternGaugeStitches,
                userSwatch.widthCm,
            )
        val adjustedRows = patternRowCount?.let { rows ->
            adjustedCount(
                rows,
                userGaugeRows,
                patternSwatch.heightCm,
                patternGaugeRows,
                userSwatch.heightCm,
            )
        }
        return GaugeResult(
            adjustedStitches = adjustedStitches,
            adjustedRows = adjustedRows,
            patternCastOnStitches = patternCastOnStitches,
            patternRowCount = patternRowCount,
        )
    }

    private fun thousandths(cm: Double): BigInteger =
        BigInteger.valueOf(Math.round(cm * 1000).coerceAtLeast(1))

    /** `count * userGauge * patternSize / (patternGauge * userSize)`, rounded half up. */
    private fun adjustedCount(
        count: Int,
        userGauge: Int,
        patternSizeCm: Double,
        patternGauge: Int,
        userSizeCm: Double,
    ): Int {
        val numerator =
            BigInteger.valueOf(count.toLong())
                .multiply(BigInteger.valueOf(userGauge.toLong()))
                .multiply(thousandths(patternSizeCm))
        val denominator =
            BigInteger.valueOf(patternGauge.toLong()).multiply(thousandths(userSizeCm))
        val (quotient, remainder) = numerator.divideAndRemainder(denominator)
        val rounded =
            if (remainder.shiftLeft(1) >= denominator) quotient.add(BigInteger.ONE) else quotient
        return if (rounded.bitLength() > 31) Int.MAX_VALUE else rounded.toInt()
    }
}
