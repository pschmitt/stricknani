import { closeBrowser } from "../lib/common";
// Account settings journey against a disposable Rust server.
// Port of ci/stricknani/web_account_browser.py.
import type { Dialog, Page, Response, Route } from "@playwright/test";

import { check, tinyPngFixture } from "../lib/common";
import {
  launchBrowser,
  stageMarker,
  type E2eConfig,
  type LiveFixture,
} from "../lib/harness";

function pathOf(url: string): string {
  try {
    return new URL(url).pathname;
  } catch {
    return "";
  }
}

async function guardLoopback(page: Page, origin: string): Promise<void> {
  await page.route("**/*", async (route: Route) => {
    const parsed = new URL(route.request().url());
    const requestOrigin = `${parsed.protocol}//${parsed.host}`;
    if (requestOrigin !== origin) {
      await route.abort("blockedbyclient");
      return;
    }
    await route.continue();
  });
}

async function valueIsNonempty(page: Page, selector: string): Promise<boolean> {
  return Boolean(
    await page
      .locator(selector)
      .evaluate(
        (element) =>
          "value" in element &&
          typeof element.value === "string" &&
          element.value.length > 0,
      ),
  );
}

async function assertNavigationStays(
  page: Page,
  expectedPath = "/app/account",
): Promise<void> {
  page.once("dialog", (dialog: Dialog) => {
    void dialog.dismiss();
  });
  const projectLink = page.getByRole("link", { name: "Projects" }).first();
  const destination = await projectLink.evaluate(
    (link) => new URL((link as HTMLAnchorElement).href).pathname,
  );
  await projectLink.click();
  check(
    pathOf(page.url()) === expectedPath && destination !== expectedPath,
    "navigation left the account page despite the unsaved-data guard",
  );
}

/** The token form sits in a collapsed disclosure behind "New token". */
async function openTokenForm(page: Page): Promise<void> {
  const toggle = page.getByRole("button", { name: "New token", exact: true });
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await page.getByLabel("Token name").waitFor({ state: "visible" });
}

async function assertUnloadIsGuarded(page: Page): Promise<void> {
  const blocked: boolean = await page.evaluate(() => {
    const event = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  });
  check(blocked, "unsaved account data did not guard document unload");
}

