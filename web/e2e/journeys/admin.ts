// Administrator users and audit journey against a disposable Rust server.
// Port of ci/stricknani/web_admin_browser.py.
import { closeBrowser } from "../lib/common";
import { randomBytes } from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";

import {
  errors,
  expect,
  type Locator,
  type Page,
  type Request,
  type Response,
  type Route,
} from "@playwright/test";

import {
  AcceptanceFailure,
  check,
  fillMarkdownField,
  playwrightErrorCategory,
  tinyPngFixture,
  waitTwoAnimationFrames,
} from "../lib/common";
import {
  FixtureError,
  OWNER_FILE,
  loadFixtureEnvironment,
  maskGithubSecret,
  outsideRepository,
  runCmd,
  serverEnvironment,
} from "../lib/fixture";
import {
  launchBrowser,
  stageMarker,
  type E2eConfig,
  type LiveFixture,
} from "../lib/harness";
import { openAccountSettings } from "../lib/ui-helpers";

type Json = Record<string, unknown>;
type Mark = (stage: string) => void;

const ACCOUNT_HEADING_NAME = /^(?:Account|Konto)$/;
const SIGN_OUT_BUTTON_NAME = /^(?:Sign out|Abmelden)$/;
const UPLOAD_PICTURE_BUTTON_NAME = /^(?:Upload picture|Bild hochladen)$/;

const OVERFLOW_ROUTES = new Set([
  "users",
  "audit",
  "resource",
  "diff",
  "project-reader",
  "project-library",
  "yarn-library",
]);

const OVERFLOW_ALLOWED_CLASSES = [
  "app-shell",
  "audit-filters",
  "change-values",
  "diff-page",
  "audit-action-badge",
  "event-row",
  "page-button",
  "page-controls",
  "page-indicator",
  "pagination",
  "resource-content",
  "resource-card",
  "resource-card__copy",
  "resource-card__image",
  "resource-card__link",
  "resource-page",
  "resource-section",
  "user-actions",
  "user-identity",
  "user-row",
];

function asRecord(value: unknown): Json | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Json)
    : null;
}

function urlPath(value: string): string {
  try {
    return new URL(value, "http://placeholder.invalid").pathname;
  } catch {
    return "";
  }
}

function urlQuery(value: string): string {
  try {
    return new URL(value, "http://placeholder.invalid").search.slice(1);
  } catch {
    return "";
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

async function isTimeout<T>(action: () => Promise<T>): Promise<T | null> {
  try {
    return await action();
  } catch (error) {
    if (error instanceof errors.TimeoutError) return null;
    throw error;
  }
}

/** Opens the overflow menu of an Admin, Users row (all actions but Disable). */
async function openUserMenu(row: Locator): Promise<void> {
  await row.locator(".action-overflow__trigger").click();
  await row
    .page()
    .getByRole("menuitem", { name: "Reset password" })
    .waitFor({ state: "visible" });
}

async function svgShapeSignature(icon: Locator): Promise<string> {
  const signature: unknown = await icon.evaluate((svg) => {
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
    return [
      ...svg.querySelectorAll("path, circle, rect, line, polyline, polygon"),
    ]
      .map((shape) => {
        const drawing = attributes
          .filter((name) => shape.hasAttribute(name))
          .map((name) => `${name}=${shape.getAttribute(name)}`)
          .join(";");
        return `${shape.tagName.toLowerCase()}:${drawing}`;
      })
      .sort()
      .join("|");
  });
  return typeof signature === "string" ? signature : "";
}

export function isAdminAvatarResponse(
  response: Response,
  origin: string,
): boolean {
  let parsed: URL;
  let expectedOrigin: URL;
  try {
    parsed = new URL(response.url());
    expectedOrigin = new URL(origin);
  } catch {
    return false;
  }
  const parts = parsed.pathname.split("/");
  return (
    response.request().resourceType() === "image" &&
    parsed.protocol === expectedOrigin.protocol &&
    parsed.host === expectedOrigin.host &&
    parts.length === 8 &&
    parts.slice(1, 5).join("/") === "api/v2/admin/users" &&
    /^[0-9]+$/.test(parts[5] ?? "") &&
    parts[6] === "avatar" &&
    (parts[7] ?? "") !== ""
  );
}

async function guardLoopback(page: Page, origin: string): Promise<void> {
  await page.route("**/*", async (route: Route) => {
    const parsed = new URL(route.request().url());
    if (`${parsed.protocol}//${parsed.host}` !== origin) {
      await route.abort("blockedbyclient");
      return;
    }
    await route.continue();
  });
}

async function signIn(
  page: Page,
  origin: string,
  email: string,
  password: string,
): Promise<void> {
  await page.goto(`${origin}/app/admin/users`, { waitUntil: "networkidle" });
  await page.locator("#email").waitFor({ state: "visible" });
  await page.locator("#email").fill(email);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { name: "Projects" }).waitFor({
    state: "visible",
  });
  await page.goto(`${origin}/app/admin/users`, { waitUntil: "networkidle" });
}

async function createUser(
  page: Page,
  opts: { email: string; password: string; admin: boolean },
): Promise<void> {
  await page.getByRole("button", { name: "Create user" }).click();
  const dialog = page.getByRole("dialog", { name: "Create a user account" });
  await dialog.waitFor({ state: "visible" });
  await dialog.getByLabel("Email address").fill(opts.email);
  await dialog.getByLabel("Temporary password").fill(opts.password);
  const checkbox = dialog.getByLabel("Administrator access");
  if (opts.admin) {
    await checkbox.check();
  } else {
    await checkbox.uncheck();
  }
  await dialog
    .getByRole("button", { name: "Create user", exact: true })
    .click();
  await dialog.waitFor({ state: "hidden" });
  await page
    .locator(".user-row")
    .filter({ hasText: opts.email })
    .waitFor({ state: "visible" });
}

async function openAccount(page: Page): Promise<void> {
  await openAccountSettings(page);
  await page
    .getByRole("heading", { name: ACCOUNT_HEADING_NAME })
    .waitFor({ state: "visible" });
}

async function signOut(page: Page): Promise<void> {
  await openAccount(page);
  await page.getByRole("button", { name: SIGN_OUT_BUTTON_NAME }).click();
  await page.waitForURL("**/app/login");
  await page.locator("#email").waitFor({ state: "visible" });
}

async function signInAccount(
  page: Page,
  origin: string,
  email: string,
  password: string,
): Promise<void> {
  await page.goto(`${origin}/app/login`, { waitUntil: "networkidle" });
  await page.locator("#email").fill(email);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { name: "Projects" }).waitFor({
    state: "visible",
  });
}

