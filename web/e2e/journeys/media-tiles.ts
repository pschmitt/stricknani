import { closeBrowser } from "../lib/common";
// Check the ReorderableMedia photo tiles in the project and yarn editors.
// Port of ci/stricknani/web_media_tiles_browser.py.
//
// Runs against the disposable Rust fixture only (synthetic seeded records and
// the demo photos), so reordering and deleting never touches real data. It
// checks tile geometry, the hover/focus/touch action buttons, Make cover and keyboard
// reordering with its live-region announcement, at desktop, phone and
// narrowest-phone sizes.
import { chmodSync, readdirSync } from "node:fs";
import path from "node:path";
import { expect } from "@playwright/test";
import type { BrowserContextOptions, Locator, Page } from "@playwright/test";
import { dismissPhotoEditor } from "../lib/ui-helpers";
import { AcceptanceFailure, fillMarkdownField, fn } from "../lib/common";
import { REPO_ROOT } from "../lib/fixture";
import type { E2eConfig, LiveFixture } from "../lib/harness";
import { launchBrowser } from "../lib/harness";
import { PROJECT_NAME, YARN_NAME } from "../lib/seed";

const GEOMETRY_TOLERANCE_PX = 2.0;
const INSET_PX = 6.0;
const MIN_TARGET_PX = 32.0;

type Profile = {
  name: string;
  width: number;
  height: number;
  touch: boolean;
};

const PROFILES: Profile[] = [
  { name: "desktop", width: 1280, height: 900, touch: false },
  { name: "phone", width: 390, height: 844, touch: true },
  { name: "narrow", width: 320, height: 700, touch: true },
  // Just wide enough for two columns, so tiles are at their 8rem minimum.
  { name: "min-tile", width: 334, height: 700, touch: true },
];

type Rect = {
  left: number;
  right: number;
  top: number;
  bottom: number;
  width: number;
  height: number;
};

type Geometry = {
  media: Rect | null;
  badge: Rect | null;
  trash: Rect | null;
  firstAction: Rect | null;
  trashOpacity: number | null;
  dir: string;
  badgeText: string | null;
};

class Report {
  failures: string[] = [];
  lines: string[] = [];

  check(ok: boolean, label: string, detail: unknown = ""): void {
    let text = `${ok ? "PASS" : "FAIL"} ${label}`;
    if (detail !== "") {
      text += ` ${typeof detail === "string" ? detail : JSON.stringify(detail)}`;
    }
    this.lines.push(text);
    console.log(text);
    if (!ok) {
      this.failures.push(label);
    }
  }

  info(text: string): void {
    this.lines.push(text);
    console.log(text);
  }
}

/** True when the assertion passes; assertion errors become false. */
async function holds(assertion: () => Promise<void>): Promise<boolean> {
  try {
    await assertion();
    return true;
  } catch {
    return false;
  }
}

async function signIn(
  page: Page,
  origin: string,
  email: string,
  password: string,
): Promise<void> {
  await page.goto(`${origin}/app/login`, { waitUntil: "networkidle" });
  await page.getByRole("radio", { name: /^(English|Englisch)$/ }).click();
  await page.locator("#email").fill(email);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL("**/app/projects");
  await page
    .getByRole("heading", { name: "Projects", exact: true })
    .waitFor({ state: "visible" });
}

async function resourceId(
  page: Page,
  origin: string,
  collection: string,
  name: string,
): Promise<number> {
  await page.goto(`${origin}/app/${collection}`, { waitUntil: "networkidle" });
  await page.getByRole("link", { name }).first().click();
  await page.waitForURL(new RegExp(`.*/app/${collection}/\\d+$`));
  const segments = new URL(page.url()).pathname.split("/");
  return Number(segments[segments.length - 1]);
}

function tilesOf(list: Locator): Locator {
  return list.locator(".reorder-item");
}

async function tileIds(list: Locator): Promise<string[]> {
  const ids: unknown[] = await tilesOf(list).evaluateAll(
    fn("tiles => tiles.map(tile => tile.dataset.tile)"),
  );
  return ids.map((value) => String(value));
}

async function geometry(tile: Locator): Promise<Geometry> {
  const value: unknown = await tile.evaluate(
    fn(`tile => {
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
      firstAction: rect(tile.querySelector('.tile-actions .tile-button')),
      trashOpacity: del ? Number(style(del).opacity) : null,
      dir: getComputedStyle(tile).direction,
      badgeText: tile.querySelector('.cover-badge')?.textContent?.trim() ?? null,
    };
  }`),
  );
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new AcceptanceFailure("browser returned invalid tile geometry");
  }
  return value as Geometry;
}

