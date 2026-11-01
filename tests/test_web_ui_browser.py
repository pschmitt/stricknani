"""Unit checks for the sanitized web UI browser acceptance runner."""

from __future__ import annotations

import stat
from pathlib import Path

import pytest

from ci.stricknani import rust_fixture, web_ui_browser


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("http://127.0.0.1:49152", "http://127.0.0.1:49152"),
        ("http://localhost:49152", "http://127.0.0.1:49152"),
    ],
)
def test_loopback_origin_normalizes_local_fixture_url(url: str, expected: str) -> None:
    assert web_ui_browser._loopback_origin(url) == expected


@pytest.mark.parametrize(
    "url",
    [
        "https://127.0.0.1:49152",
        "http://192.0.2.1:49152",
        "http://user:password@127.0.0.1:49152",
        "http://127.0.0.1:49152/?token=hidden",
    ],
)
def test_loopback_origin_rejects_unsafe_fixture_url(url: str) -> None:
    with pytest.raises(web_ui_browser.AcceptanceFailure):
        web_ui_browser._loopback_origin(url)


def test_screenshot_directory_is_private_and_outside_checkout(tmp_path: Path) -> None:
    target = web_ui_browser._private_screenshot_dir(tmp_path / "screenshots")

    assert target.is_dir()
    assert stat.S_IMODE(target.stat().st_mode) == 0o700
    assert rust_fixture.REPO_ROOT not in target.parents
