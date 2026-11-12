// Sharing and ownership transfer journey against a disposable Rust server:
// three accounts on a phone viewport. Olga owns, Bea receives, Carl takes over.
import { randomBytes } from "node:crypto";
import { mkdirSync } from "node:fs";
import { join } from "node:path";

import {
  expect,
  type BrowserContext,
  type Locator,
  type Page,
  type Route,
} from "@playwright/test";

import {
  assertNoHorizontalOverflow,
  check,
  closeBrowser,
  fn,
  waitTwoAnimationFrames,
} from "../lib/common";
import { maskGithubSecret } from "../lib/fixture";
import {
  launchBrowser,
  stageMarker,
  type E2eConfig,
  type LiveFixture,
} from "../lib/harness";
import { createFixtureAdmin } from "./admin";
import { detailMenuItem } from "../lib/detail-menu";

const PHONE = { width: 390, height: 844 };

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
}

function account(name: string): Account {
  const password = `A shared password ${randomBytes(12).toString("base64url")}`;
  maskGithubSecret(password);
  return { email: `${name}@example.invalid`, password, name };
}

async function phonePage(
  f: LiveFixture,
  context: BrowserContext,
): Promise<Page> {
  const page = await context.newPage();
  await guardLoopback(page, f.origin);
  return page;
}

function yarnBody(name: string): Json {
  return {
    name,
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
  };
}

function projectBody(name: string, yarnIds: number[]): Json {
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
  };
}

