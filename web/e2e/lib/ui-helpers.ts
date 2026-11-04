/**
 * Module-level helpers of the sanitized web UI browser acceptance runner.
 *
 * Port of the helpers in ci/stricknani/web_ui_browser.py (lines 36-2175);
 * the helpers shared by several runners live in ./common.
 */
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import {
  errors,
  expect,
  type Browser,
  type Locator,
  type Page,
  type Request,
  type Response,
  type Route,
} from "@playwright/test";

import {
  AcceptanceFailure,
  assertNoHorizontalOverflow,
  check,
  maskFixtureIdentity,
  responsePath,
} from "./common";

// Defined in ./common (shared with the sticky-geometry helper).
export { assertNoHorizontalOverflow };

/** Records the current stage of a browser journey (python `mark_stage`). */
export type MarkStage = (stage: string) => void;

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

/** JSON with recursively sorted keys (python `json.dumps(sort_keys=True)`). */
function sortedJson(value: unknown): string {
  const sort = (input: unknown): unknown => {
    if (Array.isArray(input)) return input.map(sort);
    if (isRecord(input)) {
      return Object.fromEntries(
        Object.keys(input)
          .sort()
          .map((key) => [key, sort(input[key])]),
      );
    }
    return input === undefined ? null : input;
  };
  return JSON.stringify(sort(value));
}

function sameJson(left: unknown, right: unknown): boolean {
  return sortedJson(left) === sortedJson(right);
}

function errorName(error: unknown): string {
  return error instanceof Error ? error.constructor.name : "Error";
}

export const ACCOUNT_HEADING_NAME = /^(?:Account|Konto)$/;
export const ACCOUNT_SETTINGS_ITEM_NAME =
  /^(?:Account settings|Kontoeinstellungen)$/;
export const SIGN_OUT_BUTTON_NAME = /^(?:Sign out|Abmelden)$/;
// Mirrors GREETINGS in web/src/lib/features/home/greeting.ts (random per visit).
export const HOME_GREETING_NAME = new RegExp(
  "^(?:" +
    [
      "Good morning!",
      "Morning, sunshine!",
      "Rise and knit!",
      "Mooorning!",
      "Up already?",
      "Hello there!",
      "Hellooo!",
      "Hey hey!",
      "Howdy, knitter!",
      "Well, hi!",
      "Good evening!",
      "Evening, knitter!",
      "Cosy evening?",
      "Hey, night is near!",
      "Evening!",
      "Still knitting?",
      "Hello, night owl!",
      "Still awake?",
      "Just one more row?",
      "Shh, everyone is asleep!",
    ]
      .map(escapeRegExp)
      .join("|") +
    ")$",
);
export const UPLOAD_PICTURE_BUTTON_NAME = /^(?:Upload picture|Bild hochladen)$/;
export const REMOVE_PICTURE_BUTTON_NAME = /^(?:Remove picture|Bild entfernen)$/;

/**
 * The desktop sidebar's Account entry: a plain link to the Account page.
 * Phones have no such control: the More tab carries the avatar instead.
 * Synchronous (no browser round trip), unlike the other helpers.
 */
export function accountLink(page: Page): Locator {
  return page.locator(".desktop-rail a.rail-account");
}

/** The phone bottom bar's More tab (carries the avatar when one exists). */
export function phoneMoreTab(page: Page): Locator {
  return page.locator(".mobile-navigation a.more-tab");
}

export async function assertAccountLink(
  page: Page,
  context: string,
): Promise<Locator> {
  const link = accountLink(page);
  check(
    (await link.isVisible()) &&
      ((await link.getAttribute("href")) ?? "").endsWith("/app/account") &&
      ACCOUNT_HEADING_NAME.test(
        (await link.getAttribute("aria-label")) ?? "",
      ) &&
      (await link.getAttribute("aria-haspopup")) === null,
    `the ${context} Account entry is not a plain link to the Account page`,
  );
  return link;
}

/**
 * The About page shows the Android app QR code and APK link inline (no
 * dialog), and the sidebar has no Android app entry.
 */
export async function assertAndroidAppCard(page: Page): Promise<void> {
  check(
    (await page
      .locator(".desktop-rail")
      .getByText("Android app", { exact: false })
      .count()) === 0,
    "the sidebar still has an Android app entry",
  );
  await page.locator(".desktop-rail a[href$='/app/about']").click();
  await page.waitForURL("**/app/about", { timeout: 10_000 });
  const card = page.locator("section.card", {
    has: page.getByRole("heading", { name: "Get the Android app" }),
  });
  await card.waitFor({ state: "visible", timeout: 10_000 });
  const download = card.getByRole("link", { name: "Download APK" });
  check(
    (await card.locator("svg.qr-code").count()) === 1 &&
      ((await download.getAttribute("href")) ?? "").includes("github.com"),
    "the About page lacks the Android app QR code or APK link",
  );
}

export async function openAccountSettings(page: Page): Promise<void> {
  if (await accountLink(page).isVisible()) {
    await accountLink(page).click();
  } else {
    // Phones: More tab, then the Account settings entry on the More page.
    await phoneMoreTab(page).click();
    await page.waitForURL("**/app/more", { timeout: 10_000 });
    await page.getByRole("link", { name: ACCOUNT_SETTINGS_ITEM_NAME }).click();
  }
  await page.waitForURL("**/app/account", { timeout: 10_000 });
}

export async function openReaderActions(page: Page): Promise<Locator> {
  const disclosure = page.locator("details.action-overflow");
  await disclosure.waitFor({ state: "attached" });
  check(
    (await disclosure.count()) === 1,
    "reader actions disclosure is ambiguous",
  );
  check(
    (await disclosure.locator("summary").getAttribute("aria-label")) ===
      "More actions",
    "reader actions disclosure has the wrong accessible label",
  );
  if ((await disclosure.getAttribute("open")) === null) {
    await disclosure.locator("summary").click();
  }
  await page.waitForFunction(
    () =>
      (document.querySelector("details.action-overflow") as HTMLDetailsElement)
        ?.open === true,
  );
  return disclosure;
}

interface ApiEnvelope<T> {
  data?: T;
}

interface ApiYarn {
  name: unknown;
  description: unknown;
  brand: unknown;
  colorway: unknown;
  dye_lot: unknown;
  fiber_content: unknown;
  weight_category: unknown;
  recommended_needles: unknown;
  weight_grams: unknown;
  length_meters: unknown;
  notes: unknown;
  is_ai_enhanced: unknown;
  updated_at: unknown;
}

interface ApiProject {
  name: unknown;
  category: unknown;
  needles: unknown;
  stitch_sample: unknown;
  description: unknown;
  notes: unknown;
  other_materials: unknown;
  tags: unknown;
  link: unknown;
  is_ai_enhanced: unknown;
  updated_at: unknown;
  yarn_ids?: number[];
  images?: { step_id: number | null; url?: unknown }[];
  steps?: {
    id: number;
    title: unknown;
    description: unknown;
    step_number: unknown;
  }[];
}

interface ReaderFixtureResult {
  status: number;
  id: number;
  photo_status?: number;
  yarn_status?: number;
}

