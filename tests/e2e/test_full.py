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


def assert_material_view(page: Page, route_name: str) -> None:
    """Catch the legacy utility classes that the Material 3 migration replaced."""

    page.locator("#main-content").wait_for()
    assert page.locator('link[href*="/static/css/material.css"]').count() == 1, (
        route_name
    )
    assert page.locator('#main-content [class~="btn"]').count() == 0, route_name
    assert page.locator('#main-content [class~="badge"]').count() == 0, route_name
    assert page.locator('#main-content [class~="collapse"]').count() == 0, route_name


def exercise_view_matrix(page: Page, project_id: int, yarn_id: int) -> None:
    """Render every server-backed HTML view in desktop and mobile layouts."""

    routes = {
        "home": "/",
        "projects": "/projects",
        "project-categories": "/projects/categories",
        "project-new": "/projects/new",
        "project-detail": f"/projects/{project_id}",
        "project-edit": f"/projects/{project_id}/edit",
        "yarns": "/yarn",
        "yarn-new": "/yarn/new",
        "yarn-detail": f"/yarn/{yarn_id}",
        "yarn-edit": f"/yarn/{yarn_id}/edit",
        "gauge": "/gauge/",
        "api-tokens": "/user/api-tokens",
        "admin-users": "/admin/users",
        "privacy": "/privacy",
        "offline": "/offline",
    }

    for viewport_name, width, height in (
        ("desktop", 1440, 1000),
        ("mobile", 390, 844),
    ):
        page.set_viewport_size({"width": width, "height": height})
        for route_name, route in routes.items():
            response = page.goto(f"{BASE_URL}{route}")
            assert response is not None and response.status < 400, route
            assert_material_view(page, route_name)
            screenshot(page, f"full-matrix-{viewport_name}-{route_name}.png")


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
            project_details = page.locator('label[for="details-toggle"]').bounding_box()
            assert project_details is not None
            assert project_details["width"] > 500
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
            yarn_name_box = page.locator("#yarnForm #name").bounding_box()
            assert yarn_name_box is not None
            assert yarn_name_box["width"] > 500
            page.locator("#yarnForm #name").fill("CI E2E Yarn")
            page.locator('#yarnForm input[name="brand"]').last.fill("CI Brand")
            page.locator('#yarnForm input[name="colorway"]').last.fill("CI Blue")
            page.locator('button[form="yarnForm"][type="submit"]').first.click()
            wait_for_path(page, r"/yarn/\d+\?toast=yarn_created")
            page.get_by_text("CI E2E Yarn", exact=True).first.wait_for()
            yarn_details = page.locator('label[for="details-toggle"]').bounding_box()
            assert yarn_details is not None
            assert yarn_details["width"] > 500
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

            project_id_match = re.search(r"/projects/(\d+)", project_edit_href)
            yarn_id_match = re.search(r"/yarn/(\d+)", yarn_edit_href)
            assert project_id_match is not None and yarn_id_match is not None
            exercise_view_matrix(
                page,
                project_id=int(project_id_match.group(1)),
                yarn_id=int(yarn_id_match.group(1)),
            )
            page.set_viewport_size({"width": 1440, "height": 1000})

            page.goto(f"{BASE_URL}/projects")
            assert page.locator(".md3-search-bar__control .mdi-magnify").is_visible()
            page.get_by_role("button", name="More options").click()
            page.locator(
                'button[data-action="open-dialog"][data-dialog-id="importDialog"]:visible'
            ).click()
            page.locator("#importDialog[open]").wait_for()
            page.locator("#importUrl").wait_for()
            import_url_box = page.locator("#importUrl").bounding_box()
            assert import_url_box is not None
            assert import_url_box["width"] > 400
            assert page.locator("#importDialog[open] .md3-dialog-surface").evaluate(
                "element => getComputedStyle(element).borderRadius"
            )
            page.locator('#importDialog [data-action="close-dialog"]').click()

            page.goto(f"{BASE_URL}/admin/users")
            page.locator("#admin-user-list [data-user-card]").first.wait_for()
            avatar = page.locator("#admin-user-list [data-user-card] img").first
            assert avatar.evaluate(
                "element => [element.clientWidth, element.clientHeight]"
            ) == [48, 48]
            page.get_by_role("button", name="New User").first.click()
            page.locator("#createUserDialog[open]").wait_for()
            assert page.locator("#create-user-email").is_visible()
            create_user_email_box = page.locator("#create-user-email").bounding_box()
            assert create_user_email_box is not None
            assert create_user_email_box["width"] > 400
            page.locator('#createUserDialog [data-action="close-dialog"]').click()

            page.goto(f"{BASE_URL}/projects")
            page.set_viewport_size({"width": 390, "height": 844})
            page.reload()
            page.locator("#projects-list").wait_for()
            assert page.locator('a[href="/projects/new"]').last.is_visible()
            screenshot(page, "full-06-responsive-projects.png")

            context.set_offline(True)
            page.locator("#offlineBanner").wait_for(state="visible")
            screenshot(page, "full-07-offline-banner.png")
            context.set_offline(False)

            page.locator("button.md3-navbar__account-trigger").click()
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
