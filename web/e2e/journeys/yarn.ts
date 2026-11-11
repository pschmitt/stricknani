import { closeBrowser } from "../lib/common";
import * as fs from "node:fs";
import * as path from "node:path";
import { errors, expect } from "@playwright/test";
import type {
  ConsoleMessage,
  Locator,
  Page,
  Request,
  Response,
  Route,
} from "@playwright/test";
import {
  AcceptanceFailure,
  assertStickySaveCancelGeometry,
  check,
  clickAndHandleDialog,
  delayApiRequests,
  isCancelledFetch,
  omitWebkitScreenshots,
  waitTwoAnimationFrames,
} from "../lib/common";
import { REPO_ROOT } from "../lib/fixture";
import type { E2eConfig, LiveFixture } from "../lib/harness";
import { launchBrowser, stageMarker } from "../lib/harness";
import { CATEGORY_NAME, PROJECT_NAME, YARN_NAME } from "../lib/seed";
import { detailMenuItem } from "../lib/detail-menu";

type ObservedResponse = [method: string, status: number, path: string];
type ObservedApiError = [
  method: string,
  status: number,
  path: string,
  code: string | null,
];
type ObservedMutation = [method: string, path: string];

const MUTATION_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** Fires dragenter and drop on `selector` with a DataTransfer built in the page, like a desktop drop. */
async function dropFilesOn(
  page: Page,
  selector: string,
  files: { name: string; type: string; base64: string }[],
  options: { drop: boolean },
): Promise<void> {
  await page.evaluate(
    ({ selector, files, drop }) => {
      const target = document.querySelector(selector);
      if (!target) throw new Error("drop target is missing");
      const transfer = new DataTransfer();
      for (const file of files) {
        const bytes = Uint8Array.from(atob(file.base64), (c) =>
          c.charCodeAt(0),
        );
        transfer.items.add(new File([bytes], file.name, { type: file.type }));
      }
      const fire = (type: string) =>
        target.dispatchEvent(
          new DragEvent(type, {
            bubbles: true,
            cancelable: true,
            dataTransfer: transfer,
          }),
        );
      fire("dragenter");
      fire("dragover");
      if (drop) fire("drop");
    },
    { selector, files, drop: options.drop },
  );
}

function removePrefix(value: string, prefix: string): string {
  return value.startsWith(prefix) ? value.slice(prefix.length) : value;
}

function isDigits(value: string): boolean {
  return /^\d+$/.test(value);
}

function queryEquals(url: URL, expected: Record<string, string[]>): boolean {
  const keys = [...new Set(url.searchParams.keys())].sort();
  const wanted = Object.keys(expected).sort();
  if (keys.length !== wanted.length || keys.some((k, i) => k !== wanted[i])) {
    return false;
  }
  return wanted.every((key) => {
    const actual = url.searchParams.getAll(key);
    const want = expected[key] ?? [];
    return (
      actual.length === want.length && actual.every((v, i) => v === want[i])
    );
  });
}

async function assertNoOverflow(page: Page, width: number): Promise<void> {
  const sizes = await page.evaluate(() => {
    return {
      viewport: window.innerWidth,
      doc: document.documentElement.scrollWidth,
      body: document.body.scrollWidth,
    };
  });
  check(
    sizes.viewport === width && sizes.doc <= width && sizes.body <= width,
    "a checked viewport has horizontal overflow",
  );
}

async function maskFixtureIdentity(page: Page): Promise<void> {
  await page.locator(".header-user").evaluateAll((nodes) => {
    nodes.forEach((node) => {
      node.textContent = "Demo account";
    });
  });
  await page.locator(".account-email").evaluateAll((nodes) => {
    nodes.forEach((node) => {
      node.textContent = "Demo account";
      (node as HTMLElement).title = "Demo account";
    });
  });
  await page.locator(".user-avatar").evaluateAll((nodes) => {
    nodes.forEach((node) => {
      node.textContent = "D";
    });
  });
  await page.locator("#email, #password").evaluateAll((nodes) => {
    nodes.forEach((node) => {
      (node as HTMLInputElement).value = "";
    });
  });
}

async function prepareScreenshot(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.scrollTo(0, 0);
    if (document.activeElement instanceof HTMLElement) {
      document.activeElement.blur();
    }
  });
}

async function visibleHeights(page: Page, selector: string): Promise<number[]> {
  const values: unknown = await page
    .locator(selector)
    .evaluateAll((elements) =>
      elements
        .filter((el) => el.getClientRects().length > 0)
        .map((el) => el.getBoundingClientRect().height),
    );
  if (
    !Array.isArray(values) ||
    values.some((value) => typeof value !== "number")
  ) {
    throw new AcceptanceFailure("browser returned invalid target sizes");
  }
  return values as number[];
}

async function observeFixtureUpdate(
  page: Page,
  yarnId: number,
): Promise<number> {
  const result: unknown = await page.evaluate(async (id) => {
    const sessionResponse = await fetch("/api/v2/session");
    if (!sessionResponse.ok) return sessionResponse.status;
    const sessionEnvelope = await sessionResponse.json();
    const token = sessionEnvelope?.data?.csrf_token;
    const detailResponse = await fetch(`/api/v2/yarns/${id}`);
    if (!detailResponse.ok) return detailResponse.status;
    const detail = (await detailResponse.json())?.data;
    if (!detail || typeof token !== "string") return 0;
    const response = await fetch(`/api/v2/yarns/${id}`, {
      method: "PUT",
      headers: {
        accept: "application/json",
        "content-type": "application/json",
        "x-csrf-token": token,
      },
      body: JSON.stringify({
        name: detail.name,
        description: detail.description,
        brand: detail.brand,
        colorway: "Server update",
        dye_lot: detail.dye_lot,
        fiber_content: detail.fiber_content,
        weight_category: detail.weight_category,
        recommended_needles: detail.recommended_needles,
        weight_grams: detail.weight_grams,
        length_meters: detail.length_meters,
        notes: detail.notes,
        link: detail.link,
        is_ai_enhanced: detail.is_ai_enhanced,
        expected_updated_at: detail.updated_at,
      }),
    });
    return response.status;
  }, yarnId);
  if (typeof result !== "number" || !Number.isInteger(result)) {
    throw new AcceptanceFailure("concurrent update returned an invalid status");
  }
  return result;
}