export async function prepareProjectReaderFixture(page: Page): Promise<number> {
  const result: ReaderFixtureResult = await page.evaluate(async () => {
    const sessionResponse = await fetch("/api/v2/session");
    if (!sessionResponse.ok) return { status: sessionResponse.status, id: 0 };
    const sessionEnvelope = (await sessionResponse.json()) as ApiEnvelope<{
      csrf_token?: unknown;
    }>;
    const token = sessionEnvelope?.data?.csrf_token;
    const listResponse = await fetch("/api/v2/projects");
    if (!listResponse.ok) return { status: listResponse.status, id: 0 };
    const listEnvelope = (await listResponse.json()) as ApiEnvelope<
      { id?: unknown }[]
    >;
    const id = listEnvelope?.data?.[0]?.id;
    if (
      typeof id !== "number" ||
      !Number.isSafeInteger(id) ||
      id < 1 ||
      typeof token !== "string"
    ) {
      return { status: 0, id: 0 };
    }
    const detailResponse = await fetch(`/api/v2/projects/${id}`);
    if (!detailResponse.ok) return { status: detailResponse.status, id: 0 };
    const detailEnvelope =
      (await detailResponse.json()) as ApiEnvelope<ApiProject>;
    const project = detailEnvelope?.data;
    if (!project) return { status: 0, id: 0 };
    const yarnId = project.yarn_ids?.[0];
    if (
      typeof yarnId !== "number" ||
      !Number.isSafeInteger(yarnId) ||
      yarnId < 1
    ) {
      return { status: detailResponse.status, id, photo_status: 0 };
    }
    const yarnResponse = await fetch(`/api/v2/yarns/${yarnId}`);
    if (!yarnResponse.ok) {
      return {
        status: detailResponse.status,
        id,
        photo_status: 0,
        yarn_status: yarnResponse.status,
      };
    }
    const yarn = ((await yarnResponse.json()) as ApiEnvelope<ApiYarn>)?.data;
    if (!yarn) return { status: 0, id: 0 };
    const yarnUpdateResponse = await fetch(`/api/v2/yarns/${yarnId}`, {
      method: "PUT",
      headers: {
        accept: "application/json",
        "content-type": "application/json",
        "x-csrf-token": token,
      },
      body: JSON.stringify({
        name: yarn.name,
        description: yarn.description,
        brand: yarn.brand,
        colorway: yarn.colorway,
        dye_lot: yarn.dye_lot,
        fiber_content: yarn.fiber_content,
        weight_category: yarn.weight_category,
        recommended_needles: yarn.recommended_needles,
        weight_grams: yarn.weight_grams,
        length_meters: yarn.length_meters,
        notes: yarn.notes,
        link: "https://ravelry.com/yarns/fixture",
        is_ai_enhanced: yarn.is_ai_enhanced,
        expected_updated_at: yarn.updated_at,
      }),
    });
    if (!yarnUpdateResponse.ok) {
      return {
        status: detailResponse.status,
        id,
        photo_status: 0,
        yarn_status: yarnUpdateResponse.status,
      };
    }
    const updateResponse = await fetch(`/api/v2/projects/${id}`, {
      method: "PUT",
      headers: {
        accept: "application/json",
        "content-type": "application/json",
        "x-csrf-token": token,
      },
      body: JSON.stringify({
        name: project.name,
        category: project.category,
        needles: "4 mm circular needles",
        stitch_sample: "18 stitches and 24 rows over 10 cm",
        description: "Knit a soft **textured blanket** with broad, even bands.",
        notes: "After the border, pause and check the width before continuing.",
        other_materials: "Two stitch markers and a tapestry needle.",
        tags: ["heirloom", "textured", "textured"],
        link: "https://ravelry.com/patterns/fixture",
        is_ai_enhanced: false,
        yarn_ids: project.yarn_ids,
        steps: [
          {
            title: "Cast on and knit the border",
            description: "Cast on 120 stitches and work the lower border.",
            step_number: 1,
          },
          {
            title: "Work the textured panel",
            description: "Repeat the four-row pattern to length.",
            step_number: 2,
          },
        ],
        expected_updated_at: project.updated_at,
      }),
    });
    if (!updateResponse.ok) {
      return { status: updateResponse.status, id, photo_status: 0 };
    }
    const updatedEnvelope =
      (await updateResponse.json()) as ApiEnvelope<ApiProject>;
    const updatedProject = updatedEnvelope?.data;
    const step = updatedProject?.steps?.find(
      (candidate) => candidate.title === "Work the textured panel",
    );
    const cover = project.images?.find((image) => image.step_id === null);
    if (
      typeof step?.id !== "number" ||
      !Number.isSafeInteger(step.id) ||
      typeof cover?.url !== "string"
    ) {
      return { status: updateResponse.status, id, photo_status: 0 };
    }
    const imageResponse = await fetch(cover.url);
    if (!imageResponse.ok) {
      return {
        status: updateResponse.status,
        id,
        photo_status: imageResponse.status,
      };
    }
    const image = await imageResponse.blob();
    const form = new FormData();
    form.append("file", image, "textured-panel-reference.jpg");
    form.append("alt_text", "Textured panel reference");
    const photoResponse = await fetch(
      `/api/v2/projects/${id}/steps/${step.id}/images`,
      {
        method: "POST",
        headers: { "x-csrf-token": token },
        body: form,
      },
    );
    return {
      status: updateResponse.status,
      id,
      photo_status: photoResponse.status,
      yarn_status: yarnUpdateResponse.status,
    };
  });
  if (
    result.status !== 200 ||
    !Number.isInteger(result.id) ||
    result.id < 1 ||
    result.photo_status !== 201 ||
    result.yarn_status !== 200
  ) {
    throw new AcceptanceFailure(
      "the synthetic project and step-photo fixture could not be prepared",
    );
  }
  return result.id;
}

interface PrintProbe {
  calls: number;
  imageErrors: number;
  atPrint: { complete: boolean; loaded: boolean }[];
}

type PrintProbeWindow = Window & { __printFailureProbe: PrintProbe };

export async function checkPrintImageFailureAndRetry(
  browser: Browser,
  origin: string,
  email: string,
  password: string,
  projectId: number,
  markStage: MarkStage,
): Promise<void> {
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    reducedMotion: "reduce",
    colorScheme: "light",
    locale: "en-US",
    serviceWorkers: "block",
  });
  try {
    const page = await context.newPage();
    page.setDefaultTimeout(10_000);
    markStage("opening isolated print-failure sign-in");
    const response = await page.goto(`${origin}/app/login`, {
      waitUntil: "domcontentloaded",
    });
    check(response !== null && response.status() === 200, "login_route_failed");
    await page.getByLabel("Email", { exact: true }).fill(email);
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.waitForURL("**/app/projects", { timeout: 15_000 });
    await page
      .getByRole("heading", { name: "Projects", exact: true })
      .waitFor({ state: "visible" });
    check(
      await page.evaluate(() => !navigator.serviceWorker?.controller),
      "the isolated print-failure context unexpectedly has a service worker",
    );

    markStage("opening isolated project reader");
    await page
      .locator(`.project-card a[href='/app/projects/${projectId}']`)
      .click();
    await page.waitForURL(`**/app/projects/${projectId}`, { timeout: 10_000 });
    await page
      .getByRole("heading", { name: "Heirloom Baby Blanket", exact: true })
      .waitFor({ state: "visible" });
    const images = page.locator(".print-reference-image");
    await images.first().waitFor({ state: "attached" });
    check(
      (await images.count()) === 2,
      "the print fixture image count changed",
    );

    const preparedImages = await page.evaluate((nonce: string) => {
      const images = Array.from(
        document.querySelectorAll<HTMLImageElement>(".print-reference-image"),
      );
      for (const [index, image] of images.entries()) {
        const source = image.dataset.printSrc;
        if (!source) return false;
        const url = new URL(source, location.href);
        url.searchParams.set("print_failure_probe", `${nonce}-${index}`);
        image.dataset.printSrc = url.href;
      }
      const probeWindow = window as unknown as PrintProbeWindow;
      probeWindow.__printFailureProbe = {
        calls: 0,
        imageErrors: 0,
        atPrint: [],
      };
      window.print = () => {
        probeWindow.__printFailureProbe.calls += 1;
        probeWindow.__printFailureProbe.atPrint = images.map((image) => ({
          complete: image.complete,
          loaded: image.naturalWidth > 0,
        }));
      };
      for (const image of images) {
        image.addEventListener("error", () => {
          probeWindow.__printFailureProbe.imageErrors += 1;
        });
      }
      return images.length === 2;
    }, randomBytes(9).toString("base64url"));
    check(Boolean(preparedImages), "the print fixture had no source images");

    const blockedRequests: [boolean, boolean, boolean][] = [];

    const abortProjectImage = async (route: Route): Promise<void> => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path.startsWith("/media/projects/")) {
        const isImageGet =
          request.method() === "GET" && request.resourceType() === "image";
        blockedRequests.push([request.method() === "GET", isImageGet, true]);
        if (isImageGet) {
          await route.abort();
          return;
        }
      }
      await route.continue();
    };

    await page.route("**/media/projects/**", abortProjectImage);
    markStage("waiting for aborted print image error");
    await (
      await openReaderActions(page)
    )
      .getByRole("menuitem", { name: "Print pattern", exact: true })
      .click();
    const alert = page.getByRole("alert").filter({
      hasText:
        "A reference photo could not be prepared for printing. Try again.",
    });
    await page.waitForFunction(
      () =>
        Number(
          (window as unknown as Partial<PrintProbeWindow>).__printFailureProbe
            ?.calls ?? 0,
        ) > 0 ||
        Array.from(document.querySelectorAll("[role=alert]")).some((node) =>
          node.textContent?.includes(
            "A reference photo could not be prepared for printing. Try again.",
          ),
        ),
      undefined,
      { timeout: 10_000 },
    );
    const alertVisible =
      (await alert.count()) > 0 && (await alert.first().isVisible());
    const failureState = await page.evaluate(
      () =>
        (window as unknown as Partial<PrintProbeWindow>).__printFailureProbe,
    );
    check(
      blockedRequests.length > 0 &&
        blockedRequests.every(
          (request) => request[0] && request[1] && request[2],
        ) &&
        failureState?.calls === 0 &&
        alertVisible,
      "uncached print image failure was not handled " +
        `(blocked_images=${blockedRequests.length}, ` +
        `print_calls=${failureState?.calls}, alert=${alertVisible})`,
    );

    await page.unroute("**/media/projects/**", abortProjectImage);
    markStage("retrying print after removing the image abort");
    await page.waitForFunction(
      () => {
        const button =
          document.querySelector<HTMLButtonElement>(".print-button");
        return button !== null && !button.disabled;
      },
      undefined,
      { timeout: 5_000 },
    );
    const successfulImageResponses: number[] = [];

    const observeRetryResponse = (retryResponse: Response): void => {
      const request = retryResponse.request();
      if (
        request.method() === "GET" &&
        request.resourceType() === "image" &&
        new URL(retryResponse.url()).pathname.startsWith("/media/projects/")
      ) {
        successfulImageResponses.push(retryResponse.status());
      }
    };

    page.on("response", observeRetryResponse);
    await page.evaluate(() => {
      const probeWindow = window as unknown as PrintProbeWindow;
      probeWindow.__printFailureProbe.calls = 0;
      probeWindow.__printFailureProbe.atPrint = [];
    });
    await (
      await openReaderActions(page)
    )
      .getByRole("menuitem", { name: "Print pattern", exact: true })
      .click();
    await page.waitForFunction(
      () =>
        Number(
          (window as unknown as Partial<PrintProbeWindow>).__printFailureProbe
            ?.calls ?? 0,
        ) > 0,
      undefined,
      { timeout: 15_000 },
    );
    const retryState = await page.evaluate(
      () => (window as unknown as PrintProbeWindow).__printFailureProbe,
    );
    const retryImages = retryState.atPrint ?? [];
    const loadedRetryImages = retryImages.filter(
      (image) => image.complete && image.loaded,
    ).length;
    check(
      retryState.calls === 1 &&
        retryImages.length === 2 &&
        retryImages.every((image) => image.complete && image.loaded) &&
        successfulImageResponses.length === 2 &&
        successfulImageResponses.every((status) => status === 200),
      "print retry did not load both images after removing the abort " +
        `(print_calls=${retryState.calls}, ` +
        `loaded_images=${loadedRetryImages}, ` +
        `image_responses=${successfulImageResponses.length})`,
    );
  } finally {
    await context.close();
  }
}

