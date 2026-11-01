#!/usr/bin/env python3
"""Run sanitized browser acceptance checks against the disposable Rust fixture."""

from __future__ import annotations

import argparse
import ipaddress
import os
import sys
import tempfile
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from playwright.sync_api import Page, Response, sync_playwright
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

from ci.stricknani import rust_fixture, seed_rust


class AcceptanceFailure(RuntimeError):
    """A safe-to-display browser assertion failure."""


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
    ):
        raise AcceptanceFailure("fixture URL must be plain HTTP on loopback")
    if port is None or not 1 <= port <= 65535:
        raise AcceptanceFailure("fixture URL has no valid port")
    return f"http://127.0.0.1:{port}"


def _private_screenshot_dir(path: Path | None) -> Path:
    if path is None:
        target = Path(tempfile.mkdtemp(prefix="stricknani-web-ui-"))
    else:
        target = rust_fixture._outside_repository(path, "screenshot directory")
        target.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = rust_fixture._outside_repository(target, "screenshot directory")
    if not target.is_dir():
        raise AcceptanceFailure("screenshot path must be a directory")
    target.chmod(0o700)
    return target


def _private_work_dir(path: Path | None) -> Path:
    if path is None:
        target = Path(tempfile.mkdtemp(prefix="stricknani-web-ui-"))
    else:
        target = rust_fixture._outside_repository(path, "private work directory")
        target.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = rust_fixture._outside_repository(target, "private work directory")
    if not target.is_dir():
        raise AcceptanceFailure("private work path must be a directory")
    target.chmod(0o700)
    return target


def _response_path(response: Response) -> str:
    return urlsplit(response.url).path


def _prepare_project_reader_fixture(page: Page) -> int:
    result: object = page.evaluate(
        """async () => {
          const sessionResponse = await fetch('/api/v2/session');
          if (!sessionResponse.ok) return { status: sessionResponse.status, id: 0 };
          const sessionEnvelope = await sessionResponse.json();
          const token = sessionEnvelope?.data?.csrf_token;
          const listResponse = await fetch('/api/v2/projects');
          if (!listResponse.ok) return { status: listResponse.status, id: 0 };
          const listEnvelope = await listResponse.json();
          const id = listEnvelope?.data?.[0]?.id;
          if (!Number.isSafeInteger(id) || id < 1 || typeof token !== 'string') {
            return { status: 0, id: 0 };
          }
          const detailResponse = await fetch(`/api/v2/projects/${id}`);
          if (!detailResponse.ok) return { status: detailResponse.status, id: 0 };
          const detailEnvelope = await detailResponse.json();
          const project = detailEnvelope?.data;
          const updateResponse = await fetch(`/api/v2/projects/${id}`, {
            method: 'PUT',
            headers: {
              'accept': 'application/json',
              'content-type': 'application/json',
              'x-csrf-token': token,
            },
            body: JSON.stringify({
              name: project.name,
              category: project.category,
              needles: '4 mm circular needles',
              stitch_sample: '18 stitches and 24 rows over 10 cm',
              description: 'Knit a soft **textured blanket** with broad, even bands.',
              notes: 'After the border, pause and check the width before continuing.',
              other_materials: 'Two stitch markers and a tapestry needle.',
              tags: ['heirloom', 'textured'],
              link: project.link,
              is_ai_enhanced: false,
              yarn_ids: project.yarn_ids,
              steps: [
                {
                  title: 'Cast on and knit the border',
                  description: 'Cast on 120 stitches and work the lower border.',
                  step_number: 1,
                },
                {
                  title: 'Work the textured panel',
                  description: 'Repeat the four-row pattern to length.',
                  step_number: 2,
                },
              ],
              expected_updated_at: project.updated_at,
            }),
          });
          return { status: updateResponse.status, id };
        }"""
    )
    if (
        not isinstance(result, dict)
        or result.get("status") != 200
        or not isinstance(result.get("id"), int)
        or isinstance(result.get("id"), bool)
        or result["id"] < 1
    ):
        raise AcceptanceFailure("the synthetic project fixture could not be prepared")
    return int(result["id"])


