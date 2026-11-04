import { closeBrowser } from "../lib/common";
// Focused keyboard and accessibility checks against synthetic Rust data.
// Port of ci/stricknani/web_accessibility_browser.py.
import type { Locator, Page, Request, Response } from "@playwright/test";
import { errors } from "@playwright/test";

import { AcceptanceFailure, check } from "../lib/common";
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

function assetCategory(path: string): string {
  if (path.endsWith(".js")) {
    return "js";
  }
  return path.endsWith(".css") ? "css" : "other";
}

interface DialogFocusState {
  open: boolean;
  modal: boolean;
  focusInside: boolean;
  focusOnDialog: boolean;
  focusOnBody: boolean;
  activeTag: string;
}

interface RouteState {
  path: string;
  headingExists: boolean;
  headingVisible: boolean;
  headingSemantics: boolean;
  headingNameMatches: boolean;
  documentLanguage: string;
}

interface MotionState {
  requested: boolean;
  scroll: string;
  transitionSeconds: number[];
}

export async function tabTo(
  page: Page,
  locator: Locator,
  description: string,
): Promise<void> {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    const focused = await locator.evaluate(
      (element) => document.activeElement === element,
    );
    if (focused) {
      return;
    }
    await page.keyboard.press("Tab");
  }
  throw new AcceptanceFailure(
    `keyboard navigation could not reach ${description}`,
  );
}