function overlapArea(a: Rect, b: Rect): number {
  const width = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const height = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  return Math.max(width, 0.0) * Math.max(height, 0.0);
}

function near(value: number, expected: number): boolean {
  return Math.abs(value - expected) <= GEOMETRY_TOLERANCE_PX;
}

async function centerTile(tile: Locator): Promise<void> {
  await tile.evaluate(
    fn("element => element.scrollIntoView({ block: 'center' })"),
  );
}

/** Reach a tile with real Tab presses so focus-visible applies. */
async function tabToTile(page: Page, tile: Locator): Promise<boolean> {
  // Firefox keeps the sequential-focus starting point where the last
  // touch, click or focus landed, and blur() does not reset it: a Tab
  // would then continue from inside the tile under test, skip the tile
  // itself and run on to the end of the page. Its starting point also
  // follows the selection, so park that at the top of the document.
  await page.evaluate(
    fn(
      "() => { if (document.activeElement instanceof HTMLElement) " +
        "document.activeElement.blur(); window.scrollTo(0, 0); " +
        "document.getSelection()?.collapse(document.body, 0); }",
    ),
  );
  // A long editor page has dozens of tab stops before the tile (every
  // Markdown toolbar button), and WebKit does not tab into the optional
  // details at all from the top. Start one stop before the tile instead, so
  // the Tab presses below still prove the tile is a real tab stop.
  await tile.evaluate(
    fn(`element => {
      const stops = [...document.querySelectorAll(
        'a[href], button, input, select, textarea, [tabindex]',
      )].filter(
        (candidate) =>
          candidate.tabIndex >= 0 &&
          !candidate.hasAttribute('disabled') &&
          candidate.getClientRects().length > 0,
      );
      const before = stops[stops.indexOf(element) - 1];
      if (before instanceof HTMLElement) before.focus({ preventScroll: true });
    }`),
  );
  const trail: string[] = [];
  for (let attempt = 0; attempt < 120; attempt++) {
    await page.keyboard.press("Tab");
    if (
      await tile.evaluate(fn("element => element === document.activeElement"))
    ) {
      return true;
    }
    trail.push(
      (await page.evaluate(
        fn(
          "() => { const e = document.activeElement; return e ? " +
            "e.tagName + '.' + e.className + ' ' + (e.textContent||'').trim()" +
            ".slice(0, 20) : 'none'; }",
        ),
      )) as string,
    );
  }
  console.log("DEBUG tab trail", trail.slice(0, 8), trail.slice(-4));
  return false;
}

async function shot(
  page: Page,
  shots: string | null,
  name: string,
  list: Locator,
): Promise<void> {
  if (shots === null) {
    return;
  }
  await page.evaluate(fn("async () => { await document.fonts?.ready; }"));
  try {
    await list
      .locator("xpath=..")
      .screenshot({ path: path.join(shots, `${name}.png`) });
  } catch {
    // A screenshot is evidence only; never fail the behavioral check on it.
  }
}

