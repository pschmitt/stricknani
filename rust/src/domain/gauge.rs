use std::num::NonZeroU32;

#[derive(Debug)]
pub(crate) struct GaugeResult {
    pub(crate) adjusted_stitches: u128,
    pub(crate) adjusted_rows: Option<u128>,
}

/// Swatch sizes are fixed-point so density comparisons stay exact integer maths.
pub(crate) const SWATCH_SCALE: u32 = 1_000;
/// Default measured size: 10 cm.
pub(crate) const DEFAULT_SWATCH_UNITS: u32 = 10 * SWATCH_SCALE;
/// Largest accepted size: 100 cm.
pub(crate) const MAX_SWATCH_UNITS: u32 = 100 * SWATCH_SCALE;

/// A measured swatch edge length in thousandths of a centimetre (1..=100_000).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct SwatchLength(NonZeroU32);

impl SwatchLength {
    pub(crate) fn from_units(units: u32) -> Option<Self> {
        (units <= MAX_SWATCH_UNITS)
            .then(|| NonZeroU32::new(units).map(Self))
            .flatten()
    }

    /// Parse a plain decimal such as `10`, `4.5` or `12.125` (at most three
    /// fractional digits; no sign or exponent).
    pub(crate) fn parse_decimal(text: &str) -> Option<Self> {
        let (whole, fraction) = text.split_once('.').unwrap_or((text, ""));
        let digits_only = |part: &str| part.bytes().all(|byte| byte.is_ascii_digit());
        if whole.is_empty()
            || (text.contains('.') && fraction.is_empty())
            || whole.len() > 6
            || fraction.len() > 3
            || !digits_only(whole)
            || !digits_only(fraction)
        {
            return None;
        }
        let whole: u32 = whole.parse().ok()?;
        let padded = format!("{fraction:0<3}");
        let fraction: u32 = padded.parse().ok()?;
        Self::from_units(whole.checked_mul(SWATCH_SCALE)?.checked_add(fraction)?)
    }

    fn units(self) -> u64 {
        u64::from(self.0.get())
    }
}

impl Default for SwatchLength {
    fn default() -> Self {
        Self(NonZeroU32::new(DEFAULT_SWATCH_UNITS).expect("default swatch size is non-zero"))
    }
}

/// Measured swatch sizes of the pattern and of the knitter's own swatch.
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct SwatchSizes {
    pub(crate) pattern_width: SwatchLength,
    pub(crate) pattern_height: SwatchLength,
    pub(crate) user_width: SwatchLength,
    pub(crate) user_height: SwatchLength,
}

/// Validated inputs shared by the browser API and the server-rendered form.
#[derive(Debug, Clone, Copy)]
pub(crate) struct GaugeInput {
    pub(crate) pattern_gauge_stitches: NonZeroU32,
    pub(crate) pattern_gauge_rows: NonZeroU32,
    pub(crate) user_gauge_stitches: NonZeroU32,
    pub(crate) user_gauge_rows: NonZeroU32,
    pub(crate) pattern_cast_on_stitches: NonZeroU32,
    pub(crate) pattern_row_count: Option<NonZeroU32>,
}

impl GaugeInput {
    /// Compare stitch and row densities per centimetre, so a swatch measured at
    /// any size gives the same answer as the equivalent 10 cm gauge.
    pub(crate) fn calculate_with_swatches(self, sizes: SwatchSizes) -> GaugeResult {
        GaugeResult {
            adjusted_stitches: adjusted_density_count(
                self.pattern_cast_on_stitches.get(),
                self.user_gauge_stitches.get(),
                sizes.pattern_width,
                self.pattern_gauge_stitches.get(),
                sizes.user_width,
            ),
            adjusted_rows: self.pattern_row_count.map(|row_count| {
                adjusted_density_count(
                    row_count.get(),
                    self.user_gauge_rows.get(),
                    sizes.pattern_height,
                    self.pattern_gauge_rows.get(),
                    sizes.user_height,
                )
            }),
        }
    }
}

#[cfg(test)]
pub(crate) fn adjusted_count(pattern_count: u32, user_gauge: u32, pattern_gauge: u32) -> u128 {
    let default = SwatchLength::default();
    adjusted_density_count(pattern_count, user_gauge, default, pattern_gauge, default)
}

/// `count * (user_gauge / user_size) / (pattern_gauge / pattern_size)`, rounded
/// half up, computed as one exact integer division.
fn adjusted_density_count(
    pattern_count: u32,
    user_gauge: u32,
    pattern_size: SwatchLength,
    pattern_gauge: u32,
    user_size: SwatchLength,
) -> u128 {
    let numerator =
        u128::from(pattern_count) * u128::from(user_gauge) * u128::from(pattern_size.units());
    let denominator = u128::from(pattern_gauge) * u128::from(user_size.units());
    let quotient = numerator / denominator;
    let remainder = numerator % denominator;

    quotient + u128::from(remainder * 2 >= denominator)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn len(text: &str) -> SwatchLength {
        SwatchLength::parse_decimal(text).unwrap()
    }

    #[test]
    fn default_sizes_match_the_plain_gauge_baseline() {
        assert_eq!(adjusted_count(5, 1, 2), 3);
        assert_eq!(adjusted_count(7, 1, 2), 4);
        assert_eq!(adjusted_count(121, 19, 22), 105);
        assert_eq!(
            adjusted_count(u32::MAX, u32::MAX, 1),
            u128::from(u32::MAX) * u128::from(u32::MAX)
        );
    }

    #[test]
    fn half_sized_user_swatch_equals_doubled_gauge() {
        let sizes = SwatchSizes {
            user_width: len("5"),
            ..SwatchSizes::default()
        };
        let direct = adjusted_density_count(80, 9, len("10"), 20, len("5"));
        assert_eq!(direct, adjusted_count(80, 18, 20));
        assert_eq!(sizes.user_width, len("5.000"));
    }

    #[test]
    fn ties_round_up_with_swatch_sizes() {
        // 1 * (3 / 5) / (1 / 10) = 6 exactly; 5 cm and 10 cm: 1 * 1 * 10 / (2 * 10) = 0.5 -> 1
        assert_eq!(adjusted_density_count(1, 1, len("10"), 2, len("10")), 1);
        // 1 * 3 * 5 / (2 * 5) = 1.5 -> 2
        assert_eq!(adjusted_density_count(1, 3, len("5"), 2, len("5")), 2);
        // 121 * 19 * 10 / (22 * 10) = 104.5 -> 105
        assert_eq!(
            adjusted_density_count(121, 19, len("10"), 22, len("10")),
            105
        );
    }

    #[test]
    fn decimal_parsing_is_exact_and_bounded() {
        assert_eq!(len("12.125").units(), 12_125);
        assert_eq!(len("0.001").units(), 1);
        assert_eq!(len("100").units(), 100_000);
        for bad in [
            "", "0", "0.000", "100.001", "-1", "1e1", "1.0001", ".5", "5.", "a",
        ] {
            assert!(SwatchLength::parse_decimal(bad).is_none(), "{bad}");
        }
    }
}
