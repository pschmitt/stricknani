/**
 * Shared helpers of the browser acceptance journeys (port of the common parts
 * of ci/stricknani/web_ui_browser.py).
 */
import {
  chmodSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  statSync,
} from "node:fs";
import { isIP } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { crc32, deflateSync } from "node:zlib";
import type {
  Browser,
  Dialog,
  Locator,
  Page,
  Response,
  Route,
} from "@playwright/test";
import { outsideRepository } from "./fixture";
import { splitUrl } from "./url";

/** A safe-to-display browser assertion failure. */
export class AcceptanceFailure extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AcceptanceFailure";
  }
}

/** Python `_require`; named `check` because `require` collides with CJS. */
export function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new AcceptanceFailure(message);
}

export function playwrightErrorCategory(error: unknown): string {
  const message = (
    error instanceof Error ? error.message : String(error)
  ).toLowerCase();
  if (message.includes("timeout") || message.includes("timed out")) {
    return "timeout";
  }
  if (message.includes("closed") || message.includes("crashed")) {
    return "closed-or-crashed";
  }
  if (message.includes("execution context")) return "execution-context";
  return "other";
}

export function loopbackOrigin(url: string): string {
  let parsed;
  let loopback = false;
  try {
    parsed = splitUrl(url);
    if (parsed.hostname === "localhost") {
      loopback = true;
    } else if (parsed.hostname !== null) {
      const family = isIP(parsed.hostname);
      if (family === 0) throw new Error("not an address");
      loopback =
        family === 4
          ? parsed.hostname.startsWith("127.")
          : parsed.hostname === "::1";
    }
  } catch {
    throw new AcceptanceFailure("fixture URL is invalid");
  }
  if (
    parsed.scheme !== "http" ||
    parsed.hostname === null ||
    !loopback ||
    parsed.userinfo !== null ||
    parsed.query ||
    parsed.fragment
  ) {
    throw new AcceptanceFailure("fixture URL must be plain HTTP on loopback");
  }
  if (parsed.port === null || parsed.port < 1 || parsed.port > 65535) {
    throw new AcceptanceFailure("fixture URL has no valid port");
  }
  return `http://127.0.0.1:${parsed.port}`;
}

function privateDir(
  path: string | undefined,
  label: string,
  notDirectory: string,
): string {
  let target: string;
  if (path === undefined) {
    target = mkdtempSync(join(tmpdir(), "stricknani-web-ui-"));
  } else {
    target = outsideRepository(path, label);
    // Python: mkdir(parents=True, exist_ok=False).
    if (lstatExists(target)) {
      throw new AcceptanceFailure(`${label} already exists`);
    }
    mkdirSync(target, { mode: 0o700, recursive: true });
  }
  target = outsideRepository(target, label);
  if (!statSync(target).isDirectory())
    throw new AcceptanceFailure(notDirectory);
  chmodSync(target, 0o700);
  return target;
}

function lstatExists(path: string): boolean {
  try {
    lstatSync(path);
    return true;
  } catch {
    return false;
  }
}

export function privateScreenshotDir(path?: string): string {
  return privateDir(
    path,
    "screenshot directory",
    "screenshot path must be a directory",
  );
}

export function privateWorkDir(path?: string): string {
  return privateDir(
    path,
    "private work directory",
    "private work path must be a directory",
  );
}

export function responsePath(response: Response): string {
  return new URL(response.url()).pathname;
}

export function tinyPngFixture(): Buffer {
  const chunk = (kind: Buffer, data: Buffer): Buffer => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const checksum = Buffer.alloc(4);
    checksum.writeUInt32BE(crc32(Buffer.concat([kind, data])) >>> 0);
    return Buffer.concat([length, kind, data, checksum]);
  };
  const header = Buffer.concat([
    Buffer.from([0, 0, 0, 1, 0, 0, 0, 1]),
    Buffer.from([8, 6, 0, 0, 0]),
  ]);
  const pixel = deflateSync(Buffer.from([0, 36, 118, 105, 255]));
  return Buffer.concat([
    Buffer.from("\x89PNG\r\n\x1a\n", "latin1"),
    chunk(Buffer.from("IHDR"), header),
    chunk(Buffer.from("IDAT"), pixel),
    chunk(Buffer.from("IEND"), Buffer.alloc(0)),
  ]);
}