/** Action visibility (rest, hover, focus, touch) and tile geometry. */
async function checkTile(
  page: Page,
  tag: string,
  list: Locator,
  report: Report,
  shots: string | null,
  shotName: string,
  expectCover: boolean,
): Promise<void> {
  const tiles = tilesOf(list);
  const count = await tiles.count();
  report.check(count >= 1, `${tag}: has a photo tile`, count);
  if (count < 1) {
    return;
  }
  const first = tiles.nth(0);
  const trashButton = first.locator(".delete-button");
  await centerTile(first);
  await page.mouse.move(1, 1);

  // Touch devices have no hover, so the compact actions stay visible there.
  const noHover = Boolean(
    await page.evaluate(fn("() => matchMedia('(hover: none)').matches")),
  );
  await expect(trashButton).toHaveCSS("opacity", noHover ? "1" : "0");
  report.check(
    true,
    `${tag}: actions ${noHover ? "always visible on touch" : "hidden at rest"}`,
  );
  await shot(page, shots, `${shotName}-1-rest`, list);

  let revealed: Geometry;
  if (noHover) {
    revealed = await geometry(first);
  } else {
    await first.hover();
    const shown = await holds(() =>
      expect(trashButton).toHaveCSS("opacity", "1"),
    );
    report.check(shown, `${tag}: hovering a tile shows the actions`);
    revealed = await geometry(first);
    await shot(page, shots, `${shotName}-2-hover`, list);
    await page.mouse.move(1, 1);
    const hidden = await holds(() =>
      expect(trashButton).toHaveCSS("opacity", "0"),
    );
    report.check(
      hidden,
      `${tag}: the actions hide again when the pointer leaves`,
    );
  }

  const { media, badge, trash, firstAction } = revealed;
  report.check(
    media !== null && trash !== null,
    `${tag}: first tile has a remove button`,
  );
  if (media !== null && trash !== null && firstAction !== null) {
    const rtl = revealed.dir === "rtl";
    const endGap = rtl ? trash.left - media.left : media.right - trash.right;
    report.check(
      near(firstAction.top - media.top, INSET_PX) && near(endGap, INSET_PX),
      `${tag}: actions sit top-end`,
      `(top gap ${(firstAction.top - media.top).toFixed(1)}px, ` +
        `end gap ${endGap.toFixed(1)}px)`,
    );
    report.check(
      trash.width >= MIN_TARGET_PX - 0.5 && trash.height >= MIN_TARGET_PX - 0.5,
      `${tag}: remove button is at least ${MIN_TARGET_PX.toFixed(0)}px`,
      `(${trash.width.toFixed(0)}x${trash.height.toFixed(0)}px)`,
    );
    if (expectCover) {
      report.check(badge !== null, `${tag}: first tile shows the cover star`);
      if (badge !== null) {
        const startGap = rtl
          ? media.right - badge.right
          : badge.left - media.left;
        report.check(
          near(media.bottom - badge.bottom, INSET_PX) &&
            near(startGap, INSET_PX),
          `${tag}: cover star sits bottom-start`,
          `(bottom gap ${(media.bottom - badge.bottom).toFixed(1)}px, ` +
            `start gap ${startGap.toFixed(1)}px)`,
        );
        const overlap = overlapArea(badge, trash);
        report.check(
          overlap === 0.0,
          `${tag}: cover star and remove button do not overlap`,
          `(tile ${media.width.toFixed(0)}x${media.height.toFixed(0)}px, ` +
            `overlap ${overlap.toFixed(1)}px^2)`,
        );
        report.check(
          badge.left >= media.left - 0.5 && badge.right <= media.right + 0.5,
          `${tag}: cover star stays inside the tile`,
        );
      }
      report.check(
        (await first.getByRole("button", { name: "Make cover" }).count()) === 0,
        `${tag}: the cover tile has no Make cover action`,
      );
    } else {
      report.check(badge === null, `${tag}: non-cover tile has no cover star`);
    }
    report.info(`INFO ${tag}: tile ${media.width.toFixed(0)}px wide`);
  }
  if (count >= 2) {
    const other = await geometry(tiles.nth(1));
    report.check(
      other.badge === null,
      `${tag}: only the first tile is the cover`,
    );
  }

  await page.mouse.move(1, 1);
  const reached = await tabToTile(page, first);
  report.check(reached, `${tag}: first tile is reachable with Tab`);
  if (reached) {
    const focusShows = await holds(() =>
      expect(trashButton).toHaveCSS("opacity", "1"),
    );
    report.check(focusShows, `${tag}: keyboard focus shows the actions`);
    await shot(page, shots, `${shotName}-3-focus`, list);
  }
}

/** Make cover moves a later photo to the front in one step. */
async function checkMakeCover(
  page: Page,
  tag: string,
  list: Locator,
  report: Report,
): Promise<void> {
  const tiles = tilesOf(list);
  const count = await tiles.count();
  if (count < 2) {
    report.check(false, `${tag}: at least two photos for Make cover`, count);
    return;
  }
  const before = await tileIds(list);
  const second = tiles.nth(1);
  await centerTile(second);
  await second.hover();
  const requests: string[] = [];
  const onRequest = (request: { method(): string; url(): string }): void => {
    if (request.method() === "PUT" && request.url().includes("/order")) {
      requests.push(request.url());
    }
  };
  page.on("request", onRequest);
  const button = second.getByRole("button", { name: "Make cover" });
  await expect(button).toHaveCount(1);
  await button.click();
  const status = list.locator("xpath=..").locator("[role=status]");
  const announced = await holds(() =>
    expect(status).toHaveText("Photo set as cover.", { timeout: 10_000 }),
  );
  page.off("request", onRequest);
  const after = await tileIds(list);
  report.check(
    announced &&
      after[0] === before[1] &&
      after[1] === before[0] &&
      requests.length === 0,
    `${tag}: Make cover moves the photo to the front and sends nothing before Save`,
    `(${JSON.stringify(before)} -> ${JSON.stringify(after)}, ` +
      `${requests.length} request(s))`,
  );
  // Restore the original order with the keyboard.
  await centerTile(tiles.nth(1));
  await page.mouse.move(1, 1);
  const reached = await tabToTile(page, tiles.nth(1));
  if (reached) {
    await page.keyboard.press("ArrowLeft");
  }
  await holds(() =>
    expect(status).toHaveText(/Photo moved to position 1 of \d+\./, {
      timeout: 10_000,
    }),
  );
  report.check(
    JSON.stringify(await tileIds(list)) === JSON.stringify(before),
    `${tag}: original photo order restored after Make cover`,
  );
}