export async function saveRemoteProjectVersion(
  page: Page,
  projectId: number,
  name: string,
): Promise<void> {
  const result = await page.evaluate(
    async ({ id, name }: { id: number; name: string }) => {
      const sessionResponse = await fetch("/api/v2/session");
      if (!sessionResponse.ok) return { status: sessionResponse.status };
      const session = (await sessionResponse.json()) as ApiEnvelope<{
        csrf_token?: string;
      }>;
      const currentResponse = await fetch(`/api/v2/projects/${id}`);
      if (!currentResponse.ok) return { status: currentResponse.status };
      const current = (
        (await currentResponse.json()) as ApiEnvelope<ApiProject>
      )?.data;
      if (!current) return { status: 0 };
      const response = await fetch(`/api/v2/projects/${id}`, {
        method: "PUT",
        headers: {
          accept: "application/json",
          "content-type": "application/json",
          "x-csrf-token": session?.data?.csrf_token ?? "",
        },
        body: JSON.stringify({
          name,
          category: current.category,
          needles: current.needles,
          stitch_sample: current.stitch_sample,
          description: current.description,
          notes: current.notes,
          other_materials: current.other_materials,
          tags: current.tags,
          link: current.link,
          is_ai_enhanced: current.is_ai_enhanced,
          yarn_ids: current.yarn_ids,
          steps: (current.steps ?? []).map((step) => ({
            id: step.id,
            title: step.title,
            description: step.description,
            step_number: step.step_number,
          })),
          expected_updated_at: current.updated_at,
        }),
      });
      return { status: response.status };
    },
    { id: projectId, name },
  );
  check(
    isRecord(result) && result.status === 200,
    "a second client could not save the synthetic conflict version",
  );
}

/** Reveal a photo tile's hover-only trash button and press it with the mouse. */
export async function clickTileDelete(
  page: Page,
  tile: Locator,
): Promise<void> {
  await tile.evaluate((element) => element.scrollIntoView({ block: "center" }));
  await tile.hover();
  const button = tile.getByRole("button", {
    name: "Remove photo",
    exact: true,
  });
  await expect(button).toHaveCSS("opacity", "1");
  const box = await button.boundingBox();
  check(box !== null, "the photo tile has no visible delete button");
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.up();
}

export interface LibraryDensityOptions {
  rowSelector: string;
  routeName: string;
  width: number;
}

export async function measureLibraryDensity(
  page: Page,
  { rowSelector, routeName, width }: LibraryDensityOptions,
): Promise<Record<string, unknown>> {
  const measured: unknown = await page.evaluate((selector: string) => {
    const rows = [...document.querySelectorAll(selector)];
    const visible = rows.filter((row) => {
      const r = row.getBoundingClientRect();
      return r.top < innerHeight && r.bottom > 0;
    });
    const row = rows[0];
    const title = row?.querySelector(".library-card__title");
    const image = row?.querySelector(".library-card__media");
    const rect = row?.getBoundingClientRect();
    const titleRect = title?.getBoundingClientRect();
    const targets = [
      ...document.querySelectorAll(".search-field input, .filter-trigger"),
    ].filter((el) => el.getClientRects().length);
    return {
      firstItemY: rect?.top ?? null,
      rowHeight: rect?.height ?? null,
      visibleRows: visible.length,
      titleFontSize: title
        ? parseFloat(getComputedStyle(title).fontSize)
        : null,
      titleWidth: titleRect?.width ?? null,
      thumbnailWidth: image?.getBoundingClientRect().width ?? null,
      targetHeights: targets.map((el) => el.getBoundingClientRect().height),
      controlsBeforeFirstItem:
        row && rect
          ? Math.max(
              0,
              [...document.querySelectorAll(".page-header, .library-controls")]
                .map((el) => el.getBoundingClientRect().bottom)
                .filter((bottom) => bottom < rect.top).length,
            )
          : 0,
      viewport: innerWidth,
    };
  }, rowSelector);
  if (!isRecord(measured)) {
    throw new AcceptanceFailure(
      `${routeName} density measurements were unavailable`,
    );
  }
  const {
    firstItemY,
    rowHeight,
    visibleRows,
    titleFontSize,
    titleWidth,
    thumbnailWidth,
    targetHeights,
  } = measured;
  check(
    isNumber(firstItemY) &&
      (width > 767 || firstItemY <= 200) &&
      isNumber(rowHeight) &&
      rowHeight >= 72 &&
      typeof visibleRows === "number" &&
      Number.isInteger(visibleRows) &&
      visibleRows >= 1 &&
      isNumber(titleFontSize) &&
      titleFontSize >= 14 &&
      isNumber(titleWidth) &&
      titleWidth > 0 &&
      isNumber(thumbnailWidth) &&
      thumbnailWidth >= 56 &&
      Array.isArray(targetHeights) &&
      targetHeights.length > 0 &&
      Math.min(...(targetHeights as number[])) >= 44,
    `${routeName} first-row density, readable title, thumbnail, ` +
      `or targets failed; numeric_metrics=${sortedJson(measured)}`,
  );
  return { ...measured, width };
}

export async function openLibraryFilters(page: Page): Promise<Locator> {
  const trigger = page.locator(".filter-trigger");
  const dialog = page.locator("dialog.filter-panel");
  check(
    (await trigger.count()) === 1 && (await dialog.count()) === 1,
    "library filter controls are ambiguous",
  );
  if (
    !(await dialog.evaluate((element) => (element as HTMLDialogElement).open))
  ) {
    await trigger.click();
  }
  await page.getByRole("dialog").waitFor({ state: "visible" });
  check(
    await dialog.evaluate((element) => (element as HTMLDialogElement).open),
    "the native filter dialog did not open",
  );
  return dialog;
}

export async function waitForLibraryFiltersClosed(
  dialog: Locator,
): Promise<void> {
  await dialog.waitFor({ state: "hidden" });
  check(
    !(await dialog.evaluate((element) => (element as HTMLDialogElement).open)),
    "the native filter dialog did not close",
  );
}

export async function closeLibraryFilters(dialog: Locator): Promise<void> {
  await dialog
    .getByRole("button", { name: "Close filters", exact: true })
    .click();
  await waitForLibraryFiltersClosed(dialog);
}

export async function assertHeaderParentLink(
  page: Page,
  href: string,
  description: string,
): Promise<Locator> {
  const link = page.locator(".app-header .brand-lockup.parent-link");
  const single = (await link.count()) === 1;
  const bounds = single ? await link.boundingBox() : null;
  check(
    single &&
      (await link.getAttribute("aria-label")) === "Back" &&
      (await link.getAttribute("href")) === href &&
      bounds !== null &&
      bounds.width >= 44 &&
      bounds.height >= 44,
    `${description} header parent link has the wrong route or target size`,
  );
  return link;
}

export async function visibleHeights(
  page: Page,
  selector: string | Locator,
): Promise<number[]> {
  const locator =
    typeof selector === "string" ? page.locator(selector) : selector;
  const values: unknown = await locator.evaluateAll((elements) =>
    elements
      .filter((el) => el.getClientRects().length)
      .map((el) => el.getBoundingClientRect().height),
  );
  if (!Array.isArray(values) || values.some((value) => !isNumber(value))) {
    throw new AcceptanceFailure(
      "browser returned invalid navigation target sizes",
    );
  }
  return values.map((value) => Number(value));
}

export async function assertStepInUsableViewport(
  page: Page,
  stepId: number,
): Promise<void> {
  const bounds: unknown = await page
    .locator(`#step-${stepId}`)
    .evaluate((element) => {
      const step = element.getBoundingClientRect();
      const header = document.querySelector(".app-header");
      const bottomNavigation = document.querySelector(".mobile-navigation");
      const headerBottom =
        header && getComputedStyle(header).display !== "none"
          ? header.getBoundingClientRect().bottom
          : 0;
      const navigationTop =
        bottomNavigation &&
        getComputedStyle(bottomNavigation).display !== "none"
          ? bottomNavigation.getBoundingClientRect().top
          : window.innerHeight;
      return {
        intersectsUsableViewport:
          step.bottom > headerBottom &&
          step.top < navigationTop &&
          step.bottom > 0 &&
          step.top < window.innerHeight,
        height: step.height,
      };
    });
  if (
    !isRecord(bounds) ||
    bounds.intersectsUsableViewport !== true ||
    !isNumber(bounds.height) ||
    bounds.height <= 0
  ) {
    throw new AcceptanceFailure(
      "the current pattern step is outside the usable viewport",
    );
  }
}

export async function assertMobileAppHeader(page: Page): Promise<void> {
  check(
    (await page.getByText("Welcome back", { exact: true }).count()) === 0,
    "the redundant welcome greeting remains in the web UI",
  );
  check(
    (await page
      .locator(".header-user, .header-sign-out, .mobile-sign-out")
      .count()) === 0 &&
      (await page.getByRole("heading", { level: 1 }).count()) === 1,
    "the authenticated shell has legacy account controls or no single route H1",
  );
  check(
    (await page
      .locator(".secondary-nav .nav-icon svg[aria-hidden='true']")
      .count()) > 0,
    "the authenticated secondary navigation has no decorative icons",
  );
  check(
    (await page.locator(".app-header .locale-switcher").count()) === 0,
    "the compact mobile header still reserves space for language controls",
  );
  check(
    (await page.locator(".app-header .header-account").count()) === 0 &&
      (await page.locator(".app-header button").count()) === 0,
    "the phone header still has an account avatar button",
  );
  const tabs = page.locator(".mobile-navigation a");
  const labels = await tabs.evaluateAll((nodes) =>
    nodes.map((node) => node.lastElementChild?.textContent?.trim() ?? ""),
  );
  check(
    sameJson(labels, ["Home", "Projects", "Yarns", "More"]) ||
      sameJson(labels, ["Start", "Projekte", "Garne", "Mehr"]),
    `the phone bar must have Home, Projects, Yarns and More, found ${labels.join("/")}`,
  );
  const more = phoneMoreTab(page);
  const dimensions: unknown = await more.evaluate((node) => {
    const rect = node.getBoundingClientRect();
    return { width: rect.width, height: rect.height };
  });
  check(
    isRecord(dimensions) &&
      isNumber(dimensions.width) &&
      isNumber(dimensions.height) &&
      dimensions.width >= 44 &&
      dimensions.height >= 44 &&
      (await more.getAttribute("href"))?.endsWith("/app/more") === true,
    "the More tab is too small or does not point to the More page",
  );
}

