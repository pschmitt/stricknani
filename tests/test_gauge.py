"""Test gauge calculator."""

from typing import Any

import pytest

from stricknani.utils.gauge import calculate_gauge


def test_gauge_calculation_basic() -> None:
    """Test basic gauge calculation."""
    result = calculate_gauge(
        pattern_gauge_stitches=20,
        pattern_gauge_rows=26,
        user_gauge_stitches=18,
        user_gauge_rows=24,
        pattern_cast_on_stitches=120,
        pattern_row_count=None,
    )

    assert result.adjusted_stitches == 108  # 120 * 18 / 20 = 108
    assert result.adjusted_rows is None


def test_gauge_calculation_with_rows() -> None:
    """Test gauge calculation with rows."""
    result = calculate_gauge(
        pattern_gauge_stitches=20,
        pattern_gauge_rows=26,
        user_gauge_stitches=18,
        user_gauge_rows=24,
        pattern_cast_on_stitches=120,
        pattern_row_count=100,
    )

    assert result.adjusted_stitches == 108  # 120 * 18 / 20 = 108
    assert result.adjusted_rows == 92  # round(100 * 24 / 26)


def test_gauge_calculation_exact_match() -> None:
    """Test gauge calculation when user gauge matches pattern."""
    result = calculate_gauge(
        pattern_gauge_stitches=20,
        pattern_gauge_rows=26,
        user_gauge_stitches=20,
        user_gauge_rows=26,
        pattern_cast_on_stitches=80,
        pattern_row_count=50,
    )

    assert result.adjusted_stitches == 80
    assert result.adjusted_rows == 50


def test_gauge_calculation_rounding() -> None:
    """Test that results are properly rounded."""
    result = calculate_gauge(
        pattern_gauge_stitches=22,
        pattern_gauge_rows=28,
        user_gauge_stitches=19,
        user_gauge_rows=25,
        pattern_cast_on_stitches=121,
        pattern_row_count=95,
    )

    # Should round to nearest integer
    assert isinstance(result.adjusted_stitches, int)
    assert isinstance(result.adjusted_rows, int)
    assert result.adjusted_stitches == 104  # round(121 * 19 / 22)
    assert result.adjusted_rows == 85  # round(95 * 25 / 28)


_VALID_FORM: dict[str, str] = {
    "pattern_gauge_stitches": "20",
    "pattern_gauge_rows": "26",
    "user_gauge_stitches": "18",
    "user_gauge_rows": "24",
    "pattern_cast_on_stitches": "120",
    "pattern_row_count": "100",
}


@pytest.mark.asyncio
async def test_calculate_endpoint_zero_gauge_returns_4xx(test_client: Any) -> None:
    """A zero gauge stitch count must not trigger a ZeroDivisionError (500)."""
    client, _, _, _, _ = test_client
    data = {**_VALID_FORM, "pattern_gauge_stitches": "0"}
    response = await client.post("/gauge/calculate", data=data)
    assert 400 <= response.status_code < 500
    assert response.status_code != 500


@pytest.mark.asyncio
async def test_calculate_endpoint_non_numeric_returns_4xx(test_client: Any) -> None:
    """A non-numeric field must return a client error, not a 500 ValueError."""
    client, _, _, _, _ = test_client
    data = {**_VALID_FORM, "pattern_row_count": "not-a-number"}
    response = await client.post("/gauge/calculate", data=data)
    assert 400 <= response.status_code < 500
    assert response.status_code != 500


@pytest.mark.asyncio
async def test_calculate_endpoint_valid_returns_result(test_client: Any) -> None:
    """A valid calculation still returns the correct adjusted counts."""
    client, _, _, _, _ = test_client
    response = await client.post("/gauge/calculate", data=_VALID_FORM)
    assert response.status_code == 200
    body = response.json()
    assert body["adjusted_stitches"] == 108  # 120 * 18 / 20
    assert body["adjusted_rows"] == 92  # round(100 * 24 / 26)
