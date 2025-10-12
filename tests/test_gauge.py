"""Test gauge calculator."""


from stricknani.utils.gauge import calculate_gauge


def test_gauge_calculation_basic() -> None:
    """Test basic gauge calculation."""
    result = calculate_gauge(
        pattern_gauge_stitches=20,
        pattern_gauge_rows=26,
        user_gauge_stitches=18,
        user_gauge_rows=24,
        target_width_cm=50.0,
        target_height_cm=0.0,
    )

    assert result.adjusted_stitches == 90  # (50 / 10) * 18 = 90
    assert result.adjusted_rows == 0


def test_gauge_calculation_with_height() -> None:
    """Test gauge calculation with height."""
    result = calculate_gauge(
        pattern_gauge_stitches=20,
        pattern_gauge_rows=26,
        user_gauge_stitches=18,
        user_gauge_rows=24,
        target_width_cm=50.0,
        target_height_cm=60.0,
    )

    assert result.adjusted_stitches == 90  # (50 / 10) * 18 = 90
    assert result.adjusted_rows == 144  # (60 / 10) * 24 = 144


def test_gauge_calculation_exact_match() -> None:
    """Test gauge calculation when user gauge matches pattern."""
    result = calculate_gauge(
        pattern_gauge_stitches=20,
        pattern_gauge_rows=26,
        user_gauge_stitches=20,
        user_gauge_rows=26,
        target_width_cm=40.0,
        target_height_cm=50.0,
    )

    assert result.adjusted_stitches == 80  # (40 / 10) * 20 = 80
    assert result.adjusted_rows == 130  # (50 / 10) * 26 = 130


def test_gauge_calculation_rounding() -> None:
    """Test that results are properly rounded."""
    result = calculate_gauge(
        pattern_gauge_stitches=22,
        pattern_gauge_rows=28,
        user_gauge_stitches=19,
        user_gauge_rows=25,
        target_width_cm=45.5,
        target_height_cm=55.5,
    )

    # Should round to nearest integer
    assert isinstance(result.adjusted_stitches, int)
    assert isinstance(result.adjusted_rows, int)
    assert result.adjusted_stitches == 86  # round((45.5 / 10) * 19)
    assert result.adjusted_rows == 139  # round((55.5 / 10) * 25)
