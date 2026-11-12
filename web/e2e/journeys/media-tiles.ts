import { closeBrowser } from "../lib/common";
// Check the ReorderableMedia photo tiles in the project and yarn editors.
// Port of ci/stricknani/web_media_tiles_browser.py.
//
// Runs against the disposable Rust fixture only (synthetic seeded records and
// the demo photos), so reordering and deleting never touches real data. It
// checks tile geometry, the hover/focus/touch action buttons (only trash and edit:
// no star, no drag grip), the Android-style reorder mode (press and hold, the
// expanded grid, drag anywhere on a tile with a mouse or a touch pointer, Done)
// and keyboard reordering with its live-region announcement, at desktop, phone
// and narrowest-phone sizes.
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
      badge: rect(tile.querySelector('.cover-badge:not(:empty)')),
      trash: rect(del),
      firstAction: rect(tile.querySelector('.tile-actions .tile-button')),
      trashOpacity: del ? Number(style(del).opacity) : null,
      dir: getComputedStyle(tile).direction,
      badgeText: tile.querySelector('.cover-badge:not(:empty)')?.textContent?.trim() ?? null,
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
    report.check(badge === null, `${tag}: no cover star or tag on the tile`);
    report.check(
      (await first
        .locator(".grip, [data-handle], .make-cover-button")
        .count()) === 0 &&
        (await first.getByRole("button", { name: "Make cover" }).count()) === 0,
      `${tag}: no star and no drag grip on the tile`,
    );
    const buttonCount = await first
      .locator(".tile-actions .tile-button")
      .count();
    report.check(
      buttonCount >= 1 && buttonCount <= 2,
      `${tag}: the tile has only the remove (and edit) buttons`,
      buttonCount,
    );
    report.info(`INFO ${tag}: tile ${media.width.toFixed(0)}px wide`);
  }
  if (count >= 2) {
    const other = await geometry(tiles.nth(1));
    report.check(other.badge === null, `${tag}: no tile wears a cover tag`);
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

type Point = { x: number; y: number };

async function centerOf(tile: Locator): Promise<Point> {
  const box = await tile.boundingBox();
  if (box === null) throw new AcceptanceFailure("tile has no bounding box");
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

/** Dispatch a synthetic touch-type pointer event on a tile (no real touch hold exists in every engine). */
async function touchEvent(
  tile: Locator,
  type: "pointerdown" | "pointermove" | "pointerup",
  at: Point,
): Promise<void> {
  await tile.evaluate(
    fn(`(element, args) => {
      element.dispatchEvent(new PointerEvent(args.type, {
        bubbles: true, cancelable: true, composed: true, pointerId: 7,
        pointerType: 'touch', isPrimary: true, button: 0,
        buttons: args.type === 'pointerup' ? 0 : 1,
        clientX: args.at.x, clientY: args.at.y,
      }));
    }`),
    { type, at },
  );
}

function isExpanded(list: Locator): Promise<boolean> {
  return list
    .locator("xpath=..")
    .evaluate(fn("element => element.classList.contains('expanded')"));
}

/** Press and hold a tile until reorder mode opens, with a mouse or a touch pointer. */
async function holdTile(
  page: Page,
  tile: Locator,
  pointer: "mouse" | "touch",
): Promise<void> {
  const at = await centerOf(tile);
  if (pointer === "mouse") {
    await page.mouse.move(at.x, at.y);
    await page.mouse.down();
    await page.waitForTimeout(600);
    await page.mouse.up();
  } else {
    await touchEvent(tile, "pointerdown", at);
    await page.waitForTimeout(600);
    await touchEvent(tile, "pointerup", at);
  }
}

/** Drag the tile at `from` onto the slot of the tile at `to` with the given pointer. */
async function dragTile(
  page: Page,
  list: Locator,
  from: number,
  to: number,
  pointer: "mouse" | "touch",
  cancelWithEscape = false,
): Promise<void> {
  const tiles = tilesOf(list);
  const source = tiles.nth(from);
  const start = await centerOf(source);
  const end = await centerOf(tiles.nth(to));
  const steps = 8;
  const at = (i: number): Point => ({
    x: start.x + ((end.x - start.x) * i) / steps,
    y: start.y + ((end.y - start.y) * i) / steps,
  });
  if (pointer === "mouse") {
    await page.mouse.move(start.x, start.y);
    await page.mouse.down();
    for (let i = 1; i <= steps; i++) {
      await page.mouse.move(at(i).x, at(i).y);
    }
    await page.waitForTimeout(250);
    if (cancelWithEscape) await page.keyboard.press("Escape");
    await page.mouse.up();
  } else {
    await touchEvent(source, "pointerdown", start);
    await page.waitForTimeout(600);
    for (let i = 1; i <= steps; i++) {
      await touchEvent(source, "pointermove", at(i));
      await page.waitForTimeout(40);
    }
    await page.waitForTimeout(250);
    if (cancelWithEscape) await page.keyboard.press("Escape");
    await touchEvent(source, "pointerup", end);
  }
}

/**
 * The Android-style reorder mode: press and hold opens an expanded grid with the
 * hint and Done, a tile drags from anywhere, nothing is sent before Save, the
 * keyboard has its own way in, and Done / Escape leave the mode.
 */
async function checkReorderMode(
  page: Page,
  tag: string,
  list: Locator,
  report: Report,
  shots: string | null,
  shotName: string,
  touch: boolean,
): Promise<void> {
  const tiles = tilesOf(list);
  const count = await tiles.count();
  if (count < 3) {
    report.check(
      false,
      `${tag}: at least three photos for reorder mode`,
      count,
    );
    return;
  }
  const root = list.locator("xpath=..");
  const status = root.locator("[role=status]");
  const before = await tileIds(list);
  const requests: string[] = [];
  const onRequest = (request: { method(): string; url(): string }): void => {
    if (request.method() === "PUT" && request.url().includes("/order")) {
      requests.push(request.url());
    }
  };
  page.on("request", onRequest);
  try {
    report.check(
      !(await isExpanded(list)),
      `${tag}: photos start in the compact layout`,
    );
    await centerTile(tiles.nth(0));
    // A short press does not enter the mode.
    const at = await centerOf(tiles.nth(1));
    await page.mouse.move(at.x, at.y);
    await page.mouse.down();
    await page.waitForTimeout(80);
    await page.mouse.up();
    // A tap opens the photo where the tile has an open action (yarn): close it.
    if ((await page.locator("dialog[open]").count()) > 0) {
      await page.keyboard.press("Escape");
      await expect(page.locator("dialog[open]")).toHaveCount(0);
    }
    report.check(
      !(await isExpanded(list)),
      `${tag}: a short press does not open reorder mode`,
    );
    // Press and hold: a mouse on the desktop, a touch pointer on phones.
    await holdTile(page, tiles.nth(1), touch ? "touch" : "mouse");
    const opened = await holds(async () => {
      await expect(root).toHaveClass(/expanded/);
    });
    report.check(opened, `${tag}: press and hold opens reorder mode`);
    if (!opened) return;
    await expect(root.getByRole("button", { name: "Done" })).toBeVisible();
    const hint = (await root.locator(".reorder-hint").textContent()) ?? "";
    report.check(
      /^Drag to reorder\./.test(hint.trim()),
      `${tag}: the hint is shown`,
      hint.trim(),
    );
    const grid = await list.evaluate(
      fn(`element => {
        const tiles = [...element.children];
        const tops = tiles.map(tile => Math.round(tile.getBoundingClientRect().top));
        const rects = tiles.map(tile => tile.getBoundingClientRect());
        const style = getComputedStyle(element);
        return {
          columns: tops.filter(top => Math.abs(top - tops[0]) <= 1).length,
          width: rects[0].width,
          gap: parseFloat(style.columnGap),
          grips: element.querySelectorAll('.grip, [data-handle]').length,
          stars: element.querySelectorAll('.make-cover-button').length,
          buttons: [...element.querySelectorAll('.tile-button')].map(b => b.className),
          trashShown: [...element.querySelectorAll('.delete-button')]
            .every(b => Number(getComputedStyle(b).opacity) === 1),
        };
      }`),
    );
    const g = grid as {
      columns: number;
      width: number;
      gap: number;
      grips: number;
      stars: number;
      buttons: string[];
      trashShown: boolean;
    };
    report.check(
      g.columns >= Math.min(3, count) && g.width >= 56 && g.gap === 10,
      `${tag}: the grid has at least three columns, 10px apart`,
      `(${g.columns} columns, ${g.width.toFixed(0)}px tiles, gap ${g.gap}px)`,
    );
    report.check(
      g.grips === 0 &&
        g.stars === 0 &&
        g.buttons.every((c) => c.includes("delete-button")),
      `${tag}: only trash icons on the tiles in reorder mode`,
      g.buttons.length,
    );
    report.check(
      g.trashShown,
      `${tag}: trash icons are visible without hovering`,
    );
    await shot(page, shots, `${shotName}-5-reorder-mode`, list);

    // Drag the first tile onto the third slot, anywhere on the tile.
    await centerTile(tiles.nth(0));
    await dragTile(page, list, 0, 2, touch ? "touch" : "mouse");
    const afterDrag = await holds(async () => {
      await expect(status).toHaveText(/Photo moved to position 3 of \d+\./, {
        timeout: 10_000,
      });
    });
    const dragged = await tileIds(list);
    report.check(
      afterDrag &&
        dragged[2] === before[0] &&
        dragged[0] === before[1] &&
        requests.length === 0,
      `${tag}: dragging a tile moves it and sends nothing before Save`,
      `(${JSON.stringify(before)} -> ${JSON.stringify(dragged)}, ${requests.length} request(s))`,
    );
    report.check(
      await list.evaluate(
        fn(
          "element => [...element.children].every(tile => !tile.style.transform)",
        ),
      ),
      `${tag}: no tile keeps a drag transform after the drop`,
    );
    await shot(page, shots, `${shotName}-6-dragged`, list);

    // Escape while dragging puts the tile back and changes nothing.
    const staged = await tileIds(list);
    await dragTile(page, list, 0, count - 1, touch ? "touch" : "mouse", true);
    const afterEscape = await tileIds(list);
    report.check(
      JSON.stringify(afterEscape) === JSON.stringify(staged) &&
        (await isExpanded(list)),
      `${tag}: Escape cancels a drag and keeps reorder mode`,
      `(${JSON.stringify(staged)} -> ${JSON.stringify(afterEscape)})`,
    );

    // Arrow keys also move the focused tile in reorder mode.
    await page.mouse.move(1, 1);
    const reached = await tabToTile(page, tiles.nth(2));
    if (reached) {
      await page.keyboard.press("ArrowLeft");
      await page.keyboard.press("ArrowLeft");
      await holds(async () => {
        await expect(status).toHaveText(/Photo moved to position 1 of \d+\./, {
          timeout: 10_000,
        });
      });
    }
    report.check(
      JSON.stringify(await tileIds(list)) === JSON.stringify(before),
      `${tag}: arrow keys restore the original order in reorder mode`,
      JSON.stringify(await tileIds(list)),
    );

    // Done leaves the mode.
    await root.getByRole("button", { name: "Done" }).click();
    report.check(
      !(await isExpanded(list)),
      `${tag}: Done returns to the compact layout`,
    );

    // The keyboard way in: the Reorder photos button, then Escape out.
    const enter = root.getByRole("button", { name: "Reorder photos" });
    await enter.focus();
    await page.keyboard.press("Enter");
    report.check(
      await isExpanded(list),
      `${tag}: the keyboard entry opens reorder mode`,
    );
    await tiles.nth(0).focus();
    await page.keyboard.press("Escape");
    report.check(
      !(await isExpanded(list)),
      `${tag}: Escape leaves reorder mode`,
    );
  } finally {
    page.off("request", onRequest);
  }
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
      );
      await checkTile(
        page,
        `${tag}/project-step`,
        stepList.first(),
        report,
        shots,
        `${base}-project-step`,
      );
      await checkReorderMode(
        page,
        `${tag}/project-step`,
        stepList.first(),
        report,
        shots,
        `${base}-project-step`,
        profile.touch,
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
      );
      await checkReorderMode(
        page,
        `${tag}/yarn`,
        yarnList.first(),
        report,
        shots,
        `${base}-yarn`,
        profile.touch,
      );
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