export async function exerciseAccessibility(
  page: Page,
  origin: string,
  email: string,
  password: string,
  markStage: (value: string) => void,
): Promise<void> {
  const immutableAssetFailures: string[] = [];

  page.on("response", (response: Response) => {
    const path = pathOf(response.url());
    const status = response.status();
    if (status >= 400 && path.includes("/_app/immutable/")) {
      immutableAssetFailures.push(`${assetCategory(path)}:${status}`);
    }
  });
  page.on("requestfailed", (request: Request) => {
    const path = pathOf(request.url());
    if (!path.includes("/_app/immutable/")) {
      return;
    }
    immutableAssetFailures.push(`${assetCategory(path)}:network-failed`);
  });

  markStage("loading synthetic sign-in page");
  const response = await page.goto(`${origin}/app/login`, {
    waitUntil: "domcontentloaded",
  });
  check(response !== null && response.status() === 200, "sign-in did not load");
  await page
    .getByRole("heading", { name: "Sign in", exact: true })
    .waitFor({ state: "visible", timeout: 10_000 });

  markStage("checking sign-in labels and keyboard focus");
  const emailInput = page.getByLabel("Email", { exact: true });
  const passwordInput = page.getByLabel("Password", { exact: true });
  check(
    (await emailInput.count()) === 1 && (await passwordInput.count()) === 1,
    "sign-in fields do not have unique accessible labels",
  );
  const submit = page.getByRole("button", { name: "Sign in", exact: true });
  check(
    (await submit.count()) === 1,
    "sign-in submit has no unique accessible name",
  );

  await tabTo(page, emailInput, "email field");
  check(
    await emailInput.evaluate((element) => element.matches(":focus-visible")),
    "keyboard focus is not visibly indicated on the email field",
  );
  await page.keyboard.insertText(email);
  await page.keyboard.press("Tab");
  check(
    await passwordInput.evaluate(
      (element) => document.activeElement === element,
    ),
    "Tab did not move from email to password",
  );
  await page.keyboard.insertText("synthetic-invalid-password");
  await page.keyboard.press("Tab");
  check(
    await submit.evaluate((element) => document.activeElement === element),
    "Tab did not move from password to the sign-in button",
  );

  markStage("checking invalid sign-in alert");
  await page.keyboard.press("Enter");
  const alert = page.getByRole("alert");
  await alert.waitFor({ state: "visible", timeout: 10_000 });
  check(
    (await alert.innerText()).trim() !== "",
    "sign-in error alert is empty",
  );
  check(
    (await passwordInput.inputValue()) === "synthetic-invalid-password",
    "sign-in error cleared the password draft",
  );

  markStage("completing keyboard-only sign-in");
  await page.keyboard.press("Shift+Tab");
  check(
    await passwordInput.evaluate(
      (element) => document.activeElement === element,
    ),
    "Shift+Tab did not return to the password field after the error",
  );
  await page.keyboard.press("Control+A");
  await page.keyboard.insertText(password);
  await page.keyboard.press("Tab");
  await page.keyboard.press("Enter");

  markStage("waiting for the post-login Projects landing route");
  await page.waitForURL("**/app/projects", { timeout: 10_000 });
  await page
    .getByRole("heading", { name: "Heirloom Baby Blanket", exact: true })
    .waitFor({ state: "visible", timeout: 10_000 });

  markStage("checking library keyboard navigation");
  const libraryNav = page.getByRole("navigation", {
    name: "Your library",
    exact: true,
  });
  await libraryNav.waitFor({ state: "visible", timeout: 10_000 });
  const yarnsLink = libraryNav.getByRole("link", {
    name: "Yarns",
    exact: true,
  });
  check(
    (await yarnsLink.count()) === 1,
    "library navigation has no unique Yarns link",
  );
  await tabTo(page, yarnsLink, "Yarns navigation link");
  check(
    await yarnsLink.evaluate((element) => element.matches(":focus-visible")),
    "keyboard focus is not visible on the Yarns navigation link",
  );
  markStage("activating Yarns navigation with Enter");
  const yarnListResponse = page.waitForResponse(
    (candidate) =>
      pathOf(candidate.url()) === "/api/v2/yarns" &&
      candidate.request().method() === "GET",
    { timeout: 10_000 },
  );
  await page.keyboard.press("Enter");
  const yarnListResult = await yarnListResponse;
  check(
    yarnListResult.status() === 200,
    "the keyboard-opened yarn library did not load its list",
  );
  markStage("waiting for the Yarns library route");
  try {
    await page.waitForURL("**/app/yarns", { timeout: 10_000 });
  } catch (error) {
    if (!(error instanceof errors.TimeoutError)) {
      throw error;
    }
    const currentPath = pathOf(page.url());
    const targetPath = pathOf((await yarnsLink.getAttribute("href")) ?? "");
    const focused = await yarnsLink.evaluate(
      (element) => document.activeElement === element,
    );
    throw new AcceptanceFailure(
      "Enter did not navigate from the Yarns link " +
        `(target=${targetPath || "missing"}; current=${currentPath}; ` +
        `focus retained=${focused ? "True" : "False"})`,
    );
  }
  markStage("waiting for the Yarns route to render its heading");
  try {
    await page.waitForFunction(
      () => {
        const heading = document.querySelector("#yarn-library-title");
        if (location.pathname !== "/app/yarns" || !heading) {
          return false;
        }
        const style = getComputedStyle(heading);
        const rect = heading.getBoundingClientRect();
        return (
          style.visibility !== "hidden" &&
          style.display !== "none" &&
          rect.width > 0 &&
          rect.height > 0
        );
      },
      undefined,
      { timeout: 10_000 },
    );
  } catch (error) {
    if (!(error instanceof errors.TimeoutError)) {
      throw error;
    }
    throw new AcceptanceFailure(
      "Yarns SPA route did not render its visible heading " +
        `(current path=${pathOf(page.url())}; ` +
        "immutable asset failures=" +
        `${immutableAssetFailures.length ? JSON.stringify(immutableAssetFailures) : "none"})`,
    );
  }
  markStage("checking the Yarns heading role and accessible name");
  const routeState: RouteState = await page.evaluate(() => {
    const heading = document.querySelector("#yarn-library-title");
    const style = heading ? getComputedStyle(heading) : null;
    const rect = heading?.getBoundingClientRect();
    const explicitRole = heading?.getAttribute("role");
    return {
      path: location.pathname,
      headingExists: heading !== null,
      headingVisible:
        !!heading &&
        !!style &&
        style.visibility !== "hidden" &&
        style.display !== "none" &&
        !!rect &&
        rect.width > 0 &&
        rect.height > 0,
      headingSemantics:
        heading?.tagName === "H1" &&
        (explicitRole === null || explicitRole === "heading"),
      headingNameMatches: heading?.textContent?.trim() === "Yarns",
      documentLanguage: document.documentElement.lang,
    };
  });
  check(
    routeState.headingExists &&
      routeState.headingVisible &&
      routeState.headingSemantics &&
      routeState.headingNameMatches,
    "Yarns route did not expose a visible semantic heading with its label " +
      `(path=${routeState.path}; heading=${routeState.headingExists}; ` +
      `visible=${routeState.headingVisible}; ` +
      `semantic=${routeState.headingSemantics}; ` +
      `name=${routeState.headingNameMatches}; ` +
      `language=${routeState.documentLanguage})`,
  );
  markStage("checking current-page navigation state");
  const currentLibraryNav = page.getByRole("navigation", {
    name: "Your library",
    exact: true,
  });
  const currentYarnsLink = currentLibraryNav.getByRole("link", {
    name: "Yarns",
    exact: true,
  });
  check(
    (await currentYarnsLink.count()) === 1 &&
      (await currentYarnsLink.getAttribute("aria-current")) === "page",
    "the active library route is not exposed as the current page",
  );

  markStage("waiting for the synthetic yarn card");
  const yarnHeading = page.getByRole("heading", {
    name: "Riverbend Merino DK",
    exact: true,
  });
  await yarnHeading.waitFor({ state: "visible", timeout: 10_000 });
  const yarnLink = page
    .locator(".yarn-card a.resource-card__link")
    .filter({ has: yarnHeading });
  await yarnLink.waitFor({ state: "visible", timeout: 10_000 });
  check(
    (await yarnLink.count()) === 1,
    "synthetic yarn is missing from the library",
  );
  check(
    await yarnLink.evaluate((element) => element.tagName === "A"),
    "the yarn card is not exposed as a link",
  );
  await tabTo(page, yarnLink, "synthetic yarn link");
  await page.keyboard.press("Enter");
  await page
    .getByRole("heading", { name: "Riverbend Merino DK", exact: true })
    .waitFor({ state: "visible", timeout: 10_000 });

  markStage("opening synthetic yarn from keyboard");
  const photoButton = page.locator(".photo-strip__open").first();
  await photoButton.waitFor({ state: "visible", timeout: 10_000 });
  await tabTo(page, photoButton, "yarn photo button");
  await page.keyboard.press("Enter");
  markStage("checking photo dialog focus and restoration");
  const photoDialog = page.getByRole("dialog", {
    name: "Project image viewer",
    exact: true,
  });
  await photoDialog.waitFor({ state: "visible", timeout: 5_000 });
  const closeButton = photoDialog.getByRole("button", {
    name: "Close image viewer",
    exact: true,
  });
  await closeButton.focus();
  const dialogFocusStates: DialogFocusState[] = [];
  for (const key of ["Tab", "Tab", "Shift+Tab"]) {
    await page.keyboard.press(key);
    dialogFocusStates.push(
      await page.evaluate(() => {
        const dialog = document.querySelector<HTMLDialogElement>(".lightbox");
        const active = document.activeElement;
        return {
          open: !!dialog?.open,
          modal: !!dialog?.matches(":modal"),
          focusInside: !!dialog?.contains(active),
          focusOnDialog: active === dialog,
          focusOnBody: active === document.body,
          activeTag: active?.tagName ?? "unknown",
        };
      }),
    );
  }
  const focusStayedInDialog = dialogFocusStates.every(
    (state) =>
      state.open &&
      state.modal &&
      (state.focusInside || state.focusOnDialog || state.focusOnBody),
  );
  await page.keyboard.press("Escape");
  await page.waitForFunction(
    () => !document.querySelector<HTMLDialogElement>(".lightbox")?.open,
    undefined,
    { timeout: 5_000 },
  );
  check(
    await photoButton.evaluate((element) => document.activeElement === element),
    "Escape did not restore focus to the photo trigger",
  );

  markStage("checking reduced motion");
  const motion: MotionState = await page.evaluate(() => {
    const opener = document.querySelector(".photo-strip__open");
    const durations = opener
      ? getComputedStyle(opener).transitionDuration.split(",")
      : [];
    return {
      requested: matchMedia("(prefers-reduced-motion: reduce)").matches,
      scroll: getComputedStyle(document.documentElement).scrollBehavior,
      transitionSeconds: durations.map((value) => {
        const duration = value.trim();
        return duration.endsWith("ms")
          ? Number.parseFloat(duration) / 1000
          : Number.parseFloat(duration);
      }),
    };
  });
  check(
    motion.requested &&
      motion.scroll === "auto" &&
      motion.transitionSeconds.length > 0 &&
      Math.max(...motion.transitionSeconds) <= 0.0001,
    "reduced-motion preference did not suppress motion and smooth scrolling",
  );

  // Playwright has no portable browser text-only zoom control. A 640 CSS-pixel
  // viewport is the honest 200%-layout surrogate for a 1280px baseline.
  markStage("checking effective 200 percent viewport reflow");
  await page.setViewportSize({ width: 640, height: 900 });
  check(
    await page.evaluate(
      () =>
        document.documentElement.scrollWidth <=
        document.documentElement.clientWidth,
    ),
    "the effective 200% viewport has horizontal overflow",
  );
  check(
    await page
      .getByRole("heading", { name: "Riverbend Merino DK", exact: true })
      .isVisible(),
    "the yarn title is not visible at the effective 200% viewport",
  );
  check(
    immutableAssetFailures.length === 0,
    "immutable app assets failed to load " +
      `(categories and statuses=${JSON.stringify(immutableAssetFailures)})`,
  );
  check(
    focusStayedInDialog,
    "tab cycling moved focus to an interactive page element outside the " +
      `modal (focus states=${JSON.stringify(dialogFocusStates)})`,
  );
}

export async function runAccessibilityJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const stage = stageMarker(f.privateDir);
  stage.mark("launching Chromium");
  const browser = await launchBrowser(cfg);
  try {
    const context = await browser.newContext({
      viewport: { width: 1280, height: 900 },
      reducedMotion: "reduce",
      locale: "en-US",
    });
    try {
      const page = await context.newPage();
      await exerciseAccessibility(
        page,
        f.origin,
        f.env.STRICKNANI_RUST_TEST_EMAIL,
        f.env.STRICKNANI_RUST_TEST_PASSWORD,
        (value) => stage.mark(value),
      );
    } finally {
      await context.close();
    }
  } finally {
    await closeBrowser(browser);
  }
}