async function createAdminAuditProject(
  page: Page,
  origin: string,
  mark: Mark,
): Promise<void> {
  mark("open Projects for admin audit activity");
  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  await page
    .getByRole("heading", { name: "Projects", exact: true })
    .waitFor({ state: "visible" });
  await page.getByRole("link", { name: "New project", exact: true }).click();
  await page
    .getByRole("heading", { name: "New project", exact: true })
    .waitFor({ state: "visible" });
  mark("fill admin audit project");
  let projectOptionalDetails = page.locator("details.optional-details");
  check(
    (await projectOptionalDetails.count()) === 1,
    "project details disclosure is missing from the create form",
  );
  if ((await projectOptionalDetails.getAttribute("open")) === null) {
    await projectOptionalDetails.locator("summary").click();
  }
  check(
    (await projectOptionalDetails.count()) === 1 &&
      (await projectOptionalDetails.getAttribute("open")) !== null,
    "More project details could not be expanded for the audit fixture",
  );
  await page
    .getByLabel("Project name", { exact: true })
    .fill("Admin audit fixture");
  await page
    .getByLabel("Category", { exact: true })
    .selectOption({ label: "New category…" });
  const newCategory = page.getByLabel("New category", { exact: true });
  await newCategory.fill("Blankets");
  await newCategory.press("Enter");
  await page
    .getByLabel("Needles", { exact: true })
    .fill("4 mm circular needles");
  await fillMarkdownField(
    page.getByRole("textbox", { name: "Stitch sample", exact: true }),
    "18 stitches over 10 cm",
  );
  await fillMarkdownField(
    page.getByRole("textbox", { name: "Pattern", exact: true }),
    "Synthetic audit fixture pattern",
  );
  await fillMarkdownField(
    page.getByRole("textbox", { name: "Notes", exact: true }),
    "Synthetic audit fixture notes",
  );
  await fillMarkdownField(
    page.getByRole("textbox", { name: "Other materials", exact: true }),
    "Synthetic fixture markers",
  );
  await page.getByRole("button", { name: "Add step", exact: true }).click();
  await page
    .getByLabel("Step title", { exact: true })
    .first()
    .fill("Fixture step");
  await fillMarkdownField(
    page.getByLabel("Step instructions", { exact: true }).first(),
    "Complete the synthetic audit fixture step.",
  );
  const createResponsePromise = page.waitForResponse(
    (response) =>
      urlPath(response.url()) === "/api/v2/projects" &&
      response.request().method() === "POST",
  );
  mark("submit admin audit project");
  await page
    .getByRole("button", { name: "Create project", exact: true })
    .click();
  const createResponse = await createResponsePromise;
  check(
    createResponse.status() === 201,
    "synthetic administrator audit project creation failed",
  );
  await page.waitForURL(/.*\/app\/projects\/[0-9]+$/);
  const projectIdPath = urlPath(page.url()).replace(/^\/app\/projects\//, "");
  check(
    /^[0-9]+$/.test(projectIdPath),
    "created administrator project did not open its detail route",
  );
  await page
    .getByRole("heading", { name: "Admin audit fixture", exact: true })
    .waitFor({ state: "visible" });
  mark("open admin audit project editor from detail");
  await page.getByRole("link", { name: "Edit project", exact: true }).click();
  await page.waitForURL(`**/app/projects/${projectIdPath}/edit`);
  await page
    .getByRole("heading", { name: "Edit project", exact: true })
    .waitFor({ state: "visible" });
  projectOptionalDetails = page.locator("details.optional-details");
  await projectOptionalDetails.waitFor({ state: "visible" });
  check(
    (await projectOptionalDetails.count()) === 1 &&
      (await projectOptionalDetails.getAttribute("open")) !== null,
    "More project details should be expanded after project creation",
  );
  mark("update admin audit project");
  const updateResponsePromise = page.waitForResponse(
    (response) =>
      urlPath(response.url()).startsWith("/api/v2/projects/") &&
      response.request().method() === "PUT",
  );
  await page
    .getByLabel("Needles", { exact: true })
    .fill("5 mm circular needles");
  await page.getByRole("button", { name: "Save changes", exact: true }).click();
  const updateResponse = await updateResponsePromise;
  check(
    updateResponse.status() === 200,
    "synthetic administrator audit project update failed",
  );
}

async function browserCsrfToken(page: Page, origin: string): Promise<string> {
  const response = await page.context().request.get(`${origin}/api/v2/session`);
  if (response.status() !== 200) {
    throw new AcceptanceFailure(
      "browser fixture session could not be verified",
    );
  }
  const payload = asRecord(await response.json());
  const sessionData = asRecord(payload?.data);
  if (sessionData === null) {
    throw new AcceptanceFailure("browser fixture session response was invalid");
  }
  const token = sessionData.csrf_token;
  if (typeof token !== "string" || token === "") {
    throw new AcceptanceFailure(
      "browser fixture session did not provide CSRF state",
    );
  }
  return token;
}

interface CrossOwnerResource {
  projectId: number;
  stepId: number;
  name: string;
  yarnId: number;
  yarnName: string;
}

const SAFE_YARN_PHOTO_ERROR_CODES = new Set([
  "not_found",
  "media_unavailable",
  "payload_too_large",
  "invalid_image",
  "invalid_multipart",
  "internal_error",
  "unauthorized",
  "csrf_failed",
]);

async function createCrossOwnerResource(
  page: Page,
  origin: string,
  mark: Mark,
): Promise<CrossOwnerResource> {
  const name = "Cross-owner read-only fixture";
  mark("create synthetic owner project through authenticated v2 API");
  const csrfToken = await browserCsrfToken(page, origin);
  const stepPayload: Json = {
    title: "Nested media step",
    description: "A synthetic step image and attachment belong to this owner.",
    step_number: 1,
  };
  const headers = {
    accept: "application/json",
    origin,
    "sec-fetch-site": "same-origin",
    "x-csrf-token": csrfToken,
  };
  const request = page.context().request;
  const createPayload: Json = {
    name,
    category: "Blankets",
    needles: null,
    stitch_sample: null,
    description: "Synthetic read-only resource",
    notes: null,
    other_materials: null,
    tags: [],
    link: null,
    is_ai_enhanced: false,
    yarn_ids: [],
    steps: [stepPayload],
  };
  const createResponse = await request.post(`${origin}/api/v2/projects`, {
    headers,
    data: createPayload,
  });
  if (createResponse.status() !== 201) {
    throw new AcceptanceFailure(
      `cross-owner fixture project creation failed (HTTP ${createResponse.status()})`,
    );
  }
  const project = asRecord(asRecord(await createResponse.json())?.data);
  if (project === null) {
    throw new AcceptanceFailure("cross-owner fixture response was invalid");
  }
  const projectId = project.id;
  const steps = project.steps;
  const firstStep = Array.isArray(steps) ? asRecord(steps[0]) : null;
  if (firstStep === null) {
    throw new AcceptanceFailure(
      "cross-owner fixture did not return its project step",
    );
  }
  const stepId = firstStep.id;
  if (
    typeof projectId !== "number" ||
    !Number.isInteger(projectId) ||
    projectId <= 0 ||
    typeof stepId !== "number" ||
    !Number.isInteger(stepId) ||
    stepId <= 0
  ) {
    throw new AcceptanceFailure(
      "cross-owner fixture did not return valid resource IDs",
    );
  }

  const yarnName = "Cross-owner fixture yarn";
  const yarnResponse = await request.post(`${origin}/api/v2/yarns`, {
    headers,
    data: {
      name: yarnName,
      description: "Synthetic yarn for read-only card acceptance.",
      brand: "Fixture mill",
      colorway: "Blue sample",
      weight_category: "DK",
      weight_grams: 100,
      length_meters: 220,
    },
  });
  if (yarnResponse.status() !== 201) {
    throw new AcceptanceFailure("cross-owner fixture yarn creation failed");
  }
  const yarnId = asRecord(asRecord(await yarnResponse.json())?.data)?.id;
  if (typeof yarnId !== "number" || !Number.isInteger(yarnId) || yarnId <= 0) {
    throw new AcceptanceFailure(
      "cross-owner fixture yarn response was invalid",
    );
  }
  const yarnPhotoResponse = await request.post(
    `${origin}/api/v2/yarns/${yarnId}/photos`,
    {
      headers,
      multipart: {
        file: {
          name: "synthetic-yarn-photo.png",
          mimeType: "image/png",
          buffer: tinyPngFixture(),
        },
      },
    },
  );
  if (yarnPhotoResponse.status() !== 201) {
    let safeErrorCode = "unavailable";
    let responseBody: unknown = null;
    try {
      responseBody = await yarnPhotoResponse.json();
    } catch {
      responseBody = null;
    }
    const code = asRecord(asRecord(responseBody)?.error)?.code;
    if (typeof code === "string" && SAFE_YARN_PHOTO_ERROR_CODES.has(code)) {
      safeErrorCode = code;
    }
    throw new AcceptanceFailure(
      "synthetic yarn photo upload failed " +
        `(HTTP ${yarnPhotoResponse.status()}, code ${safeErrorCode})`,
    );
  }

  const titleImageResponse = await request.post(
    `${origin}/api/v2/projects/${projectId}/images/title`,
    {
      headers,
      multipart: {
        file: {
          name: "synthetic-project-cover.png",
          mimeType: "image/png",
          buffer: tinyPngFixture(),
        },
        alt_text: "Synthetic project cover",
      },
    },
  );
  if (titleImageResponse.status() !== 201) {
    throw new AcceptanceFailure("synthetic project cover upload failed");
  }

  mark(
    "upload synthetic step image and attachment through authenticated v2 API",
  );
  const imageResponse = await request.post(
    `${origin}/api/v2/projects/${projectId}/steps/${stepId}/images`,
    {
      headers,
      multipart: {
        file: {
          name: "synthetic-step-image.png",
          mimeType: "image/png",
          buffer: tinyPngFixture(),
        },
        alt_text: "Nested fixture step image",
      },
    },
  );
  if (imageResponse.status() !== 201) {
    throw new AcceptanceFailure("synthetic step image upload failed");
  }
  const attachmentResponse = await request.post(
    `${origin}/api/v2/projects/${projectId}/attachments`,
    {
      headers,
      multipart: {
        file: {
          name: "synthetic-pattern.txt",
          mimeType: "text/plain",
          buffer: Buffer.from("Synthetic fixture attachment."),
        },
      },
    },
  );
  if (attachmentResponse.status() !== 201) {
    throw new AcceptanceFailure("synthetic attachment upload failed");
  }

  const detailResponse = await request.get(
    `${origin}/api/v2/projects/${projectId}`,
  );
  if (detailResponse.status() !== 200) {
    throw new AcceptanceFailure(
      "cross-owner fixture project could not be reloaded",
    );
  }
  const detailData = asRecord(asRecord(await detailResponse.json())?.data);
  if (detailData === null) {
    throw new AcceptanceFailure(
      "cross-owner fixture project response was invalid",
    );
  }
  const updatedAt = detailData.updated_at;
  if (typeof updatedAt !== "string") {
    throw new AcceptanceFailure(
      "cross-owner fixture project timestamp was invalid",
    );
  }

  const updatedName = "Cross-owner fixture <b>literal</b>";
  const updatePayload: Json = {
    ...createPayload,
    name: updatedName,
    expected_updated_at: updatedAt,
    yarn_ids: [yarnId],
    steps: [{ ...stepPayload, id: stepId }],
  };
  const updateResponse = await request.put(
    `${origin}/api/v2/projects/${projectId}`,
    { headers, data: updatePayload },
  );
  if (updateResponse.status() !== 200) {
    throw new AcceptanceFailure("cross-owner fixture project update failed");
  }
  return { projectId, stepId, name: updatedName, yarnId, yarnName };
}

async function deleteCrossOwnerResource(
  page: Page,
  origin: string,
  projectId: number,
): Promise<void> {
  const csrfToken = await browserCsrfToken(page, origin);
  const response = await page
    .context()
    .request.delete(`${origin}/api/v2/projects/${projectId}`, {
      headers: {
        accept: "application/json",
        origin,
        "sec-fetch-site": "same-origin",
        "x-csrf-token": csrfToken,
      },
    });
  if (response.status() !== 204) {
    throw new AcceptanceFailure("owner could not delete the synthetic project");
  }
  // Deleting moves the project to the trash; delete it permanently so that the audit event is
  // the "Deleted permanently" one of a resource that is gone.
  const purged = await page
    .context()
    .request.delete(`${origin}/api/v2/trash/projects/${projectId}`, {
      headers: {
        accept: "application/json",
        origin,
        "sec-fetch-site": "same-origin",
        "x-csrf-token": csrfToken,
      },
    });
  if (purged.status() !== 204) {
    throw new AcceptanceFailure(
      "owner could not delete the synthetic project permanently",
    );
  }
}

async function assertNoScopeFilter(page: Page, origin: string): Promise<void> {
  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  await page
    .getByRole("heading", { name: "Projects", exact: true })
    .waitFor({ state: "visible" });
  check(
    (await page.getByTestId("project-filter-mine").count()) === 1 &&
      (await page.getByTestId("project-filter-shared").count()) === 1,
    "a regular user was not offered the Mine / Shared with me owner filter",
  );
  check(
    (await page.getByTestId("project-filter-owner").count()) === 0,
    "a regular user was offered an owner they cannot see items of",
  );
}

/**
 * An administrator sees another user's project in the library with an owner badge, can narrow to
 * Mine and back, opens it with the "someone else's project" banner, edits it, and the change
 * persists across a reload.
 */
async function assertAdminEditsForeignProject(
  page: Page,
  origin: string,
  project: CrossOwnerResource,
  mark: Mark,
): Promise<void> {
  const bannerText =
    /^.+'s project Your changes are saved to their project and recorded in the audit log\.$/;
  mark("administrator library shows Mine / All and the owner badge");
  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  await page
    .getByRole("heading", { name: "Projects", exact: true })
    .waitFor({ state: "visible" });
  const all = page.getByTestId("project-filter-all");
  const mine = page.getByTestId("project-filter-mine");
  await expect(all).toHaveAttribute("aria-pressed", "true");
  const card = page.locator(".library-card").filter({ hasText: project.name });
  await card.waitFor({ state: "visible" });
  check(
    (await card.locator('[aria-label^="Owned by "]').count()) === 1,
    "the foreign project card has no owner badge",
  );
  mark("administrator narrows the library to Mine and back");
  await mine.click();
  await expect(card).toHaveCount(0);
  await expect(mine).toHaveAttribute("aria-pressed", "true");
  await all.click();
  await card.waitFor({ state: "visible" });
  await expect(all).toHaveAttribute("aria-pressed", "true");

  mark("administrator opens the foreign project and sees the banner");
  await page.goto(`${origin}/app/projects/${project.projectId}`, {
    waitUntil: "networkidle",
  });
  const banner = page.getByTestId("foreign-owner-banner");
  await banner.waitFor({ state: "visible" });
  check(
    bannerText.test(
      ((await banner.locator(".foreign-banner__copy").textContent()) ?? "")
        .replace(/\s+/g, " ")
        .trim(),
    ),
    "the foreign project banner text is wrong",
  );

  mark("administrator edits the foreign project");
  await page.goto(`${origin}/app/projects/${project.projectId}/edit`, {
    waitUntil: "networkidle",
  });
  await page.getByTestId("foreign-owner-banner").waitFor({ state: "visible" });
  const details = page.locator("details.optional-details");
  await details.waitFor({ state: "visible" });
  if ((await details.getAttribute("open")) === null) {
    await details.locator("summary").click();
  }
  const marker = "9 mm admin-edited";
  const updated = page.waitForResponse(
    (response) =>
      urlPath(response.url()) === `/api/v2/projects/${project.projectId}` &&
      response.request().method() === "PUT",
  );
  await page.getByLabel("Needles", { exact: true }).fill(marker);
  await page.getByRole("button", { name: "Save changes", exact: true }).click();
  check(
    (await updated).status() === 200,
    "the administrator could not save the foreign project",
  );

  mark("administrator edit persists after a reload");
  await page.goto(`${origin}/app/projects/${project.projectId}/edit`, {
    waitUntil: "networkidle",
  });
  const reloadedDetails = page.locator("details.optional-details");
  await reloadedDetails.waitFor({ state: "visible" });
  if ((await reloadedDetails.getAttribute("open")) === null) {
    await reloadedDetails.locator("summary").click();
  }
  await expect(page.getByLabel("Needles", { exact: true })).toHaveValue(marker);
}

async function saveScreenshot(
  page: Page,
  directory: string | undefined,
  name: string,
): Promise<void> {
  if (!directory) return;
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  const destination = path.join(directory, name);
  await page.screenshot({ path: destination, fullPage: true });
  fs.chmodSync(destination, 0o600);
}

async function findExistingNoSnapshotEvent(
  page: Page,
  origin: string,
): Promise<number> {
  const request = page.context().request;
  for (let pageNumber = 1; pageNumber < 5; pageNumber += 1) {
    const response = await request.get(
      `${origin}/api/v2/admin/audit?page=${pageNumber}`,
    );
    if (response.status() !== 200) {
      throw new AcceptanceFailure("audit fixture page could not be verified");
    }
    const data = asRecord(await response.json())?.data;
    if (!Array.isArray(data)) {
      throw new AcceptanceFailure("audit fixture page response was invalid");
    }
    for (const item of data) {
      const event = asRecord(item);
      if (event === null || event.has_changes !== false) continue;
      const eventId = event.id;
      if (typeof eventId !== "number" || !Number.isInteger(eventId)) continue;
      if (eventId <= 0) continue;
      const detailResponse = await request.get(
        `${origin}/api/v2/admin/audit/${eventId}`,
      );
      if (detailResponse.status() !== 200) continue;
      const detailData = asRecord(asRecord(await detailResponse.json())?.data);
      if (
        detailData !== null &&
        detailData.changes === null &&
        detailData.unavailable_reason === "not_recorded"
      ) {
        return eventId;
      }
    }
  }
  throw new AcceptanceFailure(
    "no existing no-snapshot audit event was available",
  );
}

async function imageDecodes(
  image: Locator,
  withTimeout: boolean,
): Promise<boolean> {
  const decoded: unknown = await image.evaluate((node, limited) => {
    const element = node as HTMLImageElement;
    const decode = element
      .decode()
      .then(() => element.complete && element.naturalWidth > 0);
    if (!limited) return decode.catch(() => false);
    return Promise.race([
      decode,
      new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 3000)),
    ]).catch(() => false);
  }, withTimeout);
  return Boolean(decoded);
}

async function assertDecodedCardImage(
  card: Locator,
  message: string,
): Promise<string> {
  const image = card.locator(
    ".library-card__media img, .resource-card__image img",
  );
  await image.scrollIntoViewIfNeeded();
  await image.waitFor({ state: "visible" });
  const source = (await image.getAttribute("src")) ?? "";
  check(await imageDecodes(image, true), message);
  return source;
}

async function assertOwnerResourceCards(
  page: Page,
  origin: string,
  resource: CrossOwnerResource,
  screenshotDir: string | undefined,
): Promise<void> {
  const { projectId, name: projectName, yarnId, yarnName } = resource;
  await page.goto(`${origin}/app/projects/${projectId}`, {
    waitUntil: "networkidle",
  });
  await page
    .getByRole("heading", { name: projectName, exact: true })
    .waitFor({ state: "visible" });
  const materials = page.locator("details.materials-card");
  check(
    (await materials.getAttribute("open")) === null,
    "project materials should start collapsed",
  );
  await materials.locator("summary").focus();
  await page.keyboard.press("Enter");
  await expect(materials).toHaveAttribute("open", "");
  const embeddedCard = page
    .locator(".materials-card .resource-card")
    .filter({ hasText: yarnName });
  await embeddedCard.waitFor({ state: "visible" });
  const embeddedLink = embeddedCard.locator("a.resource-card__link");
  const embeddedHref = (await embeddedLink.getAttribute("href")) ?? "";
  check(
    urlPath(embeddedHref).endsWith(`/app/yarns/${yarnId}`),
    "project yarn card did not use the owner's yarn route",
  );
  const embeddedImage = await assertDecodedCardImage(
    embeddedCard,
    "project yarn card preview did not decode",
  );
  check(
    urlPath(embeddedImage).startsWith(`/media/thumbnails/yarns/${yarnId}/`),
    "project yarn card did not use the owner-scoped yarn photo route",
  );
  for (const [width, suffix] of [
    [390, "390"],
    [1280, "desktop"],
  ] as const) {
    await page.setViewportSize({ width, height: 900 });
    await assertNoHorizontalOverflow(page, "project-reader");
    await saveScreenshot(page, screenshotDir, `project-reader-${suffix}.png`);
  }
  await embeddedLink.click();
  await page
    .getByRole("heading", { name: yarnName, exact: true })
    .waitFor({ state: "visible" });

  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  const projectCard = page
    .locator(".project-card")
    .filter({ hasText: projectName });
  await projectCard.waitFor({ state: "visible" });
  check(
    urlPath(
      (await projectCard
        .locator("a.resource-card__link")
        .getAttribute("href")) ?? "",
    ).endsWith(`/app/projects/${projectId}`),
    "project library card did not use the owner's project route",
  );
  const projectImage = await assertDecodedCardImage(
    projectCard,
    "project library card preview did not decode",
  );
  check(
    urlPath(projectImage).startsWith(
      `/media/thumbnails/projects/${projectId}/`,
    ),
    "project library card did not use the owner-scoped image route",
  );
  for (const [width, suffix] of [
    [390, "390"],
    [1280, "desktop"],
  ] as const) {
    await page.setViewportSize({ width, height: 900 });
    await assertNoHorizontalOverflow(page, "project-library");
    await saveScreenshot(page, screenshotDir, `project-library-${suffix}.png`);
  }
  await projectCard.locator("a.resource-card__link").click();
  await page.waitForURL(`**/app/projects/${projectId}`);
  await page
    .getByRole("heading", { name: projectName, exact: true })
    .waitFor({ state: "visible" });

  await page.goto(`${origin}/app/yarns`, { waitUntil: "networkidle" });
  const yarnCard = page.locator(".yarn-card").filter({ hasText: yarnName });
  await yarnCard.waitFor({ state: "visible" });
  check(
    urlPath(
      (await yarnCard.locator("a.resource-card__link").getAttribute("href")) ??
        "",
    ).endsWith(`/app/yarns/${yarnId}`),
    "yarn library card did not use the owner's yarn route",
  );
  const yarnImage = await assertDecodedCardImage(
    yarnCard,
    "yarn library card preview did not decode",
  );
  check(
    urlPath(yarnImage).startsWith(`/media/thumbnails/yarns/${yarnId}/`),
    "yarn library card did not use the owner-scoped photo route",
  );
  for (const [width, suffix] of [
    [390, "390"],
    [1280, "desktop"],
  ] as const) {
    await page.setViewportSize({ width, height: 900 });
    await assertNoHorizontalOverflow(page, "yarn-library");
    await saveScreenshot(page, screenshotDir, `yarn-library-${suffix}.png`);
  }

  await yarnCard.locator("a.resource-card__link").click();
  await page.waitForURL(`**/app/yarns/${yarnId}`);
  await page
    .getByRole("heading", { name: yarnName, exact: true })
    .waitFor({ state: "visible" });
}

