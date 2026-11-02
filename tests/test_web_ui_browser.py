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


def test_browser_cli_defaults_to_chromium_and_accepts_each_engine() -> None:
    parser = web_ui_browser._argument_parser()
    defaults = parser.parse_args(["--binary", "server", "--web-root", "web"])
    assert defaults.browser == "chromium"
    assert defaults.browser_executable is None
    assert defaults.chromium_executable is None

    for engine in ("chromium", "firefox", "webkit"):
        args = parser.parse_args(
            ["--binary", "server", "--web-root", "web", "--browser", engine]
        )
        assert args.browser == engine


def test_browser_cli_keeps_legacy_chromium_override() -> None:
    parser = web_ui_browser._argument_parser()
    args = parser.parse_args(
        [
            "--binary",
            "server",
            "--web-root",
            "web",
            "--chromium-executable",
            "/usr/bin/chromium",
        ]
    )
    web_ui_browser._validate_browser_args(args, parser)
    assert args.browser == "chromium"

    generic = parser.parse_args(
        [
            "--binary",
            "server",
            "--web-root",
            "web",
            "--browser",
            "webkit",
            "--browser-executable",
            "/nix/store/webkit/pw_run.sh",
        ]
    )
    web_ui_browser._validate_browser_args(generic, parser)
    assert generic.browser == "webkit"
    assert generic.browser_executable == Path("/nix/store/webkit/pw_run.sh")


def test_browser_cli_rejects_ambiguous_or_mismatched_overrides() -> None:
    parser = web_ui_browser._argument_parser()
    with pytest.raises(SystemExit):
        parser.parse_args(
            [
                "--binary",
                "server",
                "--web-root",
                "web",
                "--browser-executable",
                "/tmp/browser",
                "--chromium-executable",
                "/tmp/chromium",
            ]
        )

    args = parser.parse_args(
        [
            "--binary",
            "server",
            "--web-root",
            "web",
            "--browser",
            "firefox",
            "--chromium-executable",
            "/tmp/chromium",
        ]
    )
    with pytest.raises(SystemExit):
        web_ui_browser._validate_browser_args(args, parser)