export async function assertDesktopAccountTarget(page: Page): Promise<void> {
  const accountTarget = await assertAccountLink(page, "desktop");
  const dimensions: unknown = await accountTarget.evaluate((node) => ({
    width: node.getBoundingClientRect().width,
    height: node.getBoundingClientRect().height,
  }));
  check(
    isRecord(dimensions) &&
      isNumber(dimensions.height) &&
      dimensions.height >= 44,
    "the desktop profile target is smaller than 44px",
  );
}

/** The Account page language segmented control, found by role and name. */
export function accountLanguageGroup(page: Page): Locator {
  return page.getByRole("group", { name: /^(Language|Sprache)$/ });
}

export async function assertAccountLanguageControl(page: Page): Promise<void> {
  const group = accountLanguageGroup(page);
  check(
    (await group.count()) === 1,
    "the Account page does not have exactly one language control",
  );
  const buttons = group.getByRole("button");
  check(
    (await buttons.count()) === 2,
    "the language control does not have two options",
  );
  for (const [index, name] of ["English", "Deutsch"].entries()) {
    const button = buttons.nth(index);
    const pressed = await button.getAttribute("aria-pressed");
    check(
      (await button.innerText()).trim() === name &&
        (pressed === "true" || pressed === "false"),
      "a language option lost its visible name or pressed state",
    );
  }
  const states = new Set<string | null>();
  for (const button of await buttons.all()) {
    states.add(await button.getAttribute("aria-pressed"));
  }
  check(
    states.size === 2 && states.has("true") && states.has("false"),
    "the language control does not mark exactly one option as selected",
  );
}

export async function svgShapeSignature(icon: Locator): Promise<string> {
  const signature: unknown = await icon.evaluate((svg) =>
    [...svg.querySelectorAll("path, circle, rect, line, polyline, polygon")]
      .map((shape) => {
        const attributes = [
          "d",
          "cx",
          "cy",
          "r",
          "x",
          "y",
          "width",
          "height",
          "x1",
          "x2",
          "y1",
          "y2",
          "points",
        ];
        const drawing = attributes
          .filter((name) => shape.hasAttribute(name))
          .map((name) => `${name}=${shape.getAttribute(name)}`)
          .join(";");
        return `${shape.tagName.toLowerCase()}:${drawing}`;
      })
      .sort()
      .join("|"),
  );
  return typeof signature === "string" ? signature : "";
}

export interface ShellAvatarOptions {
  mobile: boolean;
  present: boolean;
}

export async function assertShellAvatar(
  page: Page,
  { mobile, present }: ShellAvatarOptions,
): Promise<void> {
  const target = mobile
    ? ".mobile-navigation a.more-tab"
    : ".desktop-rail .rail-account";
  const image = page.locator(`${target} .user-avatar img`);
  if (!present) {
    check(
      (await image.count()) === 0,
      "the removed avatar remains in the app shell",
    );
    return;
  }
  await image.waitFor({ state: "visible", timeout: 10_000 });
  check(
    Boolean(
      await image.evaluate((node) => {
        const avatar = node as HTMLImageElement;
        return avatar
          .decode()
          .then(() => avatar.complete && avatar.naturalWidth > 0)
          .catch(() => false);
      }),
    ),
    "the profile avatar did not render in the app shell",
  );
}

export async function openAccountFromProfile(
  page: Page,
  mobile = false,
): Promise<void> {
  if (mobile) {
    await assertMobileAppHeader(page);
  } else {
    await assertDesktopAccountTarget(page);
  }
  await openAccountSettings(page);
  await page
    .getByRole("heading", { name: ACCOUNT_HEADING_NAME })
    .waitFor({ state: "visible", timeout: 10_000 });
}

export async function accountSignOutButton(page: Page): Promise<Locator> {
  const button = page.getByRole("button", {
    name: SIGN_OUT_BUTTON_NAME,
    exact: true,
  });
  check(await button.isVisible(), "Account sign-out action is unavailable");
  return button;
}

export async function assertServiceCapabilities(
  page: Page,
  enabled: boolean,
): Promise<void> {
  const capabilities: unknown = await page.evaluate(async () => {
    const response = await fetch("/api/v2/session");
    if (!response.ok) return null;
    const envelope = (await response.json()) as ApiEnvelope<{
      capabilities?: unknown;
    }>;
    return envelope?.data?.capabilities ?? null;
  });
  if (
    !isRecord(capabilities) ||
    capabilities.ocr !== enabled ||
    capabilities.archive !== enabled
  ) {
    throw new AcceptanceFailure(
      "the browser fixture reported unexpected OCR/archive capabilities",
    );
  }
}

const ARCHIVE_BUTTON_NAME = /^(?:Request a new archive|Archive this source)$/;

function isPostTo(response: Response, path: string): boolean {
  return (
    responsePath(response) === path && response.request().method() === "POST"
  );
}

/** Parse the OCR request body and response, hiding the content on failure. */
async function readJsonExchange(
  response: Response,
  failure: string,
): Promise<{ requestPayload: unknown; resultPayload: unknown }> {
  try {
    const requestPayload: unknown = JSON.parse(
      response.request().postData() ?? "",
    );
    const resultPayload: unknown = await response.json();
    return { requestPayload, resultPayload };
  } catch {
    throw new AcceptanceFailure(failure);
  }
}

