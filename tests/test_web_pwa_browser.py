from __future__ import annotations

import pytest

from ci.stricknani.web_pwa_browser import AcceptanceFailure, _wait_for_async_condition


class PollingPage:
    def __init__(self, results: list[bool]) -> None:
        self.results = results
        self.evaluations = 0

    def evaluate(self, expression: str, arg: object = None) -> bool:
        self.evaluations += 1
        return self.results.pop(0) if self.results else False

    def wait_for_timeout(self, timeout: float) -> None:
        return None


def test_async_false_condition_times_out() -> None:
    page = PollingPage([False])

    with pytest.raises(AcceptanceFailure, match="timed out waiting"):
        _wait_for_async_condition(
            page,
            "async () => false",
            None,
            "worker activation",
            timeout_ms=0,
        )

    assert page.evaluations == 1


def test_async_condition_is_repolled_until_resolved_true() -> None:
    page = PollingPage([False, False, True])

    _wait_for_async_condition(
        page,
        "async () => false until ready",
        None,
        "worker activation",
        timeout_ms=1_000,
    )

    assert page.evaluations == 3