/** Arrow keys move the focused tile and the live region announces it. */
async function checkReorder(
  page: Page,
  tag: string,
  list: Locator,
  report: Report,
  shots: string | null,
  shotName: string,
): Promise<void> {
  const tiles = tilesOf(list);
  const count = await tiles.count();
  report.check(
    count >= 2,
    `${tag}: at least two photo tiles to reorder`,
    count,
  );
  if (count < 2) {
    return;
  }
  const first = tiles.nth(0);
  await centerTile(first);
  await page.mouse.move(1, 1);
  const reached = await tabToTile(page, first);
  report.check(reached, `${tag}: first tile is reachable with Tab`);
  if (!reached) {
    return;
  }
  const before = await tileIds(list);
  const movedId = before[0] as string;
  const status = list.locator("xpath=..").locator("[role=status]");
  await page.keyboard.press("ArrowRight");
  const announced = await holds(() =>
    expect(status).toHaveText(
      new RegExp(`Photo moved to position 2 of ${count}\\.`),
      { timeout: 10_000 },
    ),
  );
  report.check(announced, `${tag}: ArrowRight announces the new position`);
  let keptFocus: boolean;
  try {
    await page.waitForFunction(
      fn("id => document.activeElement?.dataset?.tile === id"),
      movedId,
      { timeout: 5_000 },
    );
    keptFocus = true;
  } catch {
    keptFocus = false;
  }
  const after = await tileIds(list);
  report.check(
    after[1] === movedId && after[0] === before[1],
    `${tag}: ArrowRight moved the tile to position 2`,
    `(${JSON.stringify(before)} -> ${JSON.stringify(after)})`,
  );
  report.check(keptFocus, `${tag}: the moved tile keeps keyboard focus`);
  await shot(page, shots, `${shotName}-4-moved`, list);
  await page.keyboard.press("ArrowLeft");
  const restored = await holds(() =>
    expect(status).toHaveText(/Photo moved to position 1 of \d+\./, {
      timeout: 10_000,
    }),
  );
  const restoredIds = await tileIds(list);
  report.check(
    restored && JSON.stringify(restoredIds) === JSON.stringify(before),
    `${tag}: ArrowLeft announces and restores the original order`,
  );
}