export async function waitTwoAnimationFrames(page: Page): Promise<void> {
  await page.evaluate(() => {
    return new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    });
  });
}

export async function assertNoHorizontalOverflow(
  page: Page,
  viewportWidth: number,
): Promise<void> {
  await waitTwoAnimationFrames(page);
  const dimensions = await page.evaluate(() => {
    return {
      viewport: window.innerWidth,
      document: document.documentElement.scrollWidth,
      body: document.body.scrollWidth,
    };
  });
  if (
    dimensions.document > viewportWidth ||
    dimensions.body > viewportWidth ||
    dimensions.viewport !== viewportWidth
  ) {
    const offenders = await page.evaluate(() => {
      return Array.from(document.querySelectorAll("body *"))
        .map((element) => ({
          tag: element.tagName.toLowerCase(),
          className: element.getAttribute("class") || "",
          right: Math.round(element.getBoundingClientRect().right * 100) / 100,
          left: Math.round(element.getBoundingClientRect().left * 100) / 100,
        }))
        .filter(
          (element) => element.right > window.innerWidth || element.left < 0,
        )
        .slice(0, 5);
    });
    throw new AcceptanceFailure(
      `viewport width ${viewportWidth}px overflowed ` +
        `(inner=${dimensions.viewport}, document=${dimensions.document}, ` +
        `body=${dimensions.body}; elements=${JSON.stringify(offenders)})`,
    );
  }
}

/**
 * Click a control that opens a native confirm dialog, answer it and return it.
 * `accept` may be passed as `{ accept }` or as a plain boolean.
 */
export async function clickAndHandleDialog(
  page: Page,
  locator: Locator,
  options: { accept: boolean } | boolean,
): Promise<Dialog> {
  const accept = typeof options === "boolean" ? options : options.accept;
  const handleDialog = (dialog: Dialog): void => {
    const answer =
      accept && dialog.type() === "confirm"
        ? dialog.accept()
        : dialog.dismiss();
    answer.catch(() => undefined);
  };
  page.on("dialog", handleDialog);
  let dialog: Dialog;
  try {
    [dialog] = await Promise.all([
      page.waitForEvent("dialog", { timeout: 8_000 }),
      locator.click(),
    ]);
  } finally {
    page.off("dialog", handleDialog);
  }
  check(
    dialog.type() === "confirm",
    "dirty project Cancel did not show the expected confirmation dialog",
  );
  return dialog;
}

type Box = { x: number; y: number; width: number; height: number };

export async function assertStickySaveCancelGeometry(
  page: Page,
  saveName: string,
): Promise<void> {
  const bar = page.locator(".sticky-action-bar");
  const save = page.getByRole("button", { name: saveName, exact: true });
  const cancel = page.getByRole("link", { name: "Cancel", exact: true });
  const navigation = page.locator(".mobile-navigation");

  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 });
    await page.evaluate(() => {
      window.scrollTo(0, document.documentElement.scrollHeight);
    });
    await bar.waitFor({ state: "visible" });
    await assertNoHorizontalOverflow(page, width);
    const barBox = await bar.boundingBox();
    const saveBox = await save.boundingBox();
    const cancelBox = await cancel.boundingBox();
    const navigationBox = await navigation.boundingBox();
    if (
      barBox === null ||
      saveBox === null ||
      cancelBox === null ||
      navigationBox === null
    ) {
      throw new AcceptanceFailure(
        "sticky Save/Cancel or mobile navigation has no visible geometry",
      );
    }
    const controls: Box[] = [saveBox, cancelBox];
    check(
      controls.every(
        (box) =>
          box.width >= 44 &&
          box.height >= 44 &&
          box.x >= 0 &&
          box.x + box.width <= width,
      ) &&
        Math.max(...controls.map((box) => box.y + box.height)) <=
          navigationBox.y &&
        barBox.y + barBox.height <= navigationBox.y &&
        !(
          saveBox.x < cancelBox.x + cancelBox.width &&
          saveBox.x + saveBox.width > cancelBox.x &&
          saveBox.y < cancelBox.y + cancelBox.height &&
          saveBox.y + saveBox.height > cancelBox.y
        ),
      `sticky Save/Cancel targets overlap mobile navigation at ${width}px`,
    );
    const sizes = await page.evaluate(() => {
      return {
        document: document.documentElement.scrollWidth,
        body: document.body.scrollWidth,
      };
    });
    check(
      sizes.document <= width && sizes.body <= width,
      `editor layout overflows horizontally at ${width}px`,
    );
  }

  await page.setViewportSize({ width: 1280, height: 844 });
  await assertNoHorizontalOverflow(page, 1280);
}

