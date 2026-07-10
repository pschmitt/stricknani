"""Unit tests for the in-memory auth rate limiter (T69)."""

import time

import pytest

from stricknani.utils import rate_limit


def test_is_rate_limited_false_when_under_the_cap() -> None:
    key = "test:under-cap"
    for _ in range(4):
        assert (
            rate_limit.is_rate_limited(key, max_attempts=5, window_seconds=60) is False
        )
        rate_limit.record_attempt(key)


def test_is_rate_limited_true_once_cap_is_reached() -> None:
    key = "test:at-cap"
    for _ in range(3):
        rate_limit.record_attempt(key)

    assert rate_limit.is_rate_limited(key, max_attempts=3, window_seconds=60) is True


def test_is_rate_limited_does_not_record_by_itself() -> None:
    """Peeking must not mutate state; only `record_attempt` should."""
    key = "test:peek-only"
    for _ in range(10):
        rate_limit.is_rate_limited(key, max_attempts=1, window_seconds=60)

    assert rate_limit.is_rate_limited(key, max_attempts=1, window_seconds=60) is False


def test_old_attempts_fall_out_of_the_window(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key = "test:window-expiry"
    now = 1_000.0
    times = iter([now, now, now + 120.0])

    def fake_monotonic() -> float:
        return next(times)

    monkeypatch.setattr(time, "monotonic", fake_monotonic)

    rate_limit.record_attempt(key)
    assert rate_limit.is_rate_limited(key, max_attempts=1, window_seconds=60) is True
    # 120s later (outside the 60s window), the old attempt should have
    # expired and no longer count against the cap.
    assert rate_limit.is_rate_limited(key, max_attempts=1, window_seconds=60) is False


def test_reset_rate_limits_clears_all_state() -> None:
    key = "test:reset"
    rate_limit.record_attempt(key)
    assert rate_limit.is_rate_limited(key, max_attempts=1, window_seconds=60) is True

    rate_limit.reset_rate_limits()

    assert rate_limit.is_rate_limited(key, max_attempts=1, window_seconds=60) is False
