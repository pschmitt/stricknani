#!/usr/bin/env python3
"""Exercise yarn and category journeys against a disposable Rust fixture."""

from __future__ import annotations

import argparse
import ipaddress
import os
import re
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from playwright.sync_api import (
    Page,
    Response,
    Route,
    expect,
    sync_playwright,
)
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

from ci.stricknani import rust_fixture, seed_rust, web_ui_browser


class AcceptanceFailure(RuntimeError):
    """A safe-to-display acceptance assertion failure."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


def _loopback_origin(url: str) -> str:
    try:
        parsed = urlsplit(url)
        port = parsed.port
        loopback = parsed.hostname == "localhost"
        if parsed.hostname and not loopback:
            loopback = ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError as error:
        raise AcceptanceFailure("fixture URL is invalid") from error
    if (
        parsed.scheme != "http"
        or not parsed.hostname
        or not loopback
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or port is None
        or not 1 <= port <= 65535
    ):
        raise AcceptanceFailure("fixture URL must be plain HTTP on loopback")
    return f"http://127.0.0.1:{port}"


def _assert_no_overflow(page: Page, width: int) -> None:
    sizes = page.evaluate(
        "() => ({ viewport: innerWidth, doc: document.documentElement.scrollWidth, "
        "body: document.body.scrollWidth })"
    )
    _require(
        sizes["viewport"] == width and sizes["doc"] <= width and sizes["body"] <= width,
        "a checked viewport has horizontal overflow",
    )


def _mask_fixture_identity(page: Page) -> None:
    """Replace synthetic account labels before capturing browser screenshots."""
    page.locator(".header-user").evaluate_all(
        "nodes => nodes.forEach(node => node.textContent = 'Demo account')"
    )
    page.locator(".account-email").evaluate_all(
        "nodes => nodes.forEach(node => { "
        "node.textContent = 'Demo account'; node.title = 'Demo account'; })"
    )
    page.locator(".user-avatar").evaluate_all(
        "nodes => nodes.forEach(node => node.textContent = 'D')"
    )
    page.locator("#email, #password").evaluate_all(
        "nodes => nodes.forEach(node => { node.value = ''; })"
    )


def _prepare_screenshot(page: Page) -> None:
    page.evaluate(
        "() => { window.scrollTo(0, 0); "
        "if (document.activeElement instanceof HTMLElement) "
        "document.activeElement.blur(); }"
    )


def _visible_heights(page: Page, selector: str) -> list[float]:
    values: object = page.locator(selector).evaluate_all(
        "elements => elements.filter(el => el.getClientRects().length).map("
        "el => el.getBoundingClientRect().height)"
    )
    if not isinstance(values, list) or any(
        isinstance(value, bool) or not isinstance(value, (int, float))
        for value in values
    ):
        raise AcceptanceFailure("browser returned invalid target sizes")
    return [float(value) for value in values]


def _observe_fixture_update(page: Page, yarn_id: int) -> int:
    result: object = page.evaluate(
        """async (id) => {
          const sessionResponse = await fetch('/api/v2/session');
          if (!sessionResponse.ok) return sessionResponse.status;
          const sessionEnvelope = await sessionResponse.json();
          const token = sessionEnvelope?.data?.csrf_token;
          const detailResponse = await fetch(`/api/v2/yarns/${id}`);
          if (!detailResponse.ok) return detailResponse.status;
          const detail = (await detailResponse.json())?.data;
          if (!detail || typeof token !== 'string') return 0;
          const response = await fetch(`/api/v2/yarns/${id}`, {
            method: 'PUT',
            headers: {
              'accept': 'application/json',
              'content-type': 'application/json',
              'x-csrf-token': token,
            },
            body: JSON.stringify({
              name: detail.name,
              description: detail.description,
              brand: detail.brand,
              colorway: 'Server update',
              dye_lot: detail.dye_lot,
              fiber_content: detail.fiber_content,
              weight_category: detail.weight_category,
              recommended_needles: detail.recommended_needles,
              weight_grams: detail.weight_grams,
              length_meters: detail.length_meters,
              notes: detail.notes,
              link: detail.link,
              is_ai_enhanced: detail.is_ai_enhanced,
              expected_updated_at: detail.updated_at,
            }),
          });
          return response.status;
        }""",
        yarn_id,
    )
    if isinstance(result, bool) or not isinstance(result, int):
        raise AcceptanceFailure("concurrent update returned an invalid status")
    return result


def _has_one_api_conflict(
    observed: list[tuple[str, int, str, str | None]], method: str, path: str
) -> bool:
    matches = [
        (status, code)
        for observed_method, status, observed_path, code in observed
        if observed_method == method and observed_path == path and status == 409
    ]
    return matches == [(409, "conflict")]


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    chromium_executable: Path | None = None,
) -> Path:
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
                "synthetic yarn, category, project, or photo fixture "
                "could not be prepared"
            ) from error
        finally:
            token_file.unlink(missing_ok=True)
        return run_acceptance(
            fixture_file,
            private_dir / "screenshots",
            chromium_executable,
        )
    finally:
        if started:
            rust_fixture.stop_fixture(state_file)


def run_acceptance(
    fixture_file: Path,
    screenshot_path: Path | None = None,
    chromium_executable: Path | None = None,
) -> Path:
    fixture = rust_fixture.load_fixture_environment(fixture_file)
    origin = _loopback_origin(fixture["STRICKNANI_RUST_TEST_URL"])
    email = fixture["STRICKNANI_RUST_TEST_EMAIL"]
    password = fixture["STRICKNANI_RUST_TEST_PASSWORD"]
    screenshots = web_ui_browser._private_screenshot_dir(screenshot_path)
    stage_file = screenshots.parent / "yarn-browser-stage.txt"

    def mark_stage(value: str) -> None:
        stage_file.write_text(value, encoding="ascii")
        stage_file.chmod(0o600)

    if chromium_executable is not None:
        chromium_executable = chromium_executable.expanduser().resolve()
        if not chromium_executable.is_file() or not os.access(
            chromium_executable, os.X_OK
        ):
            raise AcceptanceFailure("configured Chromium executable is unavailable")

    observed: list[tuple[str, int, str]] = []
    observed_mutation_requests: list[tuple[str, str]] = []
    csp_errors: list[bool] = []
    console_errors: list[str] = []
    page_errors: list[bool] = []
    resource_failures: list[str] = []
    observed_api_errors: list[tuple[str, int, str, str | None]] = []

    def resource_category(path: str) -> str:
        if path.startswith("/app/_app/"):
            return "app-asset"
        if path in {"/app/favicon.svg", "/favicon.ico"}:
            return "favicon"
        if path.startswith("/api/v2/yarns"):
            return "yarn-api"
        if path.startswith("/api/v2/categories"):
            return "category-api"
        if path.startswith("/media/") or path.startswith("/api/v2/media/"):
            return "media"
        return "other-resource"

    def observe_response(response: Response) -> None:
        path = urlsplit(response.url).path
        if path.startswith("/api/v2/yarns") or path.startswith("/api/v2/categories"):
            observed.append((response.request.method, response.status, path))
        if response.status >= 400 and (
            path.startswith("/api/v2/yarns") or path.startswith("/api/v2/categories")
        ):
            code: str | None = None
            try:
                body = response.json()
                error = body.get("error") if isinstance(body, dict) else None
                candidate = error.get("code") if isinstance(error, dict) else None
                if isinstance(candidate, str):
                    code = candidate
            except Exception:
                pass
            observed_api_errors.append(
                (response.request.method, response.status, path, code)
            )
        if response.status >= 400:
            resource_failures.append(
                f"HTTP {response.status} {resource_category(path)}"
            )

    def observe_request(request: object) -> None:
        path = urlsplit(getattr(request, "url", "")).path
        method = getattr(request, "method", "")
        if path.startswith("/api/v2/yarns") and method in {
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
        }:
            observed_mutation_requests.append((method, path))

    def observe_console(message: object) -> None:
        if getattr(message, "type", "") != "error":
            return
        text = getattr(message, "text", "").lower()
        if "content security policy" in text or (
            "violat" in text and ("script-src" in text or "style-src" in text)
        ):
            csp_errors.append(True)
        else:
            category = (
                "http-conflict"
                if "409" in text
                else "resource-load"
                if "failed to load resource" in text
                else "network"
                if "net::err" in text
                else "http-not-found"
                if "404" in text
                else "uncaught-exception"
                if "uncaught" in text or "unhandled" in text
                else "other-console-error"
            )
            console_errors.append(category)

    def observe_page_error(_error: object) -> None:
        page_errors.append(True)

    with sync_playwright() as playwright:
        mark_stage("launching Chromium")
        try:
            browser = playwright.chromium.launch(
                headless=True,
                executable_path=(
                    str(chromium_executable)
                    if chromium_executable is not None
                    else None
                ),
            )
        except Exception as error:
            raise AcceptanceFailure("configured Chromium could not start") from error
        try:
            context = browser.new_context(
                viewport={"width": 390, "height": 844},
                reduced_motion="reduce",
                color_scheme="light",
                locale="en-US",
            )
            page = context.new_page()
            page.on("response", observe_response)
            page.on("request", observe_request)
            page.on("console", observe_console)
            page.on("pageerror", observe_page_error)

            mark_stage("checking strict CSP and sign-in page")
            response = page.goto(f"{origin}/app/login", wait_until="networkidle")
            if response is None or response.status != 200:
                raise AcceptanceFailure("login route failed")
            mark_stage("choosing English for the yarn journey")
            page.get_by_role("button", name=re.compile(r"^(English|Englisch)$")).click()
            page.get_by_role("heading", name="Sign in").wait_for(state="visible")
            policy = response.headers.get("content-security-policy", "")
            _require(
                "default-src 'self'" in policy
                and "script-src 'self'" in policy
                and "style-src 'self'" in policy
                and "unsafe-inline" not in policy
                and "unsafe-eval" not in policy
                and "style-src-attr 'unsafe-hashes'" in policy,
                "strict frontend CSP or the scoped announcer hash is missing",
            )
            page.locator("#email").fill(email)
            page.locator("#password").fill(password)
            page.get_by_role("button", name="Sign in").click()
            mark_stage("waiting for signed-in project library")
            page.wait_for_url("**/app/projects")
            page.get_by_role("heading", name="Projects", exact=True).wait_for(
                state="visible"
            )
            page.locator(".app-shell").wait_for(state="visible")
            page.locator(".mobile-navigation").wait_for(state="visible")
            _require(
                urlsplit(page.url).path == "/app/projects",
                "sign-in did not open the project library",
            )

            mark_stage("checking yarn library search and favorite filtering")
            page.goto(f"{origin}/app/yarns", wait_until="networkidle")
            mark_stage("locating seeded yarn row")
            yarn_card = page.locator(".yarn-card").filter(
                has=page.get_by_role("heading", name=seed_rust.YARN_NAME, exact=True)
            )
            yarn_card.wait_for(state="visible")
            mark_stage("typing yarn search")
            page.get_by_role("searchbox", name="Search your yarn").fill("Riverbend")
            mark_stage("waiting for debounced yarn query")
            page.wait_for_url(
                lambda url: (
                    urlsplit(url).path == "/app/yarns"
                    and parse_qs(urlsplit(url).query).get("q") == ["Riverbend"]
                )
            )
            mark_stage("checking filtered yarn result")
            yarn_card.wait_for(state="visible")
            _require(
                page.locator(".yarn-card").count() == 1,
                "yarn search returned the wrong result count",
            )
            yarn_detail_href = yarn_card.locator("a.resource-card__link").get_attribute(
                "href"
            )
            _require(yarn_detail_href is not None, "yarn detail link is missing")
            mark_stage("opening seeded yarn details")
            yarn_card.locator("a.resource-card__link").click()
            page.wait_for_url(f"**{yarn_detail_href}")
            favorite_button = page.get_by_role(
                "button", name="Add to favorites", exact=True
            )
            _require(
                favorite_button.get_attribute("aria-pressed") == "false",
                "the yarn favorite control did not start unpressed",
            )
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path.endswith("/favorite")
                    and response.request.method == "PUT"
                )
            ) as favorite_response:
                mark_stage("saving yarn favorite")
                favorite_button.click()
            _require(
                favorite_response.value.status == 200,
                "saving the yarn favorite failed against API v2",
            )
            favorited_button = page.get_by_role(
                "button", name="Remove from favorites", exact=True
            )
            favorited_button.wait_for(state="visible")
            _require(
                favorited_button.get_attribute("aria-pressed") == "true",
                "the yarn detail favorite control did not enter its pressed state",
            )
            mark_stage("returning to searched yarn library")
            page.go_back(wait_until="domcontentloaded")
            page.wait_for_url("**/app/yarns?q=Riverbend")
            yarn_card = page.locator(".yarn-card").filter(
                has=page.get_by_role("heading", name=seed_rust.YARN_NAME, exact=True)
            )
            mark_stage("checking favorite indicator")
            yarn_card.get_by_role(
                "button", name="Remove from favorites", exact=True
            ).wait_for(state="visible")
            filter_trigger = page.locator(".filter-trigger")
            mark_stage("opening yarn filters")
            filter_trigger.click()
            filter_dialog = page.get_by_role("dialog")
            filter_dialog.wait_for(state="visible")
            favorite_filter = filter_dialog.get_by_role(
                "checkbox", name="Favorites only"
            )
            mark_stage("selecting favorites only")
            favorite_filter.check()
            mark_stage("waiting for favorite query")
            page.wait_for_url(
                lambda url: (
                    urlsplit(url).path == "/app/yarns"
                    and parse_qs(urlsplit(url).query)
                    == {"q": ["Riverbend"], "favorite": ["true"]}
                )
            )
            mark_stage("closing yarn filters")
            filter_dialog.get_by_role("button", name="Close filters").click()
            mark_stage("checking favorite-filtered result")
            yarn_card.wait_for(state="visible")
            _require(
                any(
                    path == "/api/v2/yarns" and status == 200
                    for _, status, path in observed
                ),
                "the yarn list did not load from API v2",
            )
            for width in (320, 390, 1280):
                page.set_viewport_size({"width": width, "height": 844})
                _assert_no_overflow(page, width)
                heights = _visible_heights(
                    page,
                    ".mobile-navigation a, .secondary-nav a, "
                    ".desktop-create, .filter-trigger",
                )
                _require(
                    not heights or min(heights) >= 44,
                    "a yarn-library target is below 44px",
                )
            page.set_viewport_size({"width": 390, "height": 844})
            _mask_fixture_identity(page)
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "yarn-library-mobile.png"), caret="initial"
            )

            mark_stage("opening yarn detail from library")
            page.get_by_role("link", name=seed_rust.YARN_NAME).click()
            mark_stage("waiting for yarn detail heading")
            page.get_by_role("heading", name=seed_rust.YARN_NAME, exact=True).wait_for(
                state="visible"
            )
            mark_stage("waiting for the used-in-projects section")
            page.get_by_role("heading", name="Used in projects").wait_for(
                state="visible"
            )
            mark_stage("waiting for the linked project card")
            project_card = page.locator(".project-list .resource-card").filter(
                has=page.get_by_text(seed_rust.PROJECT_NAME, exact=True)
            )
            project_link = project_card.get_by_role("link", name=seed_rust.PROJECT_NAME)
            project_link.wait_for(state="visible", timeout=10_000)
            project_href = project_link.get_attribute("href") or ""
            project_url = urlsplit(project_href)
            project_route_prefix = "/app/projects/"
            project_id_text = (
                project_url.path.removeprefix(project_route_prefix)
                if project_url.path.startswith(project_route_prefix)
                else ""
            )
            project_id_valid = project_id_text.isdigit() and int(project_id_text) > 0
            _require(
                project_card.count() == 1
                and project_link.count() == 1
                and project_link.evaluate("element => element.tagName === 'A'")
                and project_href.startswith("/")
                and not project_url.scheme
                and not project_url.netloc
                and project_id_valid,
                "the yarn detail project card link did not match its project",
            )
            detail_url = page.url
            detail_path = urlsplit(detail_url).path
            yarn_id_text = detail_path.removeprefix("/app/yarns/")
            _require(
                yarn_id_text.isdigit(), "yarn detail route did not expose a numeric ID"
            )
            yarn_id = int(yarn_id_text)
            for width in (320, 390, 1280):
                page.set_viewport_size({"width": width, "height": 844})
                _assert_no_overflow(page, width)
            page.set_viewport_size({"width": 320, "height": 844})
            page.locator(".reorder-item .thumb-button").first.wait_for(state="visible")
            page.locator(".reorder-item .thumb-button").first.click()
            zoom_dialog = page.get_by_role("dialog", name="Yarn photo")
            zoom_dialog.wait_for(state="visible")
            page.keyboard.press("Escape")
            zoom_dialog.wait_for(state="hidden")
            detail_remove_controls = page.locator(
                ".reorder-item button[aria-label='Remove photo']"
            )
            _require(
                page.get_by_label("Choose a yarn photo", exact=True).count() == 0
                and detail_remove_controls.count() == 0,
                "yarn detail exposed photo mutation controls outside Edit yarn",
            )

            mark_stage("opening yarn editor for photo management")
            page.get_by_role("link", name="Edit yarn", exact=True).click()
            page.wait_for_url(f"**/app/yarns/{yarn_id}/edit")
            page.get_by_role("heading", name="Edit yarn", exact=True).wait_for(
                state="visible"
            )
            _require(
                page.get_by_label("Choose a yarn photo", exact=True).is_enabled(),
                "the clean yarn editor did not enable photo management",
            )

            mark_stage("testing yarn photo upload and delete")
            previous_photo_urls = page.locator(".reorder-item img").evaluate_all(
                "images => images.map(image => image.currentSrc)"
            )
            before_photo_count = len(previous_photo_urls)
            upload = page.get_by_label("Choose a yarn photo")
            demo_photo = (
                Path(__file__).resolve().parents[2] / "demo_assets" / "demo_image_1.jpg"
            )
            mark_stage("submitting a new yarn photo")
            upload.set_input_files(str(demo_photo))
            mark_stage("waiting for the yarn photo upload result")
            try:
                page.get_by_role("status").filter(has_text="Photo uploaded.").wait_for(
                    state="visible", timeout=15_000
                )
            except PlaywrightTimeoutError:
                upload_status = next(
                    (
                        status
                        for _method, status, path in reversed(observed)
                        if path.startswith("/api/v2/yarns/")
                        and path.endswith("/photos")
                    ),
                    None,
                )
                upload_error_visible = page.locator(".error-message").is_visible()
                photo_count = page.locator(".reorder-item").count()
                raise AcceptanceFailure(
                    "yarn photo upload did not complete "
                    f"(API status {upload_status or 'not observed'}; "
                    f"visible error {upload_error_visible}; "
                    f"photo count {photo_count})"
                ) from None
            mark_stage("waiting for uploaded photo in the gallery")
            page.wait_for_function(
                "previous => document.querySelectorAll('.reorder-item').length "
                "> previous",
                arg=before_photo_count,
            )
            new_photo_index = page.locator(".reorder-item img").evaluate_all(
                "(images, previous) => images.findIndex(image => "
                "!previous.includes(image.currentSrc))",
                previous_photo_urls,
            )
            _require(
                isinstance(new_photo_index, int) and new_photo_index >= 0,
                "uploaded photo did not appear in the yarn gallery",
            )
            new_photo = page.locator(".reorder-item").nth(new_photo_index)
            mark_stage("opening photo removal confirmation")
            new_photo.hover()
            new_photo.get_by_role("button", name="Remove photo").click()
            photo_dialog = page.get_by_role("dialog", name="Remove this photo?")
            mark_stage("waiting for photo removal confirmation")
            photo_dialog.wait_for(state="visible")
            mark_stage("cancelling the photo removal confirmation")
            page.keyboard.press("Escape")
            photo_dialog.wait_for(state="hidden")
            mark_stage("reopening photo removal confirmation")
            new_photo.get_by_role("button", name="Remove photo").click()
            photo_dialog.wait_for(state="visible")
            mark_stage("confirming photo removal")
            photo_dialog.get_by_role("button", name="Remove photo").click()
            mark_stage("waiting for yarn photo removal result")
            try:
                page.get_by_role("status").filter(has_text="Photo removed.").wait_for(
                    state="visible", timeout=15_000
                )
            except PlaywrightTimeoutError:
                remove_status = next(
                    (
                        status
                        for _method, status, path in reversed(observed)
                        if path.startswith("/api/v2/yarns/") and "/photos/" in path
                    ),
                    None,
                )
                raise AcceptanceFailure(
                    "yarn photo removal did not complete "
                    f"(API status {remove_status or 'not observed'})"
                ) from None
            page.locator(".reorder-item").wait_for(state="visible")
            _require(
                any(
                    "/photos" in path and status in {200, 201, 204}
                    for _, status, path in observed
                ),
                "yarn photo upload or removal did not reach API v2",
            )

            mark_stage("checking yarn dirty-navigation guard and CAS conflict")
            mark_stage("waiting for yarn editor brand field")
            page.get_by_label("Brand").wait_for(state="visible")
            brand = page.get_by_label("Brand")
            mark_stage("entering dirty yarn draft")
            brand.fill("Draft brand")
            yarn_parent = page.locator(".app-header .brand-lockup.parent-link")
            parent_bounds = (
                yarn_parent.bounding_box() if yarn_parent.count() == 1 else None
            )
            _require(
                yarn_parent.count() == 1
                and yarn_parent.get_attribute("aria-label") == "Back"
                and yarn_parent.get_attribute("href") == f"/app/yarns/{yarn_id}"
                and parent_bounds is not None
                and parent_bounds["width"] >= 44
                and parent_bounds["height"] >= 44,
                "the yarn editor header parent link has the wrong route or size",
            )
            mark_stage("requesting cancellation of dirty yarn navigation")
            web_ui_browser._click_and_handle_dialog(page, yarn_parent, accept=False)
            mark_stage("checking retained yarn draft after cancellation")
            page.get_by_role("heading", name="Edit yarn", exact=True).wait_for(
                state="visible"
            )
            _require(
                brand.input_value() == "Draft brand",
                "dirty navigation discarded the yarn draft",
            )
            mark_stage("accepting dirty yarn navigation")
            web_ui_browser._click_and_handle_dialog(page, yarn_parent, accept=True)
            mark_stage("waiting for yarn details after accepted navigation")
            page.get_by_role("heading", name=seed_rust.YARN_NAME, exact=True).wait_for(
                state="visible"
            )
            mark_stage("reopening yarn editor for CAS conflict")
            page.get_by_role("link", name="Edit yarn").click()
            mark_stage("waiting for CAS editor brand field")
            page.get_by_label("Brand").wait_for(state="visible")
            brand = page.get_by_label("Brand")
            mark_stage("checking one keyboard shortcut save on the yarn editor")
            brand.fill("Shortcut saved draft")
            yarn_write_count = len(observed_mutation_requests)
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == f"/api/v2/yarns/{yarn_id}"
                    and response.request.method == "PUT"
                )
            ) as shortcut_save_response_info:
                page.keyboard.press("Control+s")
            _require(
                shortcut_save_response_info.value.status == 200,
                "Ctrl+S did not save the yarn through API v2",
            )
            _require(
                len(observed_mutation_requests) == yarn_write_count + 1,
                "Ctrl+S submitted more than one yarn save request",
            )
            page.wait_for_url(f"**/app/yarns/{yarn_id}")
            page.locator(".action-feedback[role='status']").filter(
                has_text="Yarn changes saved."
            ).wait_for(state="visible")
            page.get_by_role("link", name="Edit yarn", exact=True).click()
            page.wait_for_url(f"**/app/yarns/{yarn_id}/edit")
            page.get_by_role("heading", name="Edit yarn", exact=True).wait_for(
                state="visible"
            )
            brand = page.get_by_label("Brand")
            _require(
                brand.input_value() == "Shortcut saved draft",
                "the post-save detail/editor round-trip lost the keyboard-saved yarn",
            )
            mark_stage("entering draft for CAS conflict")
            brand.fill("Draft brand")
            mark_stage("applying concurrent yarn update")
            _require(
                _observe_fixture_update(page, yarn_id) == 200,
                "concurrent yarn update failed",
            )
            mark_stage("submitting stale yarn version")
            page.get_by_role("button", name="Save changes").click()
            mark_stage("waiting for yarn CAS conflict dialog")
            page.get_by_role("heading", name="A newer version was saved").wait_for(
                state="visible"
            )
            _require(
                _has_one_api_conflict(
                    observed_api_errors, "PUT", f"/api/v2/yarns/{yarn_id}"
                ),
                "yarn save did not return one API v2 conflict",
            )
            _require(
                brand.input_value() == "Draft brand",
                "CAS conflict lost the local draft",
            )
            page.get_by_role("button", name="Save my draft over latest").click()
            page.wait_for_url(f"**/app/yarns/{yarn_id}")
            page.locator(".action-feedback[role='status']").filter(
                has_text="Yarn changes saved."
            ).wait_for(state="visible")
            page.get_by_role("link", name="Edit yarn", exact=True).click()
            page.wait_for_url(f"**/app/yarns/{yarn_id}/edit")
            page.get_by_role("heading", name="Edit yarn", exact=True).wait_for(
                state="visible"
            )
            brand = page.get_by_label("Brand")
            _require(
                brand.input_value() == "Draft brand",
                "successful CAS retry changed the draft",
            )

            mark_stage("creating a yarn and validating required-field feedback")
            page.goto(f"{origin}/app/yarns/new", wait_until="networkidle")
            name_field = page.get_by_label("Yarn name")
            name_field.wait_for(state="visible")
            yarn_optional_details = page.locator(".optional-details")
            _require(
                yarn_optional_details.count() == 1
                and yarn_optional_details.get_attribute("open") is None,
                "new yarn details should start collapsed",
            )
            web_ui_browser._assert_sticky_save_cancel_geometry(page, "Save yarn")
            page.get_by_role("button", name="Save yarn").click()
            page.get_by_text("Enter a yarn name.", exact=True).wait_for(state="visible")
            name_field.fill("New browser yarn")
            page.get_by_label("Brand").fill("North Shore Fibers")
            yarn_optional_details.locator("summary").click()
            _require(
                yarn_optional_details.get_attribute("open") is not None,
                "More yarn details did not open when needed",
            )
            page.get_by_label("Fiber content").fill("80% wool, 20% nylon")
            page.get_by_label("Yarn weight").fill("Sport")
            page.get_by_label("Skein weight in grams").fill("-4")
            page.get_by_role("button", name="Save yarn").click()
            page.get_by_text(
                "Enter a whole number of zero or more.", exact=True
            ).wait_for(state="visible")
            page.get_by_label("Skein weight in grams").fill("100")
            page.get_by_label("Skein length in meters").fill("250")
            page.get_by_label("Yarn source URL").fill("javascript:alert(1)")
            page.get_by_role("button", name="Save yarn").click()
            page.get_by_text(
                "Enter a complete http or https URL.", exact=True
            ).wait_for(state="visible")
            page.get_by_label("Yarn source URL").fill(
                "https://example.invalid/new-yarn"
            )
            page.get_by_role("button", name="Save yarn").click()
            page.get_by_role("heading", name="New browser yarn", exact=True).wait_for(
                state="visible"
            )
            page.locator(".action-feedback[role='status']").filter(
                has_text="Yarn created."
            ).wait_for(state="visible")
            created_id_path = urlsplit(page.url).path.removeprefix("/app/yarns/")
            _require(
                created_id_path.isdigit(), "created yarn did not open its detail route"
            )
            _require(
                page.get_by_text("100 g", exact=True).count() == 1
                and page.get_by_text("250 m", exact=True).count() == 1,
                "created yarn measurements were not rendered",
            )

            mark_stage("checking yarn deletion confirmation and deletion")
            page.get_by_role("link", name="Edit yarn", exact=True).click()
            page.wait_for_url(f"**/app/yarns/{created_id_path}/edit")
            page.get_by_role("heading", name="Edit yarn", exact=True).wait_for(
                state="visible"
            )
            yarn_optional_details = page.locator(".optional-details")
            _require(
                yarn_optional_details.count() == 1
                and yarn_optional_details.get_attribute("open") is not None,
                "More yarn details should be expanded while editing",
            )
            delete_button = page.get_by_role("button", name="Delete yarn", exact=True)
            delete_button.click()
            delete_dialog = page.get_by_role("dialog", name="Delete yarn?")
            delete_dialog.wait_for(state="visible")
            page.keyboard.press("Escape")
            delete_dialog.wait_for(state="hidden")
            delete_button.click()
            delete_dialog.wait_for(state="visible")
            delete_dialog.get_by_role("button", name="Keep yarn").click()
            delete_dialog.wait_for(state="hidden")
            delete_button.click()
            delete_dialog.wait_for(state="visible")
            delete_action = delete_dialog.locator("button.ui-button--danger")
            _require(
                delete_action.count() == 1
                and delete_dialog.get_by_role(
                    "button", name="Delete yarn", exact=True
                ).count()
                == 1,
                "yarn confirmation does not have one named delete action",
            )

            yarn_delete_url = f"**/api/v2/yarns/{created_id_path}"
            held_yarn_deletes: list[Route] = []

            def hold_yarn_delete(route: Route) -> None:
                if route.request.method == "DELETE":
                    held_yarn_deletes.append(route)
                else:
                    route.continue_()

            page.route(yarn_delete_url, hold_yarn_delete)
            delete_request_count = len(observed_mutation_requests)
            keep_action = delete_dialog.get_by_role(
                "button", name="Keep yarn", exact=True
            )
            delete_action.click()
            expect(delete_action).to_be_disabled()
            expect(keep_action).to_be_disabled()
            _require(
                len(held_yarn_deletes) == 1
                and len(observed_mutation_requests) == delete_request_count + 1
                and observed_mutation_requests[-1]
                == ("DELETE", f"/api/v2/yarns/{created_id_path}")
                and delete_dialog.is_visible()
                and delete_action.is_disabled()
                and keep_action.is_disabled(),
                "yarn confirmation did not disable both actions for one pending delete",
            )
            page.keyboard.press("Escape")
            delete_dialog.wait_for(state="visible")
            page.keyboard.press("Enter")
            web_ui_browser._wait_two_animation_frames(page)
            _require(
                len(observed_mutation_requests) == delete_request_count + 1,
                "a busy yarn confirmation closed or sent a duplicate delete",
            )
            held_yarn_deletes[0].fulfill(
                status=503,
                content_type="application/json",
                body=(
                    '{"error":{"code":"internal_error",'
                    '"message":"The server could not complete this request. '
                    'Try again."}}'
                ),
            )
            page.unroute(yarn_delete_url, hold_yarn_delete)
            page.get_by_role("alert").filter(
                has_text="The server could not complete this request. Try again."
            ).first.wait_for(state="visible")
            _require(
                delete_dialog.is_visible()
                and not delete_action.is_disabled()
                and not keep_action.is_disabled()
                and page.locator("#yarn-name").input_value() == "New browser yarn",
                "failed yarn deletion lost the draft or left confirmation busy",
            )
            keep_action.click()
            delete_dialog.wait_for(state="hidden")
            delete_button.click()
            delete_dialog.wait_for(state="visible")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == f"/api/v2/yarns/{created_id_path}"
                    and response.request.method == "DELETE"
                )
            ) as delete_response_info:
                delete_action.click()
            _require(
                delete_response_info.value.status == 204,
                "yarn deletion did not complete with 204 from API v2",
            )
            page.wait_for_url("**/app/yarns")
            page.get_by_role("searchbox", name="Search your yarn").wait_for(
                state="visible"
            )
            page.get_by_role("searchbox", name="Search your yarn").fill(
                "New browser yarn"
            )
            page.wait_for_url("**/app/yarns?q=New+browser+yarn")
            page.get_by_role("heading", name="No yarn matches those filters.").wait_for(
                state="visible"
            )

            mark_stage(
                "checking category create, duplicate rename conflict, and delete"
            )
            page.goto(f"{origin}/app/categories", wait_until="networkidle")
            mark_stage("waiting for category list")
            page.get_by_role("heading", name="Categories", exact=True).wait_for(
                state="visible"
            )
            page.get_by_text(seed_rust.CATEGORY_NAME, exact=True).wait_for(
                state="visible"
            )
            category_cdp = context.new_cdp_session(page)
            category_cdp.send("Network.enable")
            category_cdp.send(
                "Network.emulateNetworkConditions",
                {
                    "offline": False,
                    "latency": 900,
                    "downloadThroughput": 1_000_000,
                    "uploadThroughput": 1_000_000,
                    "connectionType": "cellular3g",
                },
            )
            new_category = page.get_by_label("New category")
            new_category.fill("Browser category")
            mark_stage("creating category")
            page.get_by_role("button", name="Add category").click()
            _require(
                new_category.is_disabled(),
                "new category input remained editable while save was pending",
            )
            page.get_by_text("Browser category", exact=True).wait_for(state="visible")
            _require(
                new_category.input_value() == "",
                "successful category create did not clear the submitted name",
            )
            category_cdp.send(
                "Network.emulateNetworkConditions",
                {
                    "offline": False,
                    "latency": 0,
                    "downloadThroughput": -1,
                    "uploadThroughput": -1,
                },
            )
            category_cdp.detach()
            category_row = page.locator(".category-row").filter(
                has_text="Browser category"
            )
            mark_stage("opening category rename")
            category_row.get_by_role("button", name="Rename").click()
            category_form = page.locator("form.rename-form")
            edit_category = category_form.get_by_role("textbox", name="Category name")
            edit_category.wait_for(state="visible")
            edit_category.fill(seed_rust.CATEGORY_NAME)
            mark_stage("submitting duplicate category rename")
            category_save = category_form.get_by_role("button", name="Save", exact=True)
            try:
                category_save.click(timeout=5_000)
            except PlaywrightTimeoutError:
                raise AcceptanceFailure(
                    "duplicate category rename submit control did not activate "
                    f"(visible {category_save.is_visible()}; "
                    f"enabled {category_save.is_enabled()})"
                ) from None
            mark_stage("waiting for duplicate category rename conflict")
            try:
                page.locator(".rename-form [role=alert]").filter(
                    has_text="A category with this name already exists."
                ).wait_for(state="visible", timeout=10_000)
            except PlaywrightTimeoutError:
                conflict_status = next(
                    (
                        status
                        for method, status, path in reversed(observed)
                        if method == "PUT" and path.startswith("/api/v2/categories/")
                    ),
                    None,
                )
                field_alert_visible = page.locator(
                    ".rename-form [role=alert]"
                ).is_visible()
                raise AcceptanceFailure(
                    "duplicate category rename did not show field feedback "
                    f"(API status {conflict_status or 'not observed'}; "
                    f"field alert visible {field_alert_visible})"
                ) from None
            except Exception as error:
                error_text = str(error).lower()
                lookup_failure = (
                    "ambiguous text locator"
                    if "strict mode violation" in error_text
                    else "unexpected browser lookup error"
                )
                conflict_status = next(
                    (
                        status
                        for method, status, path in reversed(observed)
                        if method == "PUT" and path.startswith("/api/v2/categories/")
                    ),
                    None,
                )
                field_alert_visible = page.locator(
                    ".rename-form [role=alert]"
                ).is_visible()
                raise AcceptanceFailure(
                    "duplicate category rename feedback lookup failed "
                    f"({lookup_failure}; API status "
                    f"{conflict_status or 'not observed'}; "
                    f"field alert visible {field_alert_visible})"
                ) from None
            category_row_id = category_form.locator("input").get_attribute("id")
            if category_row_id is None or not category_row_id.startswith("category-"):
                raise AcceptanceFailure("category rename form has no stable row id")
            category_id = category_row_id.removeprefix("category-")
            _require(
                category_id.isdigit()
                and _has_one_api_conflict(
                    observed_api_errors,
                    "PUT",
                    f"/api/v2/categories/{category_id}",
                ),
                "category rename did not return one API v2 conflict",
            )
            mark_stage("checking preserved duplicate category name")
            _require(
                edit_category.input_value() == seed_rust.CATEGORY_NAME,
                "category conflict lost the attempted name",
            )
            edit_category.fill("Fresh browser category")
            mark_stage("saving category rename")
            category_cdp = context.new_cdp_session(page)
            category_cdp.send("Network.enable")
            category_cdp.send(
                "Network.emulateNetworkConditions",
                {
                    "offline": False,
                    "latency": 900,
                    "downloadThroughput": 1_000_000,
                    "uploadThroughput": 1_000_000,
                    "connectionType": "cellular3g",
                },
            )
            category_form.get_by_role("button", name="Save", exact=True).click()
            _require(
                edit_category.is_disabled(),
                "category rename input remained editable while save was pending",
            )
            page.get_by_text("Fresh browser category", exact=True).wait_for(
                state="visible"
            )
            category_cdp.send(
                "Network.emulateNetworkConditions",
                {
                    "offline": False,
                    "latency": 0,
                    "downloadThroughput": -1,
                    "uploadThroughput": -1,
                },
            )
            category_cdp.detach()
            category_row = page.locator(".category-row").filter(
                has_text="Fresh browser category"
            )
            mark_stage("opening category delete confirmation")
            category_row.get_by_role("button", name="Delete").click()
            category_dialog = page.get_by_role("dialog", name="Delete category?")
            category_dialog.wait_for(state="visible")
            mark_stage("cancelling category delete confirmation")
            page.keyboard.press("Escape")
            category_dialog.wait_for(state="hidden")
            mark_stage("reopening category delete confirmation")
            category_row.get_by_role("button", name="Delete").click()
            category_dialog.wait_for(state="visible")
            mark_stage("keeping category after confirmation")
            category_dialog.get_by_role("button", name="Keep category").click()
            category_dialog.wait_for(state="hidden")
            mark_stage("confirming category deletion")
            category_row.get_by_role("button", name="Delete").click()
            category_dialog.wait_for(state="visible")
            category_dialog.get_by_role("button", name="Delete category").click()
            page.get_by_role("status").filter(has_text="Category deleted.").wait_for(
                state="visible"
            )
            _require(
                any(
                    path == "/api/v2/categories" and status == 200
                    for _, status, path in observed
                )
                and any(
                    path == "/api/v2/categories" and status == 201
                    for _, status, path in observed
                )
                and any(
                    path.startswith("/api/v2/categories/") and status == 409
                    for _, status, path in observed
                )
                and any(
                    path.startswith("/api/v2/categories/") and status == 204
                    for _, status, path in observed
                ),
                "category list/create/conflict/delete did not complete through API v2",
            )

            for width in (320, 390, 1280):
                page.set_viewport_size({"width": width, "height": 844})
                _assert_no_overflow(page, width)
                heights = _visible_heights(
                    page,
                    ".mobile-navigation a, .desktop-navigation a, "
                    ".primary-action, .quiet-button, .danger-button",
                )
                _require(
                    not heights or min(heights) >= 44, "a category target is below 44px"
                )
            page.set_viewport_size({"width": 390, "height": 844})
            _mask_fixture_identity(page)
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "categories-mobile.png"), caret="initial"
            )

            _require(not csp_errors, "browser reported a CSP violation")
            _require(not page_errors, "browser reported a page error")
            expected_test_error_console = (
                sorted(console_errors)
                == ["http-conflict", "http-conflict", "resource-load"]
                and len([error for error in observed_api_errors if error[1] == 409])
                == 2
                and [
                    (method, path, code)
                    for method, status, path, code in observed_api_errors
                    if status == 503
                ]
                == [("DELETE", f"/api/v2/yarns/{created_id_path}", "internal_error")]
                and sorted(resource_failures)
                == [
                    "HTTP 409 category-api",
                    "HTTP 409 yarn-api",
                    "HTTP 503 yarn-api",
                ]
            )
            unexpected_console_errors = (
                [] if expected_test_error_console else console_errors
            )
            _require(
                not unexpected_console_errors,
                "browser reported unexpected console errors "
                f"({', '.join(sorted(set(unexpected_console_errors)))}; "
                f"resource failures "
                f"{', '.join(sorted(set(resource_failures))) or 'none'})",
            )
            mark_stage("browser acceptance complete")
            context.close()
        finally:
            for screenshot in screenshots.glob("*.png"):
                screenshot.chmod(0o600)
            browser.close()

    return screenshots


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path)
    parser.add_argument("--chromium-executable", type=Path)
    args = parser.parse_args(argv)
    private_dir: Path | None = None
    try:
        private_dir = web_ui_browser._private_work_dir(args.private_dir)
        screenshots = run_live_fixture(
            args.binary,
            args.web_root,
            private_dir,
            args.chromium_executable,
        )
    except (rust_fixture.FixtureError, AcceptanceFailure) as error:
        print(f"Yarn/category browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        stage_file = (
            private_dir / "yarn-browser-stage.txt" if private_dir is not None else None
        )
        stage = (
            stage_file.read_text(encoding="ascii")
            if stage_file is not None and stage_file.is_file()
            else None
        )
        print(
            "Yarn/category browser acceptance failed"
            + (f" during {stage}" if stage else "")
            + f" ({type(error).__name__})"
            + "; diagnostic details were suppressed.",
            file=sys.stderr,
        )
        return 1
    print(
        "Yarn/category browser acceptance passed: API v2, media, CAS, "
        "dialogs, and responsive routes."
    )
    print(f"Sanitized fixture screenshots: {screenshots}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