/** Mask disposable account identifiers before screenshot capture. */
export async function maskFixtureIdentity(page: Page): Promise<void> {
  await page.locator(".profile__email").evaluateAll((nodes) => {
    for (const node of nodes) node.textContent = "Demo account";
  });
  await page.locator(".user-avatar").evaluateAll((nodes) => {
    for (const node of nodes) node.textContent = "D";
  });
  await page.locator("#email, #password").evaluateAll((nodes) => {
    for (const node of nodes) (node as HTMLInputElement).value = "";
  });
}

/**
 * Python's `evaluate("x => ...")` auto-detects function source strings; the
 * Node client evaluates a string as an expression (yielding the function
 * object, serialised as `undefined`). `fn` turns function source into a real
 * function, which Playwright serialises by source and then calls with the
 * argument. Use it for every `evaluate`/`waitForFunction` that takes source.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function fn(source: string): (...args: any[]) => any {
  const body = source.trim().replace(/;+$/, "");
  return new Function(`return (${body}\n);`)() as (
    ...args: unknown[]
  ) => unknown;
}

/**
 * Close a browser even when route handlers are still pending. Journeys hold
 * or abort requests on purpose; after a failure the Node client's close waits
 * for such in-flight handlers, so drop all routes first.
 */
export async function closeBrowser(browser: Browser): Promise<void> {
  for (const context of browser.contexts()) {
    await context
      .unrouteAll({ behavior: "ignoreErrors" })
      .catch(() => undefined);
    for (const page of context.pages()) {
      await page
        .unrouteAll({ behavior: "ignoreErrors" })
        .catch(() => undefined);
    }
  }
  await browser.close();
}

/**
 * Type into a Markdown field. The visual editor is a contenteditable, which a plain `fill` does
 * not drive reliably, and the field may still be swapping from the textarea while its editor
 * chunk loads, so wait for the editors to be ready, then select all and type with the keyboard.
 */
export async function fillMarkdownField(
  field: Locator,
  text: string,
): Promise<void> {
  const page = field.page();
  await page
    .locator('.md-editor[data-ready="true"]')
    .first()
    .waitFor({ state: "attached", timeout: 15_000 })
    .catch(() => undefined);
  await field.click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.type(text);
}

/**
 * WebKit reports an in-flight fetch to the app (an API call, the app version
 * poll) that a navigation or an abort cancelled as a page error as well
 * (Playwright splits its message at the first colon into a name and a
 * message). The app catches every such rejection, and no other browser raises
 * one.
 */
export function isCancelledFetch(error: Error): boolean {
  const text = `${error?.name ?? ""}: ${error?.message ?? ""}`;
  return /^Fetch API cannot load .*\/(api\/v2|app\/_app)\/.* due to access control checks\.?$/.test(
    text,
  );
}

/**
 * Playwright's WebKit screenshot path injects an inline <style>, which the
 * strict CSP reports as a violation. Keep the real CSP check enabled and omit
 * the captures in WebKit.
 */
export function omitWebkitScreenshots(page: Page, browser: string): void {
  if (browser === "webkit") {
    Object.assign(page, { screenshot: async () => Buffer.alloc(0) });
  }
}

/**
 * Delay every API request by `latencyMs`, in any browser (a CDP network
 * throttle only exists in Chromium). Returns a function that removes the delay.
 */
export async function delayApiRequests(
  page: Page,
  latencyMs: number,
): Promise<() => Promise<void>> {
  const pattern = "**/api/v2/**";
  const handler = async (route: Route): Promise<void> => {
    await new Promise<void>((resolve) => setTimeout(resolve, latencyMs));
    await route.fallback();
  };
  await page.route(pattern, handler);
  return async () => {
    await page.unroute(pattern, handler);
  };
}