async function uploadAccountAvatar(page: Page): Promise<void> {
  await page.locator('input[type="file"][accept="image/*"]').setInputFiles({
    name: "synthetic-admin-avatar.png",
    mimeType: "image/png",
    buffer: tinyPngFixture(),
  });
  const uploadResponsePromise = page.waitForResponse(
    (response) =>
      urlPath(response.url()) === "/api/v2/account/avatar" &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: UPLOAD_PICTURE_BUTTON_NAME }).click();
  const uploadResponse = await uploadResponsePromise;
  check(
    uploadResponse.status() === 200,
    "synthetic profile avatar upload failed",
  );
  const portrait = page.locator(".avatar__portrait img");
  await portrait.waitFor({ state: "visible" });
  check(
    await imageDecodes(portrait, false),
    "synthetic profile avatar did not render in Account",
  );
}

async function assertUserAvatar(
  avatar: Locator,
  opts: { initial: string; imageExpected: boolean },
): Promise<void> {
  await avatar.waitFor({ state: "visible" });
  const image = avatar.locator("img[alt='']");
  if (opts.imageExpected) {
    await image.waitFor({ state: "visible" });
    check(
      (await imageDecodes(image, false)) &&
        (await avatar.locator("span").count()) === 0,
      "user avatar image failed to render with the initial fallback active",
    );
    return;
  }
  const fallback = avatar.locator("span");
  await fallback.waitFor({ state: "visible" });
  check(
    (await image.count()) === 0 &&
      (await fallback.innerText()).trim() === opts.initial,
    "missing or broken avatar did not show the user's initial",
  );
}

/** Wait for the combined Admin page with the given tab selected. */
async function waitAdminTab(page: Page, tab: string): Promise<void> {
  await page
    .getByRole("heading", { name: "Admin", exact: true })
    .waitFor({ state: "visible" });
  await page
    .locator(".admin-tabs a[aria-current='page']")
    .filter({ hasText: new RegExp(`^${escapeRegExp(tab)}$`) })
    .waitFor({ state: "visible" });
}

async function assertMoreNavigation(
  page: Page,
  opts: { isAdmin: boolean },
): Promise<void> {
  const origin = new URL(page.url()).origin;
  await page.goto(`${origin}/app/create`);
  await page
    .getByRole("heading", { name: "What would you like to add?", exact: true })
    .waitFor({ state: "visible" });
  const createImport = page.locator(".create-options a[href$='/imports']");
  check(
    (await createImport.count()) === 0,
    "Create still contains an Imports link",
  );
  check(
    (await page.locator(".secondary-nav a[href$='/imports']").count()) === 0,
    "Imports is duplicated in the sidebar More section",
  );

  await page.goto(`${origin}/app/more`);
  await page
    .getByRole("heading", { name: "More", exact: true })
    .waitFor({ state: "visible" });
  const cards = page.locator(".more-links");
  const expectedCards: Array<[string, string]> = [
    ["Categories", "/categories"],
    ["Gauge calculator", "/gauge"],
    ["Trash", "/trash"],
  ];
  if (opts.isAdmin) expectedCards.push(["Admin", "/admin/users"]);
  expectedCards.push(["About", "/about"]);
  // The Android app lives on About only, as does the Privacy Policy. About is
  // always the last tile.
  check(
    // Plus the Create tile (Create is first and has no sidebar entry of its
    // own in this group: it is in the primary group).
    (await cards.locator("a").count()) === expectedCards.length + 1 &&
      (
        (await cards
          .locator("a")
          .last()
          .getAttribute("href", { timeout: 1000 })) ?? ""
      ).endsWith("/about") &&
      (
        (await page
          .locator(".secondary-nav a")
          .last()
          .getAttribute("href", { timeout: 1000 })) ?? ""
      ).endsWith("/about") &&
      (await page.locator(".nav-caption").innerText()).trim() === "More" &&
      (await page.locator(".secondary-nav a[href$='/admin/audit']").count()) ===
        0 &&
      (await cards.locator("a[href$='/admin/audit']").count()) === 0 &&
      (await cards.locator("a[href$='/privacy']").count()) === 0 &&
      (await cards.locator("a[href$='/about']").count()) === 1 &&
      (await cards.locator("a[target='_blank']").count()) === 0 &&
      (await cards.locator("a[href$='/imports']").count()) === 0 &&
      (await page.locator(".secondary-nav a[href$='/imports']").count()) === 0,
    "More or the sidebar More section contains unexpected links",
  );
  check(
    (await cards
      .getByRole("link", { name: "Account", exact: true })
      .count()) === 0 &&
      (await page
        .locator(".secondary-nav a[href$='/account']:not(.rail-account)")
        .count()) === 0,
    "More or the sidebar More section contains a duplicate Account link",
  );
  check(
    (await page
      .locator(".desktop-rail a.rail-account[href$='/app/account']")
      .count()) === 1,
    "Account is unavailable from the profile links",
  );
  for (const [label, href] of expectedCards) {
    const card = cards.locator(`a[href$='${href}']`);
    const title = card.locator(".ui-navigation-tile__title");
    const description = card.locator(".ui-navigation-tile__description");
    const moreIcon = card.locator(
      ".ui-navigation-tile__icon svg[aria-hidden='true'][focusable='false']",
    );
    const sidebarIcon = page.locator(
      `.secondary-nav a[href$='${href}'] .nav-icon svg`,
    );
    const moreSignature =
      (await moreIcon.count()) === 1 ? await svgShapeSignature(moreIcon) : "";
    const sidebarSignature =
      (await sidebarIcon.count()) === 1
        ? await svgShapeSignature(sidebarIcon)
        : "";
    const cardOk = (await card.count()) === 1;
    const titleOk =
      (await title.count()) === 1 && (await title.innerText()).trim() === label;
    const descriptionOk =
      (await description.count()) === 1 &&
      Boolean((await description.innerText()).trim());
    const hrefOk = ((await card.getAttribute("href")) ?? "").endsWith(href);
    const moreIconOk = (await moreIcon.count()) === 1;
    const sidebarIconOk = (await sidebarIcon.count()) === 1;
    const pathParity =
      Boolean(moreSignature) && moreSignature === sidebarSignature;
    check(
      cardOk &&
        titleOk &&
        descriptionOk &&
        hrefOk &&
        moreIconOk &&
        sidebarIconOk &&
        pathParity,
      "a More card failed its link/description/icon parity check " +
        `(label=${label}, card=${cardOk}, title=${titleOk}, ` +
        `description=${descriptionOk}, ` +
        `href=${hrefOk}, more_icon=${moreIconOk}, ` +
        `sidebar_icon=${sidebarIconOk}, path_parity=${pathParity})`,
    );
  }
}

