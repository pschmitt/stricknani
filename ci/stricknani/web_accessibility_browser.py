#!/usr/bin/env python3
"""Run focused keyboard and accessibility checks against synthetic Rust data."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Callable
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import Locator, Page, sync_playwright
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

from ci.stricknani import rust_fixture, seed_rust, web_ui_browser


class AccessibilityFailure(RuntimeError):
    """A sanitized browser acceptance failure."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise AccessibilityFailure(message)


def _tab_to(page: Page, locator: Locator, description: str) -> None:
    for _ in range(80):
        if locator.evaluate("element => document.activeElement === element"):
            return
        page.keyboard.press("Tab")
    raise AccessibilityFailure(f"keyboard navigation could not reach {description}")


def _exercise_accessibility(
    page: Page,
    origin: str,
    email: str,
    password: str,
    mark_stage: Callable[[str], None],
) -> None:
    immutable_asset_failures: list[str] = []

    def observe_response(response: object) -> None:
        path = urlsplit(getattr(response, "url", "")).path
        status = getattr(response, "status", 0)
        if status >= 400 and "/_app/immutable/" in path:
            category = (
                "js"
                if path.endswith(".js")
                else "css"
                if path.endswith(".css")
                else "other"
            )
            immutable_asset_failures.append(f"{category}:{status}")

    def observe_request_failure(request: object) -> None:
        path = urlsplit(getattr(request, "url", "")).path
        if "/_app/immutable/" not in path:
            return
        category = (
            "js"
            if path.endswith(".js")
            else "css"
            if path.endswith(".css")
            else "other"
        )
        immutable_asset_failures.append(f"{category}:network-failed")

    page.on("response", observe_response)
    page.on("requestfailed", observe_request_failure)
    mark_stage("loading synthetic sign-in page")
    response = page.goto(f"{origin}/app/login", wait_until="domcontentloaded")
    _require(response is not None and response.status == 200, "sign-in did not load")
    page.get_by_role("heading", name="Sign in", exact=True).wait_for(
        state="visible", timeout=10_000
    )

    mark_stage("checking sign-in labels and keyboard focus")
    email_input = page.get_by_label("Email", exact=True)
    password_input = page.get_by_label("Password", exact=True)
    _require(
        email_input.count() == 1 and password_input.count() == 1,
        "sign-in fields do not have unique accessible labels",
    )
    submit = page.get_by_role("button", name="Sign in", exact=True)
    _require(submit.count() == 1, "sign-in submit has no unique accessible name")

    _tab_to(page, email_input, "email field")
    _require(
        email_input.evaluate("element => element.matches(':focus-visible')"),
        "keyboard focus is not visibly indicated on the email field",
    )
    page.keyboard.insert_text(email)
    page.keyboard.press("Tab")
    _require(
        password_input.evaluate("element => document.activeElement === element"),
        "Tab did not move from email to password",
    )
    page.keyboard.insert_text("synthetic-invalid-password")
    page.keyboard.press("Tab")
    _require(
        submit.evaluate("element => document.activeElement === element"),
        "Tab did not move from password to the sign-in button",
    )

    mark_stage("checking invalid sign-in alert")
    page.keyboard.press("Enter")
    alert = page.get_by_role("alert")
    alert.wait_for(state="visible", timeout=10_000)
    _require(alert.inner_text().strip() != "", "sign-in error alert is empty")
    _require(
        password_input.input_value() == "synthetic-invalid-password",
        "sign-in error cleared the password draft",
    )

    mark_stage("completing keyboard-only sign-in")
    page.keyboard.press("Shift+Tab")
    _require(
        password_input.evaluate("element => document.activeElement === element"),
        "Shift+Tab did not return to the password field after the error",
    )
    page.keyboard.press("Control+A")
    page.keyboard.insert_text(password)
    page.keyboard.press("Tab")
    page.keyboard.press("Enter")

    mark_stage("waiting for the post-login Projects landing route")
    page.wait_for_url("**/app/projects", timeout=10_000)
    page.get_by_role("heading", name="Heirloom Baby Blanket", exact=True).wait_for(
        state="visible", timeout=10_000
    )

    mark_stage("checking library keyboard navigation")
    library_nav = page.get_by_role("navigation", name="Your library", exact=True)
    library_nav.wait_for(state="visible", timeout=10_000)
    yarns_link = library_nav.get_by_role("link", name="Yarns", exact=True)
    _require(yarns_link.count() == 1, "library navigation has no unique Yarns link")
    _tab_to(page, yarns_link, "Yarns navigation link")
    _require(
        yarns_link.evaluate("element => element.matches(':focus-visible')"),
        "keyboard focus is not visible on the Yarns navigation link",
    )
    mark_stage("activating Yarns navigation with Enter")
    with page.expect_response(
        lambda response: (
            urlsplit(response.url).path == "/api/v2/yarns"
            and response.request.method == "GET"
        ),
        timeout=10_000,
    ) as yarn_list_response_info:
        page.keyboard.press("Enter")
    _require(
        yarn_list_response_info.value.status == 200,
        "the keyboard-opened yarn library did not load its list",
    )
    mark_stage("waiting for the Yarns library route")
    try:
        page.wait_for_url("**/app/yarns", timeout=10_000)
    except PlaywrightTimeoutError:
        current_path = urlsplit(page.url).path
        target_path = urlsplit(yarns_link.get_attribute("href") or "").path
        focused = yarns_link.evaluate("element => document.activeElement === element")
        raise AccessibilityFailure(
            "Enter did not navigate from the Yarns link "
            f"(target={target_path or 'missing'}; current={current_path}; "
            f"focus retained={focused})"
        ) from None
    mark_stage("waiting for the Yarns route to render its heading")
    try:
        page.wait_for_function(
            """() => {
              const heading = document.querySelector('#yarn-library-title');
              if (location.pathname !== '/app/yarns' || !heading) return false;
              const style = getComputedStyle(heading);
              const rect = heading.getBoundingClientRect();
              return style.visibility !== 'hidden' && style.display !== 'none'
                && rect.width > 0 && rect.height > 0;
            }""",
            timeout=10_000,
        )
    except PlaywrightTimeoutError:
        raise AccessibilityFailure(
            "Yarns SPA route did not render its visible heading "
            f"(current path={urlsplit(page.url).path}; "
            f"immutable asset failures={immutable_asset_failures or 'none'})"
        ) from None
    mark_stage("checking the Yarns heading role and accessible name")
    route_state = page.evaluate(
        """() => {
          const heading = document.querySelector('#yarn-library-title');
          const style = heading ? getComputedStyle(heading) : null;
          const rect = heading?.getBoundingClientRect();
          const explicitRole = heading?.getAttribute('role');
          return {
            path: location.pathname,
            headingExists: heading !== null,
            headingVisible: !!heading && !!style
              && style.visibility !== 'hidden' && style.display !== 'none'
              && !!rect && rect.width > 0 && rect.height > 0,
            headingSemantics: heading?.tagName === 'H1'
              && (explicitRole === null || explicitRole === 'heading'),
            headingNameMatches: heading?.textContent?.trim() === 'Yarns',
            documentLanguage: document.documentElement.lang,
          };
        }"""
    )
    _require(
        route_state["headingExists"]
        and route_state["headingVisible"]
        and route_state["headingSemantics"]
        and route_state["headingNameMatches"],
        "Yarns route did not expose a visible semantic heading with its label "
        f"(path={route_state['path']}; heading={route_state['headingExists']}; "
        f"visible={route_state['headingVisible']}; "
        f"semantic={route_state['headingSemantics']}; "
        f"name={route_state['headingNameMatches']}; "
        f"language={route_state['documentLanguage']})",
    )
    mark_stage("checking current-page navigation state")
    current_library_nav = page.get_by_role(
        "navigation", name="Your library", exact=True
    )
    current_yarns_link = current_library_nav.get_by_role(
        "link", name="Yarns", exact=True
    )
    _require(
        current_yarns_link.count() == 1
        and current_yarns_link.get_attribute("aria-current") == "page",
        "the active library route is not exposed as the current page",
    )

    mark_stage("waiting for the synthetic yarn card")
    yarn_heading = page.get_by_role("heading", name="Riverbend Merino DK", exact=True)
    yarn_heading.wait_for(state="visible", timeout=10_000)
    yarn_link = page.locator(".yarn-card a.resource-card__link").filter(
        has=yarn_heading
    )
    yarn_link.wait_for(state="visible", timeout=10_000)
    _require(yarn_link.count() == 1, "synthetic yarn is missing from the library")
    _require(
        yarn_link.evaluate("element => element.tagName === 'A'"),
        "the yarn card is not exposed as a link",
    )
    _tab_to(page, yarn_link, "synthetic yarn link")
    page.keyboard.press("Enter")
    page.get_by_role("heading", name="Riverbend Merino DK", exact=True).wait_for(
        state="visible", timeout=10_000
    )

    mark_stage("opening synthetic yarn from keyboard")
    photo_button = page.locator(".photo-open").first
    photo_button.wait_for(state="visible", timeout=10_000)
    _tab_to(page, photo_button, "yarn photo button")
    page.keyboard.press("Enter")
    mark_stage("checking photo dialog focus and restoration")
    photo_dialog = page.get_by_role("dialog", name="Yarn photo", exact=True)
    photo_dialog.wait_for(state="visible", timeout=5_000)
    photo_title = photo_dialog.get_by_role("heading", name="Yarn photo", exact=True)
    page.wait_for_function(
        "() => document.activeElement === document.querySelector("
        "'.zoom-dialog-header h2')",
        timeout=5_000,
    )
    _require(
        photo_title.evaluate("element => document.activeElement === element"),
        "opening the photo dialog did not focus its accessible title",
    )
    close_button = photo_dialog.get_by_role("button", name="Close photo", exact=True)
    page.keyboard.press("Tab")
    page.wait_for_function(
        "() => document.activeElement === document.querySelector("
        "'.zoom-dialog-header form button')",
        timeout=5_000,
    )
    _require(
        close_button.evaluate("element => document.activeElement === element"),
        "Tab from the photo dialog title did not focus its close button",
    )
    dialog_focus_states = []
    for key in ("Tab", "Tab", "Shift+Tab"):
        page.keyboard.press(key)
        dialog_focus_states.append(
            page.evaluate(
                """() => {
                  const dialog = document.querySelector('.zoom-dialog');
                  const active = document.activeElement;
                  return {
                    open: !!dialog?.open,
                    modal: !!dialog?.matches(':modal'),
                    focusInside: !!dialog?.contains(active),
                    focusOnDialog: active === dialog,
                    focusOnBody: active === document.body,
                    activeTag: active?.tagName ?? 'unknown',
                  };
                }"""
            )
        )
    focus_stayed_in_dialog = all(
        state["open"]
        and state["modal"]
        and (state["focusInside"] or state["focusOnDialog"] or state["focusOnBody"])
        for state in dialog_focus_states
    )
    page.keyboard.press("Escape")
    page.wait_for_function(
        "() => !document.querySelector('.zoom-dialog')?.open", timeout=5_000
    )
    _require(
        photo_button.evaluate("element => document.activeElement === element"),
        "Escape did not restore focus to the photo trigger",
    )

    mark_stage("checking reduced motion")
    motion = page.evaluate(
        """() => ({
          requested: matchMedia('(prefers-reduced-motion: reduce)').matches,
          scroll: getComputedStyle(document.documentElement).scrollBehavior,
          transitionSeconds: getComputedStyle(
            document.querySelector('.photo-open')
          ).transitionDuration.split(',').map(value => {
            const duration = value.trim();
            return duration.endsWith('ms')
              ? Number.parseFloat(duration) / 1000
              : Number.parseFloat(duration);
          }),
        })"""
    )
    _require(
        motion["requested"]
        and motion["scroll"] == "auto"
        and max(motion["transitionSeconds"]) <= 0.0001,
        "reduced-motion preference did not suppress motion and smooth scrolling",
    )

    # Playwright has no portable browser text-only zoom control. A 640 CSS-pixel
    # viewport is the honest 200%-layout surrogate for a 1280px baseline.
    mark_stage("checking effective 200 percent viewport reflow")
    page.set_viewport_size({"width": 640, "height": 900})
    _require(
        page.evaluate(
            "document.documentElement.scrollWidth <= "
            "document.documentElement.clientWidth"
        ),
        "the effective 200% viewport has horizontal overflow",
    )
    _require(
        page.get_by_role(
            "heading", name="Riverbend Merino DK", exact=True
        ).is_visible(),
        "the yarn title is not visible at the effective 200% viewport",
    )
    _require(
        not immutable_asset_failures,
        "immutable app assets failed to load "
        f"(categories and statuses={immutable_asset_failures})",
    )
    _require(
        focus_stayed_in_dialog,
        "tab cycling moved focus to an interactive page element outside the "
        f"modal (focus states={dialog_focus_states})",
    )