export async function exerciseProjectServiceTools(
  page: Page,
  origin: string,
  projectId: number,
  enabled: boolean,
  ocrMode: string,
  archiveMode: string,
  markStage: MarkStage,
  diagnosticDir: string,
): Promise<void> {
  await assertServiceCapabilities(page, enabled);
  if (!enabled) {
    check(
      (await page.locator(".ocr-control, .archive-control").count()) === 0,
      "disabled service controls were exposed in the project reader",
    );
    return;
  }

  markStage("opening OCR controls for a stored project image");
  const ocr = page.locator(".steps-list .step-photo-card .ocr-control").first();
  try {
    await ocr.waitFor({ state: "visible", timeout: 5_000 });
  } catch (error) {
    if (!(error instanceof errors.TimeoutError)) throw error;
    let counts: unknown;
    try {
      page.setDefaultTimeout(1_000);
      counts = await page.evaluate(() => ({
        photoCards: document.querySelectorAll(".steps-list .step-photo-card")
          .length,
        ocrControls: document.querySelectorAll(".ocr-control").length,
        routeReady: document.querySelectorAll(".reader-columns").length > 0,
        authenticated:
          document.querySelectorAll(
            ".desktop-rail .rail-account, .mobile-navigation a.more-tab",
          ).length > 0,
        viewportVisible: document.visibilityState === "visible",
      }));
    } catch (queryError) {
      if (!(queryError instanceof errors.TimeoutError)) throw queryError;
      counts = "page script did not respond to the diagnostic query";
    }
    let maskSucceeded = false;
    page.setDefaultTimeout(1_500);
    try {
      await maskFixtureIdentity(page);
      maskSucceeded = true;
    } catch (maskError) {
      if (!(maskError instanceof errors.TimeoutError)) throw maskError;
      counts = `${sortedJson(counts)}; identity mask timed out, screenshot suppressed`;
    }
    if (maskSucceeded) {
      try {
        await page.screenshot({
          path: join(diagnosticDir, "project-ocr-control-debug.png"),
          caret: "initial",
          timeout: 2_000,
        });
      } catch (shotError) {
        if (!(shotError instanceof errors.TimeoutError)) throw shotError;
        counts = `${sortedJson(counts)}; sanitized screenshot timed out`;
      }
    }
    page.setDefaultTimeout(30_000);
    throw new AcceptanceFailure(
      "project OCR control did not become visible " +
        `(sanitized DOM checks: ${typeof counts === "string" ? counts : sortedJson(counts)})`,
    );
  }
  markStage("opening the project OCR control");
  await ocr.locator("summary").click();
  markStage("selecting the project OCR language");
  const htmlLanguage = await page.locator("html").getAttribute("lang");
  let languageLabel: string;
  if (htmlLanguage === "en") {
    languageLabel = "Recognition language";
  } else if (htmlLanguage === "de") {
    languageLabel = "Erkennungssprache";
  } else {
    throw new AcceptanceFailure(
      "the OCR fixture had an unsupported page language",
    );
  }
  const language = ocr.getByLabel(languageLabel, { exact: true });
  try {
    await language.waitFor({ state: "visible", timeout: 5_000 });
  } catch (error) {
    if (!(error instanceof errors.TimeoutError)) throw error;
    const detailsOpen = await ocr.evaluate(
      (node) => (node as HTMLDetailsElement).open,
    );
    const selectorLabels = await ocr.locator("select").evaluateAll((nodes) =>
      nodes.map((node) => ({
        visible: !!(
          (node as HTMLElement).offsetWidth ||
          (node as HTMLElement).offsetHeight
        ),
        labels: Array.from((node as HTMLSelectElement).labels ?? []).map(
          (label) => label.textContent?.trim() ?? "",
        ),
      })),
    );
    await maskFixtureIdentity(page);
    await page.screenshot({
      path: join(diagnosticDir, "project-ocr-language-debug.png"),
      caret: "initial",
    });
    throw new AcceptanceFailure(
      "the OCR language selector was not available by its translated label " +
        `(details_open=${detailsOpen}; selectors=${JSON.stringify(selectorLabels)})`,
    );
  }
  check(
    (await language.inputValue()) === "deu+eng",
    "OCR did not start with the configured German and English languages",
  );
  await language.selectOption("deu");
  markStage("submitting project OCR");
  const recognize = ocr.getByRole("button", {
    name: "Recognize text",
    exact: true,
  });
  if (ocrMode === "failure") {
    const [ocrResponse] = await Promise.all([
      page.waitForResponse((response) =>
        isPostTo(response, "/api/v2/utils/ocr"),
      ),
      recognize.click(),
    ]);
    check(
      ocrResponse.status() >= 500,
      "the configured OCR failure did not reach the real API route",
    );
    await ocr.getByRole("alert").waitFor({ state: "visible" });
    check(
      (await ocr.getByRole("alert").innerText()) ===
        "Text recognition could not complete. You can enter the text manually.",
      "the OCR failure was not translated into actionable text",
    );
  } else {
    await page
      .context()
      .grantPermissions(["clipboard-read", "clipboard-write"], { origin });
    const [ocrResponse] = await Promise.all([
      page.waitForResponse((response) =>
        isPostTo(response, "/api/v2/utils/ocr"),
      ),
      recognize.click(),
    ]);
    check(ocrResponse.status() === 200, "the OCR API did not succeed");
    const { requestPayload, resultPayload } = await readJsonExchange(
      ocrResponse,
      "the OCR API returned an unreadable result",
    );
    check(
      isRecord(requestPayload) &&
        typeof requestPayload.src === "string" &&
        requestPayload.src.startsWith("/media/projects/") &&
        requestPayload.lang === "deu" &&
        requestPayload.force === false,
      "OCR did not send the selected language and owned media path",
    );
    const data = isRecord(resultPayload) ? resultPayload.data : undefined;
    if (!isRecord(data) || typeof data.text !== "string") {
      throw new AcceptanceFailure(
        "the OCR API returned unexpected result types",
      );
    }
    const recognizedText = data.text;
    const textarea = ocr.locator("textarea");
    await textarea.waitFor({ state: "visible", timeout: 5_000 });
    check(
      (await textarea.inputValue()) === recognizedText &&
        recognizedText.trim() !== "",
      "the OCR result did not display the exact real API response",
    );
    await ocr
      .getByRole("button", { name: "Copy recognized text", exact: true })
      .click();
    await ocr
      .getByRole("button", { name: "Text copied", exact: true })
      .waitFor({ state: "visible", timeout: 5_000 });
    check(
      (await page.evaluate(() => navigator.clipboard.readText())) ===
        recognizedText,
      "copy did not transfer the recognized text to the clipboard",
    );
  }

  markStage("requesting a project source archive");
  markStage("loading project archive controls");
  const archive = page.locator(".source-card .archive-control");
  await archive.waitFor({ state: "visible", timeout: 5_000 });
  markStage("checking project archive status");
  const archivePath = `/api/v2/projects/${projectId}/archive`;
  if (archiveMode === "failure") {
    const retry = archive.getByRole("button", { name: ARCHIVE_BUTTON_NAME });
    markStage("waiting for the project archive retry action");
    await retry.waitFor({ state: "visible", timeout: 10_000 });
    const [archiveResponse] = await Promise.all([
      page.waitForResponse((response) => isPostTo(response, archivePath)),
      retry.click(),
    ]);
    check(
      archiveResponse.status() === 202,
      "the project archive request was not queued by the Rust API",
    );
    await archive
      .getByText("The archive could not be completed.", { exact: true })
      .waitFor({ state: "visible", timeout: 15_000 });
    check(
      await archive
        .getByRole("button", { name: "Request a new archive", exact: true })
        .isVisible(),
      "the failed archive did not offer an explicit retry",
    );
    check(
      (await page
        .getByRole("link", { name: "Open archived copy", exact: true })
        .count()) === 0,
      "a failed archive was shown as an available copy",
    );
  } else {
    const ready = archive.getByText("An archived copy is ready.", {
      exact: true,
    });
    try {
      markStage("waiting for the project archive to complete");
      await ready.waitFor({ state: "visible", timeout: 10_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) throw error;
      const archiveButton = archive.getByRole("button", {
        name: ARCHIVE_BUTTON_NAME,
      });
      markStage("waiting for the project archive action");
      await archiveButton.waitFor({ state: "visible", timeout: 10_000 });
      const [archiveResponse] = await Promise.all([
        page.waitForResponse((response) => isPostTo(response, archivePath)),
        archiveButton.click(),
      ]);
      check(
        archiveResponse.status() === 202,
        "the project archive request was not queued by the Rust API",
      );
      markStage("waiting for the project archive action to complete");
      await ready.waitFor({ state: "visible", timeout: 20_000 });
    }
    const archivedLink = page.getByRole("link", {
      name: /^Open archived copy/,
    });
    markStage("verifying the refreshed project archive link");
    try {
      await archivedLink.waitFor({ state: "visible", timeout: 5_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) throw error;
      const details: unknown = await page.evaluate(async (id: number) => {
        const response = await fetch(`/api/v2/projects/${id}`);
        const envelope = response.ok
          ? ((await response.json()) as ApiEnvelope<{ link_archive?: unknown }>)
          : null;
        const text =
          document
            .querySelector(".source-card .archive-status")
            ?.textContent?.trim() ?? null;
        return {
          responseStatus: response.status,
          detailHasArchive: typeof envelope?.data?.link_archive === "string",
          statusText: text,
          linkCount: document.querySelectorAll(
            '.source-card a[href*="web.archive.org"]',
          ).length,
        };
      }, projectId);
      throw new AcceptanceFailure(
        "the project archive completed but its link was not shown " +
          `(sanitized state: ${sortedJson(details)})`,
      );
    }
    check(
      ((await archivedLink.getAttribute("href")) ?? "").includes(
        "web.archive.org",
      ),
      "the freshly loaded project detail did not expose its archived link",
    );
  }
  if (ocrMode === "success") {
    check(
      (await ocr.locator("textarea").inputValue()).trim() !== "",
      "project OCR result disappeared after the archive update",
    );
  } else {
    check(
      await ocr.getByRole("alert").isVisible(),
      "project OCR error disappeared after the archive update",
    );
  }
  await maskFixtureIdentity(page);
  await prepareScreenshot(page);
  await page.screenshot({
    path: join(diagnosticDir, "project-media-tools.png"),
    caret: "initial",
    fullPage: true,
  });
}

export async function exerciseYarnServiceTools(
  page: Page,
  _origin: string,
  yarnId: number,
  enabled: boolean,
  ocrMode: string,
  archiveMode: string,
  markStage: MarkStage,
  screenshots: string,
): Promise<void> {
  await assertServiceCapabilities(page, enabled);
  if (!enabled) {
    check(
      (await page.locator(".ocr-control, .archive-control").count()) === 0,
      "disabled service controls were exposed on the yarn detail",
    );
    return;
  }

  markStage("opening OCR controls for a stored yarn photo");
  const ocr = page.locator(".photo-strip__tile .ocr-control").first();
  await ocr.waitFor({ state: "visible", timeout: 5_000 });
  await ocr.locator("summary").click();
  const recognize = ocr.getByRole("button", {
    name: "Recognize text",
    exact: true,
  });
  if (ocrMode === "failure") {
    const [ocrResponse] = await Promise.all([
      page.waitForResponse((response) =>
        isPostTo(response, "/api/v2/utils/ocr"),
      ),
      recognize.click(),
    ]);
    check(
      ocrResponse.status() >= 500,
      "the yarn OCR failure did not reach the real API route",
    );
    await ocr.getByRole("alert").waitFor({ state: "visible" });
  } else {
    const [response] = await Promise.all([
      page.waitForResponse((candidate) =>
        isPostTo(candidate, "/api/v2/utils/ocr"),
      ),
      recognize.click(),
    ]);
    check(response.status() === 200, "yarn photo OCR did not succeed");
    const { requestPayload, resultPayload } = await readJsonExchange(
      response,
      "the yarn OCR response was unreadable",
    );
    const data = isRecord(resultPayload) ? resultPayload.data : undefined;
    if (
      !isRecord(requestPayload) ||
      typeof requestPayload.src !== "string" ||
      !requestPayload.src.startsWith("/media/yarns/") ||
      !isRecord(data) ||
      typeof data.text !== "string"
    ) {
      throw new AcceptanceFailure("the yarn OCR response had unexpected types");
    }
    const textarea = ocr.locator("textarea");
    await textarea.waitFor({ state: "visible", timeout: 5_000 });
    check(
      (await textarea.inputValue()) === data.text && data.text.trim() !== "",
      "the yarn OCR control did not display the exact API result",
    );
  }

  markStage("requesting a yarn source archive");
  markStage("loading yarn archive controls");
  const archive = page.locator(".spec-panel .archive-control");
  await archive.waitFor({ state: "visible", timeout: 5_000 });
  markStage("checking yarn archive status");
  const archivePath = `/api/v2/yarns/${yarnId}/archive`;
  if (archiveMode === "failure") {
    const retry = archive.getByRole("button", { name: ARCHIVE_BUTTON_NAME });
    markStage("waiting for the yarn archive retry action");
    await retry.waitFor({ state: "visible", timeout: 10_000 });
    const [archiveResponse] = await Promise.all([
      page.waitForResponse((response) => isPostTo(response, archivePath)),
      retry.click(),
    ]);
    check(
      archiveResponse.status() === 202,
      "the yarn archive request was not queued by the Rust API",
    );
    await archive
      .getByText("The archive could not be completed.", { exact: true })
      .waitFor({ state: "visible", timeout: 15_000 });
    check(
      await archive
        .getByRole("button", { name: "Request a new archive", exact: true })
        .isVisible(),
      "the failed yarn archive did not offer an explicit retry",
    );
    check(
      (await page
        .getByRole("link", { name: "Archived source", exact: true })
        .count()) === 0,
      "a failed yarn archive was shown as an available copy",
    );
  } else {
    const ready = archive.getByText("An archived copy is ready.", {
      exact: true,
    });
    try {
      markStage("waiting for the yarn archive to complete");
      await ready.waitFor({ state: "visible", timeout: 10_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) throw error;
      const archiveButton = archive.getByRole("button", {
        name: ARCHIVE_BUTTON_NAME,
      });
      markStage("waiting for the yarn archive action");
      await archiveButton.waitFor({ state: "visible", timeout: 10_000 });
      const [archiveResponse] = await Promise.all([
        page.waitForResponse((response) => isPostTo(response, archivePath)),
        archiveButton.click(),
      ]);
      check(
        archiveResponse.status() === 202,
        "the yarn archive request was not queued by the Rust API",
      );
      markStage("waiting for the yarn archive action to complete");
      await ready.waitFor({ state: "visible", timeout: 20_000 });
    }
    const archivedLink = page.getByRole("link", {
      name: "Archived source",
      exact: true,
    });
    await archivedLink.waitFor({ state: "visible", timeout: 5_000 });
    check(
      ((await archivedLink.getAttribute("href")) ?? "").includes(
        "web.archive.org",
      ),
      "the freshly loaded yarn detail did not expose its archived link",
    );
  }
  if (ocrMode === "success") {
    check(
      (await ocr.locator("textarea").inputValue()).trim() !== "",
      "yarn OCR result disappeared after the archive update",
    );
  } else {
    check(
      await ocr.getByRole("alert").isVisible(),
      "yarn OCR error disappeared after the archive update",
    );
  }
  await maskFixtureIdentity(page);
  await prepareScreenshot(page);
  await page.screenshot({
    path: join(screenshots, "yarn-media-tools.png"),
    caret: "initial",
    fullPage: true,
  });
}

interface GaugeHold {
  armed: boolean;
  responseReady: boolean;
  aborted: boolean;
  missingSignal?: boolean;
  release?: () => void;
  originalFetch: typeof fetch;
}

type GaugeHoldWindow = Window & { __gaugeResponseHold?: GaugeHold };

export async function exerciseGaugeCalculator(
  page: Page,
  origin: string,
  signedIn: boolean,
  markStage: MarkStage,
  screenshots: string,
): Promise<void> {
  const accountState = signedIn ? "signed-in" : "anonymous";
  markStage(`loading ${accountState} gauge route`);
  const routeResponse = await page.goto(`${origin}/app/gauge`, {
    waitUntil: "networkidle",
  });
  check(
    routeResponse !== null && routeResponse.status() === 200,
    "the gauge calculator route did not load",
  );
  const gaugeHeading = page.getByRole("heading", {
    name: "Gauge calculator",
    exact: true,
  });
  const accountHeading = page.getByRole("heading", {
    name: "Account",
    exact: true,
  });
  await gaugeHeading.waitFor({ state: "visible" });
  let languageSwitcher: Locator;
  let germanName: string;
  if (signedIn) {
    await openAccountSettings(page);
    await accountHeading.waitFor({ state: "visible" });
    languageSwitcher = accountLanguageGroup(page);
    germanName = "Deutsch";
  } else {
    languageSwitcher = page.locator(".public-tool-header .locale-switcher");
    germanName = "German";
  }
  const englishButton = languageSwitcher.getByRole("button", {
    name: "English",
    exact: true,
  });
  let germanButton = languageSwitcher.getByRole("button", {
    name: germanName,
    exact: true,
  });
  check(
    (await englishButton.isVisible()) && (await germanButton.isVisible()),
    "the language switch is missing from the gauge page",
  );
  markStage(`checking ${accountState} language preference persistence`);
  await englishButton.click();
  if (signedIn) {
    await accountHeading.waitFor({ state: "visible" });
    await page.goto(`${origin}/app/gauge`, { waitUntil: "networkidle" });
    await gaugeHeading.waitFor({ state: "visible" });
  }
  await gaugeHeading.waitFor({ state: "visible" });
  if (signedIn) {
    await openAccountSettings(page);
    await accountHeading.waitFor({ state: "visible" });
    germanButton = accountLanguageGroup(page).getByRole("button", {
      name: "Deutsch",
      exact: true,
    });
  }
  await germanButton.click();
  if (signedIn) {
    await page
      .getByRole("heading", { name: "Konto", exact: true })
      .waitFor({ state: "visible" });
    await page.goto(`${origin}/app/gauge`, { waitUntil: "networkidle" });
  }
  await page
    .getByRole("heading", { name: "Maschenprobe berechnen", exact: true })
    .waitFor({ state: "visible" });
  check(
    (await page.locator("html").getAttribute("lang")) === "de" &&
      (await page.evaluate(() => document.cookie)).includes("language=de"),
    "the German preference did not update the document and language cookie",
  );
  await page
    .locator(".gauge-form")
    .evaluate((form) =>
      form.dispatchEvent(
        new SubmitEvent("submit", { bubbles: true, cancelable: true }),
      ),
    );
  await page.getByRole("alert").waitFor({ state: "visible" });
  check(
    (await page.getByRole("alert").innerText()) ===
      "Prüfe die markierten Felder und versuche es erneut.",
    "the gauge validation message was not translated into German",
  );
  await page.reload({ waitUntil: "networkidle" });
  await page
    .getByRole("heading", { name: "Maschenprobe berechnen", exact: true })
    .waitFor({ state: "visible" });
  if (signedIn) {
    await openAccountSettings(page);
    await page
      .getByRole("heading", { name: "Konto", exact: true })
      .waitFor({ state: "visible" });
    await accountLanguageGroup(page).getByRole("button").nth(0).click();
    await page.goto(`${origin}/app/gauge`, { waitUntil: "networkidle" });
  } else {
    await page
      .locator(".public-tool-header .locale-switcher button")
      .nth(0)
      .click();
  }
  await gaugeHeading.waitFor({ state: "visible" });

  markStage(`checking ${accountState} gauge layout and viewport targets`);
  if (signedIn) {
    await page.locator(".app-shell").waitFor({ state: "visible" });
    check(
      (await page.locator(".mobile-navigation").isVisible()) &&
        (await page.locator(".public-tool-shell").count()) === 0,
      "the signed-in gauge route did not retain the app shell",
    );
  } else {
    check(
      (await page.locator(".public-tool-shell").count()) === 1 &&
        (await page.locator(".app-shell").count()) === 0 &&
        (await page.locator(".mobile-navigation").count()) === 0 &&
        (await page
          .getByRole("link", { name: "Projects", exact: true })
          .count()) === 0 &&
        (await page
          .getByRole("link", { name: "Yarns", exact: true })
          .count()) === 0,
      "the public gauge route exposed private library navigation",
    );
  }

  for (const width of [320, 390, 1280]) {
    await page.setViewportSize({ width, height: 844 });
    await assertNoHorizontalOverflow(page, width);
    const controls = await visibleHeights(page, ".gauge-form input");
    check(
      controls.length === 6 && Math.min(...controls) >= 44,
      "a gauge form control is below the 44px target size",
    );
  }

  const legends = await page.locator(".gauge-form legend").allInnerTexts();
  check(
    sameJson(legends, [
      "Pattern Gauge (per 10cm)",
      "Pattern Counts",
      "Your Gauge (per 10cm)",
    ]),
    "the gauge fieldsets are not ordered pattern gauge, pattern counts, your gauge",
  );
  const helpSummary = page.locator(".help-panel summary");
  check(
    (await helpSummary.locator("svg").count()) === 2 &&
      (await page.locator(".help-panel[open]").count()) === 0,
    "the gauge help summary lacks its icon and chevron or starts open",
  );
  await helpSummary.click();
  check(
    (await page.locator(".help-panel[open]").count()) === 1,
    "the gauge help summary did not open",
  );
  await helpSummary.click();
  check(
    (await page.locator(".size-button").count()) === 2,
    "the pattern and user swatch size buttons are missing from the gauge page",
  );

  await page.setViewportSize({ width: 390, height: 844 });
  markStage(`capturing sanitized ${accountState} gauge screen`);
  await maskFixtureIdentity(page);
  await prepareScreenshot(page);
  await page.screenshot({
    path: join(screenshots, `gauge-${accountState}-390.png`),
    caret: "initial",
  });
  await page.reload({ waitUntil: "networkidle" });
  await gaugeHeading.waitFor({ state: "visible" });
  const expectedInput: Record<string, number> = {
    pattern_gauge_stitches: 20,
    pattern_gauge_rows: 28,
    user_gauge_stitches: 22,
    user_gauge_rows: 30,
    pattern_cast_on_stitches: 80,
    pattern_row_count: 100,
  };
  check(
    (await page
      .getByRole("button", { name: "Calculate", exact: true })
      .count()) === 0,
    "the gauge page still shows a Calculate button",
  );
  const gaugeRequests: Request[] = [];
  const gaugeResponseStatuses: number[] = [];
  const gaugePageErrorLocations: string[] = [];

  const isGaugeRequest = (request: Request): boolean =>
    new URL(request.url()).pathname === "/api/v2/tools/gauge" &&
    request.method() === "POST";

  const observeGaugeRequest = (request: Request): void => {
    if (isGaugeRequest(request)) gaugeRequests.push(request);
  };

  const observeGaugeResponse = (response: Response): void => {
    if (responsePath(response) === "/api/v2/tools/gauge") {
      gaugeResponseStatuses.push(response.status());
    }
  };

  const observeGaugePageError = (error: Error): void => {
    const match = /(\/app\/_app\/immutable\/[^\s:)]+\.js):(\d+):(\d+)/.exec(
      error.stack ?? "",
    );
    gaugePageErrorLocations.push(
      match
        ? `${match[1]}:${match[2]}:${match[3]}`
        : "script-location-unavailable",
    );
  };

  page.on("request", observeGaugeRequest);
  page.on("response", observeGaugeResponse);
  page.on("pageerror", observeGaugePageError);

  markStage(`checking ${accountState} incomplete gauge input stays quiet`);
  await page.locator("[name='pattern_gauge_stitches']").fill("20");
  await page.waitForTimeout(700);
  check(
    gaugeRequests.length === 0 &&
      (await page.locator(".results").count()) === 0 &&
      (await page.locator(".field-error").count()) === 0 &&
      (await page.locator('[role="alert"]').count()) === 0,
    "incomplete gauge input triggered a request, a result or an error while typing",
  );
  await page.locator("[name='pattern_gauge_stitches']").fill("");

  // The optional field goes first so the only complete input set is the final one and
  // the debounce cannot fire on a half-filled form, however slow the machine is.
  markStage(`filling ${accountState} gauge inputs`);
  const fillOrder = [
    "pattern_row_count",
    ...Object.keys(expectedInput).filter(
      (field) => field !== "pattern_row_count",
    ),
  ];
  let gaugeResponse: Response;
  try {
    [gaugeResponse] = await Promise.all([
      page.waitForResponse(
        (response) => isPostTo(response, "/api/v2/tools/gauge"),
        { timeout: 10_000 },
      ),
      (async () => {
        for (const field of fillOrder) {
          await page
            .locator(`[name='${field}']`)
            .fill(String(expectedInput[field]));
        }
      })(),
    ]);
  } catch (error) {
    if (error instanceof errors.TimeoutError) {
      await maskFixtureIdentity(page);
      await prepareScreenshot(page);
      const diagnosticPath = join(
        screenshots,
        `gauge-${accountState}-failure.png`,
      );
      await page.screenshot({ path: diagnosticPath, caret: "initial" });
      throw new AcceptanceFailure(
        "the gauge form did not calculate on its own after its inputs settled " +
          `(route=${new URL(page.url()).pathname}; ` +
          `requests=${gaugeRequests.length}; ` +
          `responses=${JSON.stringify(gaugeResponseStatuses)}; ` +
          `page error locations=${JSON.stringify(gaugePageErrorLocations)}; ` +
          `screenshot=${diagnosticPath})`,
      );
    }
    throw new AcceptanceFailure(
      "automatic gauge calculation raised " +
        `${errorName(error)} before a response ` +
        `(requests=${gaugeRequests.length}; responses=${JSON.stringify(gaugeResponseStatuses)})`,
    );
  }

  markStage(`checking ${accountState} gauge response envelope`);
  check(
    gaugeResponse.status() === 200,
    "the gauge API did not return a successful calculation",
  );
  let requestInput: unknown;
  let envelope: unknown;
  try {
    requestInput = JSON.parse(gaugeResponse.request().postData() ?? "");
    envelope = await gaugeResponse.json();
  } catch {
    throw new AcceptanceFailure("the gauge API returned an unreadable result");
  }
  check(
    sameJson(requestInput, expectedInput),
    "the browser sent non-numeric or changed gauge inputs",
  );
  const data = isRecord(envelope) ? envelope.data : undefined;
  if (
    !isRecord(data) ||
    typeof data.adjusted_stitches !== "string" ||
    typeof data.adjusted_rows !== "string"
  ) {
    throw new AcceptanceFailure(
      "the gauge API returned a result with unexpected types",
    );
  }
  await page.locator(".results").waitFor({ state: "visible" });
  check(
    sameJson(await page.locator(".result-grid strong").allInnerTexts(), [
      data.adjusted_stitches,
      data.adjusted_rows,
    ]),
    "the gauge page changed the exact server-calculated result strings",
  );
  await page.waitForTimeout(700);
  check(
    (gaugeRequests.length as number) === 1,
    `one settled gauge input set sent ${gaugeRequests.length} requests, not one`,
  );
  check(
    (await page.locator(".results.stale").count()) === 0 &&
      (await page.locator(".calc-status.visible").count()) === 0,
    "the gauge result stayed dimmed after the calculation finished",
  );

  markStage(`checking ${accountState} stale gauge request cancellation`);
  const stitchInput = page.locator("[name='user_gauge_stitches']");
  await page.evaluate(() => {
    const originalFetch = window.fetch.bind(window);
    const hold: GaugeHold = {
      armed: true,
      responseReady: false,
      aborted: false,
      originalFetch,
    };
    (window as GaugeHoldWindow).__gaugeResponseHold = hold;
    window.fetch = (input: RequestInfo | URL, init: RequestInit = {}) => {
      const requestUrl = new URL(
        typeof input === "string"
          ? input
          : input instanceof URL
            ? input.href
            : input.url,
        window.location.href,
      );
      const shouldHold =
        hold.armed &&
        requestUrl.pathname === "/api/v2/tools/gauge" &&
        (init.method || "GET").toUpperCase() === "POST";
      if (!shouldHold) return originalFetch(input, init);
      hold.armed = false;
      return originalFetch(input, init).then(
        (response) =>
          new Promise<globalThis.Response>((resolve, reject) => {
            hold.responseReady = true;
            const signal = init.signal;
            if (!signal) {
              hold.missingSignal = true;
              resolve(response);
              return;
            }
            if (signal.aborted) {
              hold.aborted = true;
              reject(new DOMException("Aborted", "AbortError"));
              return;
            }
            signal.addEventListener(
              "abort",
              () => {
                hold.aborted = true;
                reject(new DOMException("Aborted", "AbortError"));
              },
              { once: true },
            );
            hold.release = () => resolve(response);
          }),
      );
    };
  });

  const raceStartInput = { ...expectedInput, user_gauge_stitches: 23 };
  let currentResponse: Response;
  try {
    const [delayedRequest] = await Promise.all([
      page.waitForRequest(isGaugeRequest, { timeout: 5_000 }),
      stitchInput.fill("23"),
    ]);
    let requestPayload: unknown;
    try {
      requestPayload = JSON.parse(delayedRequest.postData() ?? "");
    } catch {
      throw new AcceptanceFailure(
        "the delayed gauge request body was unreadable",
      );
    }
    check(
      sameJson(requestPayload, raceStartInput),
      "the delayed gauge request did not use the current input values",
    );
    await page.waitForFunction(
      () =>
        (window as GaugeHoldWindow).__gaugeResponseHold?.responseReady === true,
      undefined,
      { timeout: 5_000 },
    );
    check(
      (await page.locator(".results.stale").count()) === 1 &&
        (await page.locator(".calc-status.visible").count()) === 1,
      "the previous gauge result was not kept dimmed with a status while " +
        "recalculating",
    );
    [currentResponse] = await Promise.all([
      page.waitForResponse(
        (response) => isPostTo(response, "/api/v2/tools/gauge"),
        { timeout: 10_000 },
      ),
      (async () => {
        await stitchInput.fill("24");
        await page.waitForFunction(
          () =>
            (window as GaugeHoldWindow).__gaugeResponseHold?.aborted === true,
          undefined,
          { timeout: 5_000 },
        );
      })(),
    ]);
    check(
      !(await page.evaluate(
        () => (window as GaugeHoldWindow).__gaugeResponseHold?.missingSignal,
      )),
      "editing the gauge did not abort its in-flight API request",
    );
  } finally {
    await page.evaluate(() => {
      const hold = (window as GaugeHoldWindow).__gaugeResponseHold;
      if (hold) window.fetch = hold.originalFetch;
    });
  }

  check(
    currentResponse.status() === 200,
    "the gauge could not be recalculated after an edit cancelled a request",
  );
  let currentRequestInput: unknown;
  let currentEnvelope: unknown;
  try {
    currentRequestInput = JSON.parse(
      currentResponse.request().postData() ?? "",
    );
    currentEnvelope = await currentResponse.json();
  } catch {
    throw new AcceptanceFailure(
      "the recalculated gauge response was unreadable",
    );
  }
  check(
    (await stitchInput.inputValue()) === "24" &&
      sameJson(currentRequestInput, {
        ...raceStartInput,
        user_gauge_stitches: 24,
      }),
    "the recalculated gauge request did not preserve the edited values",
  );
  const currentData = isRecord(currentEnvelope)
    ? currentEnvelope.data
    : undefined;
  if (
    !isRecord(currentData) ||
    typeof currentData.adjusted_stitches !== "string" ||
    typeof currentData.adjusted_rows !== "string"
  ) {
    throw new AcceptanceFailure(
      "the recalculated gauge result had unexpected types",
    );
  }
  await page.locator(".results").waitFor({ state: "visible" });
  await page.waitForFunction(
    () => !document.querySelector(".results.stale"),
    undefined,
    { timeout: 5_000 },
  );
  check(
    sameJson(await page.locator(".result-grid strong").allInnerTexts(), [
      currentData.adjusted_stitches,
      currentData.adjusted_rows,
    ]),
    "the recalculated result did not match the exact server response",
  );
  await page.waitForTimeout(700);
  check(
    (gaugeRequests.length as number) === 3,
    `the cancellation scenario sent ${gaugeRequests.length} requests, not 3`,
  );

  markStage(`checking ${accountState} gauge result clears on invalid input`);
  await stitchInput.fill("0");
  await page.locator(".field-error").first().waitFor({ state: "visible" });
  check(
    (await page.locator(".results").count()) === 0,
    "an invalid gauge value left a stale result on screen",
  );
  await page.waitForTimeout(700);
  check(
    (gaugeRequests.length as number) === 3,
    "an invalid gauge value still triggered a request",
  );

  markStage(`checking ${accountState} gauge swatch size change`);
  // With the stitches still invalid no request can fire until they are restored, so
  // the size and the rows are set first and the stitches last: one request in total.
  await page.locator(".size-button").nth(1).click();
  await page.locator("#user-swatch-size-width").fill("5");
  await page.locator("#user-swatch-size-height").fill("5");
  await page.getByRole("button", { name: "Done", exact: true }).click();
  await page.locator("#user-swatch-size-width").waitFor({ state: "hidden" });
  await page.locator("[name='user_gauge_rows']").fill("12");
  await page.waitForTimeout(700);
  check(
    (gaugeRequests.length as number) === 3,
    "changing the swatch size or rows of an invalid gauge sent a request",
  );
  const [sizedResponse] = await Promise.all([
    page.waitForResponse(
      (response) => isPostTo(response, "/api/v2/tools/gauge"),
      { timeout: 10_000 },
    ),
    stitchInput.fill("9"),
  ]);
  check(
    sizedResponse.status() === 200,
    "the gauge API rejected a measured swatch size",
  );
  check(
    sameJson(JSON.parse(sizedResponse.request().postData() ?? ""), {
      ...expectedInput,
      user_gauge_stitches: 9,
      user_gauge_rows: 12,
      user_swatch_width_cm: 5,
      user_swatch_height_cm: 5,
    }),
    "the browser did not send the measured user swatch size",
  );
  // 9 x 12 over 5 x 5 cm is 18 x 24 per 10 cm: 80 * 18 / 20 and 100 * 24 / 28.
  const sizedData: unknown = (
    (await sizedResponse.json()) as ApiEnvelope<unknown>
  ).data;
  check(
    sameJson(sizedData, { adjusted_stitches: "72", adjusted_rows: "86" }),
    `a 5 x 5 cm swatch did not match the per-10 cm result (${sortedJson(sizedData)})`,
  );
  await page.waitForFunction(
    () => !document.querySelector(".results.stale"),
    undefined,
    { timeout: 5_000 },
  );
  check(
    sameJson(await page.locator(".result-grid strong").allInnerTexts(), [
      "72",
      "86",
    ]),
    "the page did not show the swatch-size-adjusted result",
  );
  await page.waitForTimeout(700);
  check(
    (gaugeRequests.length as number) === 4,
    `the swatch size scenario sent ${gaugeRequests.length} requests, not 4`,
  );
  page.off("request", observeGaugeRequest);
  page.off("response", observeGaugeResponse);
  page.off("pageerror", observeGaugePageError);
}

export async function checkMobileLanguageAndScreenshots(
  page: Page,
  origin: string,
  screenshots: string,
  markStage: MarkStage,
): Promise<void> {
  const awaitFonts = (): Promise<void> =>
    page.evaluate(async () => {
      await document.fonts.ready;
    });
  await page.setViewportSize({ width: 390, height: 844 });
  await maskFixtureIdentity(page);
  await prepareScreenshot(page);
  await page.screenshot({
    path: join(screenshots, "projects-mobile-light.png"),
    caret: "initial",
  });
  await page.emulateMedia({ colorScheme: "dark" });
  await page.reload({ waitUntil: "networkidle" });
  await page
    .locator(".project-card .resource-card__link")
    .first()
    .waitFor({ state: "visible" });
  await awaitFonts();
  await page.screenshot({
    path: join(screenshots, "projects-mobile-dark.png"),
    caret: "initial",
  });
  const projectContrast: unknown = await page.evaluate(() => {
    const card = document.querySelector(".project-card");
    const title = card?.querySelector("h2");
    const link = card?.querySelector(".resource-card__link");
    if (!card || !title || !link) return { ratio: 0 };
    const titleColor = getComputedStyle(title).color;
    const linkColor = getComputedStyle(link).color;
    const cardColor = getComputedStyle(card).color;
    const libraryColor = getComputedStyle(
      card.closest(".library") as Element,
    ).color;
    const rootColor = getComputedStyle(document.documentElement).color;
    const color = titleColor.match(/[\d.]+/g)?.map(Number);
    const surface = getComputedStyle(card)
      .backgroundColor.match(/[\d.]+/g)
      ?.map(Number);
    if (!color || !surface || color.length < 3 || surface.length < 3) {
      return {
        ratio: 0,
        titleColor,
        linkColor,
        cardColor,
        libraryColor,
        rootColor,
      };
    }
    const luminance = ([r, g, b]: number[]) =>
      [r, g, b]
        .map((value) => value / 255)
        .map((value) =>
          value <= 0.04045
            ? value / 12.92
            : Math.pow((value + 0.055) / 1.055, 2.4),
        )
        .reduce((sum, value, index) => {
          const weights = [0.2126, 0.7152, 0.0722];
          return sum + value * weights[index];
        }, 0);
    const values = [luminance(color), luminance(surface)].sort((a, b) => b - a);
    return {
      ratio: (values[0] + 0.05) / (values[1] + 0.05),
      titleColor,
      linkColor,
      cardColor,
      libraryColor,
      rootColor,
      surfaceColor: getComputedStyle(card).backgroundColor,
    };
  });
  const contrastRecord = isRecord(projectContrast) ? projectContrast : null;
  const contrastRatio = contrastRecord?.ratio;
  console.log(
    "Dark project title contrast: " +
      sortedJson(
        contrastRecord
          ? Object.fromEntries(
              [
                "ratio",
                "titleColor",
                "linkColor",
                "cardColor",
                "surfaceColor",
              ].map((key) => [key, contrastRecord[key] ?? null]),
            )
          : { ratio: null },
      ),
  );
  check(
    isNumber(contrastRatio) && contrastRatio >= 4.5,
    `project card title contrast is below 4.5:1 (${sortedJson(projectContrast)})`,
  );
  await page.emulateMedia({ colorScheme: "light" });
  await page.reload({ waitUntil: "networkidle" });
  await page
    .locator(".project-card .resource-card__link")
    .first()
    .waitFor({ state: "visible" });

  check(
    (await page.locator(".app-header .locale-switcher").count()) === 0,
    "the mobile header still reserves space for language controls",
  );
  await openAccountSettings(page);
  const accountHeading = page.getByRole("heading", {
    name: "Account",
    exact: true,
  });
  await accountHeading.waitFor({ state: "visible" });
  await accountLanguageGroup(page).waitFor({ state: "visible" });
  check(
    (await page.locator(".app-header .locale-switcher").count()) === 0 &&
      (await accountLanguageGroup(page).count()) === 1,
    "language controls did not move into Account",
  );
  await assertAccountLanguageControl(page);
  const languageTargets = await visibleHeights(
    page,
    accountLanguageGroup(page).getByRole("button"),
  );
  check(
    languageTargets.length === 2 && Math.min(...languageTargets) >= 44,
    "a mobile account language target is below 44px",
  );
  await assertNoHorizontalOverflow(page, 390);
  await maskFixtureIdentity(page);
  await prepareScreenshot(page);
  await page.screenshot({
    path: join(screenshots, "account-mobile-light.png"),
    caret: "initial",
  });
  await page.emulateMedia({ colorScheme: "dark" });
  await page.reload({ waitUntil: "networkidle" });
  await accountHeading.waitFor({ state: "visible" });
  await awaitFonts();
  await page.screenshot({
    path: join(screenshots, "account-mobile-dark.png"),
    caret: "initial",
  });
  await page.emulateMedia({ colorScheme: "light" });
  await page.reload({ waitUntil: "networkidle" });
  await accountHeading.waitFor({ state: "visible" });

  await page.evaluate(() => {
    if (document.activeElement instanceof HTMLElement) {
      document.activeElement.blur();
    }
  });
  let reachedLanguageByKeyboard = false;
  for (let attempt = 0; attempt < 18; attempt += 1) {
    await page.keyboard.press("Tab");
    const focusedText = await page.evaluate(
      () => document.activeElement?.textContent?.trim() || "",
    );
    if (focusedText === "English" || focusedText === "Deutsch") {
      reachedLanguageByKeyboard = true;
      break;
    }
  }
  check(
    reachedLanguageByKeyboard,
    "mobile language controls are not reachable in the keyboard tab order",
  );
  await accountLanguageGroup(page)
    .getByRole("button", { name: "Deutsch", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    () => document.documentElement.lang === "de",
    undefined,
    { timeout: 5_000 },
  );
  check(
    await page.getByRole("heading", { name: "Konto", exact: true }).isVisible(),
    "German locale selection did not update the account interface",
  );
  await page.reload({ waitUntil: "networkidle" });
  check(
    (await page.locator("html").getAttribute("lang")) === "de",
    "the account language choice did not persist after reload",
  );
  for (const colorScheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme });
    for (const width of [320, 390, 768, 1280]) {
      markStage(
        `checking account layout and locale at ${width}px ` +
          `in ${colorScheme} mode`,
      );
      await page.setViewportSize({ width, height: 844 });
      await assertNoHorizontalOverflow(page, width);
      const languageGroup = accountLanguageGroup(page);
      await languageGroup.waitFor({ state: "visible" });
      await assertAccountLanguageControl(page);
      const controlHeights = await visibleHeights(
        page,
        languageGroup.getByRole("button"),
      );
      check(
        controlHeights.length === 2 && Math.min(...controlHeights) >= 44,
        `locale controls are not 44px targets at ${width}px`,
      );
      for (const [localeIndex, localeCode] of [
        [1, "de"],
        [0, "en"],
      ] as const) {
        await languageGroup.getByRole("button").nth(localeIndex).click();
        await page.waitForFunction(
          (locale: string) => document.documentElement.lang === locale,
          localeCode,
          { timeout: 5_000 },
        );
        await assertNoHorizontalOverflow(page, width);
      }
    }
  }
  await page.emulateMedia({ colorScheme: "light" });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  await page
    .getByRole("heading", { name: "Heirloom Baby Blanket", exact: true })
    .waitFor({ state: "visible" });
}

/** Capture the viewport from the top without a focused skip-link overlay. */
export async function prepareScreenshot(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.scrollTo(0, 0);
    if (document.activeElement instanceof HTMLElement) {
      document.activeElement.blur();
    }
  });
}
