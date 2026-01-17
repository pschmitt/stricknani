"""Gauge calculator utilities."""

from typing import NamedTuple


class GaugeResult(NamedTuple):
    """Gauge calculation result."""

    adjusted_stitches: int
    adjusted_rows: int | None
    pattern_gauge_stitches: int
    pattern_gauge_rows: int
    user_gauge_stitches: int
    user_gauge_rows: int
    pattern_cast_on_stitches: int
    pattern_row_count: int | None


def calculate_gauge(
    pattern_gauge_stitches: int,
    pattern_gauge_rows: int,
    user_gauge_stitches: int,
    user_gauge_rows: int,
    pattern_cast_on_stitches: int,
    pattern_row_count: int | None = None,
) -> GaugeResult:
    """
    Calculate adjusted stitch and row counts based on gauge differences.

    Args:
        pattern_gauge_stitches: Pattern gauge stitches per 10cm
        pattern_gauge_rows: Pattern gauge rows per 10cm
        user_gauge_stitches: User's gauge stitches per 10cm
        user_gauge_rows: User's gauge rows per 10cm
        pattern_cast_on_stitches: Stitch count to cast on in the pattern
        pattern_row_count: Row count from the pattern (optional)

    Returns:
        GaugeResult with adjusted stitch and row counts

    Example:
        Pattern = 20 sts/10cm, User = 18 sts/10cm, Cast-on = 120
        → 108 stitches (120 * 18 / 20)
    """
    # Adjust stitch count proportionally based on gauge ratio.
    adjusted_stitches = round(
        pattern_cast_on_stitches * (user_gauge_stitches / pattern_gauge_stitches)
    )

    adjusted_rows = None
    if pattern_row_count is not None:
        adjusted_rows = round(
            pattern_row_count * (user_gauge_rows / pattern_gauge_rows)
        )

    return GaugeResult(
        adjusted_stitches=adjusted_stitches,
        adjusted_rows=adjusted_rows,
        pattern_gauge_stitches=pattern_gauge_stitches,
        pattern_gauge_rows=pattern_gauge_rows,
        user_gauge_stitches=user_gauge_stitches,
        user_gauge_rows=user_gauge_rows,
        pattern_cast_on_stitches=pattern_cast_on_stitches,
        pattern_row_count=pattern_row_count,
    )
