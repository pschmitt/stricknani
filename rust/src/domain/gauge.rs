use std::num::NonZeroU32;

#[derive(Debug)]
pub(crate) struct GaugeResult {
    pub(crate) adjusted_stitches: u64,
    pub(crate) adjusted_rows: Option<u64>,
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
    pub(crate) fn calculate(self) -> GaugeResult {
        GaugeResult {
            adjusted_stitches: adjusted_count(
                self.pattern_cast_on_stitches.get(),
                self.user_gauge_stitches.get(),
                self.pattern_gauge_stitches.get(),
            ),
            adjusted_rows: self.pattern_row_count.map(|row_count| {
                adjusted_count(
                    row_count.get(),
                    self.user_gauge_rows.get(),
                    self.pattern_gauge_rows.get(),
                )
            }),
        }
    }
}

pub(crate) fn adjusted_count(pattern_count: u32, user_gauge: u32, pattern_gauge: u32) -> u64 {
    let numerator = u64::from(pattern_count) * u64::from(user_gauge);
    let denominator = u64::from(pattern_gauge);
    let quotient = numerator / denominator;
    let remainder = numerator % denominator;

    quotient + u64::from(remainder * 2 >= denominator)
}
