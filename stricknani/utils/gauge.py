"""Gauge calculator utilities."""

from typing import NamedTuple


class GaugeResult(NamedTuple):
    """Gauge calculation result."""

    adjusted_stitches: int
    adjusted_rows: int
    pattern_gauge_stitches: int
    pattern_gauge_rows: int
    user_gauge_stitches: int
    user_gauge_rows: int
    target_width_cm: float
    target_height_cm: float


def calculate_gauge(
    pattern_gauge_stitches: int,
    pattern_gauge_rows: int,
    user_gauge_stitches: int,
    user_gauge_rows: int,
    target_width_cm: float,
    target_height_cm: float = 0.0,
) -> GaugeResult:
    """
    Calculate adjusted stitch and row counts based on gauge differences.

    Args:
        pattern_gauge_stitches: Pattern gauge stitches per 10cm
        pattern_gauge_rows: Pattern gauge rows per 10cm
        user_gauge_stitches: User's gauge stitches per 10cm
        user_gauge_rows: User's gauge rows per 10cm
        target_width_cm: Target width in cm
        target_height_cm: Target height in cm (optional)

    Returns:
        GaugeResult with adjusted stitch and row counts

    Example:
        Pattern = 20 sts/10cm, User = 18 sts/10cm, Target = 50cm
        → 90 stitches (50cm * 18sts/10cm)
    """
    # Calculate stitches needed for target width
    # Formula: (target_width_cm / 10) * user_gauge_stitches
    adjusted_stitches = round((target_width_cm / 10.0) * user_gauge_stitches)

    # Calculate rows needed for target height
    adjusted_rows = 0
    if target_height_cm > 0:
        adjusted_rows = round((target_height_cm / 10.0) * user_gauge_rows)

    return GaugeResult(
        adjusted_stitches=adjusted_stitches,
        adjusted_rows=adjusted_rows,
        pattern_gauge_stitches=pattern_gauge_stitches,
        pattern_gauge_rows=pattern_gauge_rows,
        user_gauge_stitches=user_gauge_stitches,
        user_gauge_rows=user_gauge_rows,
        target_width_cm=target_width_cm,
        target_height_cm=target_height_cm,
    )
