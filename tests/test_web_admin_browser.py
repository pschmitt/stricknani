"""Unit checks for the read-only administrator browser acceptance runner."""

from __future__ import annotations

from types import SimpleNamespace
from typing import cast

import pytest
from playwright.sync_api import Page

from ci.stricknani import web_admin_browser


@pytest.mark.parametrize("route", ["resource", "diff"])
def test_admin_responsive_guard_accepts_readonly_routes(route: str) -> None:
    page = cast(
        Page,
        SimpleNamespace(
            evaluate=lambda _script, _classes: {
                "viewport": 320,
                "documentClientWidth": 320,
                "documentScrollWidth": 320,
                "offenders": [],
            }
        ),
    )

    web_admin_browser._assert_no_horizontal_overflow(page, route)


def test_admin_responsive_guard_rejects_unknown_routes() -> None:
    page = cast(Page, SimpleNamespace())

    with pytest.raises(web_admin_browser.AcceptanceFailure):
        web_admin_browser._assert_no_horizontal_overflow(page, "unknown")


def test_admin_browser_fixture_origin_rejects_non_loopback() -> None:
    with pytest.raises(web_admin_browser.AcceptanceFailure):
        web_admin_browser._loopback_origin("http://example.invalid:8080")