async function assertAuditVisualMeasurements(page: Page): Promise<void> {
  const viewport = page.viewportSize();
  if (viewport === null) {
    throw new AcceptanceFailure("audit viewport dimensions were unavailable");
  }
  const originalWidth = viewport.width;
  try {
    for (const width of [320, 390, 1280]) {
      await page.setViewportSize({ width, height: viewport.height });
      await waitTwoAnimationFrames(page);
      const measurements = await page.evaluate(() => {
        const rows = [...document.querySelectorAll(".event-row")];
        const badges = [...document.querySelectorAll(".audit-action-badge")];
        const linear = (value: string): number[] | null => {
          const numbers = value
            .match(/[\d.]+/g)
            ?.slice(0, 3)
            .map(Number);
          if (!numbers || numbers.length !== 3) return null;
          const scale = Math.max(...numbers) <= 1 ? 255 : 1;
          const channels = numbers.map((number) => (number * scale) / 255);
          return channels.map((channel) =>
            channel <= 0.04045
              ? channel / 12.92
              : ((channel + 0.055) / 1.055) ** 2.4,
          );
        };
        const luminance = (color: string): number | null => {
          const channels = linear(color);
          return (
            channels &&
            channels[0]! * 0.2126 +
              channels[1]! * 0.7152 +
              channels[2]! * 0.0722
          );
        };
        const contrast = (foreground: string, background: string): number => {
          const first = luminance(foreground);
          const second = luminance(background);
          if (first === null || second === null) return 0;
          return (
            (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
          );
        };
        const rowMetrics = rows.map((row) => {
          const rowRect = row.getBoundingClientRect();
          // The first row has no top border; measure from the content edge.
          const rowTop =
            rowRect.top + parseFloat(getComputedStyle(row).borderTopWidth);
          const slot = row.querySelector(".event-diff-slot");
          const date = row.querySelector(".event-meta time");
          const slotRect = slot?.getBoundingClientRect();
          const dateRect = date?.getBoundingClientRect();
          const diff = row.querySelector(".show-diff");
          const diffRect = diff?.getBoundingClientRect();
          return {
            height: rowRect.height,
            slotOffset: slotRect ? slotRect.top - rowTop : null,
            slotLeft: slotRect ? slotRect.left - rowRect.left : null,
            slotHeight: slotRect?.height ?? 0,
            dateOffset: dateRect ? dateRect.top - rowTop : null,
            dateLeft: dateRect ? dateRect.left - rowRect.left : null,
            hasDiff: Boolean(diff),
            diffTargetOk:
              !diff ||
              Boolean(
                diffRect && diffRect.width >= 44 && diffRect.height >= 44,
              ),
          };
        });
        const colorsByTone = new Map<string, string>();
        const badgeMetrics = badges.map((badge) => {
          const style = getComputedStyle(badge);
          const tone =
            [...badge.classList].find((name) =>
              name.startsWith("action-badge--"),
            ) ?? "unknown";
          if (tone !== "action-badge--unknown")
            colorsByTone.set(tone, style.color);
          return {
            tone,
            contrast: contrast(style.color, style.backgroundColor),
            unclipped: badge.scrollWidth <= badge.clientWidth + 1,
          };
        });
        const spread = (values: Array<number | null>): boolean =>
          values.every((value) => value !== null && Number.isFinite(value)) &&
          Math.max(...(values as number[])) -
            Math.min(...(values as number[])) <=
            1.01;
        const heights = rowMetrics.map((row) => row.height);
        const slotOffsets = rowMetrics.map((row) => row.slotOffset);
        const dateOffsets = rowMetrics.map((row) => row.dateOffset);
        const slotLefts = rowMetrics.map((row) => row.slotLeft);
        const dateLefts = rowMetrics.map((row) => row.dateLeft);
        return {
          values: rowMetrics.map((row) => [
            Math.round(row.height * 10) / 10,
            Math.round((row.slotOffset ?? -1) * 10) / 10,
            Math.round((row.dateOffset ?? -1) * 10) / 10,
            row.hasDiff ? 1 : 0,
          ]),
          rowCount: rows.length,
          badgeCount: badges.length,
          equalRowHeights: heights.length > 0 && spread(heights),
          stableSlotOffset: slotOffsets.length > 0 && spread(slotOffsets),
          stableSlotLeft: slotLefts.length > 0 && spread(slotLefts),
          stableDateOffset: dateOffsets.length > 0 && spread(dateOffsets),
          stableDateLeft: dateLefts.length > 0 && spread(dateLefts),
          hasDiffCount: rowMetrics.filter((row) => row.hasDiff).length,
          withoutDiffCount: rowMetrics.filter((row) => !row.hasDiff).length,
          allDiffTargetsAtLeast44: rowMetrics.every((row) => row.diffTargetOk),
          slotsAtLeast44: rowMetrics.every((row) => row.slotHeight >= 44),
          textContrastAtLeast45:
            badgeMetrics.length > 0 &&
            badgeMetrics.every((badge) => badge.contrast >= 4.5),
          badgeTextUnclipped: badgeMetrics.every((badge) => badge.unclipped),
          distinctKnownToneColors:
            colorsByTone.size >= 2 &&
            new Set(colorsByTone.values()).size === colorsByTone.size,
        };
      });
      if (asRecord(measurements) === null) {
        throw new AcceptanceFailure(
          "audit visual measurements were unavailable",
        );
      }
      const failing = Object.fromEntries(
        Object.entries(measurements).filter(
          ([key, value]) => key !== "values" && value !== true,
        ),
      );
      check(
        measurements.rowCount >= 2 &&
          measurements.badgeCount >= 2 &&
          measurements.equalRowHeights === true &&
          measurements.stableSlotOffset === true &&
          measurements.stableSlotLeft === true &&
          measurements.stableDateOffset === true &&
          measurements.stableDateLeft === true &&
          measurements.allDiffTargetsAtLeast44 === true &&
          measurements.slotsAtLeast44 === true &&
          measurements.textContrastAtLeast45 === true &&
          measurements.badgeTextUnclipped === true &&
          measurements.distinctKnownToneColors === true,
        `audit action colors, contrast, or row alignment failed at ${width}px ` +
          `(${JSON.stringify(failing)}; [height, slot, date, diff] ` +
          `${JSON.stringify(measurements.values)})`,
      );
    }
  } finally {
    await page.setViewportSize({
      width: originalWidth,
      height: viewport.height,
    });
  }
}

export async function assertMobileHeaderAlignment(
  page: Page,
  expectedParentHref?: string,
): Promise<void> {
  const expectedParent = expectedParentHref !== undefined;
  await page.waitForFunction(
    (opacity) => {
      const chevron = document.querySelector(".app-header .brand-chevron");
      return Boolean(chevron && getComputedStyle(chevron).opacity === opacity);
    },
    expectedParent ? "1" : "0",
    { timeout: 5_000 },
  );
  const alignment = await page.evaluate(() => {
    const header = document.querySelector(".app-header");
    const brand = document.querySelector(".app-header .brand-lockup");
    const mark = brand?.querySelector(".brand-mark-wrap");
    const chevron = brand?.querySelector(".brand-chevron");
    const icon = chevron?.querySelector("svg");
    if (!header || !brand || !mark || !chevron) return null;
    const rect = (element: Element) => {
      const box = element.getBoundingClientRect();
      return {
        left: box.left,
        right: box.right,
        width: box.width,
        height: box.height,
      };
    };
    return {
      header: rect(header),
      brand: rect(brand),
      mark: rect(mark),
      chevron: rect(chevron),
      icon: icon ? rect(icon) : null,
      parentHref: brand.classList.contains("parent-link")
        ? brand.getAttribute("href")
        : null,
      parentLabel: brand.getAttribute("aria-label"),
      chevronOpacity: getComputedStyle(chevron).opacity,
    };
  });
  check(
    alignment !== null &&
      alignment.brand.width >= 44 &&
      alignment.brand.height >= 44 &&
      (alignment.parentHref !== null) === expectedParent &&
      (!expectedParent ||
        (alignment.parentHref === expectedParentHref &&
          alignment.parentLabel === "Back" &&
          alignment.chevronOpacity === "1" &&
          alignment.icon !== null)) &&
      (expectedParent ||
        (alignment.parentLabel === "Stricknani" &&
          alignment.chevronOpacity === "0")) &&
      alignment.mark.left >= alignment.header.left - 1 &&
      alignment.mark.right <= alignment.header.right + 1,
    "mobile parent-link brand is misaligned",
  );
}

export async function assertNoHorizontalOverflow(
  page: Page,
  route: string,
): Promise<void> {
  if (!OVERFLOW_ROUTES.has(route)) {
    throw new AcceptanceFailure("invalid admin responsive route");
  }
  const measurements = await page.evaluate((allowedClasses: string[]) => {
    const root = document.documentElement;
    const viewport = window.innerWidth;
    const offenders = [...document.querySelectorAll("body *")]
      .flatMap((element) => {
        const rect = element.getBoundingClientRect();
        const left = Math.round(rect.left * 10) / 10;
        const right = Math.round(rect.right * 10) / 10;
        const scrollWidth = element.scrollWidth;
        const clientWidth = element.clientWidth;
        if (
          right <= viewport + 1 &&
          left >= -1 &&
          scrollWidth <= clientWidth + 1
        ) {
          return [];
        }
        const classes = [...element.classList]
          .filter((name) => allowedClasses.includes(name))
          .slice(0, 4);
        return [
          {
            tag: element.tagName.toLowerCase(),
            classes,
            left,
            right,
            scrollWidth,
            clientWidth,
            rightOverflow: Math.max(
              0,
              Math.round((right - viewport) * 10) / 10,
            ),
          },
        ];
      })
      .sort(
        (left, right) =>
          Math.max(right.rightOverflow, right.scrollWidth - right.clientWidth) -
          Math.max(left.rightOverflow, left.scrollWidth - left.clientWidth),
      );
    return {
      viewport,
      documentClientWidth: root.clientWidth,
      documentScrollWidth: root.scrollWidth,
      offenders: offenders.slice(0, 8),
    };
  }, OVERFLOW_ALLOWED_CLASSES);
  if (measurements.documentScrollWidth <= measurements.viewport) return;
  const offenderDetails = measurements.offenders.map((item) => {
    const classes = item.classes.join(".");
    const classSuffix = classes ? `.${classes}` : "";
    return (
      `${item.tag}${classSuffix} left=${item.left} right=${item.right} ` +
      `scroll=${item.scrollWidth} client=${item.clientWidth}`
    );
  });
  const details = offenderDetails.join("; ") || "no element geometry matched";
  throw new AcceptanceFailure(
    `admin ${route} page overflows at ${measurements.viewport}px ` +
      `(document scroll=${measurements.documentScrollWidth}, ` +
      `client=${measurements.documentClientWidth}); ${details}`,
  );
}

export interface AdminCredentials {
  email: string;
  password: string;
}

export async function createFixtureAdmin(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<AdminCredentials> {
  let env: Record<string, string>;
  try {
    const fixture = loadFixtureEnvironment(f.fixtureFile);
    const statePath = outsideRepository(f.stateFile, "fixture state file");
    const state = asRecord(JSON.parse(fs.readFileSync(statePath, "utf-8")));
    if (state === null) throw new TypeError("invalid state");
    const runtimeValue = state.runtime;
    const owner = state.owner;
    if (typeof runtimeValue !== "string" || typeof owner !== "string") {
      throw new TypeError("invalid state");
    }
    const runtime = outsideRepository(runtimeValue, "fixture runtime");
    const runtimeParent = outsideRepository(
      path.join(f.privateDir, "runtime"),
      "fixture runtime parent",
    );
    if (path.dirname(runtime) !== path.resolve(runtimeParent)) {
      throw new AcceptanceFailure(
        "fixture runtime is outside the private runner directory",
      );
    }
    const marker = path.join(runtime, OWNER_FILE);
    if (fs.readFileSync(marker, "ascii") !== owner) {
      throw new AcceptanceFailure("fixture runtime owner marker did not match");
    }
    const markerStat = fs.statSync(marker);
    if ((markerStat.mode & 0o077) !== 0 || !markerStat.isFile()) {
      throw new AcceptanceFailure(
        "fixture runtime owner marker is not private",
      );
    }
    const port = new URL(fixture.STRICKNANI_TEST_URL).port;
    if (port === "") throw new AcceptanceFailure("fixture URL has no port");
    env = serverEnvironment(runtime, Number(port), path.resolve(cfg.webRoot));
  } catch (error) {
    if (error instanceof AcceptanceFailure) throw error;
    throw new AcceptanceFailure("fixture runtime could not be validated");
  }

  const email = `admin-${randomBytes(6).toString("hex")}@example.invalid`;
  const password = `A synthetic admin password ${randomBytes(18).toString("base64url")}`;
  maskGithubSecret(email);
  maskGithubSecret(password);
  runCmd([path.resolve(cfg.binary), "create-admin", email], {
    env,
    inputBytes: Buffer.from(`${password}\n`),
  });
  return { email, password };
}

async function runAcceptance(
  f: LiveFixture,
  admin: AdminCredentials,
  mark: Mark,
  cfg: E2eConfig,
): Promise<void> {
  const origin = f.origin;
  const adminEmail = admin.email;
  const adminPassword = admin.password;
  const screenshotDir: string | undefined = f.screenshotDir;

  const memberEmail = `member-${randomBytes(5).toString("hex")}@example.invalid`;
  const memberPassword = `A temporary password ${randomBytes(14).toString("base64url")}`;
  const secondAdminEmail = `admin-${randomBytes(5).toString("hex")}@example.invalid`;
  const secondAdminPassword = `A temporary password ${randomBytes(14).toString("base64url")}`;
  const resetPassword = `A reset password ${randomBytes(14).toString("base64url")}`;

  // Response bodies are read asynchronously; collect the work so that a
  // check can wait for every recorded response before it looks at the result.
  const pending: Promise<void>[] = [];
  const settle = async (): Promise<void> => {
    await Promise.all(pending.splice(0));
  };

  mark("launch Chromium");
  const browser = await launchBrowser(cfg);
  try {
    const context = await browser.newContext({
      locale: "en-US",
      viewport: { width: 1280, height: 900 },
      serviceWorkers: "block",
    });
    const page = await context.newPage();
    await guardLoopback(page, origin);
    mark("sign in administrator");
    await signIn(page, origin, adminEmail, adminPassword);
    await waitAdminTab(page, "Users");

    mark("check logical parent link preserves dirty-navigation confirmation");
    await page.setViewportSize({ width: 390, height: 900 });
    await page.locator('a[href="/app/projects"]:visible').first().click();
    await page.waitForURL("**/app/projects");
    await page.locator(".mobile-navigation a.more-tab").click();
    await page.waitForURL("**/app/more");
    await page.locator('.more-links a[href="/app/create"]').click();
    await page.waitForURL("**/app/create");
    await page.locator('.create-options a[href="/app/projects/new"]').click();
    await page.waitForURL("**/app/projects/new");
    await page
      .getByRole("heading", { name: "New project", exact: true })
      .waitFor({ state: "visible" });
    const projectNameField = page.getByLabel("Project name", { exact: true });
    await projectNameField.fill("Unsaved header back fixture");
    const parentProbe = await page.evaluate(() => {
      const link = document.querySelector(
        ".app-header .brand-lockup.parent-link",
      );
      const rect = link?.getBoundingClientRect();
      const icon = link
        ?.querySelector(".brand-chevron svg")
        ?.getBoundingClientRect();
      const logo = link
        ?.querySelector(".brand-mark-wrap")
        ?.getBoundingClientRect();
      return {
        route: location.pathname === "/app/projects/new",
        parentHref: link?.getAttribute("href") ?? null,
        parentLabel: link?.getAttribute("aria-label") ?? null,
        count: document.querySelectorAll(
          ".app-header .brand-lockup.parent-link",
        ).length,
        visible: Boolean(
          link &&
          rect &&
          rect.width > 0 &&
          rect.height > 0 &&
          getComputedStyle(link).visibility === "visible",
        ),
        targetSize: Boolean(rect && rect.width >= 44 && rect.height >= 44),
        iconInsideTarget: Boolean(
          rect &&
          icon &&
          icon.left >= rect.left &&
          icon.right <= rect.right &&
          icon.top >= rect.top &&
          icon.bottom <= rect.bottom,
        ),
        markX: logo?.left ?? null,
        viewport: Boolean(
          rect &&
          rect.left >= 0 &&
          rect.right <= innerWidth &&
          rect.top >= 0 &&
          rect.bottom <= innerHeight,
        ),
      };
    });
    check(
      parentProbe.route === true &&
        parentProbe.parentHref === "/app/projects" &&
        parentProbe.parentLabel === "Back" &&
        parentProbe.count === 1 &&
        parentProbe.visible === true &&
        parentProbe.targetSize === true &&
        parentProbe.iconInsideTarget === true &&
        typeof parentProbe.markX === "number" &&
        parentProbe.viewport === true,
      "header parent link was not an aligned same-app target",
    );
    await assertMobileHeaderAlignment(page, "/app/projects");

    page.once("dialog", (dialog) => {
      void dialog.dismiss();
    });
    const cancelledBackPromise = page.waitForEvent("dialog", {
      timeout: 5_000,
    });
    await page.getByRole("link", { name: "Back", exact: true }).click();
    const cancelledBack = await cancelledBackPromise;
    check(
      cancelledBack.type() === "confirm",
      "header back did not open the dirty-navigation confirmation",
    );
    await page.waitForFunction(
      () =>
        location.pathname === "/app/projects/new" &&
        (document.querySelector("#project-name") as HTMLInputElement | null)
          ?.value === "Unsaved header back fixture",
      undefined,
      { timeout: 5_000 },
    );
    await page
      .getByRole("heading", { name: "New project", exact: true })
      .waitFor({ state: "visible" });
    check(
      (await projectNameField.inputValue()) === "Unsaved header back fixture",
      "cancelling header back discarded the unsaved project name",
    );
    page.once("dialog", (dialog) => {
      void dialog.accept();
    });
    const acceptedBackPromise = page.waitForEvent("dialog", {
      timeout: 5_000,
    });
    await page.getByRole("link", { name: "Back", exact: true }).click();
    const acceptedBack = await acceptedBackPromise;
    check(
      acceptedBack.type() === "confirm",
      "accepting header back did not show the dirty-navigation confirmation",
    );
    await page.waitForURL("**/app/projects");
    await page
      .getByRole("heading", { name: "Projects", exact: true })
      .waitFor({ state: "visible" });
    await page.mouse.move(200, 500);
    await waitTwoAnimationFrames(page);
    const parentMarkX = parentProbe.markX;
    if (typeof parentMarkX === "number") {
      // The chevron slot eases in; wait for the settled position
      // rather than sampling mid-transition after two frames.
      await isTimeout(() =>
        page.waitForFunction(
          (expected) => {
            const wrap = document.querySelector(".app-header .brand-mark-wrap");
            return (
              wrap !== null &&
              Math.abs(wrap.getBoundingClientRect().left - expected) <= 1
            );
          },
          parentMarkX,
          { timeout: 2_000 },
        ),
      );
    }
    const topLevelMarkX: unknown = await page
      .locator(".app-header .brand-mark-wrap")
      .evaluate((element) => element.getBoundingClientRect().left);
    check(
      typeof topLevelMarkX === "number" &&
        typeof parentMarkX === "number" &&
        Math.abs(topLevelMarkX - parentMarkX) <= 1,
      "brand mark shifted when the chevron changed route state at 390px",
    );
    // The following icon parity check measures the desktop rail SVGs.
    // Restore desktop width after the mobile dirty-navigation check.
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto(`${origin}/app/admin/users`, { waitUntil: "networkidle" });
    await waitAdminTab(page, "Users");

    mark("check admin More cards and profile-only Account");
    await assertMoreNavigation(page, { isAdmin: true });

    mark("upload administrator actor avatar");
    await openAccount(page);
    await uploadAccountAvatar(page);
    await page.goto(`${origin}/app/admin/users`, { waitUntil: "networkidle" });
    await waitAdminTab(page, "Users");

    mark("create member");
    await createUser(page, {
      email: memberEmail,
      password: memberPassword,
      admin: false,
    });
    let memberRow = page.locator(".user-row").filter({ hasText: memberEmail });
    const memberRowText = await memberRow.innerText();
    check(
      memberRowText.includes("Member") && memberRowText.includes("Active"),
      "new account did not have the member role and active status",
    );
    await assertUserAvatar(memberRow.locator(".user-identity .user-avatar"), {
      initial: memberEmail[0]!.toUpperCase(),
      imageExpected: false,
    });

    mark("create administrator");
    await createUser(page, {
      email: secondAdminEmail,
      password: secondAdminPassword,
      admin: true,
    });
    const adminRow = page
      .locator(".user-row")
      .filter({ hasText: secondAdminEmail });
    check(
      (await adminRow.innerText()).includes("Administrator"),
      "admin role was not applied",
    );

    mark("preserve email after duplicate validation");
    const createOpener = page.getByRole("button", { name: "Create user" });
    await createOpener.click();
    const createDialog = page.getByRole("dialog", {
      name: "Create a user account",
    });
    await createDialog.getByLabel("Email address").fill(memberEmail);
    await createDialog.getByLabel("Temporary password").fill(memberPassword);
    await createDialog
      .getByRole("button", { name: "Create user", exact: true })
      .click();
    await createDialog
      .getByText("An account with that email already exists.")
      .waitFor({ state: "visible" });
    check(
      (await createDialog.getByLabel("Email address").inputValue()) ===
        memberEmail &&
        (await createDialog.getByLabel("Temporary password").inputValue()) ===
          memberPassword,
      "a validation error discarded the user form",
    );
    const unloadGuarded = await page.evaluate(() => {
      const event = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    });
    check(unloadGuarded, "an unsaved password did not guard document unload");
    page.once("dialog", (dialog) => {
      void dialog.accept();
    });
    await createDialog.getByRole("button", { name: "Cancel" }).click();
    check(
      await createOpener.evaluate(
        (element) => element === document.activeElement,
      ),
      "focus did not return to the create-user control",
    );

    mark("upload member avatar through account lifecycle");
    await signOut(page);
    await signInAccount(page, origin, memberEmail, memberPassword);
    await openAccount(page);
    await uploadAccountAvatar(page);
    mark("create cross-owner resource with step image and attachment");
    const crossOwner = await createCrossOwnerResource(page, origin, mark);
    const crossOwnerProjectId = crossOwner.projectId;
    const crossOwnerName = crossOwner.name;
    const crossOwnerYarnId = crossOwner.yarnId;
    const crossOwnerYarnName = crossOwner.yarnName;
    mark("verify owner project and yarn cards");
    await assertOwnerResourceCards(page, origin, crossOwner, screenshotDir);
    await signOut(page);
    await signIn(page, origin, adminEmail, adminPassword);
    await waitAdminTab(page, "Users");
    memberRow = page.locator(".user-row").filter({ hasText: memberEmail });
    await assertUserAvatar(memberRow.locator(".user-identity .user-avatar"), {
      initial: memberEmail[0]!.toUpperCase(),
      imageExpected: true,
    });

    mark("disable and reset member");
    memberRow = page.locator(".user-row").filter({ hasText: memberEmail });
    const disableButton = memberRow.locator(".row-actions button").first();
    await disableButton.click();
    mark("open member disable confirmation");
    const activeDialog = page.getByRole("dialog", {
      name: "Disable this account?",
    });
    await assertUserAvatar(
      activeDialog.locator(".target-identity .user-avatar"),
      {
        initial: memberEmail[0]!.toUpperCase(),
        imageExpected: true,
      },
    );
    await activeDialog.getByRole("button", { name: "Disable account" }).click();
    mark("wait for member disabled status");
    await expect(memberRow.locator(".status-pill")).toContainText("Disabled");
    check(
      await disableButton.evaluate(
        (element) => element === document.activeElement,
      ),
      "focus did not return to the changed account action",
    );
    mark("open member password reset");
    await openUserMenu(memberRow);
    await page.getByRole("menuitem", { name: "Reset password" }).click();
    const resetDialog = page.getByRole("dialog", {
      name: "Reset this password?",
    });
    await assertUserAvatar(
      resetDialog.locator(".target-identity .user-avatar"),
      {
        initial: memberEmail[0]!.toUpperCase(),
        imageExpected: true,
      },
    );
    await resetDialog.getByLabel("New temporary password").fill(resetPassword);
    await resetDialog
      .getByRole("button", { name: "Reset password", exact: true })
      .click();
    mark("wait for member password reset completion");
    await resetDialog.waitFor({ state: "hidden" });
    mark("reset keeps the disabled member disabled");
    await expect(memberRow.locator(".status-pill")).toContainText("Disabled");
    mark("wait for member reset confirmation");
    await page
      .getByRole("status")
      .filter({ hasText: "Password reset. The user must sign in again." })
      .waitFor({ state: "visible" });
    mark("re-enable member");
    await memberRow
      .getByRole("button", { name: "Enable", exact: true })
      .click();
    await page
      .getByRole("dialog", { name: "Enable this account?" })
      .getByRole("button", { name: "Enable account" })
      .click();
    await expect(memberRow.locator(".status-pill")).toContainText("Active");

    mark("own account cannot be disabled");
    let ownRow = page.locator(".user-row").filter({ hasText: adminEmail });
    await ownRow.locator(".you-marker").waitFor({ state: "visible" });
    check(
      await ownRow.getByRole("button", { name: "Disable" }).isDisabled(),
      "the signed-in administrator's own row offers Disable",
    );
    await openUserMenu(ownRow);
    check(
      (await page.getByRole("menuitem", { name: "Reset password" }).count()) ===
        1,
      "the own row lost its other actions",
    );
    await page.keyboard.press("Escape");

    mark("audit list and filters");
    await createAdminAuditProject(page, origin, mark);
    const avatarResponseStatuses: number[] = [];
    page.on("response", (response: Response) => {
      if (isAdminAvatarResponse(response, origin)) {
        avatarResponseStatuses.push(response.status());
      }
    });
    const adminImageResponseStatuses: number[] = [];
    page.on("response", (response: Response) => {
      const responsePath = urlPath(response.url());
      if (
        /^\/api\/v2\/admin\/resources\/project\/\d+\/media\/image\/\d+(?:\/thumbnail)?$/.test(
          responsePath,
        )
      ) {
        adminImageResponseStatuses.push(response.status());
      }
    });
    await page.goto(`${origin}/app/admin/audit`, { waitUntil: "networkidle" });
    await waitAdminTab(page, "Audit log");
    await page.waitForFunction(
      () => {
        const chevron = document.querySelector(".app-header .brand-chevron");
        return Boolean(chevron && getComputedStyle(chevron).opacity === "0");
      },
      undefined,
      { timeout: 5_000 },
    );
    const topLevelBrand = page.locator(".app-header .brand-lockup");
    check(
      (await topLevelBrand.count()) === 1 &&
        (await topLevelBrand.isVisible()) &&
        (await topLevelBrand.getAttribute("href")) === "/app/" &&
        (await topLevelBrand.getAttribute("aria-label")) === "Stricknani" &&
        (await page
          .locator(".app-header .brand-lockup.parent-link")
          .count()) === 0 &&
        (await topLevelBrand
          .locator(".brand-chevron")
          .evaluate((element) => getComputedStyle(element).opacity === "0")),
      "top-level Audit brand showed a parent link or visible chevron",
    );
    await page.locator(".event-row").first().waitFor({ state: "visible" });
    mark("check cross-owner audit resource card and protected image");
    const auditResourceRow = page
      .locator(".event-row")
      .filter({ hasText: crossOwnerName })
      .first();
    const auditResourceCard = auditResourceRow.locator(".resource-card");
    await auditResourceCard.waitFor({ state: "visible" });
    const auditResourceHref =
      (await auditResourceCard
        .locator("a.resource-card__link")
        .getAttribute("href")) ?? "";
    check(
      urlPath(auditResourceHref) === `/app/projects/${crossOwnerProjectId}`,
      "audit resource card did not link to the normal project page",
    );
    const auditProjectImage = await assertDecodedCardImage(
      auditResourceCard,
      "cross-owner audit project thumbnail did not decode",
    );
    check(
      new RegExp(
        `^/api/v2/admin/resources/project/${crossOwnerProjectId}/media/image/\\d+/thumbnail$`,
      ).test(urlPath(auditProjectImage)),
      "cross-owner audit project card used a non-admin image route",
    );
    const auditYarnRow = page
      .locator(".event-row")
      .filter({ hasText: crossOwnerYarnName })
      .first();
    const auditYarnCard = auditYarnRow.locator(".resource-card");
    await auditYarnCard.waitFor({ state: "visible" });
    const auditYarnHref =
      (await auditYarnCard
        .locator("a.resource-card__link")
        .getAttribute("href")) ?? "";
    check(
      urlPath(auditYarnHref) === `/app/yarns/${crossOwnerYarnId}`,
      "audit yarn card did not link to the normal yarn page",
    );
    const auditYarnImage = await assertDecodedCardImage(
      auditYarnCard,
      "cross-owner audit yarn thumbnail did not decode",
    );
    check(
      new RegExp(
        `^/api/v2/admin/resources/yarn/${crossOwnerYarnId}/media/photo/\\d+/thumbnail$`,
      ).test(urlPath(auditYarnImage)),
      "cross-owner audit yarn card used a non-admin photo route",
    );
    for (const [width, suffix] of [
      [390, "390"],
      [1280, "desktop"],
    ] as const) {
      await page.setViewportSize({ width, height: 900 });
      await assertNoHorizontalOverflow(page, "audit");
      await saveScreenshot(page, screenshotDir, `admin-audit-${suffix}.png`);
    }
    const brokenPreviewUrl = `${origin}${urlPath(auditProjectImage)}`;
    const abortCardPreview = async (route: Route): Promise<void> => {
      if (route.request().url() === brokenPreviewUrl) {
        await route.abort("failed");
      } else {
        await route.continue();
      }
    };
    const brokenPreviewPattern =
      "**/api/v2/admin/resources/project/*/media/image/*/thumbnail";
    await page.route(brokenPreviewPattern, abortCardPreview);
    await page.reload({ waitUntil: "networkidle" });
    const brokenCard = page
      .locator(".event-row")
      .filter({ hasText: crossOwnerName })
      .first()
      .locator(".resource-card");
    await brokenCard
      .locator(".resource-card__image svg")
      .waitFor({ state: "visible" });
    check(
      (await brokenCard.locator(".resource-card__image img").count()) === 0,
      "failed audit preview remained as a broken image element",
    );
    await page.unroute(brokenPreviewPattern, abortCardPreview);
    await page.reload({ waitUntil: "networkidle" });
    await page
      .locator(".event-row")
      .filter({ hasText: crossOwnerName })
      .first()
      .locator("a.resource-card__link")
      .click();
    await page
      .getByRole("heading", { name: crossOwnerName, exact: true })
      .waitFor({ state: "visible" });
    await page.goto(`${origin}/app/admin/audit`, { waitUntil: "networkidle" });
    mark("read-only cross-owner resource and lazy audit diff");
    const auditDetailRequests: string[] = [];
    const ownerResourceRequests: string[] = [];
    const recordAuditDetailRequest = (request: Request): void => {
      const requestPath = urlPath(request.url());
      if (/^\/api\/v2\/admin\/audit\/\d+$/.test(requestPath)) {
        auditDetailRequests.push(requestPath);
      }
      if (/^\/api\/v2\/(?:projects|yarns)\/\d+$/.test(requestPath)) {
        ownerResourceRequests.push(`${request.method()} ${requestPath}`);
      }
    };
    page.on("request", recordAuditDetailRequest);
    mark("open cross-owner resource route");
    await page.setViewportSize({ width: 390, height: 900 });
    const detailApiStatuses: number[] = [];
    const detailApiErrorCodes: string[] = [];
    let pageErrorCount = 0;
    page.on("response", (response: Response) => {
      const expectedPath = `/api/v2/admin/resources/project/${crossOwnerProjectId}`;
      if (urlPath(response.url()) !== expectedPath) return;
      detailApiStatuses.push(response.status());
      if (response.status() === 200) return;
      pending.push(
        (async () => {
          try {
            // A cancelled request (the app aborts obsolete loads) never
            // delivers a body, so a plain `response.json()` would stay
            // pending until the browser closes and block every later
            // `settle()`. The body is only a diagnostic aid: give it 5 s.
            const body = await Promise.race([
              response.json(),
              new Promise<undefined>((resolve) =>
                setTimeout(() => resolve(undefined), 5_000),
              ),
            ]);
            const code = asRecord(asRecord(body)?.error)?.code;
            if (
              typeof code === "string" &&
              ["admin_required", "not_found", "internal_error"].includes(code)
            ) {
              detailApiErrorCodes.push(code);
            }
          } catch {
            // The body is only a diagnostic aid.
          }
        })(),
      );
    });
    page.on("pageerror", () => {
      pageErrorCount += 1;
    });
    const expectedResourcePath = `/app/admin/resources/project/${crossOwnerProjectId}`;
    const detailPageResponse = await isTimeout(() =>
      page.goto(`${origin}${expectedResourcePath}`, {
        waitUntil: "commit",
        timeout: 10_000,
      }),
    );
    await page
      .locator(".reader.page-container--standard")
      .waitFor({ state: "visible", timeout: 10_000 });
    const resourceHeadingVisible = await page
      .getByRole("heading", { name: crossOwnerName, exact: true })
      .isVisible();
    if (!resourceHeadingVisible) {
      await settle();
      await saveScreenshot(
        page,
        screenshotDir,
        "admin-resource-route-failure.png",
      );
      const pageStatus = detailPageResponse
        ? detailPageResponse.status()
        : "none";
      const apiStatus = detailApiStatuses.at(-1) ?? "none";
      const apiCode = detailApiErrorCodes.at(-1) ?? "none";
      const contentVisible = await page.locator(".reader").isVisible();
      const errorVisible = await page
        .locator(".reader-state [role=alert]")
        .isVisible();
      const loadingVisible = await page
        .locator(".reader-loading[aria-live=polite]")
        .isVisible();
      const shellVisible = await page.locator(".app-shell").isVisible();
      const authFormVisible = await page.locator(".auth-form").isVisible();
      const routeMatches = urlPath(page.url()) === expectedResourcePath;
      const screenshotState = screenshotDir ? "saved" : "disabled";
      throw new AcceptanceFailure(
        "direct admin resource view did not render the resource " +
          `(page_status=${pageStatus}, api_status=${apiStatus}, ` +
          `api_code=${apiCode}, content=${contentVisible}, ` +
          `error=${errorVisible}, loading=${loadingVisible}, ` +
          `app_shell=${shellVisible}, auth_form=${authFormVisible}, ` +
          `route_match=${routeMatches}, page_errors=${pageErrorCount}, ` +
          `screenshot=${screenshotState})`,
      );
    }
    const resourceParent = page.locator(
      ".app-header .brand-lockup.parent-link",
    );
    check(
      (await resourceParent.count()) === 1 &&
        (await resourceParent.getAttribute("href")) === "/app/admin/audit" &&
        (await resourceParent.getAttribute("aria-label")) === "Back",
      "a direct admin resource entry did not point its header parent to Audit",
    );
    for (const width of [390, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      const parentBounds = await resourceParent.boundingBox();
      check(
        parentBounds !== null &&
          parentBounds.width >= 44 &&
          parentBounds.height >= 44,
        "the admin resource parent link target is smaller than 44px",
      );
      if (width === 390) {
        await assertMobileHeaderAlignment(page, "/app/admin/audit");
      }
      check(
        (await page.locator(".reader .back-link").count()) === 0,
        "admin project reader rendered a duplicate body Back link",
      );
    }
    await page.setViewportSize({ width: 390, height: 900 });
    mark("return from direct resource to audit");
    await resourceParent.click();
    mark("confirm explicit resource link to audit");
    await page.waitForURL("**/app/admin/audit");
    mark("reopen cross-owner resource after history fallbacks");
    await page.goto(
      `${origin}/app/admin/resources/project/${crossOwnerProjectId}`,
      { waitUntil: "domcontentloaded" },
    );
    await page
      .getByRole("heading", { name: crossOwnerName, exact: true })
      .waitFor({ state: "visible" });
    mark("check resource view is read-only and does not fetch history");
    check(
      auditDetailRequests.length === 0,
      "resource view eagerly fetched audit history",
    );
    check(
      ownerResourceRequests.length === 0,
      "admin project reader fetched owner-scoped project or yarn data",
    );
    check(
      (await page
        .locator(
          ".reader form, .reader input, .reader textarea, .reader select",
        )
        .count()) === 0,
      "admin project reader exposed editable form fields",
    );
    check(
      (await page
        .locator(".reader-admin-owner")
        .getByText(memberEmail, { exact: true })
        .count()) === 1,
      "read-only resource view did not identify its owner",
    );
    await assertUserAvatar(page.locator(".reader-admin-owner .user-avatar"), {
      initial: memberEmail[0]!.toUpperCase(),
      imageExpected: true,
    });
    const materials = page.locator("details.materials-card");
    await materials.waitFor({ state: "visible" });
    if ((await materials.getAttribute("open")) === null) {
      await materials.locator("summary").click();
    }
    const relatedYarnCard = materials
      .locator(".resource-card")
      .filter({ hasText: crossOwnerYarnName });
    await relatedYarnCard.waitFor({ state: "visible" });
    const relatedYarnHref =
      (await relatedYarnCard
        .locator("a.resource-card__link")
        .getAttribute("href")) ?? "";
    const relatedYarnImage = await assertDecodedCardImage(
      relatedYarnCard,
      "admin related-yarn thumbnail did not decode",
    );
    check(
      urlPath(relatedYarnHref) ===
        `/app/admin/resources/yarn/${crossOwnerYarnId}` &&
        new RegExp(
          `^/api/v2/admin/resources/yarn/${crossOwnerYarnId}/media/photo/\\d+/thumbnail$`,
        ).test(urlPath(relatedYarnImage)),
      "admin related-yarn card did not use scoped resource routes",
    );
    check(
      (await page
        .locator(".reader-heading-actions .favorite-button")
        .count()) === 0 &&
        (await page.locator(".reader-edit-link").count()) === 0 &&
        (await page.locator(".archive-control").count()) === 0 &&
        (await page.locator(".ocr-control").count()) === 0,
      "admin project reader exposed owner-only mutation controls",
    );
    const utilityOverflow = page.locator(
      ".reader-heading-actions details.action-overflow",
    );
    check(
      (await utilityOverflow.count()) === 1 &&
        (await utilityOverflow.getAttribute("open")) === null &&
        (await utilityOverflow.locator(".print-button").count()) === 1 &&
        (await utilityOverflow
          .locator(".text-size-controls button")
          .count()) === 2,
      "admin project reader did not keep print and text-size utilities " +
        "in the collapsed overflow",
    );
    for (const width of [320, 390, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      const reader = page.locator(".reader.page-container--standard");
      const readerBounds = await reader.boundingBox();
      check(
        readerBounds !== null &&
          readerBounds.x >= 0 &&
          readerBounds.x + readerBounds.width <= width + 1,
        `admin project reader exceeded the viewport at ${width}px`,
      );
      await assertNoHorizontalOverflow(page, "project-reader");
    }
    mark("load cross-owner step image");
    const stepMedia = page.locator(".step-photo-card").filter({
      has: page.locator('img[alt="Nested fixture step image"]'),
    });
    const stepImage = stepMedia.locator("img.screen-reference-image");
    const imageCount = await stepImage.count();
    const imageVisible = imageCount === 1 && (await stepImage.isVisible());
    const imageUrlIsAdmin =
      imageCount === 1 &&
      /^\/api\/v2\/admin\/resources\/project\/\d+\/media\/image\/\d+\/thumbnail$/.test(
        urlPath((await stepImage.getAttribute("src")) ?? ""),
      );
    const imageDecoded =
      imageCount === 1 && (await imageDecodes(stepImage, true));
    check(
      imageCount === 1 && imageVisible && imageUrlIsAdmin && imageDecoded,
      "cross-owner step image check failed " +
        `(count=${imageCount}, visible=${imageVisible}, ` +
        `admin_route=${imageUrlIsAdmin}, decoded=${imageDecoded}, ` +
        `statuses=${adminImageResponseStatuses.join(",")})`,
    );
    const step = page.locator(".steps-list > li").filter({ has: stepMedia });
    check(
      (await step.count()) === 1 &&
        (await step.locator(":scope > .step-number").innerText()).trim() ===
          "1",
      "read-only resource view did not identify the image's step",
    );
    mark("download cross-owner attachment");
    // The link's accessible name also carries its type and size.
    const attachmentLink = page.locator(".attachments-card a").filter({
      has: page.locator("strong", { hasText: "synthetic-pattern.txt" }),
    });
    check(
      (await attachmentLink.count()) === 1,
      "admin project reader did not list the synthetic attachment once",
    );
    await attachmentLink.waitFor({ state: "visible" });
    const attachmentUrl = (await attachmentLink.getAttribute("href")) ?? "";
    const attachmentResponse = await page.request.get(
      `${origin}${attachmentUrl}`,
    );
    check(
      attachmentUrl.startsWith("/api/v2/admin/resources/project/") &&
        attachmentResponse.status() === 200 &&
        (attachmentResponse.headers()["content-disposition"] ?? "").includes(
          "attachment;",
        ),
      "cross-owner attachment did not use the admin media download route",
    );
    mark("open audit list for lazy diff");
    await page.goto(`${origin}/app/admin/audit`, { waitUntil: "networkidle" });
    const crossOwnerRow = page
      .locator(".event-row")
      .filter({ hasText: crossOwnerName })
      .first();
    await crossOwnerRow.waitFor({ state: "visible" });
    const showDiff = crossOwnerRow.getByRole("link", { name: "Show diff" });
    await showDiff.waitFor({ state: "visible" });
    mark("open audit diff");
    await showDiff.click();
    await page
      .getByRole("heading", { name: crossOwnerName, exact: true })
      .waitFor({ state: "visible" });
    const nameChange = page.locator(".change-list li").filter({
      has: page.getByRole("heading", { name: "Project name", exact: true }),
    });
    check(
      auditDetailRequests.length > 0 &&
        (await nameChange.count()) === 1 &&
        (await nameChange
          .locator(".change-values .value")
          .last()
          .innerText()) === crossOwnerName &&
        (await page.locator(".change-values img").count()) === 0,
      "lazy audit diff did not render escaped, human-readable changes",
    );
    mark("check audit diff request count and responsive layout");
    check(
      (auditDetailRequests.length as number) === 1,
      "audit diff detail request was not lazy or was fetched more than once",
    );
    for (const [width, suffix] of [
      [390, "390"],
      [1280, "desktop"],
    ] as const) {
      await page.setViewportSize({ width, height: 900 });
      check(
        (await page.locator(".diff-back-link").count()) === 0,
        "audit diff rendered a duplicate body Back link",
      );
      await assertNoHorizontalOverflow(page, "diff");
      await saveScreenshot(
        page,
        screenshotDir,
        `admin-audit-detail-with-diff-${suffix}.png`,
      );
    }
    const noSnapshotEventId = await findExistingNoSnapshotEvent(page, origin);
    mark("open existing audit event without a change snapshot");
    const noSnapshotApi: {
      status?: number;
      reason?: unknown;
      changesMissing?: boolean;
    } = {};
    const recordNoSnapshotDetail = (response: Response): void => {
      if (
        urlPath(response.url()) !== `/api/v2/admin/audit/${noSnapshotEventId}`
      ) {
        return;
      }
      noSnapshotApi.status = response.status();
      if (response.status() !== 200) return;
      pending.push(
        (async () => {
          let payload: unknown;
          try {
            payload = await response.json();
          } catch {
            noSnapshotApi.reason = "invalid_response";
            return;
          }
          const data = asRecord(asRecord(payload)?.data);
          noSnapshotApi.reason =
            data !== null ? data.unavailable_reason : "invalid_response";
          noSnapshotApi.changesMissing = data !== null && data.changes === null;
        })(),
      );
    };
    page.on("response", recordNoSnapshotDetail);
    // Uploads now record their media, so none of the fixture's events lacks
    // details. Serve one without its media to stand in for an old upload.
    const stripMedia = async (route: Route): Promise<void> => {
      const response = await route.fetch();
      const body = asRecord(await response.json());
      const data = asRecord(body?.data);
      const event = asRecord(data?.event);
      if (event !== null) event.media = null;
      await route.fulfill({ response, json: body });
    };
    const noSnapshotApiPattern = `**/api/v2/admin/audit/${noSnapshotEventId}`;
    await page.route(noSnapshotApiPattern, stripMedia);
    const noSnapshotPageResponse = await isTimeout(() =>
      page.goto(`${origin}/app/admin/audit/${noSnapshotEventId}`, {
        waitUntil: "domcontentloaded",
        timeout: 10_000,
      }),
    );
    const noSnapshotNotice = page.locator(".diff-page > p.notice-panel");
    await isTimeout(() =>
      noSnapshotNotice.waitFor({ state: "visible", timeout: 10_000 }),
    );
    const noSnapshotVisible = await noSnapshotNotice.isVisible();
    const noSnapshotLoading = await page
      .locator(".diff-page [aria-live=polite]")
      .isVisible();
    const noSnapshotError = await page
      .locator(".diff-page [role=alert]")
      .isVisible();
    const noSnapshotRouteMatches =
      urlPath(page.url()) === `/app/admin/audit/${noSnapshotEventId}`;
    await settle();
    await page.unroute(noSnapshotApiPattern, stripMedia);
    if (!(
      noSnapshotVisible &&
      !noSnapshotLoading &&
      !noSnapshotError &&
      noSnapshotApi.status === 200 &&
      noSnapshotApi.reason === "not_recorded" &&
      noSnapshotApi.changesMissing === true
    )) {
      await saveScreenshot(
        page,
        screenshotDir,
        "admin-audit-no-snapshot-failure.png",
      );
      const noSnapshotPageStatus = noSnapshotPageResponse
        ? noSnapshotPageResponse.status()
        : "none";
      const apiReason =
        typeof noSnapshotApi.reason === "string"
          ? noSnapshotApi.reason
          : "none";
      throw new AcceptanceFailure(
        "no-snapshot detail did not render its unavailable notice " +
          `(route=${noSnapshotRouteMatches}, ` +
          `page_status=${noSnapshotPageStatus}, ` +
          `api_status=${noSnapshotApi.status ?? "none"}, ` +
          `api_reason=${apiReason}, ` +
          `changes_missing=${noSnapshotApi.changesMissing ?? false}, ` +
          `notice=${noSnapshotVisible}, loading=${noSnapshotLoading}, ` +
          `error=${noSnapshotError}, ` +
          `screenshot=${screenshotDir ? "saved" : "disabled"})`,
      );
    }
    page.off("response", recordNoSnapshotDetail);
    const auditParent = page.locator(".app-header .brand-lockup.parent-link");
    check(
      (await auditParent.count()) === 1 &&
        (await auditParent.getAttribute("href")) === "/app/admin/audit" &&
        (await auditParent.getAttribute("aria-label")) === "Back",
      "an audit detail entry did not point its header parent to Audit",
    );
    for (const [width, suffix] of [
      [390, "390"],
      [1280, "desktop"],
    ] as const) {
      await page.setViewportSize({ width, height: 900 });
      const auditParentBounds = await auditParent.boundingBox();
      check(
        auditParentBounds !== null &&
          auditParentBounds.width >= 44 &&
          auditParentBounds.height >= 44,
        "the audit detail parent link target is smaller than 44px",
      );
      if (width === 390) {
        await assertMobileHeaderAlignment(page, "/app/admin/audit");
      }
      check(
        (await page.locator(".diff-back-link").count()) === 0,
        "no-snapshot audit diff rendered a duplicate body Back link",
      );
      await assertNoHorizontalOverflow(page, "diff");
      await saveScreenshot(
        page,
        screenshotDir,
        `admin-audit-detail-no-snapshot-${suffix}.png`,
      );
    }
    mark("click no-snapshot return-to-audit link");
    await auditParent.click();
    mark("confirm no-snapshot return to audit");
    await page.waitForURL("**/app/admin/audit");
    for (const width of [320, 390, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      await assertNoHorizontalOverflow(page, "diff");
    }
    await page.setViewportSize({ width: 390, height: 900 });
    mark("open More before audit resource history journey");
    await page.goto(`${origin}/app/more`, { waitUntil: "networkidle" });
    await page
      .getByRole("heading", { name: "More", exact: true })
      .waitFor({ state: "visible" });
    mark("open Audit from More");
    await page
      .locator(".more-links")
      .getByRole("link", { name: "Admin", exact: false })
      .click();
    await page.waitForURL("**/app/admin/users");
    await page.getByRole("link", { name: "Audit log", exact: true }).click();
    await page.waitForURL("**/app/admin/audit");
    await waitAdminTab(page, "Audit log");
    await page.getByLabel("Entity").selectOption("project");
    await page.getByLabel("Action").fill("updated");
    await page.getByRole("button", { name: "Filter" }).click();
    await page.waitForURL("**/app/admin/audit?entity=project&action=updated");
    const historyResourceRow = page
      .locator(".event-row")
      .filter({ hasText: crossOwnerName })
      .first();
    await historyResourceRow.waitFor({ state: "visible" });
    await historyResourceRow.scrollIntoViewIfNeeded();
    const auditScrollBeforeResource: number = await page.evaluate(
      () => window.scrollY,
    );
    await historyResourceRow.locator("a.resource-card__link").click();
    await page.waitForURL(`**/app/projects/${crossOwnerProjectId}`);
    mark("return from resource to filtered audit with native browser history");
    await page.goBack({ waitUntil: "domcontentloaded" });
    await page.waitForURL("**/app/admin/audit?entity=project&action=updated");
    await historyResourceRow.waitFor({ state: "visible" });
    await page.waitForFunction(
      (target) => Math.abs(window.scrollY - target) <= 2,
      auditScrollBeforeResource,
      { timeout: 5_000 },
    );
    const restoredEntity =
      (await page.getByLabel("Entity").inputValue()) === "project";
    const restoredAction =
      (await page.getByLabel("Action").inputValue()) === "updated";
    const auditScrollAfterBack: number = await page.evaluate(
      () => window.scrollY,
    );
    const scrollRestored =
      Math.abs(auditScrollAfterBack - auditScrollBeforeResource) <= 2;
    check(
      restoredEntity && restoredAction && scrollRestored,
      "browser Back did not restore filtered audit state and scroll " +
        `(entity=${restoredEntity}, action=${restoredAction}, ` +
        `scroll_restored=${scrollRestored})`,
    );
    // Admin is one area with Users and Audit log tabs: More -> Users -> Audit
    // log are separate history entries (the audit filters replace theirs), so
    // Back from the audit log lands on the Users tab and one more Back on More.
    mark("return from audit to Users with native browser history");
    await page.goBack({ waitUntil: "domcontentloaded" });
    await page.waitForURL("**/app/admin/users");
    mark("return from Users to More with native browser history");
    await page.goBack({ waitUntil: "domcontentloaded" });
    await page.waitForURL("**/app/more");
    await page
      .getByRole("heading", { name: "More", exact: true })
      .waitFor({ state: "visible" });
    await page.goto(
      `${origin}/app/admin/resources/project/${crossOwnerProjectId}`,
      { waitUntil: "networkidle" },
    );
    mark("check resource responsive layout");
    for (const width of [320, 390, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      await assertNoHorizontalOverflow(page, "resource");
    }
    await page.goto(`${origin}/app/admin/audit`, { waitUntil: "networkidle" });
    page.off("request", recordAuditDetailRequest);
    mark("check audit actor avatar and known action icons");
    const adminActorRow = page
      .locator(".event-row")
      .filter({ has: page.getByText(adminEmail, { exact: true }) })
      .first();
    const actorRowCount = await adminActorRow.count();
    mark(`wait for admin actor row (found=${actorRowCount > 0})`);
    await adminActorRow.waitFor({ state: "visible" });
    const actorAvatar = adminActorRow.locator(".actor-identity .user-avatar");
    const actorImage = actorAvatar.locator("img[alt='']");
    const actorImageCount = await actorImage.count();
    const actorImageHasSrc = Boolean(await actorImage.getAttribute("src"));
    const latestAvatarStatus = avatarResponseStatuses.at(-1) ?? "none";
    mark(
      "wait for admin actor image " +
        `(element=${actorImageCount > 0}; src=${actorImageHasSrc}; ` +
        `http=${latestAvatarStatus})`,
    );
    await assertUserAvatar(actorAvatar, {
      initial: adminEmail[0]!.toUpperCase(),
      imageExpected: true,
    });
    mark("inspect known audit action icons");
    const visibleActions = await page
      .locator(".audit-action-badge > span")
      .allInnerTexts();
    const knownActionShapes: Record<string, string> = {};
    for (const label of [
      "Created",
      "Updated",
      "Deleted",
      "Uploaded",
      "Removed",
    ]) {
      if (!visibleActions.includes(label)) continue;
      mark(`inspect ${label} audit action icon`);
      const event = page
        .locator(".event-row")
        .filter({ has: page.getByText(label, { exact: true }) })
        .first();
      const icon = event.locator(
        ".audit-action-badge svg[aria-hidden='true'][focusable='false']",
      );
      check(
        (await icon.count()) === 1,
        "a known audit action did not render one decorative SVG",
      );
      knownActionShapes[label] = await svgShapeSignature(icon);
    }
    check(
      visibleActions.includes("Created") &&
        visibleActions.includes("Updated") &&
        Boolean(knownActionShapes.Created) &&
        Boolean(knownActionShapes.Updated) &&
        knownActionShapes.Created !== knownActionShapes.Updated,
      "create and update audit actions did not render distinct shapes",
    );
    const filteredAuditResponse: { status?: number; events?: unknown } = {};
    const recordFilteredAuditResponse = (response: Response): void => {
      if (
        urlPath(response.url()) !== "/api/v2/admin/audit" ||
        urlQuery(response.url()) !== "entity=project&action=created"
      ) {
        return;
      }
      filteredAuditResponse.status = response.status();
      if (response.status() !== 200) return;
      pending.push(
        (async () => {
          try {
            filteredAuditResponse.events = asRecord(
              await response.json(),
            )?.data;
          } catch {
            filteredAuditResponse.events = undefined;
          }
        })(),
      );
    };
    page.on("response", recordFilteredAuditResponse);
    mark("filter audit events by entity");
    await page.getByLabel("Entity").selectOption("project");
    // Choosing an entity filters at once and reloads the list, which
    // resets the action draft; let that settle before typing one.
    await page.waitForURL("**/app/admin/audit?entity=project");
    await page.waitForLoadState("networkidle");
    await page.locator(".event-row").first().waitFor({ state: "visible" });
    mark("filter audit events by action");
    await page.getByLabel("Action").fill("created");
    await page.getByRole("button", { name: "Filter" }).click();
    mark("wait for the filtered audit URL");
    await page.waitForURL("**/app/admin/audit?entity=project&action=created");
    mark("wait for filtered audit rows");
    await page.locator(".event-row").first().waitFor({ state: "visible" });
    mark("read filtered audit rows");
    await settle();
    const apiEventsValue = filteredAuditResponse.events;
    const apiEvents: unknown[] = Array.isArray(apiEventsValue)
      ? apiEventsValue
      : [];
    const rows = await page.locator(".event-row").all();
    const expectedRows: Array<[string, string]> = [];
    for (const item of apiEvents) {
      const event = asRecord(item);
      if (event === null) continue;
      const entityId = event.entity_id;
      const resource = asRecord(event.resource);
      if (
        event.entity_type !== "project" ||
        event.action !== "created" ||
        typeof entityId !== "number" ||
        !Number.isInteger(entityId) ||
        resource === null ||
        resource.kind !== "project"
      ) {
        continue;
      }
      const resourceName = resource.name;
      if (
        resourceName !== null &&
        resourceName !== undefined &&
        typeof resourceName !== "string"
      ) {
        continue;
      }
      expectedRows.push([
        "Created",
        resourceName ? resourceName : `project #${entityId}`,
      ]);
    }
    const renderedRows: Array<[string, string]> = [];
    for (const row of rows) {
      renderedRows.push([
        (await row.locator(".audit-action-badge > span").innerText()).trim(),
        (await row.locator(".resource-card__name").innerText()).trim(),
      ]);
    }
    const filtersMatchApi =
      filteredAuditResponse.status === 200 &&
      Array.isArray(apiEventsValue) &&
      expectedRows.length === apiEvents.length;
    const renderedMatchesApi =
      JSON.stringify(renderedRows) === JSON.stringify(expectedRows);
    check(
      filtersMatchApi && renderedMatchesApi,
      "audit filters returned an event outside the selected entity/action",
    );
    page.off("response", recordFilteredAuditResponse);
    const auditResponse: { status?: number; safe?: boolean } = {};
    page.on("response", (response: Response) => {
      if (urlPath(response.url()) !== "/api/v2/admin/audit") return;
      auditResponse.status = response.status();
      if (response.status() !== 200) return;
      pending.push(
        (async () => {
          try {
            const data = asRecord(await response.json())?.data;
            auditResponse.safe =
              Array.isArray(data) &&
              data.every((item) => {
                const entry = asRecord(item);
                return entry !== null && !("details" in entry);
              });
          } catch {
            auditResponse.safe = false;
          }
        })(),
      );
    });
    mark("clamp an out-of-range audit page");
    await page.goto(
      `${origin}/app/admin/audit?page=9999&entity=project&action=created`,
      { waitUntil: "networkidle" },
    );
    await waitAdminTab(page, "Audit log");
    // Page controls only render when there is more than one page, so
    // a clamped, short filtered list shows its rows and no pager.
    await page.locator(".event-row").first().waitFor({ state: "visible" });
    await page.waitForFunction(
      () => !new URLSearchParams(location.search).has("page"),
    );
    check(
      !new URL(page.url()).search.slice(1).includes("page="),
      "out-of-range audit page did not clamp to the filtered last page",
    );
    await settle();
    check(
      auditResponse.status === 200 && auditResponse.safe === true,
      "audit API did not return its safe DTO projection",
    );

    mark("check generic icon for an unknown audit action");
    const injected = { unknownAction: false };
    const injectUnknownAction = async (route: Route): Promise<void> => {
      const response = await route.fetch();
      const payload: unknown = await response.json();
      const data = asRecord(payload)?.data;
      if (!injected.unknownAction && Array.isArray(data) && data.length > 0) {
        const template = data.find((event) => {
          const record = asRecord(event);
          return (
            record !== null &&
            record.actor_email === adminEmail &&
            typeof record.actor_avatar_url === "string" &&
            record.actor_avatar_url !== "" &&
            typeof record.id === "number"
          );
        });
        const templateRecord = asRecord(template);
        if (templateRecord !== null) {
          const ids = data.flatMap((entry) => {
            const record = asRecord(entry);
            return record !== null && typeof record.id === "number"
              ? [record.id]
              : [];
          });
          data.unshift({
            ...templateRecord,
            id: Math.max(...ids) + 1,
            action: "unrecognized_fixture_action",
            has_changes: false,
          });
          injected.unknownAction = true;
        }
      }
      await route.fulfill({ response, json: payload });
    };
    await page.route("**/api/v2/admin/audit*", injectUnknownAction);
    await page.goto(`${origin}/app/admin/audit`, { waitUntil: "networkidle" });
    const unknownRow = page.locator(".event-row").filter({
      has: page.getByText("unrecognized_fixture_action", { exact: true }),
    });
    await unknownRow.waitFor({ state: "visible" });
    mark("measure audit action colors, contrast, and row geometry");
    await assertAuditVisualMeasurements(page);
    const unknownActionLabel = unknownRow.locator(".audit-action-badge > span");
    const unknownBadge = unknownRow.locator(".audit-action-badge");
    const unknownIcon = unknownRow.locator(
      ".audit-action-badge svg[aria-hidden='true'][focusable='false']",
    );
    const unknownBadgeClass = (await unknownBadge.getAttribute("class")) ?? "";
    check(
      injected.unknownAction &&
        (await unknownActionLabel.innerText()).trim() ===
          "unrecognized_fixture_action" &&
        unknownBadgeClass.includes("action-badge--unknown") &&
        ![
          "action-badge--create",
          "action-badge--update",
          "action-badge--upload",
          "action-badge--delete",
        ].some((tone) => unknownBadgeClass.includes(tone)) &&
        (await unknownIcon.count()) === 1 &&
        Boolean(await svgShapeSignature(unknownIcon)),
      "an unknown audit action lost its raw label or generic SVG",
    );
    await assertUserAvatar(unknownRow.locator(".actor-identity .user-avatar"), {
      initial: adminEmail[0]!.toUpperCase(),
      imageExpected: true,
    });
    await page.unroute("**/api/v2/admin/audit*", injectUnknownAction);

    mark("check broken member avatar initial fallback");
    await page.goto(`${origin}/app/admin/users`, { waitUntil: "networkidle" });
    memberRow = page.locator(".user-row").filter({ hasText: memberEmail });
    let memberAvatar = memberRow.locator(".user-identity .user-avatar");
    const memberImage = memberAvatar.locator("img[alt='']");
    await memberImage.waitFor({ state: "visible" });
    await assertUserAvatar(memberAvatar, {
      initial: memberEmail[0]!.toUpperCase(),
      imageExpected: true,
    });
    const avatarSource: string = await memberImage.evaluate(
      (image) => (image as HTMLImageElement).currentSrc,
    );
    const avatarParts = new URL(avatarSource);
    check(
      `${avatarParts.protocol}//${avatarParts.host}` === origin,
      "member avatar did not use the loopback fixture media URL",
    );
    const blockFixtureAvatar = async (route: Route): Promise<void> => {
      await route.abort("failed");
    };
    await page.route(avatarSource, blockFixtureAvatar);
    await page.reload({ waitUntil: "networkidle" });
    memberAvatar = page
      .locator(".user-row")
      .filter({ hasText: memberEmail })
      .locator(".user-identity .user-avatar");
    await assertUserAvatar(memberAvatar, {
      initial: memberEmail[0]!.toUpperCase(),
      imageExpected: false,
    });
    await page.unroute(avatarSource, blockFixtureAvatar);

    mark("audit shows uploaded pictures and files with a viewer");
    await page.goto(`${origin}/app/admin/audit`, { waitUntil: "networkidle" });
    await page.locator(".event-row").first().waitFor({ state: "visible" });
    const eventRowsWithoutDetails: number = await page
      .locator(".event-row")
      .evaluateAll(
        (rows) =>
          rows.filter(
            (row) =>
              !row.querySelector(
                ".show-diff, [data-audit-media-show], [data-audit-media-open], " +
                  "[data-audit-media-note], [data-audit-no-details]",
              ),
          ).length,
      );
    check(
      eventRowsWithoutDetails === 0,
      "an audit row offered neither details, media nor a no-details note",
    );
    const coverRow = page
      .locator(".event-row")
      .filter({ hasText: "synthetic-project-cover.png" })
      .first();
    await coverRow.waitFor({ state: "visible" });
    await coverRow.getByRole("button", { name: "Show picture" }).click();
    const mediaDialog = page.locator("dialog.media-dialog[open]");
    await mediaDialog.waitFor({ state: "visible" });
    const viewerPicture = mediaDialog.locator("img");
    const viewerSource: string =
      (await viewerPicture.getAttribute("src")) ?? "";
    await page.waitForFunction(
      () => {
        const image = document.querySelector<HTMLImageElement>(
          "dialog.media-dialog[open] img",
        );
        return Boolean(image?.complete && image.naturalWidth > 0);
      },
      undefined,
      { timeout: 5_000 },
    );
    check(
      new RegExp(
        `^/api/v2/admin/resources/project/${crossOwnerProjectId}/media/image/\\d+$`,
      ).test(viewerSource),
      "the audit picture viewer did not use the read-only admin media route",
    );
    await page.keyboard.press("Escape");
    await mediaDialog.waitFor({ state: "detached" });
    const fileRow = page
      .locator(".event-row")
      .filter({ hasText: "synthetic-pattern.txt" })
      .first();
    const fileLink = fileRow.getByRole("link", { name: "Open file" });
    await fileLink.waitFor({ state: "visible" });
    const fileHref = (await fileLink.getAttribute("href")) ?? "";
    check(
      new RegExp(
        `^/api/v2/admin/resources/project/${crossOwnerProjectId}/media/attachment/\\d+$`,
      ).test(fileHref),
      "the audit file link did not use the read-only admin media route",
    );
    const fileStatus: number = await page.evaluate(async (href) => {
      const response = await fetch(href);
      return response.status;
    }, fileHref);
    check(fileStatus === 200, "the audit file link did not download");

    mark("member is denied admin API access");
    mark("locate administrator sign-out control");
    mark("click administrator sign-out");
    await signOut(page);
    mark("fill member sign-in");
    await page.locator("#email").fill(memberEmail);
    await page.locator("#password").fill(resetPassword);
    await page.getByRole("button", { name: "Sign in" }).click();
    mark("wait for member sign-in");
    await page.getByRole("heading", { name: "Projects" }).waitFor({
      state: "visible",
    });
    mark("ordinary user is denied cross-owner resource view");
    await page.goto(
      `${origin}/app/admin/resources/project/${crossOwnerProjectId}`,
      { waitUntil: "networkidle" },
    );
    await page
      .getByRole("heading", { name: "You do not have access to this page." })
      .waitFor({ state: "visible" });
    const resourceForbiddenStatus: number = await page.evaluate(
      async (projectId) => {
        const response = await fetch(
          `/api/v2/admin/resources/project/${projectId}`,
        );
        return response.status;
      },
      crossOwnerProjectId,
    );
    check(
      resourceForbiddenStatus === 403,
      "a member session was not denied by the admin resource API",
    );
    mark("member is not offered the Mine / All filter");
    await assertNoScopeFilter(page, origin);
    mark("administrator edits the member's project");
    await signOut(page);
    await signInAccount(page, origin, adminEmail, adminPassword);
    await assertAdminEditsForeignProject(page, origin, crossOwner, mark);
    await signOut(page);
    await signInAccount(page, origin, memberEmail, resetPassword);
    mark("owner deletes synthetic resource through authenticated v2 API");
    await deleteCrossOwnerResource(page, origin, crossOwnerProjectId);

    await signOut(page);
    mark("wait for member sign-out");
    mark("fill administrator sign-in");
    await page.locator("#email").fill(adminEmail);
    await page.locator("#password").fill(adminPassword);
    await page.getByRole("button", { name: "Sign in" }).click();
    mark("wait for administrator sign-in");
    await page.getByRole("heading", { name: "Projects" }).waitFor({
      state: "visible",
    });
    mark("open administrator user route");
    await page.goto(`${origin}/app/admin/users`, { waitUntil: "networkidle" });
    ownRow = page.locator(".user-row").filter({ hasText: adminEmail });
    await openUserMenu(ownRow);
    await page.getByRole("menuitem", { name: "Reset password" }).click();
    const ownResetDialog = page.getByRole("dialog", {
      name: "Reset this password?",
    });
    await ownResetDialog
      .getByLabel("New temporary password")
      .fill(adminPassword);
    await ownResetDialog
      .getByRole("button", { name: "Reset password", exact: true })
      .click();
    await page.locator("#email").waitFor({ state: "visible" });
    check(
      (await page.locator("#password").inputValue()) === "",
      "self password reset retained a sign-in password field",
    );
    await page.locator("#email").fill(adminEmail);
    await page.locator("#password").fill(adminPassword);
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByRole("heading", { name: "Projects" }).waitFor({
      state: "visible",
    });

    mark("deleted resource remains linked to its event detail");
    await page.goto(`${origin}/app/admin/audit`, { waitUntil: "networkidle" });
    const deletedEventRow = page
      .locator(".event-row")
      .filter({ hasText: "Deleted" })
      .filter({ hasText: crossOwnerName })
      // The trashed and the permanent deletion both read as deleted once the project is gone.
      .first();
    const deletedResourceLink = deletedEventRow.locator(
      "a.resource-card__link",
    );
    await deletedResourceLink.waitFor({ state: "visible" });
    const deletedEventHref =
      (await deletedResourceLink.getAttribute("href")) ?? "";
    const deletedEventMatch = /^\/app\/admin\/audit\/(\d+)$/.exec(
      deletedEventHref,
    );
    if (deletedEventMatch === null) {
      throw new AcceptanceFailure(
        "deleted resource link did not use the event detail route",
      );
    }
    const deletedEventApiPath = `/api/v2/admin/audit/${deletedEventMatch[1]}`;
    const deletedEventResponse: {
      status?: number;
      exists?: unknown;
      name?: unknown;
    } = {};
    const recordDeletedEvent = (response: Response): void => {
      if (urlPath(response.url()) !== deletedEventApiPath) return;
      if (response.status() !== 200) {
        deletedEventResponse.status = response.status();
        return;
      }
      pending.push(
        (async () => {
          let payload: unknown;
          try {
            payload = await response.json();
          } catch {
            return;
          }
          const data = asRecord(asRecord(payload)?.data);
          const event = asRecord(data?.event);
          const resource = asRecord(event?.resource);
          deletedEventResponse.exists = resource?.exists ?? null;
          deletedEventResponse.name = resource?.name ?? null;
          deletedEventResponse.status = response.status();
        })(),
      );
    };
    page.on("response", recordDeletedEvent);
    await deletedResourceLink.click();
    await page
      .getByRole("heading", { name: crossOwnerName, exact: true })
      .waitFor({ state: "visible" });
    await page
      .getByText("Resource unavailable", { exact: true })
      .waitFor({ state: "visible" });
    await settle();
    check(
      deletedEventResponse.status === 200 &&
        deletedEventResponse.exists === false &&
        deletedEventResponse.name === crossOwnerName,
      "deleted resource did not resolve to an honest event detail",
    );
    page.off("response", recordDeletedEvent);

    mark("audit says deleted pictures and files are deleted");
    await page.goto(`${origin}/app/admin/audit`, { waitUntil: "networkidle" });
    const deletedFileRow = page
      .locator(".event-row")
      .filter({ hasText: "synthetic-pattern.txt" })
      .first();
    await deletedFileRow.waitFor({ state: "visible" });
    check(
      (await deletedFileRow.locator(".media-note--deleted").count()) === 1 &&
        (await deletedFileRow
          .locator("[data-audit-media-note]")
          .getByText("deleted", { exact: false })
          .count()) === 1 &&
        (await deletedFileRow
          .getByRole("link", { name: "Open file" })
          .count()) === 0,
      "a deleted file was not marked deleted in the audit log",
    );

    mark("responsive layout and sensitive data check");
    for (const [route, tab] of [
      ["users", "Users"],
      ["audit", "Audit log"],
    ] as const) {
      mark(`measure admin ${route} responsive layout`);
      await page.goto(`${origin}/app/admin/${route}`, {
        waitUntil: "networkidle",
      });
      await waitAdminTab(page, tab);
      // Mirrors the Python runner, where the loop variable keeps its last
      // value (1280): the mobile alignment check below never runs.
      let width = 0;
      for (width of [320, 390, 768, 1280]) {
        await page.setViewportSize({ width, height: 900 });
        await assertNoHorizontalOverflow(page, route);
      }
      if (route === "audit" && width <= 390) {
        await assertMobileHeaderAlignment(page);
      }
    }
    const localKeys: string[] = await page.evaluate(() =>
      Object.keys(window.localStorage),
    );
    check(
      !localKeys.some((key) =>
        ["password", "admin-draft"].some((word) =>
          key.toLowerCase().includes(word),
        ),
      ),
      "administrator passwords or drafts were persisted in local storage",
    );
    mark("top-level direct audit entry has a reserved slot and no parent link");
    const freshPage = await context.newPage();
    await guardLoopback(freshPage, origin);
    await freshPage.goto("about:blank");
    await freshPage.evaluate((target) => {
      location.replace(target);
    }, `${origin}/app/admin/audit`);
    await freshPage.waitForURL("**/app/admin/audit");
    await waitAdminTab(freshPage, "Audit log");
    await freshPage.waitForFunction(
      () => {
        const chevron = document.querySelector(".app-header .brand-chevron");
        return Boolean(chevron && getComputedStyle(chevron).opacity === "0");
      },
      undefined,
      { timeout: 5_000 },
    );
    const freshTopLevelBrand = freshPage.locator(".app-header .brand-lockup");
    const freshHistoryLength: number = await freshPage.evaluate(
      () => history.length,
    );
    check(
      freshHistoryLength === 1 &&
        (await freshTopLevelBrand.count()) === 1 &&
        (await freshTopLevelBrand.getAttribute("href")) === "/app/" &&
        (await freshTopLevelBrand.getAttribute("aria-label")) ===
          "Stricknani" &&
        (await freshPage
          .locator(".app-header .brand-lockup.parent-link")
          .count()) === 0 &&
        (await freshTopLevelBrand
          .locator(".brand-chevron")
          .evaluate((element) => getComputedStyle(element).opacity === "0")),
      "top-level direct audit entry showed a parent link or chevron",
    );
    await freshPage.close();
    await context.close();
  } finally {
    await closeBrowser(browser);
  }
}

export async function runAdminJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const stage = stageMarker(f.privateDir);
  const mark: Mark = (value) => stage.mark(value);
  try {
    const admin = await createFixtureAdmin(f, cfg);
    await runAcceptance(f, admin, mark, cfg);
  } catch (error) {
    if (error instanceof AcceptanceFailure || error instanceof FixtureError) {
      throw error;
    }
    const current = stage.current() || "administrator users and audit journey";
    throw new AcceptanceFailure(
      `Admin browser acceptance failed during ${current} ` +
        `(${playwrightErrorCategory(error)}); diagnostic details were suppressed.`,
    );
  }
}