export async function runAccountJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const origin = f.origin;
  const email = f.env.STRICKNANI_TEST_EMAIL;
  const oldPassword = f.env.STRICKNANI_TEST_PASSWORD;
  const stage = stageMarker(f.privateDir);
  const markStage = (value: string): void => stage.mark(value);

  const browser = await launchBrowser(cfg);
  try {
    const context = await browser.newContext({
      locale: "en-US",
      viewport: { width: 1280, height: 900 },
      serviceWorkers: "block",
    });
    try {
      const page = await context.newPage();
      await guardLoopback(page, origin);

      page.on("response", (response: Response) => {
        const path = pathOf(response.url());
        const method = response.request().method();
        if (path === "/api/v2/account/api-tokens") {
          const action = method === "POST" ? "create" : "list";
          markStage(`token ${action} HTTP ${response.status()}`);
        } else if (
          path.startsWith("/api/v2/account/api-tokens/") &&
          method === "DELETE"
        ) {
          markStage(`token revoke HTTP ${response.status()}`);
        }
      });

      markStage("sign in");
      await page.goto(`${origin}/app/account`, { waitUntil: "networkidle" });
      await page.locator("#email").waitFor({ state: "visible" });
      await page.locator("#email").fill(email);
      await page.locator("#password").fill(oldPassword);
      await page.getByRole("button", { name: "Sign in" }).click();
      await page.getByRole("heading", { name: "Projects" }).waitFor({
        state: "visible",
      });

      markStage("navigate to account route");
      await page.goto(`${origin}/app/account`, { waitUntil: "networkidle" });
      markStage("wait for account heading");
      await page
        .getByRole("heading", { name: "Account", exact: true })
        .waitFor({
          state: "visible",
        });
      markStage("wait for profile heading");
      await page.getByRole("heading", { name: "Profile" }).waitFor({
        state: "visible",
      });
      markStage("load profile data");
      await page
        .locator(".profile__email, .notice-error")
        .first()
        .waitFor({ state: "visible" });
      check(
        (await page.locator(".profile__email").count()) === 1,
        "the account profile request failed",
      );
      check(
        (await page.locator(".profile__email").innerText()).trim() === email,
        "the profile email did not match the signed-in fixture",
      );

      markStage("protect token-name draft during navigation");
      await openTokenForm(page);
      await page.getByLabel("Token name").fill("Browser acceptance token");
      await assertUnloadIsGuarded(page);
      await assertNavigationStays(page);
      check(
        (await page.getByLabel("Token name").inputValue()) ===
          "Browser acceptance token",
        "navigation discarded the token-name draft",
      );

      markStage("mobile layout");
      for (const width of [390, 320]) {
        await page.setViewportSize({ width, height: 844 });
        await page.getByLabel("Token name").waitFor({ state: "visible" });
        const mobileMetrics = await page.evaluate(() => {
          const field = document.querySelector("#token-name");
          const button = document.querySelector(".token-create-row button");
          return {
            viewport: document.documentElement.clientWidth,
            content: document.documentElement.scrollWidth,
            fieldFont: field ? parseFloat(getComputedStyle(field).fontSize) : 0,
            buttonHeight: button ? button.getBoundingClientRect().height : 0,
          };
        });
        check(
          mobileMetrics.content <= mobileMetrics.viewport &&
            mobileMetrics.fieldFont >= 16 &&
            mobileMetrics.buttonHeight >= 44,
          "account settings overflow or have undersized mobile controls",
        );
      }
      await page.setViewportSize({ width: 1280, height: 900 });

      markStage("submit new API token name");
      await page.getByRole("button", { name: "Create token" }).click();
      markStage("show one-time token dialog");
      const tokenDialog = page.getByRole("dialog", {
        name: "Your new API token",
      });
      await tokenDialog.waitFor({ state: "visible" });
      await tokenDialog
        .getByRole("heading", { name: "Your new API token" })
        .waitFor({ state: "visible" });
      markStage("verify one-time token display");
      check(
        Boolean(
          await page
            .locator("[data-secret-value]")
            .evaluate(
              (input) =>
                input instanceof HTMLInputElement && input.value.length > 0,
            ),
        ),
        "the one-time token was not shown",
      );
      markStage("protect one-time token reveal");
      await page.keyboard.press("Escape");
      check(
        await tokenDialog.isVisible(),
        "Escape dismissed a token that had not been acknowledged",
      );
      await assertUnloadIsGuarded(page);
      markStage("copy one-time token");
      await page.evaluate(() => {
        const target = window as unknown as Record<string, unknown>;
        target.__accountClipboardDenied = false;
        Object.defineProperty(navigator, "clipboard", {
          configurable: true,
          value: {
            writeText: () => {
              target.__accountClipboardDenied = true;
              return Promise.reject(
                new DOMException("Permission denied", "NotAllowedError"),
              );
            },
          },
        });
      });
      await page.getByRole("button", { name: "Copy token" }).click();
      await tokenDialog.locator(".copy-status").waitFor({ state: "visible" });
      check(
        (await tokenDialog.locator(".copy-status").innerText()) ===
          "Copy was blocked. Select and copy the token manually.",
        "the denied clipboard write did not show its manual-copy fallback",
      );
      check(
        await page.evaluate(
          () =>
            (window as unknown as Record<string, unknown>)
              .__accountClipboardDenied === true,
        ),
        "the browser clipboard denial case was not exercised",
      );
      check(
        await page
          .locator("[data-secret-value]")
          .evaluate((input) => input === document.activeElement),
        "the manual-copy fallback did not focus the token field",
      );
      check(
        await page
          .locator("[data-secret-value]")
          .evaluate(
            (input) =>
              input instanceof HTMLInputElement &&
              input.selectionStart === 0 &&
              input.selectionEnd === input.value.length &&
              input.value.length > 0,
          ),
        "the manual-copy fallback did not select the full token",
      );
      await page.evaluate(() => {
        delete (window as unknown as Record<string, unknown>)
          .__accountClipboardDenied;
      });
      markStage("acknowledge one-time token");
      await page.getByLabel("I saved this token somewhere safe.").check();
      markStage("hide one-time token");
      await page.getByRole("button", { name: "Hide token" }).click();
      await page.waitForTimeout(50);
      const tokenDialogState: Record<string, boolean> = await page.evaluate(
        () => ({
          open: document.querySelector("dialog[open]") !== null,
          secret: document.querySelector("[data-secret-value]") !== null,
          acknowledged: (() => {
            const input = document.querySelector(".acknowledge-token input");
            return input instanceof HTMLInputElement && input.checked;
          })(),
        }),
      );
      markStage(
        "after hide: " +
          `open=${tokenDialogState.open ? "True" : "False"}; ` +
          `secret=${tokenDialogState.secret ? "True" : "False"}; ` +
          `ack=${tokenDialogState.acknowledged ? "True" : "False"}`,
      );
      await page.waitForFunction(
        () =>
          document.querySelector("dialog[open]") === null &&
          document.querySelector("[data-secret-value]") === null,
        undefined,
        { timeout: 5000 },
      );

      markStage("open API-token revoke confirmation");
      const row = page
        .locator(".token-row")
        .filter({ hasText: "Browser acceptance token" });
      await row
        .getByRole("button", { name: "Revoke token Browser acceptance token" })
        .click();
      const revokeDialog = page.getByRole("dialog").filter({
        has: page.getByRole("heading", { name: "Revoke this token?" }),
      });
      await revokeDialog.waitFor({ state: "visible" });
      markStage("revoke API token");
      await revokeDialog.getByRole("button", { name: "Revoke token" }).click();
      markStage("verify revoked token removal");
      await row.waitFor({ state: "detached" });
      markStage("verify revoke focus target");
      // The form collapses after a token is created, so focus returns to the
      // "New token" toggle; with the form open it would be the name field.
      check(
        await page.evaluate(() => {
          const active = document.activeElement;
          return (
            active?.id === "token-name" ||
            active?.getAttribute("aria-controls") === "token-panel"
          );
        }),
        "focus did not return to the token controls after revocation",
      );

      markStage("upload and remove avatar");
      await page.locator('input[type="file"]').setInputFiles({
        name: "profile.png",
        mimeType: "image/png",
        buffer: tinyPngFixture(),
      });
      markStage("protect avatar draft during navigation");
      await assertUnloadIsGuarded(page);
      await assertNavigationStays(page);
      check(
        await page.getByText("profile.png", { exact: true }).isVisible(),
        "navigation discarded the selected avatar",
      );
      await page.getByRole("button", { name: "Upload picture" }).click();
      const avatar = page.locator(".avatar__portrait img");
      await avatar.waitFor({ state: "visible" });
      check(
        Boolean(
          await avatar.evaluate(
            (image) =>
              (image as HTMLImageElement).complete &&
              (image as HTMLImageElement).naturalWidth > 0,
          ),
        ),
        "the uploaded profile picture did not render",
      );
      await page.getByRole("button", { name: "Remove picture" }).click();
      await avatar.waitFor({ state: "detached" });
      check(
        (await page.locator(".avatar__portrait").innerText()).trim() === "R",
        "the profile placeholder did not return after removal",
      );

      markStage("reject incorrect current password");
      const passwordToggle = page.getByRole("button", {
        name: "Change password",
        exact: true,
      });
      if ((await passwordToggle.getAttribute("aria-expanded")) !== "true") {
        await passwordToggle.click();
      }
      await page.locator("#current-password").waitFor({ state: "visible" });
      await page.locator("#current-password").fill("wrong synthetic password");
      await page
        .locator("#new-password")
        .fill("New synthetic account password 2026");
      await page
        .locator("#confirm-password")
        .fill("New synthetic account password 2026");
      markStage("protect password draft during navigation");
      await assertUnloadIsGuarded(page);
      await assertNavigationStays(page);
      check(
        (await valueIsNonempty(page, "#current-password")) &&
          (await valueIsNonempty(page, "#new-password")) &&
          (await valueIsNonempty(page, "#confirm-password")),
        "navigation discarded the password draft",
      );
      await page.getByRole("button", { name: "Save new password" }).click();
      await page
        .locator("#current-password-error")
        .waitFor({ state: "visible" });
      check(
        (await valueIsNonempty(page, "#current-password")) &&
          (await valueIsNonempty(page, "#new-password")),
        "password fields were not preserved after a validation error",
      );

      markStage("change password and sign in again");
      const newPassword = "New synthetic account password 2026";
      await page.locator("#current-password").fill(oldPassword);
      await page.locator("#new-password").fill(newPassword);
      await page.locator("#confirm-password").fill(newPassword);
      await page.getByRole("button", { name: "Save new password" }).click();
      await page.locator("#email").waitFor({ state: "visible" });
      await page.locator("#email").fill(email);
      await page.locator("#password").fill(newPassword);
      await page.getByRole("button", { name: "Sign in" }).click();
      await page.getByRole("heading", { name: "Projects" }).waitFor({
        state: "visible",
      });

      markStage("check browser persistence");
      const localKeys: string[] = await page.evaluate(() =>
        Object.keys(window.localStorage),
      );
      check(
        !localKeys.some((key) =>
          ["token", "password", "draft"].some((term) =>
            key.toLowerCase().includes(term),
          ),
        ),
        "account secrets or drafts were persisted in local storage",
      );
    } finally {
      await context.close();
    }
  } finally {
    await closeBrowser(browser);
  }
}
