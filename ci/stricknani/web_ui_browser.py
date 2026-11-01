#!/usr/bin/env python3
"""Run sanitized browser acceptance checks against the disposable Rust fixture."""

from __future__ import annotations

import argparse
import ipaddress
import os
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import Page, Response, sync_playwright

from ci.stricknani import rust_fixture


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


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    chromium_executable: Path | None = None,
) -> Path:
    """Start, exercise, and safely stop one disposable server fixture."""
    fixture_file = private_dir / "fixture.json"
    state_file = private_dir / "fixture-state.json"
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
        path = urlsplit(url).path
        if path not in {"/api/v2/auth/login", "/api/v2/auth/logout"}:
            return
        headers = getattr(request, "headers", {})
        csrf_requests.append((path, bool(headers.get("x-csrf-token"))))

    def observe_response(response: Response) -> None:
        path = _response_path(response)
        if path in {"/api/v2/auth/login", "/api/v2/auth/logout"}:
            auth_responses.append((path, response.status))
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
            page.screenshot(
                path=str(screenshots / "signed-out-mobile.png"), full_page=True
            )

            mark_stage("real password login and session cookie")
            page.locator("#email").fill(email)
            page.locator("#password").fill(password)
            page.get_by_role("button", name="Sign in").click()
            page.locator(".app-shell").wait_for(state="visible")
            page.locator(".mobile-navigation").wait_for(state="visible")
            page.wait_for_url("**/app/projects")
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
            page.locator(".header-user").evaluate(
                "node => node.textContent = 'Test account'"
            )
            page.locator(".account-email").evaluate(
                "node => node.textContent = 'Test account'"
            )
            page.locator(".account-email").evaluate(
                "node => node.title = 'Test account'"
            )
            page.locator(".avatar").evaluate_all(
                "nodes => nodes.forEach(node => node.textContent = 'T')"
            )
            page.screenshot(
                path=str(screenshots / "signed-in-mobile.png"), full_page=True
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
            page.screenshot(
                path=str(screenshots / "signed-out-after-logout.png"), full_page=True
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
