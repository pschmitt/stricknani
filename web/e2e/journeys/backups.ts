// Data export and restore journey (the Your data section of the web Account page, docs/data-export.md)
// against a disposable Rust server. Ada owns the data and downloads it, Bo
// shares a project with her, Cy restores the downloads into an empty account.
import { createHash, randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import {
  expect,
  type BrowserContext,
  type Page,
  type Route,
} from "@playwright/test";

import { makeTarGz, readArchive, type ArchiveEntry } from "../lib/archive";
import { check, closeBrowser, fn, tinyPngFixture } from "../lib/common";
import { maskGithubSecret } from "../lib/fixture";
import {
  launchBrowser,
  stageMarker,
  type E2eConfig,
  type LiveFixture,
} from "../lib/harness";
import { createFixtureAdmin } from "./admin";

const PHONE = { width: 390, height: 844 };
const DESKTOP = { width: 1280, height: 900 };
const WAIT = 30_000;
const MANIFEST_FORMAT = "stricknani-export";

type Json = Record<string, unknown>;

interface ApiResult {
  status: number;
  json: Json | null;
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

/** A same-origin JSON call with the session's CSRF token; never logs bodies. */
async function api(
  page: Page,
  method: string,
  path: string,
  body?: Json,
): Promise<ApiResult> {
  return (await page.evaluate(
    fn(`async ({ method, path, body }) => {
      const session = await (
        await fetch("/api/v2/session", { credentials: "same-origin" })
      ).json();
      const response = await fetch(path, {
        method,
        credentials: "same-origin",
        headers: {
          "content-type": "application/json",
          "x-csrf-token": session.data.csrf_token,
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      return { status: response.status, json: text ? JSON.parse(text) : null };
    }`),
    { method, path, body },
  )) as ApiResult;
}

function dataOf(result: ApiResult, expected: number, what: string): Json {
  check(result.status === expected, `${what} returned HTTP ${result.status}`);
  const data = result.json?.data;
  check(typeof data === "object" && data !== null, `${what} had no data`);
  return data as Json;
}

function idOf(value: Json, what: string): number {
  check(typeof value.id === "number", `${what} had no id`);
  return value.id;
}

/** Multipart upload with the session cookie, CSRF token and same-origin headers. */
async function upload(
  page: Page,
  origin: string,
  path: string,
  what: string,
  altText?: string,
): Promise<void> {
  const session = (await (
    await page.request.get(`${origin}/api/v2/session`)
  ).json()) as { data: { csrf_token: string } };
  const response = await page.request.post(`${origin}${path}`, {
    headers: {
      accept: "application/json",
      origin,
      "sec-fetch-site": "same-origin",
      "x-csrf-token": session.data.csrf_token,
    },
    multipart: {
      file: {
        name: "fixture.png",
        mimeType: "image/png",
        buffer: tinyPngFixture(),
      },
      ...(altText === undefined ? {} : { alt_text: altText }),
    },
  });
  check(
    response.status() === 201,
    `${what} upload returned HTTP ${response.status()}`,
  );
}

async function signIn(
  page: Page,
  origin: string,
  email: string,
  password: string,
): Promise<void> {
  await page.goto(`${origin}/app/`, { waitUntil: "networkidle" });
  await page.locator("#email").waitFor({ state: "visible" });
  await page.locator("#email").fill(email);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("heading", { name: "Projects" }).waitFor({
    state: "visible",
  });
}

interface Account {
  email: string;
  password: string;
  name: string;
  id: number;
}

function account(name: string): Account {
  const password = `A backup password ${randomBytes(12).toString("base64url")}`;
  maskGithubSecret(password);
  return { email: `${name}@example.invalid`, password, name, id: 0 };
}

function projectBody(name: string, yarnIds: number[], extra: Json = {}): Json {
  return {
    name,
    category: null,
    needles: null,
    stitch_sample: null,
    description: null,
    notes: null,
    other_materials: null,
    tags: [],
    link: null,
    is_ai_enhanced: false,
    yarn_ids: yarnIds,
    steps: [{ title: "Cast on", description: "Start.", step_number: 1 }],
    ...extra,
  };
}

function utcDates(): string[] {
  const now = Date.now();
  return [now - 3_600_000, now, now + 3_600_000].map((time) =>
    new Date(time).toISOString().slice(0, 10),
  );
}

interface ParsedExport {
  top: string;
  paths: string[];
  manifest: Json;
  projects: Json[];
  yarns: Json[];
}

function asJson(value: unknown, what: string): Json {
  check(
    typeof value === "object" && value !== null,
    `${what} is not an object`,
  );
  return value as Json;
}

function jsonEntry(
  entries: ArchiveEntry[],
  top: string,
  name: string,
): unknown {
  const entry = entries.find((item) => item.path === `${top}/${name}`);
  check(entry !== undefined, `${name} is missing from the archive`);
  return JSON.parse(entry.data.toString("utf8"));
}

/** Media entries of every project and yarn in a parsed export. */
function mediaOf(projects: Json[], yarns: Json[]): Json[] {
  const media: Json[] = [];
  for (const project of projects) {
    for (const key of ["images", "attachments"]) {
      media.push(...((project[key] as Json[] | undefined) ?? []));
    }
    for (const step of (project.steps as Json[] | undefined) ?? []) {
      media.push(...((step.images as Json[] | undefined) ?? []));
    }
  }
  for (const yarn of yarns) {
    media.push(...((yarn.photos as Json[] | undefined) ?? []));
  }
  return media;
}

function parseExport(archive: Buffer, kind: string): ParsedExport {
  const entries = readArchive(archive);
  check(entries.length > 0, `${kind} archive has no entries`);
  const first = entries[0]!;
  const top = first.path.split("/")[0] ?? "";
  check(
    /^stricknani-export-\d{4}-\d{2}-\d{2}$/.test(top),
    `${kind} archive has an unexpected top-level directory`,
  );
  check(
    first.path === `${top}/manifest.json`,
    `${kind} archive does not start with manifest.json`,
  );
  const manifest = asJson(JSON.parse(first.data.toString("utf8")), "manifest");
  check(manifest.format === MANIFEST_FORMAT, `${kind} manifest format differs`);
  check(manifest.format_version === 1, `${kind} manifest version differs`);
  const projects = jsonEntry(entries, top, "projects.json") as Json[];
  const yarns = jsonEntry(entries, top, "yarns.json") as Json[];
  check(Array.isArray(projects) && Array.isArray(yarns), `${kind} JSON shape`);
  const counts = asJson(manifest.counts, "manifest counts");
  check(counts.projects === projects.length, `${kind} project count differs`);
  check(counts.yarns === yarns.length, `${kind} yarn count differs`);
  let written = 0;
  for (const media of mediaOf(projects, yarns)) {
    if (media.file === null || media.file === undefined) continue;
    written += 1;
    const entry = entries.find(
      (item) => item.path === `${top}/${String(media.file)}`,
    );
    check(entry !== undefined, `${kind} archive lacks a listed media file`);
    check(
      createHash("sha256").update(entry.data).digest("hex") === media.sha256,
      `${kind} media sha256 does not match the file bytes`,
    );
    check(
      entry.data.length === media.size_bytes,
      `${kind} media size does not match the file bytes`,
    );
  }
  check(counts.media_files === written, `${kind} media count differs`);
  return {
    top,
    paths: entries.map((entry) => entry.path.slice(top.length + 1)).sort(),
    manifest,
    projects,
    yarns,
  };
}

export async function runBackupsJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const origin = f.origin;
  const stage = stageMarker(f.privateDir);
  const mark = (value: string): void => stage.mark(value);
  const downloads = join(f.privateDir, "downloads");
  mkdirSync(downloads, { recursive: true });
  let downloadCounter = 0;
  const downloadDir = (): string => {
    downloadCounter += 1;
    const dir = join(downloads, String(downloadCounter));
    mkdirSync(dir);
    return dir;
  };

  const admin = await createFixtureAdmin(f, cfg);
  const ada = account("ada");
  const bo = account("bo");
  const cy = account("cy");

  const browser = await launchBrowser(cfg);
  try {
    const context = async (viewport = PHONE): Promise<BrowserContext> =>
      browser.newContext({
        locale: "en-US",
        viewport,
        isMobile: viewport.width < 600,
        hasTouch: viewport.width < 600,
        serviceWorkers: "block",
        acceptDownloads: true,
      });
    const pageOf = async (ctx: BrowserContext): Promise<Page> => {
      const page = await ctx.newPage();
      await guardLoopback(page, origin);
      return page;
    };

    mark("create accounts");
    const adminContext = await context();
    const adminPage = await pageOf(adminContext);
    await signIn(adminPage, origin, admin.email, admin.password);
    for (const person of [ada, bo, cy]) {
      person.id = idOf(
        dataOf(
          await api(adminPage, "POST", "/api/v2/admin/users", {
            email: person.email,
            password: person.password,
            is_admin: false,
          }),
          201,
          `create ${person.name}`,
        ),
        `create ${person.name}`,
      );
    }
    await adminContext.close();

    mark("bo data");
    const boContext = await context();
    const boPage = await pageOf(boContext);
    await signIn(boPage, origin, bo.email, bo.password);
    const boProject = idOf(
      dataOf(
        await api(
          boPage,
          "POST",
          "/api/v2/projects",
          projectBody("Borrowed Shawl", []),
        ),
        201,
        "bo project",
      ),
      "bo project",
    );
    await upload(
      boPage,
      origin,
      `/api/v2/projects/${boProject}/images/title`,
      "bo cover",
      "Fixture photo",
    );

    mark("ada data");
    const adaContext = await context();
    const adaPage = await pageOf(adaContext);
    await signIn(adaPage, origin, ada.email, ada.password);
    dataOf(
      await api(adaPage, "POST", "/api/v2/categories", { name: "Sweaters" }),
      201,
      "category",
    );
    const yarn = dataOf(
      await api(adaPage, "POST", "/api/v2/yarns", {
        name: "Sky Merino",
        description: null,
        brand: "Fixture Mill",
        colorway: null,
        dye_lot: null,
        fiber_content: null,
        weight_category: "DK",
        recommended_needles: null,
        weight_grams: 100,
        length_meters: 200,
        notes: null,
        link: null,
        is_ai_enhanced: false,
      }),
      201,
      "yarn",
    );
    const yarnId = idOf(yarn, "yarn");
    await upload(
      adaPage,
      origin,
      `/api/v2/yarns/${yarnId}/photos`,
      "yarn photo",
    );
    const adaProject = dataOf(
      await api(
        adaPage,
        "POST",
        "/api/v2/projects",
        projectBody("Aran Sweater", [yarnId], {
          category: "Sweaters",
          tags: ["wool", "winter"],
          steps: [
            { title: "Cast on", description: "Start.", step_number: 1 },
            { title: "Body", description: "Knit.", step_number: 2 },
          ],
        }),
      ),
      201,
      "ada project",
    );
    const adaProjectId = idOf(adaProject, "ada project");
    const firstStep = idOf(
      asJson((adaProject.steps as unknown[])[0], "step"),
      "step",
    );
    await upload(
      adaPage,
      origin,
      `/api/v2/projects/${adaProjectId}/images/title`,
      "cover",
      "Fixture photo",
    );
    await upload(
      adaPage,
      origin,
      `/api/v2/projects/${adaProjectId}/steps/${firstStep}/images`,
      "step photo",
      "Fixture photo",
    );
    check(
      (await api(adaPage, "PUT", `/api/v2/projects/${adaProjectId}/favorite`))
        .status < 300,
      "favoriting the project failed",
    );
    dataOf(
      await api(boPage, "POST", `/api/v2/projects/${boProject}/shares`, {
        user_id: ada.id,
        role: "viewer",
      }),
      201,
      "share with ada",
    );
    await boContext.close();

    // 1-2. Ada: reach the page through More, then by reload; download both types.
    mark("ada opens backups through more");
    await adaPage.goto(`${origin}/app/`, { waitUntil: "networkidle" });
    await adaPage
      .locator(".mobile-navigation a")
      .filter({ hasText: "More" })
      .click();
    await adaPage.getByRole("heading", { name: "More", exact: true }).waitFor();
    await adaPage
      .getByRole("link", { name: "Account settings", exact: true })
      .click();
    await adaPage.getByRole("heading", { name: "Account", level: 1 }).waitFor();
    await adaPage.getByRole("heading", { name: "Your data" }).waitFor();
    check(
      new URL(adaPage.url()).pathname.replace(/\/+$/, "") === "/app/account",
      "More > Account settings did not open /app/account",
    );
    mark("ada reloads backups");
    await adaPage.reload({ waitUntil: "networkidle" });
    await adaPage.getByRole("heading", { name: "Your data" }).waitFor();
    mark("ada follows an old backups link");
    await adaPage.goto(`${origin}/app/backups`, { waitUntil: "networkidle" });
    await adaPage.getByRole("heading", { name: "Your data" }).waitFor();
    check(
      new URL(adaPage.url()).pathname.replace(/\/+$/, "") === "/app/account" &&
        new URL(adaPage.url()).hash === "#backups",
      "/app/backups did not redirect to /app/account#backups",
    );

    const archiveSelect = adaPage.getByRole("button", {
      name: "Archive type",
      exact: true,
    });
    const archiveListbox = adaPage.getByRole("listbox", {
      name: "Archive type",
    });
    const tarOption = archiveListbox.getByRole("option", {
      name: ".tar.gz",
      exact: true,
    });
    const zipOption = archiveListbox.getByRole("option", {
      name: ".zip",
      exact: true,
    });
    await archiveSelect.click();
    await expect(tarOption).toHaveAttribute("aria-selected", "true", {
      timeout: WAIT,
    });
    await expect(zipOption).toHaveAttribute("aria-selected", "false", {
      timeout: WAIT,
    });
    await tarOption.click();
    await expect(archiveSelect).toHaveText(".tar.gz", { timeout: WAIT });
    const downloadLink = adaPage.getByRole("link", {
      name: "Download",
      exact: true,
    });
    await expect(downloadLink).toHaveAttribute(
      "href",
      /\/api\/v2\/account\/export$/,
      {
        timeout: WAIT,
      },
    );

    const saveDownload = async (
      kind: string,
    ): Promise<{ name: string; path: string }> => {
      const pending = adaPage.waitForEvent("download", { timeout: WAIT });
      await downloadLink.click();
      const download = await pending;
      const name = download.suggestedFilename();
      const path = join(downloadDir(), name);
      await download.saveAs(path);
      check((await download.failure()) === null, `${kind} download failed`);
      return { name, path };
    };

    mark("ada downloads tar.gz");
    const tarFile = await saveDownload("tar.gz");
    const namePattern = (ext: string): RegExp =>
      new RegExp(`^stricknani-export-(\\d{4}-\\d{2}-\\d{2})\\.${ext}$`);
    const tarMatch = namePattern("tar\\.gz").exec(tarFile.name);
    check(
      tarMatch !== null,
      `tar.gz download name is unexpected: ${tarFile.name}`,
    );
    check(
      utcDates().includes(tarMatch[1]!),
      "tar.gz download name carries a wrong date",
    );
    const tar = parseExport(readFileSync(tarFile.path), "tar.gz");
    const counts = asJson(tar.manifest.counts, "counts");
    check(
      counts.projects === 2,
      `unexpected project count ${String(counts.projects)}`,
    );
    check(counts.yarns === 1, `unexpected yarn count ${String(counts.yarns)}`);
    check(
      counts.categories === 1,
      `unexpected category count ${String(counts.categories)}`,
    );
    check(
      counts.media_files === 4,
      `unexpected media count ${String(counts.media_files)}`,
    );
    const account_ = asJson(tar.manifest.account, "manifest account");
    check(account_.id === ada.id, "manifest names a different account");
    const shared = tar.projects.find(
      (project) => project.name === "Borrowed Shawl",
    );
    check(
      shared !== undefined,
      "the shared project is missing from the export",
    );
    check(
      asJson(shared.access, "access").role === "viewer",
      "the shared project is not exported with role viewer",
    );
    check(
      asJson(shared.owner, "owner").id === bo.id,
      "the shared project does not name its owner",
    );
    const own = tar.projects.find((project) => project.name === "Aran Sweater");
    check(own !== undefined, "the own project is missing from the export");
    check(
      asJson(own.access, "access").role === "owner",
      "own project role differs",
    );
    check(own.is_favorite === true, "the favourite flag is not exported");
    check(own.category === "Sweaters", "the category is not exported");
    check(
      JSON.stringify(own.tags) === JSON.stringify(["wool", "winter"]),
      "the tags are not exported",
    );
    check((own.steps as unknown[]).length === 2, "the steps are not exported");

    mark("ada downloads zip");
    await archiveSelect.click();
    await zipOption.click();
    await archiveSelect.click();
    await expect(zipOption).toHaveAttribute("aria-selected", "true", {
      timeout: WAIT,
    });
    await zipOption.press("Escape");
    await expect(archiveSelect).toHaveText(".zip", { timeout: WAIT });
    await expect(downloadLink).toHaveAttribute("href", /archive=zip$/, {
      timeout: WAIT,
    });
    const zipFile = await saveDownload("zip");
    check(
      namePattern("zip").test(zipFile.name),
      `zip download name is unexpected: ${zipFile.name}`,
    );
    const zip = parseExport(readFileSync(zipFile.path), "zip");
    check(
      JSON.stringify(zip.paths) === JSON.stringify(tar.paths),
      "the zip and the tar.gz hold different entries",
    );
    check(
      JSON.stringify(zip.manifest.counts) ===
        JSON.stringify(tar.manifest.counts),
      "the zip and the tar.gz have different counts",
    );

    // 2b. Ada: an encrypted .tar.gz with a non-ASCII passphrase.
    mark("ada downloads encrypted tar.gz");
    const secret = `Sch\u00e4fchen \u{1F9F6} ${randomBytes(6).toString("base64url")}`;
    maskGithubSecret(secret);
    await archiveSelect.click();
    await tarOption.click();
    await archiveSelect.click();
    await expect(tarOption).toHaveAttribute("aria-selected", "true", {
      timeout: WAIT,
    });
    await tarOption.press("Escape");
    await expect(archiveSelect).toHaveText(".tar.gz", { timeout: WAIT });
    await adaPage.getByLabel("Encrypt with a passphrase").check();
    const passField = adaPage.locator("#export-passphrase");
    const repeatField = adaPage.locator("#export-repeat");
    await expect(adaPage.getByLabel("Passphrase", { exact: true })).toHaveCount(
      2,
    );
    await expect(passField).toHaveAttribute("type", "password");
    await expect(repeatField).toHaveAttribute("type", "password");
    await adaPage
      .getByText(
        "At least 12 characters. Without it the file cannot be opened; it is not stored anywhere.",
      )
      .waitFor({ state: "visible", timeout: WAIT });
    const encryptedButton = adaPage.getByRole("button", {
      name: "Download",
      exact: true,
    });
    await passField.fill("short");
    await repeatField.fill("short");
    await encryptedButton.click();
    await adaPage
      .getByText("The passphrase is too short.")
      .waitFor({ state: "visible", timeout: WAIT });
    await passField.fill(secret);
    await repeatField.fill(`${secret}x`);
    await adaPage
      .getByText("The passphrases do not match.")
      .waitFor({ state: "visible", timeout: WAIT });
    await repeatField.fill(secret);
    const encryptedPending = adaPage.waitForEvent("download", {
      timeout: WAIT,
    });
    await encryptedButton.click();
    const encryptedDownload = await encryptedPending;
    const encryptedName = encryptedDownload.suggestedFilename();
    check(
      namePattern("tar\\.gz\\.age").test(encryptedName),
      `encrypted download name is unexpected: ${encryptedName}`,
    );
    const encryptedPath = join(downloadDir(), encryptedName);
    await encryptedDownload.saveAs(encryptedPath);
    check(
      (await encryptedDownload.failure()) === null,
      "encrypted download failed",
    );
    const encryptedBytes = readFileSync(encryptedPath);
    check(
      encryptedBytes.subarray(0, 22).toString("latin1") ===
        "age-encryption.org/v1\n",
      "the encrypted download does not start with the age header",
    );
    check(
      !encryptedBytes.includes(Buffer.from("manifest.json")),
      "the encrypted download leaks archive content",
    );
    await expect(passField).toHaveValue("", { timeout: WAIT });
    await expect(repeatField).toHaveValue("", { timeout: WAIT });
    await adaContext.close();

    // 3. Cy: restore the tar.gz, then the zip.
    mark("cy signs in");
    const cyContext = await context(DESKTOP);
    const cyPage = await pageOf(cyContext);
    await signIn(cyPage, origin, cy.email, cy.password);
    const listLength = async (path: string): Promise<number> => {
      const result = await api(cyPage, "GET", path);
      check(result.status === 200, `${path} returned HTTP ${result.status}`);
      const rows = result.json?.data;
      check(Array.isArray(rows), `${path} had no list`);
      return rows.length;
    };
    check(
      (await listLength("/api/v2/projects")) === 0,
      "cy did not start empty",
    );

    const openBackups = async (): Promise<void> => {
      await cyPage.goto(`${origin}/app/backups`, { waitUntil: "networkidle" });
      await cyPage
        .getByRole("heading", { name: "Restore from a file" })
        .waitFor();
    };
    const chooseFile = async (path: string): Promise<void> => {
      const chooser = cyPage.waitForEvent("filechooser", { timeout: WAIT });
      await cyPage.getByRole("button", { name: "Choose file" }).click();
      await (await chooser).setFiles(path);
    };
    const confirmDialog = async (fileName: string): Promise<void> => {
      const dialog = cyPage.getByRole("dialog", { name: "Restore this file?" });
      await dialog.waitFor({ state: "visible", timeout: WAIT });
      await expect(dialog.getByText(fileName, { exact: true })).toBeVisible({
        timeout: WAIT,
      });
      await dialog
        .getByRole("button", { name: "Restore", exact: true })
        .click();
      await dialog.waitFor({ state: "hidden", timeout: WAIT });
    };
    const restoreFile = async (file: {
      name: string;
      path: string;
    }): Promise<void> => {
      await chooseFile(file.path);
      await confirmDialog(file.name);
    };
    const expectResult = async (expected: ParsedExport): Promise<void> => {
      const result = cyPage.getByRole("status").filter({
        has: cyPage.getByRole("heading", { name: "Restore finished" }),
      });
      await result.waitFor({ state: "visible", timeout: WAIT });
      const counts = asJson(expected.manifest.counts, "counts");
      for (const [label, value] of [
        ["Projects", counts.projects],
        ["Yarns", counts.yarns],
        ["Photos and files", counts.media_files],
      ] as const) {
        await expect(result.locator(`dt:text-is("${label}") + dd`)).toHaveText(
          String(value),
          { timeout: WAIT },
        );
      }
      check(
        (await result.getByText("Missing in the file").count()) === 0,
        "the restore reported missing files",
      );
    };

    mark("cy restores tar.gz");
    await openBackups();
    await restoreFile(tarFile);
    await expectResult(tar);
    check(
      (await listLength("/api/v2/projects")) === 2,
      "tar.gz restore project count",
    );
    check(
      (await listLength("/api/v2/yarns")) === 1,
      "tar.gz restore yarn count",
    );

    mark("cy checks the project list");
    await cyPage
      .getByRole("status")
      .getByRole("link", { name: "Projects", exact: true })
      .click();
    const card = (name: string): ReturnType<Page["locator"]> =>
      cyPage
        .locator(".project-card")
        .filter({ has: cyPage.getByRole("heading", { name, exact: true }) });
    await card("Aran Sweater").waitFor({ state: "visible", timeout: WAIT });
    await card("Borrowed Shawl").waitFor({ state: "visible", timeout: WAIT });
    check(
      (await card("Borrowed Shawl").getByTestId("owner-badge").count()) === 0,
      "the restored shared project still shows an owner badge",
    );
    const cover = card("Aran Sweater").locator("img");
    await cover.waitFor({ state: "visible", timeout: WAIT });
    await expect
      .poll(
        () => cover.evaluate((img) => (img as HTMLImageElement).naturalWidth),
        { timeout: WAIT },
      )
      .toBeGreaterThan(0);
    const coverUrl = await cover.getAttribute("src");
    check(
      coverUrl !== null && coverUrl.startsWith("/"),
      "cover has no same-origin URL",
    );
    check(
      (await cyPage.request.get(`${origin}${coverUrl}`)).status() === 200,
      "the restored cover thumbnail did not load",
    );

    mark("cy restores zip");
    await openBackups();
    await restoreFile(zipFile);
    await expectResult(zip);
    check(
      (await listLength("/api/v2/projects")) === 4,
      "zip restore did not add duplicates",
    );
    check(
      (await listLength("/api/v2/yarns")) === 2,
      "zip restore yarn duplicates",
    );
    const names = asJson(
      (await api(cyPage, "GET", "/api/v2/projects")).json,
      "list",
    ).data as Json[];
    for (const name of ["Aran Sweater", "Borrowed Shawl"]) {
      check(
        names.filter((project) => project.name === name).length === 2,
        `${name} is not listed twice after the second restore`,
      );
    }

    // 4. Errors leave nothing behind.
    mark("cy restore errors");
    await openBackups();
    const bogus = join(downloadDir(), "bogus.tar.gz");
    writeFileSync(bogus, randomBytes(2048));
    await restoreFile({ name: "bogus.tar.gz", path: bogus });
    await cyPage
      .getByRole("alert")
      .filter({ hasText: "This file is not a Stricknani download." })
      .waitFor({ state: "visible", timeout: WAIT });

    const future = join(downloadDir(), "future.tar.gz");
    const stub = (value: unknown): Buffer => Buffer.from(JSON.stringify(value));
    const topDir = "stricknani-export-2099-01-01";
    writeFileSync(
      future,
      makeTarGz([
        {
          path: `${topDir}/manifest.json`,
          data: stub({
            format: MANIFEST_FORMAT,
            format_version: 2,
            created_at: "2099-01-01T00:00:00Z",
            counts: { projects: 0, yarns: 0, categories: 0, media_files: 0 },
          }),
        },
        { path: `${topDir}/projects.json`, data: stub([]) },
        { path: `${topDir}/yarns.json`, data: stub([]) },
        { path: `${topDir}/categories.json`, data: stub([]) },
      ]),
    );
    await restoreFile({ name: "future.tar.gz", path: future });
    await cyPage
      .getByRole("alert")
      .filter({
        hasText:
          "This download is from a newer version of Stricknani. Update the server first.",
      })
      .waitFor({ state: "visible", timeout: WAIT });
    await expect(
      cyPage.getByRole("heading", { name: "Restore finished" }),
    ).toHaveCount(0);
    check(
      (await listLength("/api/v2/projects")) === 4,
      "a failed restore added projects",
    );
    check(
      (await listLength("/api/v2/yarns")) === 2,
      "a failed restore added yarns",
    );

    // 4b. Encrypted restore: ask for the passphrase, refuse a wrong one.
    mark("cy restores encrypted");
    await openBackups();
    await restoreFile({ name: encryptedName, path: encryptedPath });
    const passDialog = cyPage.getByRole("dialog", {
      name: "This file is encrypted",
    });
    await passDialog.waitFor({ state: "visible", timeout: WAIT });
    const dialogField = passDialog.getByLabel("Passphrase", { exact: true });
    await expect(dialogField).toHaveAttribute("type", "password");
    await dialogField.fill(`${secret}-wrong`);
    await passDialog
      .getByRole("button", { name: "Restore", exact: true })
      .click();
    await passDialog
      .getByText("That passphrase does not open this file.")
      .waitFor({ state: "visible", timeout: WAIT });
    check(
      (await listLength("/api/v2/projects")) === 4,
      "a wrong passphrase added projects",
    );
    await expect(dialogField).toHaveValue("", { timeout: WAIT });
    await dialogField.fill(secret);
    await passDialog
      .getByRole("button", { name: "Restore", exact: true })
      .click();
    await passDialog.waitFor({ state: "hidden", timeout: WAIT });
    await expectResult(tar);
    check(
      (await listLength("/api/v2/projects")) === 6,
      "the encrypted restore project count",
    );
    check(
      (await listLength("/api/v2/yarns")) === 3,
      "the encrypted restore yarn count",
    );

    // 5. German catalog wording on the same page.
    mark("german");
    const deContext = await browser.newContext({
      storageState: await cyContext.storageState(),
      locale: "de-DE",
      viewport: PHONE,
      isMobile: true,
      hasTouch: true,
      serviceWorkers: "block",
    });
    await deContext.addCookies([
      { name: "language", value: "de", url: origin },
    ]);
    const dePage = await pageOf(deContext);
    await dePage.goto(`${origin}/app/backups`, { waitUntil: "networkidle" });
    await dePage.getByRole("heading", { name: "Konto", level: 1 }).waitFor({
      state: "visible",
      timeout: WAIT,
    });
    await dePage
      .getByRole("heading", { name: "Deine Daten" })
      .waitFor({ timeout: WAIT });
    const archiveTypeSelect = dePage.getByRole("button", {
      name: "Archivtyp",
      exact: true,
    });
    await archiveTypeSelect.waitFor({ timeout: WAIT });
    await archiveTypeSelect.click();
    const germanArchiveListbox = dePage.getByRole("listbox", {
      name: "Archivtyp",
    });
    await expect(
      germanArchiveListbox.getByRole("option", {
        name: ".tar.gz",
        exact: true,
      }),
    ).toHaveAttribute("aria-selected", "true", { timeout: WAIT });
    await germanArchiveListbox
      .getByRole("option", { name: ".tar.gz", exact: true })
      .click();
    await dePage
      .getByRole("link", { name: "Herunterladen", exact: true })
      .waitFor({
        timeout: WAIT,
      });
    await dePage
      .getByRole("heading", { name: "Aus einer Datei wiederherstellen" })
      .waitFor({ timeout: WAIT });
    await dePage
      .getByRole("button", { name: "Datei auswählen" })
      .waitFor({ timeout: WAIT });
    await dePage
      .getByText("Formatversion 1", { exact: true })
      .waitFor({ timeout: WAIT });
    check(
      (await dePage.getByRole("heading", { name: "Your data" }).count()) === 0,
      "English wording remained in the German Your data section",
    );
    await deContext.close();

    mark("done");
    await cyContext.close();
  } finally {
    await closeBrowser(browser);
  }
}
