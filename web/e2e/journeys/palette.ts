import { check, closeBrowser } from "../lib/common";
// Command palette journey: Ctrl+K opens it, a seeded project is found and opened,
// a pasted link offers the two imports, Escape closes it and gives focus back.
import type { Page } from "@playwright/test";

import {
  launchBrowser,
  stageMarker,
  type E2eConfig,
  type LiveFixture,
} from "../lib/harness";
import { PROJECT_NAME } from "../lib/seed";

async function signIn(page: Page, f: LiveFixture): Promise<void> {
  await page.goto(`${f.origin}/app/`, { waitUntil: "networkidle" });
  await page.locator("#email").waitFor({ state: "visible" });
  await page.locator("#email").fill(f.env.STRICKNANI_TEST_EMAIL);
  await page.locator("#password").fill(f.env.STRICKNANI_TEST_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { name: "Projects" }).waitFor({
    state: "visible",
  });
}

export async function runPaletteJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const stage = stageMarker(f.privateDir);
  const browser = await launchBrowser(cfg);
  try {
    const context = await browser.newContext({
      locale: "en-US",
      viewport: { width: 1280, height: 900 },
      serviceWorkers: "block",
    });
    try {
      const page = await context.newPage();
      stage.mark("sign in");
      await signIn(page, f);

      stage.mark("open with the keyboard");
      const dialog = page.getByRole("dialog", { name: "Search everything" });
      await page.keyboard.press("Control+k");
      await dialog.waitFor({ state: "visible" });
      const input = dialog.getByRole("combobox");
      check(
        await input.evaluate((el) => el === document.activeElement),
        "the search field is not focused",
      );

      stage.mark("find a project");
      await input.fill(PROJECT_NAME.slice(0, 7).toLowerCase());
      const row = dialog.getByRole("option", { name: PROJECT_NAME });
      await row.first().waitFor({ state: "visible" });
      await page.keyboard.press("Enter");
      await page.waitForURL(/\/app\/projects\/\d+/);
      await dialog.waitFor({ state: "hidden" });

      stage.mark("a pasted link offers both imports");
      await page.keyboard.press("Control+k");
      await dialog.waitFor({ state: "visible" });
      await input.fill("https://example.com/patterns/socks");
      const importOptions = dialog.getByRole("option");
      await importOptions.first().waitFor({ state: "visible" });
      const names = await importOptions.allInnerTexts();
      check(
        names.length === 2 &&
          names[0].includes("Import this pattern") &&
          names[1].includes("Import as yarn"),
        "a pasted link did not offer exactly the two imports",
      );
      await importOptions.nth(1).click();
      await page.waitForURL(/\/app\/yarns\/new\?url=/);

      stage.mark("escape closes and restores focus");
      // The header has no search button: the palette opens with the shortcut.
      const trigger = page.getByRole("link", { name: "Projects" }).first();
      await trigger.focus();
      await page.keyboard.press("Control+k");
      await dialog.waitFor({ state: "visible" });
      await page.keyboard.press("Escape");
      await dialog.waitFor({ state: "hidden" });
      check(
        await trigger.evaluate((el) => el === document.activeElement),
        "focus did not return to the element that opened the palette",
      );
    } finally {
      await context.close();
    }
  } finally {
    await closeBrowser(browser);
  }
}
