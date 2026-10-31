#[derive(Debug)]
pub(crate) struct GaugeResult {
    pub(crate) adjusted_stitches: u64,
    pub(crate) adjusted_rows: Option<u64>,
}

pub(crate) fn adjusted_count(pattern_count: u32, user_gauge: u32, pattern_gauge: u32) -> u64 {
    let numerator = u64::from(pattern_count) * u64::from(user_gauge);
    let denominator = u64::from(pattern_gauge);
    let quotient = numerator / denominator;
    let remainder = numerator % denominator;

    quotient + u64::from(remainder * 2 >= denominator)
}
