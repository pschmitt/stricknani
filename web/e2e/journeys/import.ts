import { closeBrowser } from "../lib/common";
// Import review journeys against a disposable Rust fixture.
// Port of ci/stricknani/web_import_browser.py.
import { chmodSync, writeFileSync } from "node:fs";
import path from "node:path";

import type {
  ConsoleMessage,
  Dialog,
  Page,
  Response,
  Route,
} from "@playwright/test";
import { errors } from "@playwright/test";

import { AcceptanceFailure, check, maskFixtureIdentity } from "../lib/common";
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

async function assertNoOverflow(page: Page, width: number): Promise<void> {
  const sizes = await page.evaluate(() => ({
    viewport: innerWidth,
    document: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
  }));
  check(
    sizes.viewport === width && sizes.document <= width && sizes.body <= width,
    `import page overflows at ${width}px`,
  );
}

const SYNTHETIC_HTML = `<!doctype html><html><head>
<title>Imported sample cardigan</title>
<script type="application/ld+json">
{"@context":"https://schema.org","@type":"Recipe",
"name":"Imported sample cardigan",
"description":"A small file-import fixture.",
"yarns":[{"name":"Fixture blue wool","brand":{"name":"Test mill"},
"weight":"100 g","length":"210 m",
"image":"https://images.example.test/yarn.jpg"}],
"recipeInstructions":[{"@type":"HowToStep","name":"Cast on",
"text":"Cast on 32 stitches."}]}
</script>
</head><body><h1>Imported sample cardigan</h1>
<p>Fixture content only.</p><img src="https://images.example.test/cover.jpg">
</body></html>`;

