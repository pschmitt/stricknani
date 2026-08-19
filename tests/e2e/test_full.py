"""Longer browser journeys for manual and scheduled verification."""

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
    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(SCREENSHOT_DIR / name), full_page=True)


def wait_for_path(page: Page, pattern: str) -> None:
    page.wait_for_url(re.compile(rf"{re.escape(BASE_URL)}{pattern}"))


def test_full_user_journey() -> None:
    """Exercise the disposable fixture's project and yarn CRUD journeys."""

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
            page.locator("#login-email").fill(EMAIL)
            page.locator("#login-password").fill(PASSWORD)
            page.locator('form[action="/auth/login"] button[type="submit"]').click()
            wait_for_path(page, r"/projects(?:\?.*)?")
            screenshot(page, "full-01-projects-list.png")

            page.locator('a[href="/projects/new"]').first.click()
            page.locator("#projectForm #name").fill("CI E2E Project")
            page.locator('button[form="projectForm"][type="submit"]').first.click()
            wait_for_path(page, r"/projects/\d+\?toast=project_created")
            page.get_by_text("CI E2E Project", exact=True).first.wait_for()
            screenshot(page, "full-02-project-detail.png")

            project_edit_href = page.locator(
                'a[href^="/projects/"][href$="/edit"]'
            ).first.get_attribute("href")
            assert project_edit_href is not None
            page.goto(f"{BASE_URL}{project_edit_href}")
            page.locator("#projectForm").wait_for()
            page.locator("#projectForm #name").fill("CI E2E Project Updated")
            page.locator('button[form="projectForm"][type="submit"]').first.click()
            wait_for_path(page, r"/projects/\d+\?toast=project_updated")
            page.get_by_text("CI E2E Project Updated", exact=True).first.wait_for()
            screenshot(page, "full-03-project-edit-result.png")

            page.goto(f"{BASE_URL}/yarn")
            page.locator('a[href="/yarn/new"]').first.click()
            page.locator("#yarnForm #name").fill("CI E2E Yarn")
            page.locator('#yarnForm input[name="brand"]').last.fill("CI Brand")
            page.locator('#yarnForm input[name="colorway"]').last.fill("CI Blue")
            page.locator('button[form="yarnForm"][type="submit"]').first.click()
            wait_for_path(page, r"/yarn/\d+\?toast=yarn_created")
            page.get_by_text("CI E2E Yarn", exact=True).first.wait_for()
            screenshot(page, "full-04-yarn-detail.png")

            yarn_edit_href = page.locator(
                'a[href^="/yarn/"][href$="/edit"]'
            ).first.get_attribute("href")
            assert yarn_edit_href is not None
            page.goto(f"{BASE_URL}{yarn_edit_href}")
            page.locator("#yarnForm").wait_for()
            page.locator("#yarnForm #name").fill("CI E2E Yarn Updated")
            page.locator('button[form="yarnForm"][type="submit"]').first.click()
            wait_for_path(page, r"/yarn/\d+\?toast=yarn_updated")
            page.get_by_text("CI E2E Yarn Updated", exact=True).first.wait_for()
            screenshot(page, "full-05-yarn-edit-result.png")

            page.goto(f"{BASE_URL}/projects")
            page.get_by_role("button", name="More options").click()
            page.locator(
                'button[data-action="open-dialog"][data-dialog-id="importDialog"]:visible'
            ).click()
            page.locator("#importDialog[open]").wait_for()
            page.locator("#importUrl").wait_for()
            page.locator('#importDialog [data-action="close-dialog"]').click()

            page.set_viewport_size({"width": 390, "height": 844})
            page.reload()
            page.locator("#projects-list").wait_for()
            assert page.locator('a[href="/projects/new"]').last.is_visible()
            screenshot(page, "full-06-responsive-projects.png")

            context.set_offline(True)
            page.locator("#offlineBanner").wait_for(state="visible")
            screenshot(page, "full-07-offline-banner.png")
            context.set_offline(False)

            page.locator("button.avatar").click()
            page.locator('form[action="/auth/logout"] button[type="submit"]').evaluate(
                "button => button.click()"
            )
            wait_for_path(page, r"/login(?:\?.*)?")
            page.locator("#login-form").wait_for()
            screenshot(page, "full-08-logged-out.png")
        except BaseException:
            screenshot(page, "full-failure.png")
            raise
        finally:
            context.close()
            browser.close()