function hasOneApiConflict(
  observed: ObservedApiError[],
  method: string,
  apiPath: string,
): boolean {
  const matches = observed.filter(
    ([m, status, p]) => m === method && p === apiPath && status === 409,
  );
  return (
    matches.length === 1 &&
    matches[0]?.[1] === 409 &&
    matches[0]?.[3] === "conflict"
  );
}

function resourceCategory(p: string): string {
  if (p.startsWith("/app/_app/")) return "app-asset";
  if (p === "/app/favicon.svg" || p === "/favicon.ico") return "favicon";
  if (p.startsWith("/api/v2/yarns")) return "yarn-api";
  if (p.startsWith("/api/v2/categories")) return "category-api";
  if (p.startsWith("/media/") || p.startsWith("/api/v2/media/")) return "media";
  return "other-resource";
}

function lastStatus(
  observed: ObservedResponse[],
  predicate: (method: string, p: string) => boolean,
): number | undefined {
  for (let i = observed.length - 1; i >= 0; i -= 1) {
    const entry = observed[i];
    if (entry && predicate(entry[0], entry[2])) return entry[1];
  }
  return undefined;
}

/** Latency added to API requests while a save is observed in flight. */
const API_DELAY_MS = 900;

export async function runYarnJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const origin = f.origin;
  const email = f.env.STRICKNANI_TEST_EMAIL;
  const password = f.env.STRICKNANI_TEST_PASSWORD;
  const screenshots = f.screenshotDir;
  const stage = stageMarker(path.dirname(screenshots));
  const markStage = (value: string): void => stage.mark(value);

  const observed: ObservedResponse[] = [];
  const observedMutationRequests: ObservedMutation[] = [];
  const cspErrors: boolean[] = [];
  const cspSamples: string[] = [];
  const consoleErrors: string[] = [];
  const pageErrors: boolean[] = [];
  const resourceFailures: string[] = [];
  const observedApiErrors: ObservedApiError[] = [];
  const pending: Promise<void>[] = [];

  const observeResponse = (response: Response): void => {
    const p = new URL(response.url()).pathname;
    const yarnOrCategory =
      p.startsWith("/api/v2/yarns") || p.startsWith("/api/v2/categories");
    const method = response.request().method();
    const status = response.status();
    if (yarnOrCategory) observed.push([method, status, p]);
    if (status >= 400) {
      resourceFailures.push(`HTTP ${status} ${resourceCategory(p)}`);
    }
    if (status >= 400 && yarnOrCategory) {
      pending.push(
        (async () => {
          let code: string | null = null;
          try {
            const body: unknown = await response.json();
            const error =
              body && typeof body === "object"
                ? (body as Record<string, unknown>).error
                : undefined;
            const candidate =
              error && typeof error === "object"
                ? (error as Record<string, unknown>).code
                : undefined;
            if (typeof candidate === "string") code = candidate;
          } catch {
            // Body is optional.
          }
          observedApiErrors.push([method, status, p, code]);
        })(),
      );
    }
  };
  const settle = async (): Promise<void> => {
    await Promise.all(pending);
  };
  const observeRequest = (request: Request): void => {
    const p = new URL(request.url()).pathname;
    const method = request.method();
    if (p.startsWith("/api/v2/yarns") && MUTATION_METHODS.has(method)) {
      observedMutationRequests.push([method, p]);
    }
  };
  const observeConsole = (message: ConsoleMessage): void => {
    if (message.type() !== "error") return;
    const text = message.text().toLowerCase();
    if (
      text.includes("content security policy") ||
      (text.includes("violat") &&
        (text.includes("script-src") || text.includes("style-src")))
    ) {
      cspErrors.push(true);
      cspSamples.push(message.text().slice(0, 200));
    } else {
      const category = text.includes("409")
        ? "http-conflict"
        : text.includes("failed to load resource")
          ? "resource-load"
          : text.includes("net::err")
            ? "network"
            : text.includes("404")
              ? "http-not-found"
              : text.includes("uncaught") || text.includes("unhandled")
                ? "uncaught-exception"
                : "other-console-error";
      consoleErrors.push(category);
    }
  };

  markStage("launching Chromium");
  const browser = await launchBrowser(cfg);
  try {
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
      reducedMotion: "reduce",
      colorScheme: "light",
      locale: "en-US",
      serviceWorkers: "block",
    });
    const page = await context.newPage();
    omitWebkitScreenshots(page, cfg.browser);
    page.on("response", observeResponse);
    page.on("request", observeRequest);
    page.on("console", observeConsole);
    page.on("pageerror", (error) => {
      if (!isCancelledFetch(error)) pageErrors.push(true);
    });

    markStage("checking strict CSP and sign-in page");
    const response = await page.goto(`${origin}/app/login`, {
      waitUntil: "networkidle",
    });
    if (response === null || response.status() !== 200) {
      throw new AcceptanceFailure("login route failed");
    }
    markStage("choosing English for the yarn journey");
    await page.getByRole("radio", { name: /^(English|Englisch)$/ }).click();
    await page.getByRole("heading", { name: "Sign in" }).waitFor({
      state: "visible",
    });
    const policy = response.headers()["content-security-policy"] ?? "";
    check(
      policy.includes("default-src 'self'") &&
        policy.includes("script-src 'self'") &&
        policy.includes("style-src 'self'") &&
        !policy.includes("unsafe-inline") &&
        !policy.includes("unsafe-eval") &&
        policy.includes("style-src-attr 'unsafe-hashes'"),
      "strict frontend CSP or the scoped announcer hash is missing",
    );
    await page.locator("#email").fill(email);
    await page.locator("#password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    markStage("waiting for signed-in project library");
    await page.waitForURL("**/app/projects");
    await page
      .getByRole("heading", { name: "Projects", exact: true })
      .waitFor({ state: "visible" });
    await page.locator(".app-shell").waitFor({ state: "visible" });
    await page.locator(".mobile-navigation").waitFor({ state: "visible" });
    check(
      new URL(page.url()).pathname === "/app/projects",
      "sign-in did not open the project library",
    );

    markStage("checking yarn library search and favorite filtering");
    await page.goto(`${origin}/app/yarns`, { waitUntil: "networkidle" });
    markStage("locating seeded yarn row");
    let yarnCard = page.locator(".yarn-card").filter({
      has: page.getByRole("heading", { name: YARN_NAME, exact: true }),
    });
    await yarnCard.waitFor({ state: "visible" });
    markStage("typing yarn search");
    const yarnSearch = page.getByRole("searchbox", {
      name: "Search your yarn",
    });
    check(
      (await yarnSearch.getAttribute("placeholder")) === "Search" &&
        (await yarnSearch.locator("xpath=..").innerText()).trim() === "",
      "yarn search should show only the Search placeholder",
    );
    await page
      .getByRole("searchbox", { name: "Search your yarn" })
      .fill("Riverbend");
    markStage("waiting for debounced yarn query");
    await page.waitForURL(
      (url) =>
        url.pathname === "/app/yarns" && queryEquals(url, { q: ["Riverbend"] }),
    );
    markStage("checking filtered yarn result");
    await yarnCard.waitFor({ state: "visible" });
    check(
      (await page.locator(".yarn-card").count()) === 1,
      "yarn search returned the wrong result count",
    );
    const yarnDetailHref = await yarnCard
      .locator("a.resource-card__link")
      .getAttribute("href");
    check(yarnDetailHref !== null, "yarn detail link is missing");
    markStage("opening seeded yarn details");
    await yarnCard.locator("a.resource-card__link").click();
    await page.waitForURL(`**${yarnDetailHref}`);
    const favoriteButton = await detailMenuItem(page, "Add to favorites");
    markStage("saving yarn favorite");
    const [favoriteResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith("/favorite") &&
          r.request().method() === "PUT",
      ),
      favoriteButton.click(),
    ]);
    check(
      favoriteResponse.status() === 200,
      "saving the yarn favorite failed against API v2",
    );
    await detailMenuItem(page, "Remove from favorites");
    await page.keyboard.press("Escape");
    markStage("returning to searched yarn library");
    await page.goBack({ waitUntil: "domcontentloaded" });
    await page.waitForURL("**/app/yarns?q=Riverbend");
    yarnCard = page.locator(".yarn-card").filter({
      has: page.getByRole("heading", { name: YARN_NAME, exact: true }),
    });
    markStage("checking favorite indicator");
    await yarnCard
      .getByRole("button", { name: "Remove from favorites", exact: true })
      .waitFor({ state: "visible" });
    markStage("selecting favorites only");
    await page.getByTestId("yarn-filter-favorites").click();
    markStage("waiting for favorite query");
    await page.waitForURL(
      (url) =>
        url.pathname === "/app/yarns" &&
        queryEquals(url, { q: ["Riverbend"], favorite: ["true"] }),
    );
    markStage("checking favorite-filtered result");
    await yarnCard.waitFor({ state: "visible" });
    check(
      observed.some(([, status, p]) => p === "/api/v2/yarns" && status === 200),
      "the yarn list did not load from API v2",
    );
    for (const width of [320, 390, 1280]) {
      await page.setViewportSize({ width, height: 844 });
      await assertNoOverflow(page, width);
      const heights = await visibleHeights(
        page,
        ".mobile-navigation a, .secondary-nav a, " +
          ".desktop-create, [data-testid=yarn-filter-pills] button",
      );
      check(
        heights.length === 0 || Math.min(...heights) >= 44,
        "a yarn-library target is below 44px",
      );
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await maskFixtureIdentity(page);
    await prepareScreenshot(page);
    await page.screenshot({
      path: path.join(screenshots, "yarn-library-mobile.png"),
      caret: "initial",
    });

    markStage("opening yarn detail from library");
    await page.getByRole("link", { name: YARN_NAME }).click();
    markStage("waiting for yarn detail heading");
    await page
      .getByRole("heading", { name: YARN_NAME, exact: true })
      .waitFor({ state: "visible" });
    markStage("waiting for the used-in-projects section");
    await page
      .getByRole("heading", { name: "Used in projects" })
      .waitFor({ state: "visible" });
    markStage("waiting for the linked project card");
    const projectCard = page.locator(".project-list .resource-card").filter({
      has: page.getByText(PROJECT_NAME, { exact: true }),
    });
    const projectLink = projectCard.getByRole("link", { name: PROJECT_NAME });
    await projectLink.waitFor({ state: "visible", timeout: 10_000 });
    const projectHref = (await projectLink.getAttribute("href")) ?? "";
    const projectUrl = new URL(projectHref, "http://placeholder.invalid");
    const projectRoutePrefix = "/app/projects/";
    const projectIdText = projectUrl.pathname.startsWith(projectRoutePrefix)
      ? projectUrl.pathname.slice(projectRoutePrefix.length)
      : "";
    const projectIdValid = isDigits(projectIdText) && Number(projectIdText) > 0;
    check(
      (await projectCard.count()) === 1 &&
        (await projectLink.count()) === 1 &&
        (await projectLink.evaluate((element) => element.tagName === "A")) &&
        projectHref.startsWith("/") &&
        !projectHref.startsWith("//") &&
        !/^[a-z][a-z0-9+.-]*:/i.test(projectHref) &&
        projectIdValid,
      "the yarn detail project card link did not match its project",
    );
    const detailPath = new URL(page.url()).pathname;
    const yarnIdText = removePrefix(detailPath, "/app/yarns/");
    check(
      isDigits(yarnIdText),
      "yarn detail route did not expose a numeric ID",
    );
    const yarnId = Number(yarnIdText);
    for (const width of [320, 390, 1280]) {
      await page.setViewportSize({ width, height: 844 });
      await assertNoOverflow(page, width);
    }
    await page.setViewportSize({ width: 320, height: 844 });
    await page.locator(".photo-strip__open").first().waitFor({
      state: "visible",
    });
    await page.locator(".photo-strip__open").first().click();
    const zoomDialog = page.getByRole("dialog", {
      name: "Project image viewer",
    });
    await zoomDialog.waitFor({ state: "visible" });
    await page.keyboard.press("Escape");
    await zoomDialog.waitFor({ state: "hidden" });
    const detailRemoveControls = page.locator(
      ".photo-strip button[aria-label='Remove photo']",
    );
    check(
      (await page
        .getByLabel("Choose a yarn photo", { exact: true })
        .count()) === 0 && (await detailRemoveControls.count()) === 0,
      "yarn detail exposed photo mutation controls outside Edit yarn",
    );

    markStage("opening yarn editor for photo management");
    await page.getByRole("link", { name: "Edit yarn", exact: true }).click();
    await page.waitForURL(`**/app/yarns/${yarnId}/edit`);
    await page
      .getByRole("heading", { name: "Edit yarn", exact: true })
      .waitFor({ state: "visible" });
    check(
      await page.getByLabel("Choose a yarn photo", { exact: true }).isEnabled(),
      "the clean yarn editor did not enable photo management",
    );

    markStage("testing yarn photo upload and delete");
    const previousPhotoUrls = await page
      .locator(".reorder-item img")
      .evaluateAll((images) =>
        images.map((image) => (image as HTMLImageElement).currentSrc),
      );
    const beforePhotoCount = previousPhotoUrls.length;
    const upload = page.getByLabel("Choose a yarn photo");
    const demoPhoto = path.join(REPO_ROOT, "demo_assets", "demo_image_1.jpg");
    markStage("submitting a new yarn photo");
    await upload.setInputFiles(demoPhoto);
    markStage("waiting for the yarn photo upload result");
    try {
      await page
        .getByRole("status")
        .filter({ hasText: "Photo uploaded." })
        .waitFor({ state: "visible", timeout: 15_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) throw error;
      const uploadStatus = lastStatus(
        observed,
        (_m, p) => p.startsWith("/api/v2/yarns/") && p.endsWith("/photos"),
      );
      const uploadErrorVisible = await page
        .locator(".error-message")
        .isVisible();
      const photoCount = await page.locator(".reorder-item").count();
      throw new AcceptanceFailure(
        "yarn photo upload did not complete " +
          `(API status ${uploadStatus ?? "not observed"}; ` +
          `visible error ${uploadErrorVisible}; ` +
          `photo count ${photoCount})`,
      );
    }
    markStage("waiting for uploaded photo in the gallery");
    await page.waitForFunction(
      (previous) =>
        document.querySelectorAll(".reorder-item").length > previous,
      beforePhotoCount,
    );
    const newPhotoIndex = await page
      .locator(".reorder-item img")
      .evaluateAll(
        (images, previous) =>
          images.findIndex(
            (image) =>
              !previous.includes((image as HTMLImageElement).currentSrc),
          ),
        previousPhotoUrls,
      );
    check(
      Number.isInteger(newPhotoIndex) && newPhotoIndex >= 0,
      "uploaded photo did not appear in the yarn gallery",
    );
    const newPhoto = page.locator(".reorder-item").nth(newPhotoIndex);
    markStage("opening photo removal confirmation");
    await newPhoto.hover();
    await newPhoto.getByRole("button", { name: "Remove photo" }).click();
    const photoDialog = page.getByRole("dialog", {
      name: "Remove this photo?",
    });
    markStage("waiting for photo removal confirmation");
    await photoDialog.waitFor({ state: "visible" });
    markStage("cancelling the photo removal confirmation");
    await page.keyboard.press("Escape");
    await photoDialog.waitFor({ state: "hidden" });
    markStage("reopening photo removal confirmation");
    await newPhoto.getByRole("button", { name: "Remove photo" }).click();
    await photoDialog.waitFor({ state: "visible" });
    markStage("confirming photo removal");
    await photoDialog.getByRole("button", { name: "Remove photo" }).click();
    markStage("waiting for yarn photo removal result");
    try {
      await page
        .getByRole("status")
        .filter({ hasText: "Photo removed." })
        .waitFor({ state: "visible", timeout: 15_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) throw error;
      const removeStatus = lastStatus(
        observed,
        (_m, p) => p.startsWith("/api/v2/yarns/") && p.includes("/photos/"),
      );
      throw new AcceptanceFailure(
        "yarn photo removal did not complete " +
          `(API status ${removeStatus ?? "not observed"})`,
      );
    }
    await page.locator(".reorder-item").waitFor({ state: "visible" });
    check(
      observed.some(
        ([, status, p]) =>
          p.includes("/photos") && [200, 201, 204].includes(status),
      ),
      "yarn photo upload or removal did not reach API v2",
    );

    markStage("dropping files on the yarn photo section");
    const dropPhotoUrls = await page
      .locator(".reorder-item img")
      .evaluateAll((images) =>
        images.map((image) => (image as HTMLImageElement).currentSrc),
      );
    const photoBase64 = fs.readFileSync(demoPhoto).toString("base64");
    await dropFilesOn(
      page,
      ".photo-manager",
      [{ name: "demo.jpg", type: "image/jpeg", base64: photoBase64 }],
      { drop: false },
    );
    await expect(page.locator(".photo-manager")).toHaveAttribute(
      "data-file-drop",
      "active",
    );
    await expect(page.locator(".photo-manager")).toHaveAttribute(
      "data-file-drop-label",
      "Drop to add photos",
    );
    await dropFilesOn(
      page,
      ".photo-manager",
      [{ name: "notes.pdf", type: "application/pdf", base64: "JVBERg==" }],
      { drop: true },
    );
    await page
      .getByRole("alert")
      .filter({ hasText: "Choose a supported image file." })
      .waitFor({ state: "visible" });
    check(
      (await page.locator(".reorder-item").count()) === dropPhotoUrls.length,
      "a dropped non-image file changed the yarn gallery",
    );
    await dropFilesOn(
      page,
      ".photo-manager",
      [{ name: "demo.jpg", type: "image/jpeg", base64: photoBase64 }],
      { drop: true },
    );
    await page
      .getByRole("status")
      .filter({ hasText: "Photo uploaded." })
      .waitFor({ state: "visible", timeout: 15_000 });
    await page.waitForFunction(
      (previous) =>
        document.querySelectorAll(".reorder-item").length > previous,
      dropPhotoUrls.length,
    );
    await expect(page.locator(".photo-manager")).toHaveAttribute(
      "data-file-drop",
      "idle",
    );
    const droppedIndex = await page
      .locator(".reorder-item img")
      .evaluateAll(
        (images, previous) =>
          images.findIndex(
            (image) =>
              !previous.includes((image as HTMLImageElement).currentSrc),
          ),
        dropPhotoUrls,
      );
    check(droppedIndex >= 0, "a dropped photo did not appear in the gallery");
    const droppedPhoto = page.locator(".reorder-item").nth(droppedIndex);
    await droppedPhoto.hover();
    await droppedPhoto.getByRole("button", { name: "Remove photo" }).click();
    const dropDialog = page.getByRole("dialog", { name: "Remove this photo?" });
    await dropDialog.waitFor({ state: "visible" });
    await dropDialog.getByRole("button", { name: "Remove photo" }).click();
    await page
      .getByRole("status")
      .filter({ hasText: "Photo removed." })
      .waitFor({ state: "visible", timeout: 15_000 });
    await page.waitForFunction(
      (previous) =>
        document.querySelectorAll(".reorder-item").length === previous,
      dropPhotoUrls.length,
    );

    markStage("checking yarn dirty-navigation guard and CAS conflict");
    markStage("waiting for yarn editor brand field");
    await page.getByLabel("Brand").waitFor({ state: "visible" });
    let brand = page.getByLabel("Brand");
    markStage("entering dirty yarn draft");
    await brand.fill("Draft brand");
    const yarnParent = page.locator(".app-header .brand-lockup.parent-link");
    const parentBounds =
      (await yarnParent.count()) === 1 ? await yarnParent.boundingBox() : null;
    check(
      (await yarnParent.count()) === 1 &&
        (await yarnParent.getAttribute("aria-label")) === "Back" &&
        (await yarnParent.getAttribute("href")) === `/app/yarns/${yarnId}` &&
        parentBounds !== null &&
        parentBounds.width >= 44 &&
        parentBounds.height >= 44,
      "the yarn editor header parent link has the wrong route or size",
    );
    markStage("requesting cancellation of dirty yarn navigation");
    await clickAndHandleDialog(page, yarnParent, { accept: false });
    markStage("checking retained yarn draft after cancellation");
    await page
      .getByRole("heading", { name: "Edit yarn", exact: true })
      .waitFor({ state: "visible" });
    check(
      (await brand.inputValue()) === "Draft brand",
      "dirty navigation discarded the yarn draft",
    );
    markStage("accepting dirty yarn navigation");
    await clickAndHandleDialog(page, yarnParent, { accept: true });
    markStage("waiting for yarn details after accepted navigation");
    await page
      .getByRole("heading", { name: YARN_NAME, exact: true })
      .waitFor({ state: "visible" });
    markStage("reopening yarn editor for CAS conflict");
    await page.getByRole("link", { name: "Edit yarn" }).click();
    markStage("waiting for CAS editor brand field");
    await page.getByLabel("Brand").waitFor({ state: "visible" });
    brand = page.getByLabel("Brand");
    markStage("checking one keyboard shortcut save on the yarn editor");
    await brand.fill("Shortcut saved draft");
    const yarnWriteCount = observedMutationRequests.length;
    const [shortcutSaveResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `/api/v2/yarns/${yarnId}` &&
          r.request().method() === "PUT",
      ),
      page.keyboard.press("Control+s"),
    ]);
    check(
      shortcutSaveResponse.status() === 200,
      "Ctrl+S did not save the yarn through API v2",
    );
    check(
      observedMutationRequests.length === yarnWriteCount + 1,
      "Ctrl+S submitted more than one yarn save request",
    );
    await page.waitForURL(`**/app/yarns/${yarnId}`);
    await page
      .locator(".action-feedback[role='status']")
      .filter({ hasText: "Yarn changes saved." })
      .waitFor({ state: "visible" });
    await page.getByRole("link", { name: "Edit yarn", exact: true }).click();
    await page.waitForURL(`**/app/yarns/${yarnId}/edit`);
    await page
      .getByRole("heading", { name: "Edit yarn", exact: true })
      .waitFor({ state: "visible" });
    brand = page.getByLabel("Brand");
    check(
      (await brand.inputValue()) === "Shortcut saved draft",
      "the post-save detail/editor round-trip lost the keyboard-saved yarn",
    );
    markStage("entering draft for CAS conflict");
    await brand.fill("Draft brand");
    markStage("applying concurrent yarn update");
    check(
      (await observeFixtureUpdate(page, yarnId)) === 200,
      "concurrent yarn update failed",
    );
    markStage("submitting stale yarn version");
    await page.getByRole("button", { name: "Save changes" }).click();
    markStage("waiting for yarn CAS conflict dialog");
    await page
      .getByRole("heading", { name: "A newer version was saved" })
      .waitFor({ state: "visible" });
    await settle();
    check(
      hasOneApiConflict(observedApiErrors, "PUT", `/api/v2/yarns/${yarnId}`),
      "yarn save did not return one API v2 conflict",
    );
    check(
      (await brand.inputValue()) === "Draft brand",
      "CAS conflict lost the local draft",
    );
    await page
      .getByRole("button", { name: "Save my draft over latest" })
      .click();
    await page.waitForURL(`**/app/yarns/${yarnId}`);
    await page
      .locator(".action-feedback[role='status']")
      .filter({ hasText: "Yarn changes saved." })
      .waitFor({ state: "visible" });
    await page.getByRole("link", { name: "Edit yarn", exact: true }).click();
    await page.waitForURL(`**/app/yarns/${yarnId}/edit`);
    await page
      .getByRole("heading", { name: "Edit yarn", exact: true })
      .waitFor({ state: "visible" });
    brand = page.getByLabel("Brand");
    check(
      (await brand.inputValue()) === "Draft brand",
      "successful CAS retry changed the draft",
    );

    markStage("creating a yarn and validating required-field feedback");
    await page.goto(`${origin}/app/yarns/new`, { waitUntil: "networkidle" });
    const nameField = page.getByLabel("Yarn name");
    await nameField.waitFor({ state: "visible" });
    const yarnEditorSections = () =>
      page
        .locator(
          ".yarn-form > fieldset, .yarn-form > .photo-section, .yarn-form > .photo-placeholder",
        )
        .evaluateAll((elements) =>
          elements.map((element) =>
            element.matches("fieldset")
              ? (element.querySelector("legend")?.textContent?.trim() ?? "")
              : "Photos",
          ),
        );
    // Same sections in the same order as the Android editor.
    expect(await yarnEditorSections(), "new yarn editor sections").toEqual([
      "Yarn information",
      "Photos",
      "More yarn details",
      "Description and notes",
    ]);
    check(
      (await page.getByLabel("Dye lot").isVisible()) &&
        (await page.getByLabel("Fiber content").isVisible()),
      "the dye lot and the yarn details should be visible without a disclosure",
    );
    await assertStickySaveCancelGeometry(page, "Save yarn");
    await page.getByRole("button", { name: "Save yarn" }).click();
    await page
      .getByText("Enter a yarn name.", { exact: true })
      .waitFor({ state: "visible" });
    await nameField.fill("New browser yarn");
    await page.getByLabel("Brand").fill("North Shore Fibers");
    await page.getByLabel("Fiber content").fill("80% wool, 20% nylon");
    await page.getByLabel("Yarn weight").fill("Sport");
    await page.getByLabel("Skein weight in grams").fill("-4");
    await page.getByRole("button", { name: "Save yarn" }).click();
    await page
      .getByText("Enter a whole number of zero or more.", { exact: true })
      .waitFor({ state: "visible" });
    await page.getByLabel("Skein weight in grams").fill("100");
    await page.getByLabel("Skein length in meters").fill("250");
    await page.getByLabel("Yarn source URL").fill("javascript:alert(1)");
    await page.getByRole("button", { name: "Save yarn" }).click();
    await page
      .getByText("Enter a complete http or https URL.", { exact: true })
      .waitFor({ state: "visible" });
    await page
      .getByLabel("Yarn source URL")
      .fill("https://example.invalid/new-yarn");
    const creationPhoto = path.join(
      REPO_ROOT,
      "demo_assets",
      "demo_image_1.jpg",
    );
    markStage("selecting a photo before creating the yarn");
    await page
      .getByLabel("Choose yarn photos", { exact: true })
      .setInputFiles(creationPhoto);
    const queuedPhoto = page.locator(".queued-photos li img");
    await queuedPhoto.waitFor({ state: "visible" });
    await expect
      .poll(() =>
        queuedPhoto.evaluate(
          (image) => (image as HTMLImageElement).naturalWidth,
        ),
      )
      .toBeGreaterThan(0);
    const previewLoaded = await queuedPhoto.evaluate(
      (image) => (image as HTMLImageElement).naturalWidth > 0,
    );
    check(previewLoaded, "the selected yarn photo preview did not load");

    let failFirstPhotoUpload = true;
    await page.route("**/api/v2/yarns/*/photos", async (route) => {
      if (failFirstPhotoUpload) {
        failFirstPhotoUpload = false;
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ error: { code: "unavailable" } }),
        });
      } else {
        await route.continue();
      }
    });
    markStage("creating yarn and retrying its first photo upload");
    const [createResponse, failedPhotoResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === "POST" &&
          new URL(response.url()).pathname === "/api/v2/yarns",
      ),
      page.waitForResponse(
        (response) =>
          response.request().method() === "POST" &&
          /^\/api\/v2\/yarns\/\d+\/photos$/.test(
            new URL(response.url()).pathname,
          ),
      ),
      page.getByRole("button", { name: "Save yarn" }).click(),
    ]);
    check(createResponse.status() === 201, "yarn creation did not succeed");
    check(
      failedPhotoResponse.status() === 503,
      "the first yarn photo upload did not reach the retryable failure",
    );
    await page
      .getByRole("alert")
      .getByText(
        "Some photos could not be uploaded. Save again to retry them.",
        {
          exact: true,
        },
      )
      .waitFor({ state: "visible" });
    await expect(queuedPhoto).toBeVisible();
    const retryPhotoResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /^\/api\/v2\/yarns\/\d+\/photos$/.test(
          new URL(response.url()).pathname,
        ),
    );
    await page.getByRole("button", { name: "Save yarn" }).click();
    const successfulPhotoResponse = await retryPhotoResponse;
    check(
      successfulPhotoResponse.status() === 201,
      "retrying the yarn photo upload did not succeed",
    );
    check(
      observed.filter(
        ([method, , p]) => method === "POST" && p === "/api/v2/yarns",
      ).length === 1,
      "retrying a failed photo upload created a duplicate yarn",
    );
    await page
      .getByRole("heading", { name: "New browser yarn", exact: true })
      .waitFor({ state: "visible" });
    await page
      .locator(".action-feedback[role='status']")
      .filter({ hasText: "Yarn created." })
      .waitFor({ state: "visible" });
    const createdIdPath = removePrefix(
      new URL(page.url()).pathname,
      "/app/yarns/",
    );
    check(
      isDigits(createdIdPath),
      "created yarn did not open its detail route",
    );
    check(
      (await page.getByText("100 g", { exact: true }).count()) === 1 &&
        (await page.getByText("250 m", { exact: true }).count()) === 1,
      "created yarn measurements were not rendered",
    );
    const createdPhoto = page.locator(".photo-strip img");
    await createdPhoto.waitFor({ state: "visible" });
    check(
      await createdPhoto.evaluate(
        (image) => (image as HTMLImageElement).naturalWidth > 0,
      ),
      "the saved yarn photo was not rendered",
    );

    markStage("checking yarn deletion confirmation and deletion");
    await page.getByRole("link", { name: "Edit yarn", exact: true }).click();
    await page.waitForURL(`**/app/yarns/${createdIdPath}/edit`);
    await page
      .getByRole("heading", { name: "Edit yarn", exact: true })
      .waitFor({ state: "visible" });
    expect(await yarnEditorSections(), "edit yarn editor sections").toEqual([
      "Yarn information",
      "Photos",
      "More yarn details",
      "Description and notes",
    ]);
    const deleteButton = page.getByRole("button", {
      name: "Delete yarn",
      exact: true,
    });
    await deleteButton.click();
    const deleteDialog = page.getByRole("dialog", { name: "Delete yarn?" });
    await deleteDialog.waitFor({ state: "visible" });
    await page.keyboard.press("Escape");
    await deleteDialog.waitFor({ state: "hidden" });
    await deleteButton.click();
    await deleteDialog.waitFor({ state: "visible" });
    await deleteDialog.getByRole("button", { name: "Keep yarn" }).click();
    await deleteDialog.waitFor({ state: "hidden" });
    await deleteButton.click();
    await deleteDialog.waitFor({ state: "visible" });
    const deleteAction = deleteDialog.locator("button.ui-button--danger");
    check(
      (await deleteAction.count()) === 1 &&
        (await deleteDialog
          .getByRole("button", { name: "Delete yarn", exact: true })
          .count()) === 1,
      "yarn confirmation does not have one named delete action",
    );

    const yarnDeleteUrl = `**/api/v2/yarns/${createdIdPath}`;
    const heldYarnDeletes: Route[] = [];
    const holdYarnDelete = async (route: Route): Promise<void> => {
      if (route.request().method() === "DELETE") {
        heldYarnDeletes.push(route);
      } else {
        await route.continue();
      }
    };
    await page.route(yarnDeleteUrl, holdYarnDelete);
    const deleteRequestCount = observedMutationRequests.length;
    const keepAction = deleteDialog.getByRole("button", {
      name: "Keep yarn",
      exact: true,
    });
    await deleteAction.click();
    await expect(deleteAction).toBeDisabled();
    await expect(keepAction).toBeDisabled();
    const lastMutation =
      observedMutationRequests[observedMutationRequests.length - 1];
    check(
      heldYarnDeletes.length === 1 &&
        observedMutationRequests.length === deleteRequestCount + 1 &&
        lastMutation?.[0] === "DELETE" &&
        lastMutation[1] === `/api/v2/yarns/${createdIdPath}` &&
        (await deleteDialog.isVisible()) &&
        (await deleteAction.isDisabled()) &&
        (await keepAction.isDisabled()),
      "yarn confirmation did not disable both actions for one pending delete",
    );
    await page.keyboard.press("Escape");
    await deleteDialog.waitFor({ state: "visible" });
    await page.keyboard.press("Enter");
    await waitTwoAnimationFrames(page);
    check(
      observedMutationRequests.length === deleteRequestCount + 1,
      "a busy yarn confirmation closed or sent a duplicate delete",
    );
    const heldDelete = heldYarnDeletes[0];
    check(heldDelete !== undefined, "yarn delete request was not held");
    await heldDelete.fulfill({
      status: 503,
      contentType: "application/json",
      body:
        '{"error":{"code":"internal_error",' +
        '"message":"The server could not complete this request. ' +
        'Try again."}}',
    });
    await page.unroute(yarnDeleteUrl, holdYarnDelete);
    await page
      .getByRole("alert")
      .filter({
        hasText: "The server could not complete this request. Try again.",
      })
      .first()
      .waitFor({ state: "visible" });
    check(
      (await deleteDialog.isVisible()) &&
        !(await deleteAction.isDisabled()) &&
        !(await keepAction.isDisabled()) &&
        (await page.locator("#yarn-name").inputValue()) === "New browser yarn",
      "failed yarn deletion lost the draft or left confirmation busy",
    );
    await keepAction.click();
    await deleteDialog.waitFor({ state: "hidden" });
    await deleteButton.click();
    await deleteDialog.waitFor({ state: "visible" });
    const [deleteResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `/api/v2/yarns/${createdIdPath}` &&
          r.request().method() === "DELETE",
      ),
      deleteAction.click(),
    ]);
    check(
      deleteResponse.status() === 204,
      "yarn deletion did not complete with 204 from API v2",
    );
    await page.waitForURL("**/app/yarns");
    await page
      .getByRole("searchbox", { name: "Search your yarn" })
      .waitFor({ state: "visible" });
    await page
      .getByRole("searchbox", { name: "Search your yarn" })
      .fill("New browser yarn");
    await page.waitForURL("**/app/yarns?q=New+browser+yarn");
    await page
      .getByRole("heading", { name: "No yarn matches those filters." })
      .waitFor({ state: "visible" });

    markStage(
      "checking category create, duplicate rename conflict, and delete",
    );
    await page.goto(`${origin}/app/categories`, { waitUntil: "networkidle" });
    markStage("waiting for category list");
    await page
      .getByRole("heading", { name: "Categories", exact: true })
      .waitFor({ state: "visible" });
    await page
      .getByText(CATEGORY_NAME, { exact: true })
      .waitFor({ state: "visible" });
    let removeApiDelay = await delayApiRequests(page, API_DELAY_MS);
    const newCategory = page.getByLabel("New category");
    await newCategory.fill("Browser category");
    markStage("creating category");
    await page.getByRole("button", { name: "Add category" }).click();
    check(
      await newCategory.isDisabled(),
      "new category input remained editable while save was pending",
    );
    await page
      .getByText("Browser category", { exact: true })
      .waitFor({ state: "visible" });
    check(
      (await newCategory.inputValue()) === "",
      "successful category create did not clear the submitted name",
    );
    await removeApiDelay();
    let categoryRow = page
      .locator(".category-row")
      .filter({ hasText: "Browser category" });
    markStage("opening category rename");
    await categoryRow.getByRole("button", { name: "Rename" }).click();
    const categoryForm = page.locator("form.rename-form");
    const editCategory = categoryForm.getByRole("textbox", {
      name: "Category name",
    });
    await editCategory.waitFor({ state: "visible" });
    await editCategory.fill(CATEGORY_NAME);
    markStage("submitting duplicate category rename");
    const categorySave = categoryForm.getByRole("button", {
      name: "Save",
      exact: true,
    });
    try {
      await categorySave.click({ timeout: 5_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) throw error;
      throw new AcceptanceFailure(
        "duplicate category rename submit control did not activate " +
          `(visible ${await categorySave.isVisible()}; ` +
          `enabled ${await categorySave.isEnabled()})`,
      );
    }
    markStage("waiting for duplicate category rename conflict");
    const renameConflictStatus = (): number | undefined =>
      lastStatus(
        observed,
        (m, p) => m === "PUT" && p.startsWith("/api/v2/categories/"),
      );
    try {
      await page
        .locator(".rename-form [role=alert]")
        .filter({ hasText: "A category with this name already exists." })
        .waitFor({ state: "visible", timeout: 10_000 });
    } catch (error) {
      const fieldAlertVisible = await page
        .locator(".rename-form [role=alert]")
        .isVisible()
        .catch(() => false);
      if (error instanceof errors.TimeoutError) {
        throw new AcceptanceFailure(
          "duplicate category rename did not show field feedback " +
            `(API status ${renameConflictStatus() ?? "not observed"}; ` +
            `field alert visible ${fieldAlertVisible})`,
        );
      }
      const errorText = String(error).toLowerCase();
      const lookupFailure = errorText.includes("strict mode violation")
        ? "ambiguous text locator"
        : "unexpected browser lookup error";
      throw new AcceptanceFailure(
        "duplicate category rename feedback lookup failed " +
          `(${lookupFailure}; API status ` +
          `${renameConflictStatus() ?? "not observed"}; ` +
          `field alert visible ${fieldAlertVisible})`,
      );
    }
    const categoryRowId = await categoryForm
      .locator("input")
      .getAttribute("id");
    if (categoryRowId === null || !categoryRowId.startsWith("category-")) {
      throw new AcceptanceFailure("category rename form has no stable row id");
    }
    const categoryId = removePrefix(categoryRowId, "category-");
    await settle();
    check(
      isDigits(categoryId) &&
        hasOneApiConflict(
          observedApiErrors,
          "PUT",
          `/api/v2/categories/${categoryId}`,
        ),
      "category rename did not return one API v2 conflict",
    );
    markStage("checking preserved duplicate category name");
    check(
      (await editCategory.inputValue()) === CATEGORY_NAME,
      "category conflict lost the attempted name",
    );
    await editCategory.fill("Fresh browser category");
    markStage("saving category rename");
    removeApiDelay = await delayApiRequests(page, API_DELAY_MS);
    await categoryForm
      .getByRole("button", { name: "Save", exact: true })
      .click();
    check(
      await editCategory.isDisabled(),
      "category rename input remained editable while save was pending",
    );
    await page
      .getByText("Fresh browser category", { exact: true })
      .waitFor({ state: "visible" });
    await removeApiDelay();
    categoryRow = page
      .locator(".category-row")
      .filter({ hasText: "Fresh browser category" });
    markStage("opening category delete confirmation");
    await categoryRow.getByRole("button", { name: "Delete" }).click();
    const categoryDialog = page.getByRole("dialog", {
      name: "Delete category?",
    });
    await categoryDialog.waitFor({ state: "visible" });
    markStage("cancelling category delete confirmation");
    await page.keyboard.press("Escape");
    await categoryDialog.waitFor({ state: "hidden" });
    markStage("reopening category delete confirmation");
    await categoryRow.getByRole("button", { name: "Delete" }).click();
    await categoryDialog.waitFor({ state: "visible" });
    markStage("keeping category after confirmation");
    await categoryDialog.getByRole("button", { name: "Keep category" }).click();
    await categoryDialog.waitFor({ state: "hidden" });
    markStage("confirming category deletion");
    await categoryRow.getByRole("button", { name: "Delete" }).click();
    await categoryDialog.waitFor({ state: "visible" });
    await categoryDialog
      .getByRole("button", { name: "Delete category" })
      .click();
    await page
      .getByRole("status")
      .filter({ hasText: "Category deleted." })
      .waitFor({ state: "visible" });
    check(
      observed.some(
        ([, status, p]) => p === "/api/v2/categories" && status === 200,
      ) &&
        observed.some(
          ([, status, p]) => p === "/api/v2/categories" && status === 201,
        ) &&
        observed.some(
          ([, status, p]) =>
            p.startsWith("/api/v2/categories/") && status === 409,
        ) &&
        observed.some(
          ([, status, p]) =>
            p.startsWith("/api/v2/categories/") && status === 204,
        ),
      "category list/create/conflict/delete did not complete through API v2",
    );

    for (const width of [320, 390, 1280]) {
      await page.setViewportSize({ width, height: 844 });
      await assertNoOverflow(page, width);
      const heights = await visibleHeights(
        page,
        ".mobile-navigation a, .desktop-navigation a, " +
          ".primary-action, .quiet-button, .danger-button",
      );
      check(
        heights.length === 0 || Math.min(...heights) >= 44,
        "a category target is below 44px",
      );
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await maskFixtureIdentity(page);
    await prepareScreenshot(page);
    await page.screenshot({
      path: path.join(screenshots, "categories-mobile.png"),
      caret: "initial",
    });

    await settle();
    check(
      cspErrors.length === 0,
      `browser reported a CSP violation: ${cspSamples.slice(0, 2).join(" | ")}`,
    );
    check(pageErrors.length === 0, "browser reported a page error");
    const sortedConsole = [...consoleErrors].sort();
    const status503 = observedApiErrors.filter((e) => e[1] === 503);
    const expectedTestErrorConsole =
      sortedConsole.length === 4 &&
      sortedConsole[0] === "http-conflict" &&
      sortedConsole[1] === "http-conflict" &&
      sortedConsole[2] === "resource-load" &&
      sortedConsole[3] === "resource-load" &&
      observedApiErrors.filter((e) => e[1] === 409).length === 2 &&
      status503.length === 2 &&
      status503.some(
        (entry) =>
          entry[0] === "DELETE" &&
          entry[2] === `/api/v2/yarns/${createdIdPath}` &&
          entry[3] === "internal_error",
      ) &&
      status503.some(
        (entry) =>
          entry[0] === "POST" &&
          entry[2] === `/api/v2/yarns/${createdIdPath}/photos` &&
          entry[3] === "unavailable",
      ) &&
      JSON.stringify([...resourceFailures].sort()) ===
        JSON.stringify([
          "HTTP 409 category-api",
          "HTTP 409 yarn-api",
          "HTTP 503 yarn-api",
          "HTTP 503 yarn-api",
        ]);
    const unexpectedConsoleErrors = expectedTestErrorConsole
      ? []
      : consoleErrors;
    check(
      unexpectedConsoleErrors.length === 0,
      "browser reported unexpected console errors " +
        `(${[...new Set(unexpectedConsoleErrors)].sort().join(", ")}; ` +
        "resource failures " +
        `${[...new Set(resourceFailures)].sort().join(", ") || "none"})`,
    );
    markStage("browser acceptance complete");
    await context.close();
  } finally {
    try {
      for (const entry of fs.readdirSync(screenshots)) {
        if (entry.endsWith(".png")) {
          fs.chmodSync(path.join(screenshots, entry), 0o600);
        }
      }
    } catch {
      // Screenshot directory may be missing after an early failure.
    }
    await closeBrowser(browser);
  }
}