/** Runs the journey and returns the directory with sanitized screenshots. */
export async function runImportJourney(
  f: LiveFixture,
  cfg: E2eConfig,
): Promise<string> {
  const origin = f.origin;
  const email = f.env.STRICKNANI_TEST_EMAIL;
  const password = f.env.STRICKNANI_TEST_PASSWORD;
  const screenshots = f.screenshotDir;
  const stage = stageMarker(f.privateDir);
  const markStage = (value: string): void => stage.mark(value);

  const authStatusFile = path.join(f.privateDir, "import-auth-status.txt");
  const authStatuses: Record<"session" | "login", number | null> = {
    session: null,
    login: null,
  };
  const recordAuthStatuses = (): void => {
    const lines = (["session", "login"] as const).map(
      (name) => `${name}=${authStatuses[name] ?? "not-seen"}`,
    );
    writeFileSync(authStatusFile, `${lines.join("\n")}\n`, {
      encoding: "ascii",
      mode: 0o600,
    });
    chmodSync(authStatusFile, 0o600);
  };
  recordAuthStatuses();

  let cspErrors = 0;
  let pageErrors = 0;
  const importResponses: Array<[string, number]> = [];
  const resourceFailures: string[] = [];
  const filePreviewYarnCounts: number[] = [];

  const isImportApi = (p: string): boolean =>
    p.startsWith("/api/v2/imports/") ||
    p === "/api/v2/projects" ||
    p === "/api/v2/yarns";

  const observeResponse = async (response: Response): Promise<void> => {
    const p = pathOf(response.url());
    const status = response.status();
    if (p === "/api/v2/session") {
      authStatuses.session = status;
      recordAuthStatuses();
    } else if (p === "/api/v2/auth/login") {
      authStatuses.login = status;
      recordAuthStatuses();
    }
    if (isImportApi(p)) {
      importResponses.push([p, status]);
    }
    if (p === "/api/v2/imports/preview-file" && status === 200) {
      try {
        const payload = (await response.json()) as {
          data?: { draft?: { project?: { yarns?: unknown } } };
        };
        const yarns = payload.data?.draft?.project?.yarns;
        if (Array.isArray(yarns)) {
          filePreviewYarnCounts.push(yarns.length);
        }
      } catch {
        // Unreadable payloads only lose a diagnostic count.
      }
    }
    if (status >= 400 && isImportApi(p)) {
      resourceFailures.push(`HTTP ${status} ${p}`);
    }
  };

  const observeConsole = (message: ConsoleMessage): void => {
    if (message.type() !== "error") {
      return;
    }
    const text = message.text().toLowerCase();
    if (
      text.includes("content security policy") ||
      (text.includes("violat") &&
        (text.includes("script-src") || text.includes("style-src")))
    ) {
      cspErrors += 1;
    }
  };

  const dismissNext = (page: Page): void => {
    page.once("dialog", (dialog: Dialog) => {
      void dialog.dismiss();
    });
  };

  markStage("launching Chromium");
  const browser = await launchBrowser(cfg);
  try {
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
      reducedMotion: "reduce",
      colorScheme: "light",
      locale: "en-US",
    });
    const page = await context.newPage();
    page.on("response", (response) => {
      void observeResponse(response);
    });
    page.on("console", observeConsole);
    page.on("pageerror", () => {
      pageErrors += 1;
    });

    markStage("loading disposable login route");
    const response = await page.goto(`${origin}/app/login`, {
      waitUntil: "networkidle",
    });
    if (response === null || response.status() !== 200) {
      throw new AcceptanceFailure("login route failed");
    }
    markStage("checking strict frontend CSP");
    const policy = response.headers()["content-security-policy"] ?? "";
    check(
      policy.includes("default-src 'self'") &&
        policy.includes("script-src 'self'") &&
        policy.includes("style-src 'self'") &&
        policy.includes("style-src-attr 'unsafe-hashes'") &&
        !policy.includes("unsafe-inline") &&
        !policy.includes("unsafe-eval"),
      "strict frontend CSP is missing",
    );
    markStage("choosing English for the import journey");
    await page.getByRole("button", { name: /^(English|Englisch)$/ }).click();
    await page
      .getByRole("heading", { name: "Sign in", exact: true })
      .waitFor({ state: "visible" });
    markStage("filling disposable login email");
    await page.locator("#email").fill(email);
    markStage("filling disposable login password");
    await page.locator("#password").fill(password);
    markStage("submitting disposable login");
    try {
      await page.getByRole("button", { name: "Sign in" }).click();
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) {
        throw error;
      }
      // Do not expose Playwright's locator log or form values. These fixed
      // booleans distinguish a disabled control, locale change, invalid form
      // and client exception without capturing secrets.
      const controls: Record<string, boolean> = await page.evaluate(() => ({
        form: Boolean(document.querySelector("form.auth-form")),
        enabled: Boolean(
          document.querySelector(
            'form.auth-form button[type="submit"]:enabled',
          ),
        ),
        valid: Boolean(document.querySelector("form.auth-form:valid")),
        inert: Boolean(
          document.querySelector("form.auth-form")?.closest("[inert]"),
        ),
        english: document.documentElement.lang === "en",
        german: document.documentElement.lang === "de",
      }));
      const flags = ["form", "enabled", "valid", "inert", "english", "german"]
        .map((key) => `${key}:${controls[key] === true ? 1 : 0}`)
        .join(",");
      throw new AcceptanceFailure(
        `disposable login control timed out (${flags},` +
          `page-error:${pageErrors > 0 ? 1 : 0},` +
          `csp-error:${cspErrors > 0 ? 1 : 0})`,
      );
    }
    markStage("waiting for authenticated app shell");
    await page.locator(".app-shell").waitFor({ state: "visible" });

    markStage("checking capability-gated import options");
    await page.goto(`${origin}/app/imports`, { waitUntil: "networkidle" });
    await page
      .getByRole("heading", { name: "Import a project" })
      .waitFor({ state: "visible" });
    check(
      await page.getByRole("button", { name: "From a link" }).isDisabled(),
      "URL import was not disabled for the fixture capability",
    );
    // The AI opt-in stays on the page but greyed out, with the reason, when
    // the server has no AI capability (it used to be hidden).
    await page
      .getByText("AI import is turned off on this server.")
      .waitFor({ state: "visible" });
    const aiOptIn = page.getByLabel("Ask AI to improve extraction");
    check(
      (await aiOptIn.count()) === 1 &&
        (await aiOptIn.isDisabled()) &&
        !(await aiOptIn.isChecked()),
      "AI opt-in was usable without the AI capability",
    );

    markStage("sharing a link to the app with link imports off");
    const sharedLink = "https://www.garnstudio.com/pattern.php?id=12752&cid=9";
    const shareText = `Cosy pattern ${sharedLink}`;
    await page.goto(
      `${origin}/app/share?text=${encodeURIComponent(shareText)}`,
      { waitUntil: "networkidle" },
    );
    await page
      .getByRole("heading", { name: "Link import is off" })
      .waitFor({ state: "visible" });
    check(
      (await page.getByTestId("share-project").count()) === 0,
      "the share chooser was offered with link imports off",
    );
    await page.goto(
      `${origin}/app/share?text=${encodeURIComponent("no link here")}`,
      { waitUntil: "networkidle" },
    );
    await page
      .getByRole("heading", { name: "No link found" })
      .waitFor({ state: "visible" });

    markStage("sharing a link with link imports on prefills the import page");
    // The fixture server has link imports off: report them on for this check.
    await page.route("**/api/v2/session", async (route) => {
      const upstream = await route.fetch();
      const body = (await upstream.json()) as {
        data?: { capabilities?: Record<string, boolean> };
      };
      if (body.data?.capabilities) {
        body.data.capabilities.external_imports = true;
      }
      await route.fulfill({ response: upstream, json: body });
    });
    await page.goto(
      `${origin}/app/share?text=${encodeURIComponent(shareText)}`,
      { waitUntil: "networkidle" },
    );
    await page
      .getByRole("heading", {
        name: "Import this link as a project or as a yarn?",
      })
      .waitFor({ state: "visible" });
    check(
      (await page.getByTestId("share-host").innerText()) === "garnstudio.com",
      "the share chooser did not show the link's host",
    );
    await page.getByTestId("share-project").click();
    await page
      .getByRole("heading", { name: "Import a project" })
      .waitFor({ state: "visible" });
    const prefilled = page.getByLabel("Pattern page URL");
    await prefilled.waitFor({ state: "visible" });
    check(
      (await prefilled.inputValue()) === sharedLink,
      "the shared link did not prefill the project import",
    );
    check(
      importResponses.filter(([p]) => p === "/api/v2/imports/preview")
        .length === 0,
      "choosing a share started an import without confirmation",
    );
    await page.goto(
      `${origin}/app/share?url=${encodeURIComponent(sharedLink)}`,
      { waitUntil: "networkidle" },
    );
    await page.getByTestId("share-yarn").click();
    const yarnPrefilled = page.getByLabel("Yarn page URL");
    await yarnPrefilled.waitFor({ state: "visible" });
    check(
      (await yarnPrefilled.inputValue()) === sharedLink,
      "the shared link did not prefill the yarn import",
    );
    await page.unroute("**/api/v2/session");
    await page.goto(`${origin}/app/imports`, { waitUntil: "networkidle" });

    markStage("previewing text and checking review fields");
    const sourceText =
      "Cozy import fixture\n" +
      "A reviewed pattern for a small test swatch.\n" +
      "Cast on 48 stitches. Work four rows in garter stitch.";
    await page.getByLabel("Pattern text or instructions").fill(sourceText);
    await page.getByRole("button", { name: "Preview and review" }).click();
    await page
      .getByRole("heading", { name: "Review and correct" })
      .waitFor({ state: "visible" });
    const projectName = page.getByLabel("Project name");
    await projectName.waitFor({ state: "visible" });
    check(
      Boolean(await projectName.inputValue()),
      "text preview did not extract a title",
    );

    markStage("checking source and draft preservation after network failure");
    await projectName.fill("Edited title retained after failure");
    await page
      .getByLabel("Pattern text or instructions")
      .fill(`${sourceText}\nUpdated source.`);
    markStage("cancelling replacement of a dirty review");
    dismissNext(page);
    await page.getByRole("button", { name: "Preview and review" }).click();
    check(
      (await projectName.inputValue()) ===
        "Edited title retained after failure" &&
        (await page.getByLabel("Pattern text or instructions").inputValue()) ===
          `${sourceText}\nUpdated source.` &&
        (await page
          .getByRole("heading", { name: "Review and correct" })
          .isVisible()),
      "cancelling review replacement discarded the existing draft",
    );
    let failedOnce = false;
    const failNextPreview = async (route: Route): Promise<void> => {
      if (!failedOnce) {
        failedOnce = true;
        await route.abort();
      } else {
        await route.continue();
      }
    };
    await page.route("**/api/v2/imports/preview", failNextPreview);
    page.once("dialog", (dialog: Dialog) => {
      void dialog.accept();
    });
    await page.getByRole("button", { name: "Preview and review" }).click();
    await page.getByRole("alert").waitFor({ state: "visible" });
    check(failedOnce, "network failure route was not exercised");
    check(
      (await projectName.inputValue()) ===
        "Edited title retained after failure" &&
        (await page.getByLabel("Pattern text or instructions").inputValue()) ===
          `${sourceText}\nUpdated source.` &&
        (await page
          .getByRole("heading", { name: "Review and correct" })
          .isVisible()),
      "a failed preview discarded its current source or reviewed draft",
    );
    await page.unroute("**/api/v2/imports/preview", failNextPreview);

    markStage("checking the unsaved import navigation guard");
    dismissNext(page);
    await page
      .locator(".mobile-navigation")
      .getByRole("link", { name: "Yarns", exact: true })
      .click();
    check(
      pathOf(page.url()) === "/app/imports" &&
        (await projectName.inputValue()) ===
          "Edited title retained after failure",
      "dismissed navigation warning did not preserve the import review",
    );

    markStage("clearing the required project name");
    await projectName.fill("");
    markStage("submitting project validation");
    await page.getByRole("button", { name: "Create project" }).click();
    markStage("waiting for project validation feedback");
    await page.getByRole("alert").waitFor({ state: "visible" });
    markStage("checking project review remains after validation");
    const validationState = await page.evaluate(() => ({
      path: location.pathname,
      review: document.querySelector(".review-card") !== null,
      name: document.querySelector("input[required]") !== null,
      alert: document.querySelector("[role=alert]") !== null,
    }));
    check(
      validationState.path === "/app/imports" &&
        validationState.review &&
        validationState.name &&
        validationState.alert,
      "validation feedback did not preserve the reviewed draft " +
        `(review=${validationState.review}, ` +
        `name=${validationState.name}, ` +
        `alert=${validationState.alert})`,
    );
    markStage("restoring project title after validation");
    await projectName.fill("Reviewed import swatch");
    markStage("saving import notes before project create");
    const noteFields = page.getByLabel("Notes", { exact: true });
    check(
      (await noteFields.count()) === 1,
      "review page must render exactly one notes field",
    );
    await noteFields.fill("Created after checking every imported field.");
    for (const width of [320, 390, 1280]) {
      await page.setViewportSize({ width, height: 844 });
      await assertNoOverflow(page, width);
      const targetHeights: number[] = await page
        .locator(".source-tabs button, .primary, .secondary")
        .evaluateAll((nodes) =>
          nodes
            .filter((node) => node.getClientRects().length > 0)
            .map((node) => node.getBoundingClientRect().height),
        );
      check(
        targetHeights.length === 0 || Math.min(...targetHeights) >= 44,
        `an import action target is below 44px at ${width}px`,
      );
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await maskFixtureIdentity(page);
    await page.keyboard.press("Escape");
    const reviewHeading = page.getByRole("heading", {
      name: "Review and correct",
    });
    await reviewHeading.evaluate((element) => {
      element.tabIndex = -1;
      element.focus();
    });
    await reviewHeading.scrollIntoViewIfNeeded();
    await page.evaluate(() => {
      window.scrollBy(0, -80);
    });
    await page.screenshot({
      path: path.join(screenshots, "import-review-mobile.png"),
      caret: "initial",
    });

    markStage("checking save action clears the mobile navigation");
    const createButton = page.getByRole("button", { name: "Create project" });
    await createButton.scrollIntoViewIfNeeded();
    const createRect = await createButton.boundingBox();
    const navigationRect = await page
      .locator(".mobile-navigation")
      .boundingBox();
    check(
      createRect !== null &&
        navigationRect !== null &&
        createRect.height >= 44 &&
        createRect.y + createRect.height <= navigationRect.y,
      "the create action is obscured by mobile navigation",
    );
    await page.screenshot({
      path: path.join(screenshots, "import-form-bottom-mobile.png"),
      caret: "initial",
    });

    markStage("creating reviewed project through API v2");
    // Delay the real API response to cover the in-flight mutation path.
    // Fields should be locked while saving, and a navigation attempt
    // must still preserve the reviewed draft until the request settles.
    const cdp = await context.newCDPSession(page);
    await cdp.send("Network.enable");
    await cdp.send("Network.emulateNetworkConditions", {
      offline: false,
      latency: 900,
      downloadThroughput: 1_000_000,
      uploadThroughput: 1_000_000,
      connectionType: "cellular3g",
    });
    await createButton.click();
    check(
      await projectName.isDisabled(),
      "review fields remained editable during project creation",
    );
    dismissNext(page);
    await page
      .locator(".mobile-navigation")
      .getByRole("link", { name: "Yarns", exact: true })
      .click();
    check(
      pathOf(page.url()) === "/app/imports",
      "dismissed navigation warning left the pending import",
    );
    await page
      .getByRole("link", { name: "Open created project" })
      .waitFor({ state: "visible" });
    await cdp.send("Network.emulateNetworkConditions", {
      offline: false,
      latency: 0,
      downloadThroughput: -1,
      uploadThroughput: -1,
    });
    await cdp.detach();
    check(
      importResponses.some(
        ([p, status]) => p === "/api/v2/projects" && status === 201,
      ),
      "reviewed project was not created through API v2",
    );

    markStage("starting a second import");
    await page.getByRole("button", { name: "Start another import" }).click();
    markStage("choosing the file source");
    await page.getByRole("button", { name: "Upload file" }).click();
    markStage("selecting synthetic HTML file");
    await page.getByLabel("Choose a file").setInputFiles({
      name: "sample.html",
      mimeType: "text/html",
      buffer: Buffer.from(SYNTHETIC_HTML, "utf-8"),
    });
    markStage("submitting HTML file preview");
    await page.getByRole("button", { name: "Preview and review" }).click();
    markStage("waiting for HTML file review");
    try {
      await page
        .getByRole("heading", { name: "Review and correct" })
        .waitFor({ state: "visible" });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) {
        throw error;
      }
      const fileStatuses = importResponses
        .filter(([p]) => p === "/api/v2/imports/preview-file")
        .map(([, status]) => status);
      const fileStatus =
        fileStatuses.length > 0
          ? String(fileStatuses[fileStatuses.length - 1])
          : "not observed";
      throw new AcceptanceFailure(
        `HTML file preview did not show review (API status ${fileStatus})`,
      );
    }
    markStage("checking extracted yarn in HTML review");
    try {
      await page
        .getByRole("heading", { name: "Yarn for this project" })
        .waitFor({ state: "visible" });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) {
        throw error;
      }
      const count =
        filePreviewYarnCounts.length > 0
          ? String(filePreviewYarnCounts[filePreviewYarnCounts.length - 1])
          : "unavailable";
      throw new AcceptanceFailure(
        `HTML preview did not render imported yarn details (count ${count})`,
      );
    }
    markStage("checking the extracted yarn name field");
    const importedYarnName = page
      .locator(".yarn-card input:not([type='radio']):not([type='search'])")
      .first();
    await importedYarnName.waitFor({ state: "visible" });
    check(
      (await importedYarnName.inputValue()) === "Fixture blue wool",
      "HTML import did not populate the editable yarn name",
    );
    check(
      (await page.getByText("External images are disabled.").count()) > 0,
      "disabled external image capability was not explained",
    );
    check(
      await page
        .getByRole("radio", { name: "Create a new yarn" })
        .first()
        .isChecked(),
      "a yarn without a stash match did not default to creating it",
    );
    await page.getByRole("button", { name: "Create project" }).click();
    await page
      .getByRole("link", { name: "Open created project" })
      .waitFor({ state: "visible" });
    check(
      importResponses.filter(
        ([p, status]) => p === "/api/v2/projects" && status === 201,
      ).length >= 2,
      "imported project (with its new yarn) was not created through API v2",
    );

    check(cspErrors === 0, "browser reported a CSP violation");
    check(pageErrors === 0, "browser reported an uncaught page exception");
    check(
      resourceFailures.length === 0,
      `an API resource returned an unexpected error (${resourceFailures.join(", ")})`,
    );
    return screenshots;
  } finally {
    await closeBrowser(browser);
  }
}
