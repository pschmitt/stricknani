"""Fast, deterministic browser coverage for pull requests."""

from __future__ import annotations

import os
import re
from pathlib import Path

import pytest
from playwright.sync_api import Page, sync_playwright

pytestmark = pytest.mark.e2e


BASE_URL = os.environ.get("E2E_BASE_URL", "http://127.0.0.1:8765")
EMAIL = os.environ.get("E2E_EMAIL", "e2e@example.invalid")
PASSWORD = os.environ.get("E2E_PASSWORD", "ci-e2e-password")
SCREENSHOT_DIR = Path(os.environ.get("E2E_SCREENSHOT_DIR", "e2e-artifacts/screenshots"))


def screenshot(page: Page, name: str) -> None:
    """Save a full-page screenshot when the runner has an artifact directory."""

    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(SCREENSHOT_DIR / name), full_page=True)


def wait_for_path(page: Page, pattern: str) -> None:
    page.wait_for_url(re.compile(rf"{re.escape(BASE_URL)}{pattern}"))


def test_pr_smoke_journey() -> None:
    """Cover login, list/detail navigation, account settings, and logout."""

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            color_scheme="light",
            locale="en-US",
            viewport={"width": 1440, "height": 1000},
        )
        page = context.new_page()

        try:
            page.goto(f"{BASE_URL}/login")
            page.locator("#login-form").wait_for()
            page.locator("footer").wait_for()
            assert page.evaluate(
                "document.documentElement.scrollHeight <= window.innerHeight"
            )
            page.locator("#login-email").fill(EMAIL)
            page.locator("#login-password").fill(PASSWORD)
            page.locator('form[action="/auth/login"] button[type="submit"]').click()
            wait_for_path(page, r"/projects(?:\?.*)?")

            page.locator("#projects-list").wait_for()
            screenshot(page, "smoke-01-projects-list.png")

            page.locator('a[href="/projects/new"]').first.click()
            page.locator("#projectForm #name").fill("PR Smoke Project")
            page.locator('button[form="projectForm"][type="submit"]').first.click()
            wait_for_path(page, r"/projects/\d+\?toast=project_created")
            page.get_by_text("PR Smoke Project", exact=True).first.wait_for()
            screenshot(page, "smoke-02-project-detail.png")

            page.locator("button.avatar").click()
            page.get_by_text("Language", exact=True).wait_for()
            page.get_by_text("Appearance", exact=True).wait_for()
            page.locator('a[href="/user/api-tokens"]').click()
            wait_for_path(page, r"/user/api-tokens")
            page.get_by_text("API Tokens", exact=True).first.wait_for()
            screenshot(page, "smoke-03-account-settings.png")

            page.locator("button.avatar").click()
            page.locator('form[action="/auth/logout"] button[type="submit"]').evaluate(
                "button => button.click()"
            )
            wait_for_path(page, r"/login(?:\?.*)?")
            page.locator("#login-form").wait_for()
            screenshot(page, "smoke-04-logged-out.png")
        except BaseException:
            screenshot(page, "smoke-failure.png")
            raise
        finally:
            context.close()
            browser.close()