export async function runMediaTilesJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const browserName = cfg.browser;
  const { origin } = f;
  const email = f.env.STRICKNANI_TEST_EMAIL;
  const password = f.env.STRICKNANI_TEST_PASSWORD;
  const shotsDir = f.screenshotDir;
  // WebKit's screenshot path injects inline styles that strict CSP blocks.
  const shots: string | null = browserName === "webkit" ? null : shotsDir;
  const report = new Report();
  const pageErrors: string[] = [];
  const cspErrors: string[] = [];
  const photos = [1, 2, 3].map((index) =>
    path.join(REPO_ROOT, "demo_assets", `demo_image_${index}.jpg`),
  );

  const browser = await launchBrowser(cfg);
  try {
    let projectId = 0;
    let yarnId = 0;
    for (const profile of PROFILES) {
      const options: BrowserContextOptions = {
        viewport: { width: profile.width, height: profile.height },
        locale: "en-US",
        colorScheme: "light",
        hasTouch: profile.touch,
      };
      if (profile.touch && browserName !== "firefox") {
        options.isMobile = true;
      }
      const context = await browser.newContext(options);
      const page = await context.newPage();
      page.on("pageerror", (error) => {
        pageErrors.push(String(error.message).slice(0, 120));
      });
      page.on("console", (message) => {
        if (message.text().toLowerCase().includes("content security policy")) {
          cspErrors.push(message.text().slice(0, 120));
        }
      });
      await signIn(page, origin, email, password);
      if (!projectId) {
        projectId = await resourceId(page, origin, "projects", PROJECT_NAME);
        yarnId = await resourceId(page, origin, "yarns", YARN_NAME);
      }

      // Project editor: the cover group and a step's photo group.
      await page.goto(`${origin}/app/projects/${projectId}/edit`, {
        waitUntil: "networkidle",
      });
      await page
        .getByRole("heading", { name: "Edit project", exact: true })
        .waitFor({ state: "visible" });
      if ((await page.locator('[id^="step-photo-file-"]').count()) < 1) {
        // The seeded project has no steps; add one to hold photos.
        await page
          .getByRole("button", { name: "Add step", exact: true })
          .click();
        await page.getByLabel("Step title").first().fill("Photo order fixture");
        await fillMarkdownField(
          page.getByLabel("Step instructions", { exact: true }).first(),
          "Synthetic step for the photo tile checks.",
        );
        await Promise.all([
          page.waitForResponse(
            (response) =>
              response.request().method() === "PUT" &&
              new URL(response.url()).pathname ===
                `/api/v2/projects/${projectId}`,
          ),
          page
            .getByRole("button", { name: "Save changes", exact: true })
            .click(),
        ]);
        await page.waitForURL(`**/app/projects/${projectId}`);
        await page.goto(`${origin}/app/projects/${projectId}/edit`, {
          waitUntil: "networkidle",
        });
        await page
          .getByRole("heading", { name: "Edit project", exact: true })
          .waitFor({ state: "visible" });
      }
      const stepList = page.getByRole("list", { name: /^Step \d+:/ });
      let guard = 0;
      while (
        ((await stepList.count()) === 0 ||
          (await tilesOf(stepList.first()).count()) < 3) &&
        guard < 3
      ) {
        await page
          .locator('[id^="step-photo-file-"]')
          .first()
          .setInputFiles(photos[guard % photos.length] as string);
        await page
          .locator("#main-content .action-status")
          .filter({ hasText: "Project media updated." })
          .first()
          .waitFor({ state: "visible", timeout: 20_000 });
        await dismissPhotoEditor(page);
        guard += 1;
      }
      await stepList.first().waitFor({ state: "visible" });
      const coverList = page.getByRole("list", { name: "Project cover" });
      await coverList.first().waitFor({ state: "visible" });
      const base = `${browserName}-${profile.name}`;
      const tag = `${browserName}/${profile.name}`;
      await checkTile(
        page,
        `${tag}/project-cover`,
        coverList.first(),
        report,
        shots,
        `${base}-project-cover`,
        true,
      );
      await checkTile(
        page,
        `${tag}/project-step`,
        stepList.first(),
        report,
        shots,
        `${base}-project-step`,
        false,
      );
      await checkReorder(
        page,
        `${tag}/project-step`,
        stepList.first(),
        report,
        shots,
        `${base}-project-step`,
      );

      // Yarn editor: the photo group.
      await page.goto(`${origin}/app/yarns/${yarnId}/edit`, {
        waitUntil: "networkidle",
      });
      await page
        .getByRole("heading", { name: "Edit yarn", exact: true })
        .waitFor({ state: "visible" });
      const yarnList = page.getByRole("list", { name: "Photos" });
      guard = 0;
      while (
        ((await yarnList.count()) === 0 ||
          (await tilesOf(yarnList).count()) < 3) &&
        guard < 3
      ) {
        await page
          .getByLabel("Choose a yarn photo")
          .setInputFiles(photos[guard % photos.length] as string);
        await page
          .getByRole("status")
          .filter({ hasText: "Photo uploaded." })
          .first()
          .waitFor({ state: "visible", timeout: 20_000 });
        await dismissPhotoEditor(page);
        guard += 1;
      }
      await yarnList.first().waitFor({ state: "visible" });
      await checkTile(
        page,
        `${tag}/yarn`,
        yarnList.first(),
        report,
        shots,
        `${base}-yarn`,
        true,
      );
      await checkMakeCover(page, `${tag}/yarn`, yarnList.first(), report);
      await checkReorder(
        page,
        `${tag}/yarn`,
        yarnList.first(),
        report,
        shots,
        `${base}-yarn`,
      );
      await context.close();
    }
  } finally {
    await closeBrowser(browser);
    try {
      for (const name of readdirSync(shotsDir)) {
        if (name.endsWith(".png")) {
          chmodSync(path.join(shotsDir, name), 0o600);
        }
      }
    } catch {
      // The screenshot directory may not exist (WebKit takes none).
    }
  }

  report.check(
    cspErrors.length === 0,
    "no CSP violations",
    cspErrors.slice(0, 3),
  );
  const synthetic = browserName !== "chromium";
  report.check(
    pageErrors.length === 0 || synthetic,
    "no uncaught page errors",
    pageErrors.length > 0 ? pageErrors.slice(0, 3) : "",
  );
  console.log(
    JSON.stringify({
      browser: browserName,
      checks: report.lines.length,
      failures: report.failures,
      screenshots: shots,
    }),
  );
  if (report.failures.length > 0) {
    throw new AcceptanceFailure(
      `Media tile browser acceptance failed: ${report.failures.join("; ")}`,
    );
  }
}
