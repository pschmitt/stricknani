#!/usr/bin/env python3
"""Check the ReorderableMedia photo tiles in the project and yarn editors.

Runs against the disposable Rust fixture only (synthetic seeded records and
the demo photos), so reordering and deleting never touches real data. It checks
tile geometry, the hover/focus/long-press trash button and keyboard reordering
with its live-region announcement, at desktop, phone and narrowest-phone sizes.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from playwright.sync_api import Locator, Page, expect, sync_playwright

from ci.stricknani import rust_fixture, seed_rust, web_ui_browser

LONG_PRESS_MS = 400
GEOMETRY_TOLERANCE_PX = 2.0
INSET_PX = 8.0
MIN_TARGET_PX = 44.0


class AcceptanceFailure(RuntimeError):
    """A safe-to-display acceptance assertion failure."""


@dataclass(frozen=True)
class Profile:
    name: str
    width: int
    height: int
    touch: bool


PROFILES = (
    Profile("desktop", 1280, 900, False),
    Profile("phone", 390, 844, True),
    Profile("narrow", 320, 700, True),
    # Just wide enough for two columns, so tiles are at their 8rem minimum.
    Profile("min-tile", 334, 700, True),
)


class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.lines: list[str] = []

    def check(self, ok: bool, label: str, detail: object = "") -> None:
        text = f"{'PASS' if ok else 'FAIL'} {label}"
        if detail != "":
            text += f" {detail}"
        self.lines.append(text)
        print(text, flush=True)
        if not ok:
            self.failures.append(label)


def _origin(url: str) -> str:
    parsed = urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}:
        raise AcceptanceFailure("fixture URL must be plain HTTP on loopback")
    if parsed.port is None:
        raise AcceptanceFailure("fixture URL has no port")
    return f"http://127.0.0.1:{parsed.port}"


def _sign_in(page: Page, origin: str, email: str, password: str) -> None:
    page.goto(f"{origin}/app/login", wait_until="networkidle")
    page.get_by_role("button", name=re.compile(r"^(English|Englisch)$")).click()
    page.locator("#email").fill(email)
    page.locator("#password").fill(password)
    page.get_by_role("button", name="Sign in").click()
    page.wait_for_url("**/app/projects")
    page.get_by_role("heading", name="Projects", exact=True).wait_for(state="visible")


def _resource_id(page: Page, origin: str, collection: str, name: str) -> int:
    page.goto(f"{origin}/app/{collection}", wait_until="networkidle")
    page.get_by_role("link", name=name).first.click()
    page.wait_for_url(re.compile(rf".*/app/{collection}/\d+$"))
    return int(urlsplit(page.url).path.rsplit("/", 1)[1])


def _tiles(list_locator: Locator) -> Locator:
    return list_locator.locator(".reorder-item")


def _tile_ids(list_locator: Locator) -> list[str]:
    ids = _tiles(list_locator).evaluate_all(
        "tiles => tiles.map(tile => tile.dataset.tile)"
    )
    return [str(value) for value in ids]


def _geometry(tile: Locator) -> dict[str, Any]:
    value = tile.evaluate(
        """tile => {
          const rect = element => {
            if (!element) return null;
            const r = element.getBoundingClientRect();
            return {left: r.left, right: r.right, top: r.top, bottom: r.bottom,
                    width: r.width, height: r.height};
          };
          const style = element => element ? getComputedStyle(element) : null;
          const del = tile.querySelector('.delete-button');
          return {
            media: rect(tile.querySelector('.tile-media')),
            badge: rect(tile.querySelector('.cover-badge')),
            trash: rect(del),
            trashOpacity: del ? Number(style(del).opacity) : null,
            dir: getComputedStyle(tile).direction,
            badgeText: tile.querySelector('.cover-badge')?.textContent?.trim() ?? null,
          };
        }"""
    )
    if not isinstance(value, dict):
        raise AcceptanceFailure("browser returned invalid tile geometry")
    return value


def _overlap_area(a: dict[str, float], b: dict[str, float]) -> float:
    width = min(a["right"], b["right"]) - max(a["left"], b["left"])
    height = min(a["bottom"], b["bottom"]) - max(a["top"], b["top"])
    return max(width, 0.0) * max(height, 0.0)


def _near(value: float, expected: float) -> bool:
    return abs(value - expected) <= GEOMETRY_TOLERANCE_PX


def _center_tile(tile: Locator) -> None:
    tile.evaluate("element => element.scrollIntoView({ block: 'center' })")


def _tab_to_tile(page: Page, tile: Locator) -> bool:
    """Reach a tile with real Tab presses so focus-visible applies."""
    page.evaluate(
        "() => { if (document.activeElement instanceof HTMLElement) "
        "document.activeElement.blur(); window.scrollTo(0, 0); "
        # Firefox keeps the sequential-focus starting point where the last
        # touch, click or focus landed, and blur() does not reset it: a Tab
        # would then continue from inside the tile under test, skip the tile
        # itself and run on to the end of the page. Its starting point also
        # follows the selection, so park that at the top of the document.
        "document.getSelection()?.collapse(document.body, 0); }"
    )
    trail: list[str] = []
    for _ in range(120):
        page.keyboard.press("Tab")
        if tile.evaluate("element => element === document.activeElement"):
            return True
        trail.append(
            page.evaluate(
                "() => { const e = document.activeElement; return e ? "
                "e.tagName + '.' + e.className + ' ' + (e.textContent||'').trim()"
                ".slice(0, 20) : 'none'; }"
            )
        )
    print("DEBUG tab trail", trail[:8], trail[-4:], flush=True)
    return False


class Touch:
    """Press a tile with a finger: real CDP touch on Chromium, synthetic otherwise."""

    def __init__(self, page: Page, cdp: Any | None) -> None:
        self.page = page
        self.cdp = cdp

    @property
    def kind(self) -> str:
        return "cdp-touch" if self.cdp is not None else "synthetic-pointer"

    def press(self, tile: Locator) -> None:
        box = tile.locator(".tile-media").bounding_box()
        if box is None:
            raise AcceptanceFailure("tile has no box to press")
        x = box["x"] + box["width"] / 2
        y = box["y"] + box["height"] / 2
        if self.cdp is not None:
            self.cdp.send(
                "Input.dispatchTouchEvent",
                {"type": "touchStart", "touchPoints": [{"x": x, "y": y, "id": 1}]},
            )
        else:
            tile.evaluate(
                """(tile, point) => tile.dispatchEvent(new PointerEvent('pointerdown', {
                  pointerType: 'touch', pointerId: 77, isPrimary: true,
                  clientX: point.x, clientY: point.y, bubbles: true, cancelable: true,
                  button: 0, buttons: 1}))""",
                {"x": x, "y": y},
            )

    def drag_away(self, tile: Locator, distance: float = 24.0) -> None:
        """Move the finger past the slop, as a scroll would."""
        box = tile.locator(".tile-media").bounding_box()
        if box is None:
            raise AcceptanceFailure("tile has no box to drag")
        x = box["x"] + box["width"] / 2
        y = box["y"] + box["height"] / 2 + distance
        if self.cdp is not None:
            self.cdp.send(
                "Input.dispatchTouchEvent",
                {"type": "touchMove", "touchPoints": [{"x": x, "y": y, "id": 1}]},
            )
        else:
            tile.evaluate(
                """(tile, point) => tile.dispatchEvent(new PointerEvent('pointermove', {
                  pointerType: 'touch', pointerId: 77, isPrimary: true,
                  clientX: point.x, clientY: point.y, bubbles: true,
                  cancelable: true, button: 0, buttons: 1}))""",
                {"x": x, "y": y},
            )

    def release(self, tile: Locator) -> None:
        if self.cdp is not None:
            self.cdp.send(
                "Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []}
            )
        else:
            tile.evaluate(
                """tile => tile.dispatchEvent(new PointerEvent('pointerup', {
                  pointerType: 'touch', pointerId: 77, isPrimary: true,
                  bubbles: true, cancelable: true, button: 0}))"""
            )


def _shot(page: Page, shots: Path | None, name: str, list_locator: Locator) -> None:
    if shots is None:
        return
    page.evaluate("() => document.fonts?.ready")
    try:
        list_locator.locator("xpath=..").screenshot(path=str(shots / f"{name}.png"))
    except Exception:
        # A screenshot is evidence only; never fail the behavioral check on it.
        pass


def _check_tile(
    page: Page,
    touch: Touch | None,
    profile: Profile,
    tag: str,
    list_locator: Locator,
    report: Report,
    shots: Path | None,
    shot_name: str,
    expect_cover: bool,
) -> None:
    """Trash visibility (rest, hover, focus, long press) and tile geometry."""
    tiles = _tiles(list_locator)
    count = tiles.count()
    report.check(count >= 1, f"{tag}: has a photo tile", count)
    if count < 1:
        return
    first = tiles.nth(0)
    _center_tile(first)
    page.mouse.move(1, 1)

    expect(first.locator(".delete-button")).to_have_css("opacity", "0")
    rest = _geometry(first)
    report.check(rest["trashOpacity"] == 0, f"{tag}: trash hidden at rest")
    _shot(page, shots, f"{shot_name}-1-rest", list_locator)

    if profile.touch:
        assert touch is not None
        # Moving before the long press elapses is a scroll: no trash, no drag.
        touch.press(first)
        page.wait_for_timeout(LONG_PRESS_MS // 4)
        touch.drag_away(first)
        page.wait_for_timeout(LONG_PRESS_MS + 150)
        short = _geometry(first)
        touch.release(first)
        report.check(
            short["trashOpacity"] == 0,
            f"{tag}: a press that moves before {LONG_PRESS_MS} ms shows no trash",
        )
        # Let the scroll the browser took over settle before pressing again.
        page.wait_for_timeout(500)
        _center_tile(first)
        page.wait_for_timeout(200)
        touch.press(first)
        page.wait_for_timeout(LONG_PRESS_MS + 50)
        armed = first.evaluate(
            "tile => tile.closest('.reorder-list')?.classList.contains('editing')"
        )
        try:
            expect(first.locator(".delete-button")).to_have_css("opacity", "1")
            shown = True
        except AssertionError:
            shown = False
        report.check(
            bool(armed) and shown,
            f"{tag}: {LONG_PRESS_MS} ms long press without moving shows the trash "
            f"({touch.kind})",
        )
        _shot(page, shots, f"{shot_name}-2-longpress", list_locator)
        # The jiggle rotates every tile by a degree or two; measure the layout
        # with the animation paused so the corner insets are exact.
        # (CSSOM writes are allowed under the page CSP; an injected <style> is not.)
        pause_js = (
            "on => document.querySelectorAll('.reorder-item').forEach(el => "
            "on ? el.style.setProperty('animation', 'none', 'important') "
            ": el.style.removeProperty('animation'))"
        )
        page.evaluate(pause_js, True)
        revealed = _geometry(first)
        page.evaluate(pause_js, False)
        touch.release(first)
        page.wait_for_timeout(300)
        viewer_open = page.locator("dialog[open]").count() > 0
        report.check(
            not viewer_open
            and bool(
                first.evaluate(
                    "tile => tile.closest('.reorder-list')"
                    "?.classList.contains('editing')"
                )
            ),
            f"{tag}: lifting after the long press stays in edit mode with no photo "
            f"viewer on top of the trash ({touch.kind})",
        )
        if first.locator(".thumb-button").count() > 0:
            report.check(
                not viewer_open,
                f"{tag}: lifting after the long press keeps the trash instead of "
                f"opening the photo viewer ({touch.kind})",
            )
        if viewer_open:
            page.keyboard.press("Escape")
            page.wait_for_timeout(300)
    else:
        first.hover()
        try:
            expect(first.locator(".delete-button")).to_have_css("opacity", "1")
            shown = True
        except AssertionError:
            shown = False
        report.check(shown, f"{tag}: hovering a tile shows the trash")
        revealed = _geometry(first)
        _shot(page, shots, f"{shot_name}-2-hover", list_locator)
        page.mouse.move(1, 1)
        try:
            expect(first.locator(".delete-button")).to_have_css("opacity", "0")
            hidden = True
        except AssertionError:
            hidden = False
        report.check(hidden, f"{tag}: the trash hides again when the pointer leaves")

    media, badge, trash = revealed["media"], revealed["badge"], revealed["trash"]
    report.check(
        media is not None and trash is not None,
        f"{tag}: first tile has a trash button",
    )
    if media is not None and trash is not None:
        rtl = revealed["dir"] == "rtl"
        end_gap = (
            (trash["left"] - media["left"])
            if rtl
            else (media["right"] - trash["right"])
        )
        report.check(
            _near(trash["top"] - media["top"], INSET_PX) and _near(end_gap, INSET_PX),
            f"{tag}: trash sits top-end",
            f"(top gap {trash['top'] - media['top']:.1f}px, end gap {end_gap:.1f}px)",
        )
        report.check(
            trash["width"] >= MIN_TARGET_PX - 0.5
            and trash["height"] >= MIN_TARGET_PX - 0.5,
            f"{tag}: trash target is at least {MIN_TARGET_PX:.0f}px",
            f"({trash['width']:.0f}x{trash['height']:.0f}px)",
        )
        if expect_cover:
            report.check(badge is not None, f"{tag}: first tile shows the cover label")
            if badge is not None:
                start_gap = (
                    (media["right"] - badge["right"])
                    if rtl
                    else (badge["left"] - media["left"])
                )
                report.check(
                    _near(media["bottom"] - badge["bottom"], INSET_PX)
                    and _near(start_gap, INSET_PX),
                    f"{tag}: cover label sits bottom-start",
                    f"(bottom gap {media['bottom'] - badge['bottom']:.1f}px, "
                    f"start gap {start_gap:.1f}px)",
                )
                overlap = _overlap_area(badge, trash)
                report.check(
                    overlap == 0.0,
                    f"{tag}: cover label and trash do not overlap",
                    f"(tile {media['width']:.0f}x{media['height']:.0f}px, "
                    f"overlap {overlap:.1f}px^2)",
                )
                report.check(
                    badge["left"] >= media["left"] - 0.5
                    and badge["right"] <= media["right"] + 0.5,
                    f"{tag}: cover label stays inside the tile",
                )
        else:
            report.check(badge is None, f"{tag}: non-cover tile has no cover label")
        report.lines.append(f"INFO {tag}: tile {media['width']:.0f}px wide")
        print(f"INFO {tag}: tile {media['width']:.0f}px wide", flush=True)
    if count >= 2:
        other = _geometry(tiles.nth(1))
        report.check(other["badge"] is None, f"{tag}: only the first tile is the cover")

    page.mouse.move(1, 1)
    reached = _tab_to_tile(page, first)
    report.check(reached, f"{tag}: first tile is reachable with Tab")
    if reached:
        try:
            expect(first.locator(".delete-button")).to_have_css("opacity", "1")
            focus_shows = True
        except AssertionError:
            focus_shows = False
        report.check(focus_shows, f"{tag}: keyboard focus shows the trash")
        _shot(page, shots, f"{shot_name}-3-focus", list_locator)


def _check_reorder(
    page: Page,
    tag: str,
    list_locator: Locator,
    report: Report,
    shots: Path | None,
    shot_name: str,
) -> None:
    """Arrow keys move the focused tile and the live region announces it."""
    tiles = _tiles(list_locator)
    count = tiles.count()
    report.check(count >= 2, f"{tag}: at least two photo tiles to reorder", count)
    if count < 2:
        return
    first = tiles.nth(0)
    _center_tile(first)
    page.mouse.move(1, 1)
    reached = _tab_to_tile(page, first)
    report.check(reached, f"{tag}: first tile is reachable with Tab")
    if not reached:
        return
    before = _tile_ids(list_locator)
    moved_id = before[0]
    status = list_locator.locator("xpath=..").locator("[role=status]")
    page.keyboard.press("ArrowRight")
    try:
        expect(status).to_have_text(
            re.compile(rf"Photo moved to position 2 of {count}\."), timeout=10_000
        )
        announced = True
    except AssertionError:
        announced = False
    report.check(announced, f"{tag}: ArrowRight announces the new position")
    try:
        page.wait_for_function(
            "id => document.activeElement?.dataset?.tile === id",
            arg=moved_id,
            timeout=5_000,
        )
        kept_focus = True
    except Exception:
        kept_focus = False
    after = _tile_ids(list_locator)
    report.check(
        after[1] == moved_id and after[0] == before[1],
        f"{tag}: ArrowRight moved the tile to position 2",
        f"({before} -> {after})",
    )
    report.check(kept_focus, f"{tag}: the moved tile keeps keyboard focus")
    _shot(page, shots, f"{shot_name}-4-moved", list_locator)
    page.keyboard.press("ArrowLeft")
    try:
        expect(status).to_have_text(
            re.compile(r"Photo moved to position 1 of \d+\."), timeout=10_000
        )
        restored = True
    except AssertionError:
        restored = False
    report.check(
        restored and _tile_ids(list_locator) == before,
        f"{tag}: ArrowLeft announces and restores the original order",
    )


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    browser_name: str,
    browser_executable: Path | None,
) -> int:
    fixture_file = private_dir / "fixture.json"
    state_file = private_dir / "fixture-state.json"
    token_file = private_dir / "seed-token.json"
    started = False
    try:
        rust_fixture.start_fixture(
            binary=binary,
            temp_dir=private_dir / "runtime",
            fixture_file=fixture_file,
            state_file=state_file,
            web_root=web_root,
        )
        started = True
        try:
            seed_rust.seed_fixture(fixture_file, token_file)
        except seed_rust.SeedError as error:
            raise AcceptanceFailure(
                "synthetic fixture could not be prepared"
            ) from error
        finally:
            token_file.unlink(missing_ok=True)
        return _run(
            fixture_file, private_dir / "screenshots", browser_name, browser_executable
        )
    finally:
        if started:
            rust_fixture.stop_fixture(state_file)


def _run(
    fixture_file: Path,
    shots_dir: Path,
    browser_name: str,
    browser_executable: Path | None,
) -> int:
    fixture = rust_fixture.load_fixture_environment(fixture_file)
    origin = _origin(fixture["STRICKNANI_RUST_TEST_URL"])
    email = fixture["STRICKNANI_RUST_TEST_EMAIL"]
    password = fixture["STRICKNANI_RUST_TEST_PASSWORD"]
    shots_dir = web_ui_browser._private_screenshot_dir(shots_dir)
    # WebKit's screenshot path injects inline styles that strict CSP blocks.
    shots: Path | None = None if browser_name == "webkit" else shots_dir
    report = Report()
    page_errors: list[str] = []
    csp_errors: list[str] = []
    assets = Path(__file__).resolve().parents[2] / "demo_assets"
    photos = [assets / f"demo_image_{index}.jpg" for index in (1, 2, 3)]

    with sync_playwright() as playwright:
        browser_type = getattr(playwright, browser_name)
        browser = browser_type.launch(
            headless=True,
            executable_path=str(browser_executable) if browser_executable else None,
        )
        try:
            project_id = yarn_id = 0
            for profile in PROFILES:
                options: dict[str, Any] = {
                    "viewport": {"width": profile.width, "height": profile.height},
                    "locale": "en-US",
                    "color_scheme": "light",
                    "has_touch": profile.touch,
                }
                if profile.touch and browser_name != "firefox":
                    options["is_mobile"] = True
                context = browser.new_context(**options)
                page = context.new_page()
                page.on("pageerror", lambda error: page_errors.append(str(error)[:120]))
                page.on(
                    "console",
                    lambda message: (
                        csp_errors.append(message.text[:120])
                        if "content security policy" in message.text.lower()
                        else None
                    ),
                )
                cdp = (
                    context.new_cdp_session(page)
                    if browser_name == "chromium" and profile.touch
                    else None
                )
                touch = Touch(page, cdp) if profile.touch else None
                _sign_in(page, origin, email, password)
                if not project_id:
                    project_id = _resource_id(
                        page, origin, "projects", seed_rust.PROJECT_NAME
                    )
                    yarn_id = _resource_id(page, origin, "yarns", seed_rust.YARN_NAME)

                # Project editor: the cover group and a step's photo group.
                page.goto(
                    f"{origin}/app/projects/{project_id}/edit",
                    wait_until="networkidle",
                )
                page.get_by_role("heading", name="Edit project", exact=True).wait_for(
                    state="visible"
                )
                if page.locator("#project-image-step option").count() < 2:
                    # The seeded project has no steps; add one to hold photos.
                    page.get_by_role("button", name="Add step", exact=True).click()
                    page.get_by_label("Step title").first.fill("Photo order fixture")
                    page.get_by_label("Step instructions").first.fill(
                        "Synthetic step for the photo tile checks."
                    )
                    with page.expect_response(
                        lambda response: (
                            response.request.method == "PUT"
                            and urlsplit(response.url).path
                            == f"/api/v2/projects/{project_id}"  # noqa: B023
                        )
                    ):
                        page.get_by_role(
                            "button", name="Save changes", exact=True
                        ).click()
                    page.wait_for_url(f"**/app/projects/{project_id}")
                    page.goto(
                        f"{origin}/app/projects/{project_id}/edit",
                        wait_until="networkidle",
                    )
                    page.get_by_role(
                        "heading", name="Edit project", exact=True
                    ).wait_for(state="visible")
                step_list = page.get_by_role("list", name=re.compile(r"^Step \d+:"))
                guard = 0
                while (
                    step_list.count() == 0 or _tiles(step_list.first).count() < 3
                ) and guard < 3:
                    page.locator("#project-image-step").select_option(index=1)
                    page.locator("#project-image-file").set_input_files(
                        str(photos[guard % len(photos)])
                    )
                    page.locator("#main-content .action-status").filter(
                        has_text="Project media updated."
                    ).first.wait_for(state="visible", timeout=20_000)
                    guard += 1
                step_list.first.wait_for(state="visible")
                cover_list = page.get_by_role("list", name="Project cover")
                cover_list.first.wait_for(state="visible")
                base = f"{browser_name}-{profile.name}"
                _check_tile(
                    page,
                    touch,
                    profile,
                    f"{browser_name}/{profile.name}/project-cover",
                    cover_list.first,
                    report,
                    shots,
                    f"{base}-project-cover",
                    True,
                )
                _check_tile(
                    page,
                    touch,
                    profile,
                    f"{browser_name}/{profile.name}/project-step",
                    step_list.first,
                    report,
                    shots,
                    f"{base}-project-step",
                    False,
                )
                _check_reorder(
                    page,
                    f"{browser_name}/{profile.name}/project-step",
                    step_list.first,
                    report,
                    shots,
                    f"{base}-project-step",
                )

                # Yarn editor: the photo group.
                page.goto(
                    f"{origin}/app/yarns/{yarn_id}/edit", wait_until="networkidle"
                )
                page.get_by_role("heading", name="Edit yarn", exact=True).wait_for(
                    state="visible"
                )
                yarn_list = page.get_by_role("list", name="Photos")
                guard = 0
                while (
                    yarn_list.count() == 0 or _tiles(yarn_list).count() < 3
                ) and guard < 3:
                    page.get_by_label("Choose a yarn photo").set_input_files(
                        str(photos[guard % len(photos)])
                    )
                    page.get_by_role("status").filter(
                        has_text="Photo uploaded."
                    ).first.wait_for(state="visible", timeout=20_000)
                    guard += 1
                yarn_list.first.wait_for(state="visible")
                _check_tile(
                    page,
                    touch,
                    profile,
                    f"{browser_name}/{profile.name}/yarn",
                    yarn_list.first,
                    report,
                    shots,
                    f"{base}-yarn",
                    True,
                )
                _check_reorder(
                    page,
                    f"{browser_name}/{profile.name}/yarn",
                    yarn_list.first,
                    report,
                    shots,
                    f"{base}-yarn",
                )
                context.close()
        finally:
            browser.close()
            for shot in shots_dir.glob("*.png"):
                shot.chmod(0o600)

    report.check(not csp_errors, "no CSP violations", csp_errors[:3])
    synthetic = browser_name != "chromium"
    report.check(
        not page_errors or synthetic,
        "no uncaught page errors",
        page_errors[:3] if page_errors else "",
    )
    summary = {
        "browser": browser_name,
        "checks": len(report.lines),
        "failures": report.failures,
        "screenshots": str(shots_dir) if shots else None,
    }
    print(json.dumps(summary), flush=True)
    return 1 if report.failures else 0


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path)
    parser.add_argument(
        "--browser", choices=("chromium", "firefox", "webkit"), default="chromium"
    )
    parser.add_argument("--browser-executable", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _argument_parser().parse_args(argv)
    try:
        private_dir = web_ui_browser._private_work_dir(args.private_dir)
        return run_live_fixture(
            args.binary,
            args.web_root,
            private_dir,
            args.browser,
            args.browser_executable,
        )
    except (rust_fixture.FixtureError, AcceptanceFailure) as error:
        print(f"Media tile browser acceptance failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