async function openShareDialog(page: Page): Promise<Locator> {
  const menu = await actionsMenu(page);
  await menu.getByRole("menuitem", { name: "Share", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Share", exact: true });
  await dialog.waitFor({ state: "visible" });
  await dialog.getByRole("list", { name: "People with access" }).waitFor();
  return dialog;
}

async function pickPerson(dialog: Locator, name: string): Promise<void> {
  await dialog.getByLabel("Find people").fill(name);
  const list = dialog.getByRole("list", { name: "People", exact: true });
  const person = list.getByRole("button", { name: new RegExp(name, "i") });
  await person.first().waitFor();
  // Right after typing, only matches are listed (no stale directory under the query).
  const names = await list.getByRole("button").allTextContents();
  check(
    names.length > 0 &&
      names.every((value) => value.toLowerCase().includes(name.toLowerCase())),
    `the picker listed non-matches for "${name}": ${names.join(", ")}`,
  );
  await person.first().click();
  // A chosen person replaces the list, with a way to change the choice.
  await dialog.getByText("Selected person").waitFor();
  check(
    (await dialog
      .getByRole("list", { name: "People", exact: true })
      .count()) === 0,
    "the list stayed under the chosen person",
  );
  await dialog.getByRole("button", { name: "Change" }).waitFor();
}

/** A recipient who cannot edit must find no way into an editor. */
async function assertNoEditorEntry(page: Page, what: string): Promise<void> {
  const editLinks = await page.locator('a[href*="/edit"]').count();
  check(editLinks === 0, `${what} had ${editLinks} link(s) into an editor`);
  check(
    (await page.getByText("Add a note").count()) === 0,
    `${what} offered Add a note`,
  );
  check(
    (await page.getByRole("button", { name: /archive|recognize/i }).count()) ===
      0,
    `${what} offered an owner-only media action`,
  );
}

async function shareWith(
  page: Page,
  name: string,
  role: "viewer" | "editor",
): Promise<void> {
  const dialog = await openShareDialog(page);
  await pickPerson(dialog, name);
  if (role === "editor")
    await dialog.getByRole("radio", { name: "Can edit", exact: true }).click();
  await dialog
    .getByRole("button", { name: new RegExp(`Share with ${name}`, "i") })
    .click();
  await dialog.getByText(new RegExp(`Shared with ${name}\\.`, "i")).waitFor();
  await dialog.getByRole("button", { name: "Done" }).click();
  await dialog.waitFor({ state: "hidden" });
}

async function actionsMenu(page: Page): Promise<Locator> {
  await page.locator('summary[aria-label="More actions"]').click();
  return page.getByRole("menu", { name: /actions$/ });
}

async function closeMenu(page: Page): Promise<void> {
  await page.keyboard.press("Escape");
}

/** Without manage rights, Share has no dialog to open: it only shares the link. */
async function assertShareHasNoDialog(
  page: Page,
  message: string,
): Promise<void> {
  const menu = await actionsMenu(page);
  await menu.getByRole("menuitem", { name: "Share", exact: true }).click();
  await page.waitForTimeout(400);
  check(
    (await page.getByRole("dialog", { name: "Share", exact: true }).count()) ===
      0,
    message,
  );
}

export async function runSharingJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<void> {
  const origin = f.origin;
  const stage = stageMarker(f.privateDir);
  const mark = (value: string): void => stage.mark(value);
  const shots = process.env.STRICKNANI_SHARING_SHOTS;
  if (shots) mkdirSync(shots, { recursive: true });
  const shoot = async (page: Page, name: string): Promise<void> => {
    if (!shots) return;
    await waitTwoAnimationFrames(page);
    await page.screenshot({ path: join(shots, name) });
  };

  const admin = await createFixtureAdmin(f, cfg);
  const olga = account("olga");
  const bea = account("bea");
  const carl = account("carl");

  const browser = await launchBrowser(cfg);
  try {
    const context = async (): Promise<BrowserContext> =>
      browser.newContext({
        locale: "en-US",
        viewport: PHONE,
        isMobile: true,
        hasTouch: true,
        serviceWorkers: "block",
      });

    mark("create accounts");
    const adminContext = await context();
    const adminPage = await phonePage(f, adminContext);
    await signIn(adminPage, origin, admin.email, admin.password);
    for (const person of [olga, bea, carl]) {
      dataOf(
        await api(adminPage, "POST", "/api/v2/admin/users", {
          email: person.email,
          password: person.password,
          is_admin: false,
        }),
        201,
        `create ${person.name}`,
      );
    }
    await adminContext.close();

    mark("olga data");
    const olgaContext = await context();
    const olgaPage = await phonePage(f, olgaContext);
    await signIn(olgaPage, origin, olga.email, olga.password);
    const yarnA = idOf(
      dataOf(
        await api(olgaPage, "POST", "/api/v2/yarns", yarnBody("Sky Merino")),
        201,
        "yarn A",
      ),
      "yarn A",
    );
    const yarnB = idOf(
      dataOf(
        await api(olgaPage, "POST", "/api/v2/yarns", yarnBody("Harbor Wool")),
        201,
        "yarn B",
      ),
      "yarn B",
    );
    dataOf(
      await api(olgaPage, "POST", "/api/v2/yarns", yarnBody("Alpaca Loop")),
      201,
      "yarn C",
    );
    const project1 = idOf(
      dataOf(
        await api(
          olgaPage,
          "POST",
          "/api/v2/projects",
          projectBody("Shared Cardigan", [yarnA]),
        ),
        201,
        "project 1",
      ),
      "project 1",
    );
    const project2 = idOf(
      dataOf(
        await api(
          olgaPage,
          "POST",
          "/api/v2/projects",
          projectBody("Moving Mittens", [yarnB]),
        ),
        201,
        "project 2",
      ),
      "project 2",
    );

    mark("bea signs in");
    const beaContext = await context();
    const beaPage = await phonePage(f, beaContext);
    await signIn(beaPage, origin, bea.email, bea.password);
    const candidateRequests: string[] = [];
    beaPage.on("request", (request) => {
      if (request.url().includes("/yarn-candidates"))
        candidateRequests.push(request.url());
    });

    // 1. Share as viewer.
    mark("share as viewer");
    await olgaPage.goto(`${origin}/app/projects/${project1}`, {
      waitUntil: "networkidle",
    });
    let dialog = await openShareDialog(olgaPage);
    await dialog.getByRole("button", { name: "Share link" }).waitFor();
    await dialog
      .getByText("Opens for people who already have access.", { exact: false })
      .waitFor();
    await dialog
      .getByText(
        "People you share this with can also see its linked yarns, read-only.",
      )
      .waitFor();
    await dialog.getByText("1 linked yarn", { exact: true }).waitFor();
    await dialog.getByText("Owner", { exact: true }).waitFor();
    await pickPerson(dialog, "bea");
    check(
      (await dialog
        .getByRole("radio", { name: "Can view", exact: true })
        .getAttribute("aria-checked")) === "true",
      "the new share did not default to Can view",
    );
    await shoot(olgaPage, "share-dialog.png");
    await dialog.getByRole("button", { name: /Share with bea/i }).click();
    await dialog.getByText(/Shared with bea\./i).waitFor();
    const roleSelect = dialog.getByLabel(/Role of bea/i);
    check((await roleSelect.inputValue()) === "viewer", "bea is not a viewer");
    // The same person is no longer offered again.
    await dialog.getByLabel("Find people").fill("bea");
    await olgaPage.waitForTimeout(600);
    check(
      (await dialog
        .getByRole("list", { name: "People", exact: true })
        .count()) === 0,
      "an existing recipient was offered again",
    );
    await assertNoHorizontalOverflow(olgaPage, PHONE.width);
    await dialog.getByRole("button", { name: "Done" }).click();
    // The dialog closed from a menu item that is gone: focus returns to the
    // menu button, and the page no longer carries a standalone Share button.
    check(
      await olgaPage.evaluate(
        fn(
          `() => document.activeElement?.getAttribute("aria-label") === "More actions"`,
        ),
      ),
      "focus did not return to the More actions button",
    );
    check(
      (await olgaPage
        .getByRole("button", { name: "Share", exact: true })
        .count()) === 0,
      "the standalone Share button is still on the page",
    );
    await shoot(olgaPage, "project-without-share-button.png");
    await olgaPage.locator('summary[aria-label="More actions"]').click();
    await shoot(olgaPage, "project-menu.png");
    await closeMenu(olgaPage);

    // 2. The viewer sees a read-only project and can leave.
    mark("viewer view");
    await beaPage.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
    const card = beaPage.getByRole("link", { name: /Shared Cardigan/ }).first();
    await card.waitFor({ state: "visible" });
    await beaPage
      .getByTestId("owner-badge")
      .filter({ hasText: /olga/i })
      .first()
      .waitFor();
    await beaPage.goto(`${origin}/app/projects/${project1}`, {
      waitUntil: "networkidle",
    });
    const notice = beaPage.getByTestId("access-notice");
    await notice.waitFor({ state: "visible" });
    const recipientLine = (
      (await notice.locator(".user-label__name").first().textContent()) ?? ""
    ).trim();
    const recipientText = ((await notice.textContent()) ?? "").trim();
    check(
      recipientLine.toLowerCase() === "olga" &&
        /Shared by\s*\S?\s*olga · Can view/i.test(recipientText),
      `the recipient line is wrong: ${recipientText}`,
    );
    check(
      (await beaPage.getByRole("link", { name: "Edit project" }).count()) === 0,
      "a viewer saw Edit project",
    );
    await assertNoEditorEntry(beaPage, "a project viewer");
    await assertShareHasNoDialog(beaPage, "a viewer saw Share");
    let menu = await actionsMenu(beaPage);
    check(
      (await menu.getByRole("menuitem", { name: "Delete project" }).count()) ===
        0,
      "a viewer saw Delete project",
    );
    await closeMenu(beaPage);
    await notice.getByRole("button", { name: "Leave" }).waitFor();
    await shoot(beaPage, "recipient-view.png");
    await beaPage.goto(`${origin}/app/projects/${project1}/edit`, {
      waitUntil: "networkidle",
    });
    await beaPage
      .getByText("You can view this project but not edit it.")
      .waitFor();

    // 3. The linked yarn opens read-only through the project.
    mark("derived yarn");
    await beaPage.goto(`${origin}/app/yarns/${yarnA}`, {
      waitUntil: "networkidle",
    });
    const yarnNotice = beaPage.getByTestId("access-notice");
    await yarnNotice.waitFor({ state: "visible" });
    await yarnNotice.getByText("Visible through a shared project").waitFor();
    check(
      (await yarnNotice.getByRole("button", { name: "Leave" }).count()) === 0,
      "a derived yarn offered Leave",
    );
    check(
      (await beaPage.getByRole("link", { name: "Edit yarn" }).count()) === 0,
      "a derived yarn offered Edit yarn",
    );
    await assertNoEditorEntry(beaPage, "a derived yarn viewer");
    await assertShareHasNoDialog(beaPage, "a derived yarn offered Share");
    menu = await actionsMenu(beaPage);
    check(
      (await menu.getByRole("menuitem", { name: "Delete yarn" }).count()) === 0,
      "a derived yarn offered Delete yarn",
    );
    await closeMenu(beaPage);
    await beaPage.goto(`${origin}/app/yarns/${yarnA}/edit`, {
      waitUntil: "networkidle",
    });
    await beaPage
      .getByText("You can view this yarn but not edit it.")
      .waitFor();
    // Nothing else of Olga's is visible: the other project and yarn stay hidden.
    const hidden = await api(beaPage, "GET", `/api/v2/projects/${project2}`);
    check(hidden.status === 404, "an unshared project was visible");

    // 4. Raise the role: the editor edits but cannot share, transfer or delete.
    mark("share as editor");
    await olgaPage.goto(`${origin}/app/projects/${project1}`, {
      waitUntil: "networkidle",
    });
    dialog = await openShareDialog(olgaPage);
    await dialog.getByLabel(/Role of bea/i).selectOption("editor");
    await dialog.getByText(/Role updated for bea\./i).waitFor();
    await dialog.getByRole("button", { name: "Done" }).click();
    await beaPage.goto(`${origin}/app/projects/${project1}`, {
      waitUntil: "networkidle",
    });
    await beaPage.getByRole("link", { name: "Edit project" }).waitFor();
    check(
      (
        (await beaPage.getByTestId("access-notice").textContent()) ?? ""
      ).includes("Can edit"),
      "the editor line is wrong",
    );
    await assertShareHasNoDialog(beaPage, "an editor saw Share");
    menu = await actionsMenu(beaPage);
    check(
      (await menu.getByRole("menuitem", { name: "Delete project" }).count()) ===
        0,
      "an editor saw Delete project",
    );
    await closeMenu(beaPage);

    // The editor links only the owner's yarns, through the candidates list.
    mark("editor yarn picker");
    await beaPage.goto(`${origin}/app/projects/${project1}/edit`, {
      waitUntil: "networkidle",
    });
    check(
      (await beaPage
        .getByRole("button", { name: "Delete project" })
        .count()) === 0,
      "an editor saw the delete zone",
    );
    await beaPage.getByLabel("Find yarn").fill("Alpaca");
    await beaPage.getByRole("button", { name: "Search", exact: true }).click();
    await beaPage.getByText("Alpaca Loop").waitFor();
    check(
      candidateRequests.length > 0,
      "the editor did not use yarn-candidates",
    );

    // 5. Revocation mid-session: the next action lands on the list with a notice.
    mark("revoke mid session");
    await beaPage.goto(`${origin}/app/projects/${project1}`, {
      waitUntil: "networkidle",
    });
    await beaPage.getByTestId("access-notice").waitFor();
    await olgaPage.goto(`${origin}/app/projects/${project1}`, {
      waitUntil: "networkidle",
    });
    dialog = await openShareDialog(olgaPage);
    await dialog.getByRole("button", { name: /Remove bea/i }).click();
    await dialog.getByText(/bea no longer has access\./i).waitFor();
    await dialog.getByRole("button", { name: "Done" }).click();
    // Favorite is in the ⋮ menu of the project page.
    await (await detailMenuItem(beaPage, /favorites$/)).click();
    await beaPage.waitForURL(/\/app\/projects\/?$/);
    await beaPage
      .getByText("You no longer have access to this project.")
      .waitFor();

    // 6. A recipient leaves on their own.
    mark("leave");
    await shareWith(olgaPage, "bea", "viewer");
    await beaPage.goto(`${origin}/app/projects/${project1}`, {
      waitUntil: "networkidle",
    });
    await beaPage
      .getByTestId("access-notice")
      .getByRole("button", { name: "Leave" })
      .click();
    const leave = beaPage.getByRole("dialog", { name: "Leave this project?" });
    await leave.getByRole("button", { name: "Leave" }).click();
    await beaPage.waitForURL(/\/app\/projects\/?$/);
    await beaPage.getByText("You left the project.").waitFor();
    check(
      (await api(beaPage, "GET", `/api/v2/projects/${project1}`)).status ===
        404,
      "a recipient could still read the project after leaving",
    );

    // 7. Transfer without the yarns: the previous owner stays an editor.
    mark("transfer without yarns");
    await olgaPage.goto(`${origin}/app/projects/${project1}`, {
      waitUntil: "networkidle",
    });
    dialog = await openShareDialog(olgaPage);
    await dialog.getByRole("button", { name: "Transfer ownership" }).click();
    const transfer = olgaPage.getByRole("dialog", {
      name: "Transfer ownership",
    });
    await transfer.waitFor({ state: "visible" });
    const moveBox = transfer.getByRole("checkbox", {
      name: "Also move 1 linked yarn",
    });
    await moveBox.waitFor();
    check(!(await moveBox.isChecked()), "moving yarns was on by default");
    await pickPerson(transfer, "bea");
    await shoot(olgaPage, "transfer-confirmation.png");
    await transfer.getByRole("button", { name: /Transfer to bea/i }).click();
    await olgaPage.getByTestId("access-notice").waitFor({ state: "visible" });
    const editorNotice = olgaPage.getByTestId("access-notice");
    const editorLine = ((await editorNotice.textContent()) ?? "").trim();
    check(
      (await editorNotice.locator(".user-label__name").first().textContent())
        ?.trim()
        .toLowerCase() === "bea" &&
        /Shared by\s*\S?\s*bea · Can edit/i.test(editorLine),
      `the previous owner is not an editor: ${editorLine}`,
    );
    await assertShareHasNoDialog(
      olgaPage,
      "the previous owner still saw Share",
    );
    const kept = dataOf(
      await api(olgaPage, "GET", `/api/v2/yarns/${yarnA}`),
      200,
      "yarn A",
    );
    check(
      (kept.access as Json).role === "owner",
      "the linked yarn moved although it was not requested",
    );
    const moved1 = dataOf(
      await api(beaPage, "GET", `/api/v2/projects/${project1}`),
      200,
      "project 1 as bea",
    );
    check(
      (moved1.access as Json).role === "owner",
      "bea does not own the project",
    );

    // 8. Transfer with the yarns.
    mark("transfer with yarns");
    await olgaPage.goto(`${origin}/app/projects/${project2}`, {
      waitUntil: "networkidle",
    });
    dialog = await openShareDialog(olgaPage);
    await dialog.getByRole("button", { name: "Transfer ownership" }).click();
    const transfer2 = olgaPage.getByRole("dialog", {
      name: "Transfer ownership",
    });
    await transfer2
      .getByRole("checkbox", { name: "Also move 1 linked yarn" })
      .check();
    await pickPerson(transfer2, "carl");
    await transfer2.getByRole("button", { name: /Transfer to carl/i }).click();
    await olgaPage.getByTestId("access-notice").waitFor({ state: "visible" });
    const movedYarn = dataOf(
      await api(olgaPage, "GET", `/api/v2/yarns/${yarnB}`),
      200,
      "yarn B",
    );
    check(
      (movedYarn.access as Json).role === "editor",
      "the moved yarn did not leave an editor share",
    );
    await assertNoHorizontalOverflow(olgaPage, PHONE.width);

    // Desktop width and German text on the phone-width dialogs.
    mark("desktop and german");
    const project3 = idOf(
      dataOf(
        await api(
          olgaPage,
          "POST",
          "/api/v2/projects",
          projectBody("Desktop Check", []),
        ),
        201,
        "project 3",
      ),
      "project 3",
    );
    await olgaPage.setViewportSize({ width: 1280, height: 900 });
    await olgaPage.goto(`${origin}/app/projects/${project3}`, {
      waitUntil: "networkidle",
    });
    dialog = await openShareDialog(olgaPage);
    await assertNoHorizontalOverflow(olgaPage, 1280);
    await shoot(olgaPage, "share-dialog-desktop.png");
    await dialog.getByRole("button", { name: "Done" }).click();
    await olgaPage.setViewportSize(PHONE);

    const germanContext = await browser.newContext({
      storageState: await olgaContext.storageState(),
      locale: "de-DE",
      viewport: { width: 320, height: 640 },
      isMobile: true,
      hasTouch: true,
      serviceWorkers: "block",
    });
    const dePage = await phonePage(f, germanContext);
    await germanContext.addCookies([
      { name: "language", value: "de", url: origin },
    ]);
    await dePage.goto(`${origin}/app/projects/${project3}`, {
      waitUntil: "networkidle",
    });
    await dePage.locator('summary[aria-label="Weitere Aktionen"]').click();
    await dePage.getByRole("menuitem", { name: "Teilen", exact: true }).click();
    const deDialog = dePage.getByRole("dialog", { name: "Teilen" });
    await deDialog.waitFor({ state: "visible" });
    await deDialog
      .getByRole("list", { name: /Personen mit Zugriff/ })
      .waitFor();
    await deDialog.getByLabel("Personen suchen").fill("bea");
    await deDialog
      .getByRole("list", { name: "Personen", exact: true })
      .getByRole("button")
      .first()
      .click();
    const dialogOverflow = async (label: string): Promise<void> => {
      await waitTwoAnimationFrames(dePage);
      const widths = await dePage.evaluate(
        fn(`() => {
          const open = document.querySelector("dialog[open]:last-of-type");
          return {
            dialog: open ? open.scrollWidth - open.clientWidth : -1,
            page: document.documentElement.scrollWidth - window.innerWidth,
          };
        }`),
      );
      check(
        widths.dialog <= 0 && widths.page <= 0,
        `German ${label} overflowed: ${JSON.stringify(widths)}`,
      );
    };
    await dialogOverflow("share dialog");
    await shoot(dePage, "share-dialog-de-320.png");
    await deDialog.getByRole("button", { name: "Besitz übertragen" }).click();
    const deTransfer = dePage.getByRole("dialog", {
      name: "Besitz übertragen",
    });
    await deTransfer.waitFor({ state: "visible" });
    await deTransfer.getByLabel("Personen suchen").fill("carl");
    await deTransfer
      .getByRole("list", { name: "Personen", exact: true })
      .getByRole("button")
      .first()
      .click();
    await dialogOverflow("transfer dialog");
    await shoot(dePage, "transfer-confirmation-de-320.png");
    await germanContext.close();

    mark("done");
    await olgaContext.close();
    await beaContext.close();
  } finally {
    await closeBrowser(browser);
  }
}
