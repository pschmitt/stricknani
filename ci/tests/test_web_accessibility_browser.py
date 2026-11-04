"""Unit checks for the focused accessibility browser runner."""

from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace
from typing import cast
from unittest.mock import Mock, call

import pytest
from playwright.sync_api import Locator, Page

from ci.stricknani import web_accessibility_browser


def test_accessibility_runner_requires_private_fixture_directory() -> None:
    parser = web_accessibility_browser._argument_parser()

    with pytest.raises(SystemExit):
        parser.parse_args(["--binary", "server", "--web-root", "web"])


def test_accessibility_runner_accepts_frozen_artifact_paths() -> None:
    args = web_accessibility_browser._argument_parser().parse_args(
        [
            "--binary",
            "/build/frozen/server",
            "--web-root",
            "/build/frozen/web",
            "--private-dir",
            "/build/run/accessibility",
            "--browser-executable",
            "/nix/store/playwright/chromium",
        ]
    )

    assert args.binary == Path("/build/frozen/server")
    assert args.web_root == Path("/build/frozen/web")
    assert args.private_dir == Path("/build/run/accessibility")
    assert args.browser_executable == Path("/nix/store/playwright/chromium")


def test_tab_to_reaches_target_with_keyboard_events() -> None:
    press = Mock()
    page = cast(Page, SimpleNamespace(keyboard=SimpleNamespace(press=press)))
    locator = cast(
        Locator,
        SimpleNamespace(evaluate=Mock(side_effect=[False, False, True])),
    )

    web_accessibility_browser._tab_to(page, locator, "target field")

    assert press.call_args_list == [call("Tab"), call("Tab")]


def test_tab_to_does_not_advance_when_target_is_already_focused() -> None:
    press = Mock()
    page = cast(Page, SimpleNamespace(keyboard=SimpleNamespace(press=press)))
    locator = cast(Locator, SimpleNamespace(evaluate=Mock(return_value=True)))

    web_accessibility_browser._tab_to(page, locator, "target field")

    press.assert_not_called()