def run_live_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    browser_executable: Path | None = None,
) -> None:
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
            signup_enabled=False,
        )
        started = True
        try:
            seed_rust.seed_fixture(fixture_file, token_file)
        except seed_rust.SeedError:
            raise AccessibilityFailure(
                "the synthetic browser fixture could not be seeded"
            ) from None
        finally:
            token_file.unlink(missing_ok=True)

        fixture = rust_fixture.load_fixture_environment(fixture_file)
        origin = web_ui_browser._loopback_origin(fixture["STRICKNANI_RUST_TEST_URL"])
        stage_file = private_dir / "accessibility-stage.txt"

        def mark_stage(value: str) -> None:
            stage_file.write_text(value, encoding="ascii")
            stage_file.chmod(0o600)

        with sync_playwright() as playwright:
            mark_stage("launching Chromium")
            browser = playwright.chromium.launch(
                headless=True,
                executable_path=(
                    str(browser_executable) if browser_executable is not None else None
                ),
            )
            try:
                context = browser.new_context(
                    viewport={"width": 1280, "height": 900},
                    reduced_motion="reduce",
                    locale="en-US",
                )
                try:
                    page = context.new_page()
                    _exercise_accessibility(
                        page,
                        origin,
                        fixture["STRICKNANI_RUST_TEST_EMAIL"],
                        fixture["STRICKNANI_RUST_TEST_PASSWORD"],
                        mark_stage,
                    )
                finally:
                    context.close()
            finally:
                browser.close()
    except rust_fixture.FixtureError:
        raise AccessibilityFailure(
            "the disposable Rust fixture could not start safely"
        ) from None
    finally:
        if started:
            rust_fixture.stop_fixture(state_file)


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path, required=True)
    parser.add_argument("--browser-executable", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _argument_parser().parse_args(argv)
    private_dir: Path | None = None
    try:
        private_dir = web_ui_browser._private_work_dir(args.private_dir)
        run_live_fixture(
            args.binary,
            args.web_root,
            private_dir,
            args.browser_executable,
        )
    except (AccessibilityFailure, rust_fixture.FixtureError) as error:
        print(f"Accessibility browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        stage = "unknown stage"
        if private_dir is not None:
            stage_file = private_dir / "accessibility-stage.txt"
            if stage_file.is_file():
                stage = stage_file.read_text(encoding="ascii")
        print(
            "Accessibility browser acceptance failed during "
            f"{stage}; details suppressed ({type(error).__name__}).",
            file=sys.stderr,
        )
        return 1
    print(
        "Accessibility browser checks passed: keyboard login, navigation, "
        "dialog focus, form labels and error alert, reduced motion, and "
        "effective 200% viewport reflow."
    )
    print("Screen-reader and real-phone acceptance were not measured.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