def _assert_no_horizontal_overflow(page: Page, viewport_width: int) -> None:
    dimensions = page.evaluate(
        """() => ({
          viewport: window.innerWidth,
          document: document.documentElement.scrollWidth,
          body: document.body.scrollWidth
        })"""
    )
    _require(
        dimensions["document"] <= viewport_width
        and dimensions["body"] <= viewport_width
        and dimensions["viewport"] == viewport_width,
        "a checked viewport has horizontal overflow",
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
        raise AcceptanceFailure("browser returned invalid navigation target sizes")
    return [float(value) for value in values]


def _mask_fixture_identity(page: Page) -> None:
    """Mask disposable account identifiers before screenshot capture."""
    page.locator(".header-user").evaluate_all(
        "nodes => nodes.forEach(node => node.textContent = 'Demo account')"
    )
    page.locator(".account-email").evaluate_all(
        "nodes => nodes.forEach(node => { "
        "node.textContent = 'Demo account'; node.title = 'Demo account'; })"
    )
    page.locator(".avatar").evaluate_all(
        "nodes => nodes.forEach(node => node.textContent = 'D')"
    )
    page.locator("#email, #password").evaluate_all(
        "nodes => nodes.forEach(node => { node.value = ''; })"
    )


def _prepare_screenshot(page: Page) -> None:
    """Capture the viewport from the top without a focused skip-link overlay."""
    page.evaluate(
        "() => { window.scrollTo(0, 0); "
        "if (document.activeElement instanceof HTMLElement) "
        "document.activeElement.blur(); }"
    )


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    chromium_executable: Path | None = None,
) -> Path:
    """Start, exercise, and safely stop one disposable server fixture."""
    fixture_file = private_dir / "fixture.json"
    state_file = private_dir / "fixture-state.json"
    token_file = private_dir / "browser-seed-token.json"
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
                "synthetic project and photo fixture could not be seeded"
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
    screenshots = _private_screenshot_dir(screenshot_path)
    stage_file = screenshots.parent / "browser-stage.txt"

    def mark_stage(value: str) -> None:
        stage_file.write_text(value, encoding="ascii")
        stage_file.chmod(0o600)

    if chromium_executable is not None:
        chromium_executable = chromium_executable.expanduser().resolve()
        if not chromium_executable.is_file() or not os.access(
            chromium_executable, os.X_OK
        ):
            raise AcceptanceFailure("configured Chromium executable is unavailable")

    session_observations: list[tuple[int, bool, bool, bool]] = []
    csrf_requests: list[tuple[str, bool]] = []
    auth_responses: list[tuple[str, int]] = []
    project_responses: list[tuple[str, int]] = []
    project_list_sizes: list[int | None] = []
    project_list_queries: list[dict[str, list[str]]] = []
    yarn_responses: list[tuple[str, int]] = []
    category_responses: list[tuple[str, int]] = []
    app_asset_statuses: list[int] = []
    catalog_statuses: list[int] = []
    csp_errors: list[str] = []
    console_errors: list[str] = []
    page_errors: list[bool] = []
    responsive_failures: list[str] = []
    expected_404_path = "/app/this-route-does-not-exist"
    expected_404_window = [False]

    def observe_request(request: object) -> None:
        url = getattr(request, "url", "")
        request_url = urlsplit(url)
        path = request_url.path
        if path == "/api/v2/projects":
            project_list_queries.append(parse_qs(request_url.query))
        if path not in {"/api/v2/auth/login", "/api/v2/auth/logout"} and not (
            path.startswith("/api/v2/projects/") and path.endswith("/favorite")
        ):
            return
        headers = getattr(request, "headers", {})
        csrf_requests.append((path, bool(headers.get("x-csrf-token"))))

    def observe_response(response: Response) -> None:
        path = _response_path(response)
        if path in {"/api/v2/auth/login", "/api/v2/auth/logout"}:
            auth_responses.append((path, response.status))
        if path.startswith("/api/v2/projects/") or path == "/api/v2/projects":
            project_responses.append((path, response.status))
        if path == "/api/v2/projects" and response.request.method == "GET":
            size: int | None = None
            if response.status == 200:
                try:
                    payload = response.json()
                    data = payload.get("data") if isinstance(payload, dict) else None
                    if isinstance(data, list):
                        size = len(data)
                except Exception:
                    pass
            project_list_sizes.append(size)
        if path.startswith("/api/v2/yarns/") or path == "/api/v2/yarns":
            yarn_responses.append((path, response.status))
        if path.startswith("/api/v2/categories"):
            category_responses.append((path, response.status))
        if path in {"/api/v2/session", "/api/v2/auth/login"}:
            session_user_is_null = False
            csrf_present = False
            numeric_user_id = False
            if response.status == 200:
                try:
                    payload = response.json()
                except Exception:
                    payload = None
                data = payload.get("data") if isinstance(payload, dict) else None
                if isinstance(data, dict):
                    csrf_present = isinstance(data.get("csrf_token"), str) and bool(
                        data["csrf_token"]
                    )
                    user = data.get("user")
                    session_user_is_null = user is None
                    numeric_user_id = (
                        isinstance(user, dict)
                        and isinstance(user.get("id"), int)
                        and not isinstance(user.get("id"), bool)
                        and 0 < user["id"] < 2**53
                    )
            session_observations.append(
                (response.status, session_user_is_null, csrf_present, numeric_user_id)
            )
        elif path.startswith("/app/_app/"):
            app_asset_statuses.append(response.status)
        elif path == "/app/messages.json":
            catalog_statuses.append(response.status)

    def observe_console(message: object) -> None:
        text = getattr(message, "text", "").lower()
        csp_violation = "content security policy" in text or (
            "violat" in text and ("script-src" in text or "style-src" in text)
        )
        if getattr(message, "type", "") != "error":
            return
        if csp_violation:
            # Keep only a marker; browser text may include page values.
            directives = (
                "script-src",
                "style-src",
                "default-src",
                "img-src",
                "font-src",
                "connect-src",
            )
            directive = next(
                (item for item in directives if item in text), "CSP directive"
            )
            if directive == "style-src":
                violation_kind = (
                    "inline-style" if "inline" in text else "stylesheet-or-rule"
                )
                csp_errors.append(f"{directive}:{violation_kind}")
            else:
                csp_errors.append(directive)
            console_errors.append("csp_violation")
            return
        location = getattr(message, "location", {})
        source_path = urlsplit(location.get("url", "")).path
        if (
            expected_404_window[0]
            and ("404" in text or "not found" in text)
            and source_path == expected_404_path
        ):
            console_errors.append("expected_unknown_route_404")
        else:
            console_errors.append("unexpected_console_error")

    def observe_page_error(_error: object) -> None:
        page_errors.append(True)

    with sync_playwright() as playwright:
        mark_stage("launching packaged Chromium")
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
            raise AcceptanceFailure(
                "configured Chromium could not start in headless mode"
            ) from error
        try:
            context = browser.new_context(
                viewport={"width": 390, "height": 844},
                reduced_motion="reduce",
                color_scheme="light",
            )
            page = context.new_page()
            page.on("request", observe_request)
            page.on("response", observe_response)
            page.on("console", observe_console)
            page.on("pageerror", observe_page_error)

            mark_stage("anonymous sign-in and CSP")
            response = page.goto(f"{origin}/app/login", wait_until="networkidle")
            if response is None or response.status != 200:
                raise AcceptanceFailure("direct login route did not load")
            page.locator("#email").wait_for(state="visible")
            page.get_by_role("heading", name="Sign in").wait_for(state="visible")
            _require(
                page.locator(".app-shell").count() == 0,
                "protected navigation appeared while signed out",
            )
            _require(
                page.locator(".mobile-navigation").count() == 0,
                "mobile library navigation appeared while signed out",
            )

            policy = response.headers.get("content-security-policy", "")
            _require(
                "default-src 'self'" in policy
                and "script-src 'self'" in policy
                and "style-src 'self'" in policy
                and "unsafe-inline" not in policy
                and "unsafe-eval" not in policy
                and "sha256-" in policy,
                "strict frontend CSP was not attached to the app shell",
            )
            _require(
                bool(app_asset_statuses)
                and all(status == 200 for status in app_asset_statuses),
                "a bundled app asset failed to load",
            )
            _require(
                bool(catalog_statuses)
                and all(status == 200 for status in catalog_statuses),
                "the public translation catalog failed to load",
            )
            favicon_response = page.request.get(f"{origin}/app/favicon.svg")
            _require(favicon_response.status == 200, "the app favicon failed to load")
            _require(
                any(
                    status == 200 and user_null and csrf
                    for status, user_null, csrf, _ in session_observations
                ),
                "anonymous session bootstrap did not provide CSRF state",
            )

            mark_stage("anonymous responsive layout")
            for width in (320, 390, 1280):
                page.set_viewport_size({"width": width, "height": 844})
                _assert_no_horizontal_overflow(page, width)
                form_heights = _visible_heights(
                    page, "#email, #password, .auth-form button"
                )
                _require(
                    len(form_heights) == 3 and min(form_heights) >= 44,
                    "a sign-in control is below the 44px target size",
                )
            page.set_viewport_size({"width": 390, "height": 844})
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "signed-out-mobile.png"), caret="initial"
            )

            mark_stage("real password login and session cookie")
            page.locator("#email").fill(email)
            page.locator("#password").fill(password)
            page.get_by_role("button", name="Sign in").click()
            mark_stage("waiting for authenticated app shell")
            page.locator(".app-shell").wait_for(state="visible")
            mark_stage("waiting for authenticated mobile navigation")
            page.locator(".mobile-navigation").wait_for(state="visible")
            current_route = urlsplit(page.url).path
            mark_stage(f"checking project route after login at {current_route}")
            _require(
                current_route.rstrip("/") == "/app/projects",
                "password login did not open the project library route",
            )
            _require(
                any(
                    path == "/api/v2/auth/login" and has_csrf
                    for path, has_csrf in csrf_requests
                ),
                "login did not send the CSRF header",
            )
            _require(
                any(
                    status == 200 and numeric
                    for status, _, _, numeric in session_observations
                ),
                "authenticated session did not expose a numeric user ID",
            )
            login_response = next(
                (
                    status
                    for path, status in auth_responses
                    if path == "/api/v2/auth/login"
                ),
                None,
            )
            _require(
                login_response == 200,
                "password login did not succeed against the Rust API",
            )

            cookies = context.cookies(origin)
            session_cookie = next(
                (
                    cookie
                    for cookie in cookies
                    if cookie["name"] == "stricknani_session"
                ),
                None,
            )
            _require(
                session_cookie is not None
                and session_cookie.get("httpOnly") is True
                and session_cookie.get("sameSite") == "Lax",
                "login did not establish the expected protected browser session cookie",
            )
            cookies.clear()
            session_cookie = None
            mark_stage("preparing the synthetic project reader fixture")
            project_id = _prepare_project_reader_fixture(page)
            mark_stage("opening the authenticated project library")
            page.goto(f"{origin}/app/projects", wait_until="networkidle")
            mark_stage("waiting for the seeded project card")
            try:
                page.get_by_role(
                    "heading", name="Heirloom Baby Blanket", exact=True
                ).wait_for(state="visible", timeout=10_000)
            except PlaywrightTimeoutError:
                list_statuses = [
                    status
                    for path, status in project_responses
                    if path == "/api/v2/projects"
                ]
                api_status = list_statuses[-1] if list_statuses else "not observed"
                list_size = (
                    project_list_sizes[-1] if project_list_sizes else "not observed"
                )
                card_count = page.locator(".project-card").count()
                empty_count = page.locator(".empty-state").count()
                loading = page.locator(".loading-grid").count() > 0
                ui_error_visible = page.locator(".notice-error").count() > 0
                library_count = page.locator(".library").count()
                debug_screenshot = screenshots / "project-library-debug.png"
                _mask_fixture_identity(page)
                _prepare_screenshot(page)
                page.screenshot(path=str(debug_screenshot), caret="initial")
                raise AcceptanceFailure(
                    "seeded project did not render "
                    f"(API status {api_status}; list items={list_size}; "
                    f"cards={card_count}; empty state={empty_count}; "
                    f"still loading={loading}; UI error visible={ui_error_visible}; "
                    f"library component={library_count}; screenshot={debug_screenshot})"
                ) from None
            _require(
                any(
                    path == "/api/v2/projects" and status == 200
                    for path, status in project_responses
                ),
                "the real project library API did not load",
            )

            mobile_links = page.locator(".mobile-navigation a")
            _require(
                mobile_links.count() == 4, "signed-in mobile navigation is incomplete"
            )
            expected_links = {
                "Projects": "/app/projects",
                "Yarns": "/app/yarns",
                "Create": "/app/create",
                "More": "/app/more",
            }
            for label, href in expected_links.items():
                link = page.locator(".mobile-navigation a").filter(has_text=label)
                _require(
                    link.count() == 1 and link.get_attribute("href") == href,
                    "a mobile navigation link has the wrong destination",
                )
            mark_stage("project library search and URL filters")
            project_card = page.locator(".project-card").filter(
                has=page.get_by_role(
                    "heading", name="Heirloom Baby Blanket", exact=True
                )
            )
            _require(project_card.count() == 1, "the seeded project card is missing")
            project_photo = project_card.locator(".project-image img")
            _require(
                project_photo.count() == 1
                and project_photo.evaluate("image => image.src.includes('/media/')"),
                "the project card did not use the seeded same-origin photo",
            )
            search_box = page.get_by_role("searchbox", name="Search your projects")
            search_box.fill("Heirloom")
            mark_stage("searching project library by name")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and parse_qs(urlsplit(response.url).query).get("q") == ["Heirloom"]
                )
            ) as search_response_info:
                page.get_by_role("button", name="Search", exact=True).click()
            _require(
                search_response_info.value.status == 200,
                "project-name search did not succeed against the Rust API",
            )
            page.locator(".project-card").wait_for(state="visible")
            _require(
                page.locator(".project-card").count() == 1,
                "project search did not return the matching card",
            )
            mark_stage("filtering project library by category")
            selected_category: list[str] = []
            category_select = page.get_by_role("combobox")
            try:
                category_select.wait_for(state="visible", timeout=5_000)
            except PlaywrightTimeoutError:
                _mask_fixture_identity(page)
                _prepare_screenshot(page)
                category_debug = page.evaluate(
                    """() => ({
                      selectCount: document.querySelectorAll('select').length,
                      libraryCount: document.querySelectorAll('.library').length,
                      visible: document.visibilityState === 'visible',
                      hasBody: Boolean(document.body),
                    })"""
                )
                category_debug_screenshot = screenshots / "category-stage-debug.png"
                page.screenshot(path=str(category_debug_screenshot), caret="initial")
                raise AcceptanceFailure(
                    "project category control did not become visible "
                    f"(diagnostics={category_debug}; "
                    f"native select count={page.locator('select').count()}; "
                    f"accessible combobox count={category_select.count()})"
                ) from None
            _require(category_select.count() == 1, "category filter is ambiguous")
            _require(
                category_select.evaluate("select => select.labels.length > 0"),
                "the project category filter is not programmatically labeled",
            )
            try:
                with page.expect_response(
                    lambda response: (
                        urlsplit(response.url).path == "/api/v2/projects"
                        and parse_qs(urlsplit(response.url).query).get("category")
                        == ["Schal"]
                    ),
                    timeout=10_000,
                ) as category_filter_response_info:
                    selected_category = category_select.select_option(
                        label="Schal", timeout=5_000
                    )
            except PlaywrightTimeoutError:
                category_in_url = parse_qs(urlsplit(page.url).query).get(
                    "category"
                ) == ["Schal"]
                category_request_seen = any(
                    query.get("category") == ["Schal"] for query in project_list_queries
                )
                category_value_selected = (
                    category_select.input_value(timeout=1_000) == "Schal"
                )
                raise AcceptanceFailure(
                    "category filter did not reach the project API "
                    f"(URL updated={category_in_url}; "
                    f"request observed={category_request_seen}; "
                    f"select returned value={selected_category == ['Schal']}; "
                    f"selected in DOM={category_value_selected})"
                ) from None
            _require(
                category_filter_response_info.value.status == 200,
                "category filtering did not succeed against the Rust API",
            )
            page.locator(".project-card").wait_for(state="visible")
            _require(
                any(
                    path == "/api/v2/categories" and status == 200
                    for path, status in category_responses
                ),
                "project categories did not load from the Rust API",
            )
            mark_stage("filtering project library by tag")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and parse_qs(urlsplit(response.url).query).get("tag")
                    == ["heirloom"]
                )
            ) as tag_filter_response_info:
                page.get_by_role("button", name="heirloom", exact=True).click()
            _require(
                tag_filter_response_info.value.status == 200,
                "tag filtering did not succeed against the Rust API",
            )
            page.locator(".project-card").wait_for(state="visible")
            search_box = page.get_by_role("searchbox", name="Search your projects")
            search_box.fill("no such knitting project")
            mark_stage("checking the project no-match state")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and parse_qs(urlsplit(response.url).query).get("q")
                    == ["no such knitting project"]
                )
            ) as no_match_response_info:
                page.get_by_role("button", name="Search", exact=True).click()
            _require(
                no_match_response_info.value.status == 200,
                "no-match search did not succeed against the Rust API",
            )
            page.get_by_role(
                "heading", name="No projects match those filters."
            ).wait_for(state="visible")
            _require(
                page.locator(".project-card").count() == 0,
                "the no-match state still showed a project",
            )
            mark_stage("clearing project filters")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and not urlsplit(response.url).query
                )
            ) as clear_filters_response_info:
                page.locator(".filters").get_by_role(
                    "button", name="Clear filters", exact=True
                ).click()
            _require(
                clear_filters_response_info.value.status == 200,
                "clearing filters did not reload the project list",
            )
            page.get_by_role(
                "heading", name="Heirloom Baby Blanket", exact=True
            ).wait_for(state="visible")
            project_card = page.locator(".project-card").filter(
                has=page.get_by_role(
                    "heading", name="Heirloom Baby Blanket", exact=True
                )
            )
            mark_stage("saving a project favorite")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path.endswith("/favorite")
                    and response.request.method == "PUT"
                )
            ) as favorite_response_info:
                page.get_by_role("button", name="Add to favorites").click()
            _require(
                favorite_response_info.value.status == 200,
                "saving the project favorite failed against the Rust API",
            )
            page.get_by_role("button", name="Remove from favorites").wait_for(
                state="visible"
            )
            mark_stage("filtering project library by favorites")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == "/api/v2/projects"
                    and parse_qs(urlsplit(response.url).query).get("favorite")
                    == ["true"]
                )
            ) as favorite_filter_response_info:
                page.get_by_label("Favorites", exact=True).check()
            _require(
                favorite_filter_response_info.value.status == 200,
                "favorite filtering did not succeed against the Rust API",
            )
            page.locator(".project-card").wait_for(state="visible")
            _require(
                page.locator(".project-card").count() == 1,
                "favorite filtering did not keep the saved project",
            )
            _require(
                any(
                    path.endswith("/favorite") and has_csrf
                    for path, has_csrf in csrf_requests
                ),
                "the favorite action did not send CSRF",
            )
            _require(
                any(
                    path.endswith("/favorite") and status == 200
                    for path, status in project_responses
                ),
                "the favorite action did not persist through the Rust API",
            )
            mark_stage("authenticated responsive layout")
            for width in (320, 390, 1280):
                page.set_viewport_size({"width": width, "height": 844})
                _assert_no_horizontal_overflow(page, width)
                selector = (
                    ".mobile-navigation a"
                    if width <= 760
                    else ".primary-nav a, .secondary-nav a"
                )
                heights = _visible_heights(page, selector)
                if not heights:
                    responsive_failures.append(
                        f"no signed-in navigation targets were visible at {width}px"
                    )
                elif min(heights) < 44:
                    responsive_failures.append(
                        f"signed-in navigation target measured {min(heights):.1f}px "
                        f"at {width}px"
                    )
                _require(
                    page.evaluate(
                        "matchMedia('(prefers-reduced-motion: reduce)').matches"
                    ),
                    "reduced-motion preference was not honored by the browser context",
                )
                _require(
                    page.evaluate(
                        "getComputedStyle(document.documentElement).scrollBehavior"
                    )
                    == "auto",
                    "reduced-motion scroll behavior was not applied",
                )
            page.set_viewport_size({"width": 390, "height": 844})
            _mask_fixture_identity(page)
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "signed-in-mobile.png"),
                caret="initial",
            )

            mark_stage("project reader, ordered steps, media, and print")
            with page.expect_response(
                lambda response: (
                    urlsplit(response.url).path == f"/api/v2/projects/{project_id}"
                    and response.request.method == "GET"
                )
            ) as reader_response_info:
                page.locator(
                    f".project-card a[href='/app/projects/{project_id}']"
                ).click()
            page.wait_for_url(f"**/app/projects/{project_id}")
            reader_response = reader_response_info.value
            _require(reader_response.status == 200, "project detail API failed")
            mark_stage("checking project reader title and ordered steps")
            page.get_by_role(
                "heading", name="Heirloom Baby Blanket", exact=True
            ).wait_for(state="visible")
            step_titles = page.locator(".steps-list h3").all_inner_texts()
            _require(
                step_titles
                == [
                    "Cast on and knit the border",
                    "Work the textured panel",
                ],
                "the reader did not display pattern steps in order",
            )
            mark_stage("checking sanitized pattern text")
            description = page.locator(".pattern-section > .reading-copy > .rich-text")
            sanitized_text = description.locator("strong")
            if sanitized_text.count() != 1 or sanitized_text.inner_text() != (
                "textured blanket"
            ):
                text_debug = page.locator(".pattern-section .rich-text").evaluate_all(
                    "nodes => nodes.map(node => ({"
                    "strongCount: node.querySelectorAll('strong').length, "
                    "textLength: node.textContent?.length ?? 0, "
                    "hasFixturePhrase: "
                    "node.textContent?.includes('textured blanket') ?? false, "
                    "hasMarkdownMarkers: node.textContent?.includes('**') ?? false"
                    "}))"
                )
                _mask_fixture_identity(page)
                _prepare_screenshot(page)
                page.screenshot(
                    path=str(screenshots / "reader-text-debug.png"),
                    caret="initial",
                )
                raise AcceptanceFailure(
                    "sanitized description structure did not match "
                    f"the fixture (blocks={text_debug}; "
                    f"strong elements={sanitized_text.count()})"
                )
            _require(
                "**" not in description.inner_text(),
                "raw Markdown was displayed in the pattern reader",
            )
            mark_stage("checking linked yarn API details")
            page.get_by_text("Riverbend Merino DK", exact=True).wait_for(
                state="visible", timeout=5_000
            )
            _require(
                any(
                    path.startswith("/api/v2/yarns/") and status == 200
                    for path, status in yarn_responses
                ),
                "linked yarn details did not load from the Rust API",
            )
            _require(
                page.locator(".gallery-image").count() == 1,
                "the single-image project fixture changed unexpectedly",
            )
            page.set_viewport_size({"width": 320, "height": 844})
            mark_stage("opening the single seeded project photo at 320px")
            page.locator(".gallery-image").first.scroll_into_view_if_needed()
            page.locator(".gallery-image").first.click()
            dialog = page.get_by_role("dialog", name="Project image viewer")
            dialog.wait_for(state="visible")
            lightbox_image = dialog.locator("img")
            lightbox_image.wait_for(state="visible")
            lightbox_image.evaluate("image => image.decode()")
            image_bounds = lightbox_image.bounding_box()
            _require(
                lightbox_image.evaluate(
                    "image => image.complete && image.naturalWidth > 0"
                )
                and image_bounds is not None
                and image_bounds["width"] >= 180
                and image_bounds["height"] >= 140,
                "the seeded photo did not load at a useful size in the 320px viewer",
            )
            mark_stage("checking Escape close and focus restoration")
            page.keyboard.press("Escape")
            dialog.wait_for(state="hidden")
            _require(
                page.evaluate(
                    "document.activeElement === "
                    "document.querySelector('.gallery-image')"
                ),
                "closing the image viewer did not restore focus",
            )
            page.set_viewport_size({"width": 390, "height": 844})
            mark_stage("checking reader text size and print layout")
            reading_copy = page.locator(".pattern-section > .reading-copy")
            small_font = float(
                reading_copy.evaluate(
                    "element => getComputedStyle(element).fontSize"
                ).removesuffix("px")
            )
            page.get_by_role("button", name="Increase text size").click()
            try:
                page.wait_for_function(
                    "initial => {"
                    "const element = document.querySelector("
                    "'.pattern-section > .reading-copy'); "
                    "return element !== null && "
                    "parseFloat(getComputedStyle(element).fontSize) > initial;"
                    "}",
                    arg=small_font,
                    timeout=5_000,
                )
            except PlaywrightTimeoutError:
                raise AcceptanceFailure(
                    "reader text-size control did not update the description text "
                    f"(class={reading_copy.get_attribute('class')}; "
                    f"font size={
                        reading_copy.evaluate(
                            'element => getComputedStyle(element).fontSize'
                        )
                    })"
                ) from None
            large_font = float(
                reading_copy.evaluate(
                    "element => getComputedStyle(element).fontSize"
                ).removesuffix("px")
            )
            _require(
                large_font > small_font, "reader text-size control did not grow text"
            )
            page.emulate_media(media="print")
            _require(
                not page.locator(".reader-topline").is_visible()
                and page.locator(".steps-list > li").nth(1).is_visible(),
                "print layout hid reader controls but omitted pattern instructions",
            )
            page.emulate_media(media="screen")
            page.get_by_role("button", name="Remove from favorites").click()
            page.get_by_role("button", name="Add to favorites").wait_for(
                state="visible"
            )
            _mask_fixture_identity(page)
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "project-reader-mobile.png"),
                caret="initial",
            )

            mark_stage("direct routes and explicit unfinished states")
            route_headings = {
                "/app/": "Your knitting, in one place.",
                "/app/login": "You are already signed in.",
                "/app/more": "More",
                "/app/create": "What would you like to add?",
                "/app/admin/users": "You do not have access to this page.",
                "/app/admin/audit": "You do not have access to this page.",
            }
            for route, heading in route_headings.items():
                mark_stage(f"checking direct route {route}")
                route_response = page.goto(f"{origin}{route}", wait_until="networkidle")
                _require(
                    route_response is not None and route_response.status == 200,
                    "a supported direct app route did not return 200",
                )
                page.get_by_role("heading", name=heading).wait_for(state="visible")
                if route == "/app/more":
                    _require(
                        page.get_by_text(
                            "This area is not connected yet."
                        ).is_visible(),
                        "an unfinished feature was presented as complete",
                    )
                if route == "/app/create":
                    _require(
                        page.get_by_text(
                            "These creation forms are part of a later preview slice."
                        ).is_visible(),
                        "unfinished creation forms were presented as complete",
                    )
            mark_stage("checking unknown route rejection")
            expected_404_window[0] = True
            try:
                unknown = page.request.get(f"{origin}{expected_404_path}")
            finally:
                expected_404_window[0] = False
            _require(
                unknown.status == 404, "unknown app route was not rejected with 404"
            )

            page.goto(f"{origin}/app/projects", wait_until="networkidle")
            page.locator(".app-shell").wait_for(state="visible")
            mark_stage("logout and anonymous session restoration")
            page.set_viewport_size({"width": 1280, "height": 844})
            logout_button = page.locator(".header-sign-out")
            _require(
                logout_button.is_visible(), "desktop sign-out action is unavailable"
            )
            logout_button.click()
            page.locator("#email").wait_for(state="visible")
            page.get_by_role("heading", name="Sign in").wait_for(state="visible")
            _require(
                any(
                    path == "/api/v2/auth/logout" and has_csrf
                    for path, has_csrf in csrf_requests
                ),
                "logout did not send the CSRF header",
            )
            _require(
                any(
                    path == "/api/v2/auth/logout" and status == 204
                    for path, status in auth_responses
                ),
                "logout did not complete with 204",
            )
            remaining_cookies = context.cookies(origin)
            _require(
                not any(
                    cookie["name"] == "stricknani_session"
                    for cookie in remaining_cookies
                ),
                "logout did not clear the browser session cookie",
            )
            remaining_cookies.clear()
            page.wait_for_function(
                "() => document.querySelector('.app-shell') === null",
            )
            _require(
                any(
                    status == 200 and user_null and csrf
                    for status, user_null, csrf, _ in session_observations
                ),
                "anonymous session was not restored after logout",
            )
            page.locator("#email").fill("")
            page.locator("#password").fill("")
            page.set_viewport_size({"width": 390, "height": 844})
            _prepare_screenshot(page)
            page.screenshot(
                path=str(screenshots / "signed-out-after-logout.png"),
                caret="initial",
            )

            _require(
                not csp_errors,
                "CSP console violation from: " + ", ".join(sorted(set(csp_errors))),
            )
            _require(not page_errors, "the browser reported a page error")
            _require(
                all(error == "expected_unknown_route_404" for error in console_errors),
                "the browser reported an unexpected console error",
            )
            _require(
                not responsive_failures,
                responsive_failures[0]
                if responsive_failures
                else "responsive target failure",
            )
            stage_file.unlink(missing_ok=True)
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
        private_dir = _private_work_dir(args.private_dir)
        screenshots = run_live_fixture(
            args.binary,
            args.web_root,
            private_dir,
            args.chromium_executable,
        )
    except rust_fixture.FixtureError as error:
        print(
            f"Web UI browser acceptance could not start safely: {error}",
            file=sys.stderr,
        )
        return 1
    except AcceptanceFailure as error:
        print(f"Web UI browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception:
        stage_file = (
            private_dir / "browser-stage.txt" if private_dir is not None else None
        )
        if stage_file is not None and stage_file.is_file():
            stage = stage_file.read_text(encoding="ascii")
            print(
                f"Web UI browser acceptance failed during {stage}; "
                "diagnostic details were suppressed.",
                file=sys.stderr,
            )
        else:
            print(
                "Web UI browser acceptance failed; diagnostic details were suppressed.",
                file=sys.stderr,
            )
        return 1
    print(
        "Web UI browser acceptance passed: strict CSP, session auth, routes, "
        "responsive layout, and logout."
    )
    print(f"Sanitized screenshots: {screenshots}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
