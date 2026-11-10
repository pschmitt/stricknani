import { closeBrowser } from "../lib/common";
// Web UI browser journey (port of ci/stricknani/web_ui_browser.py
// run_live_fixture/run_acceptance).
//
// This file holds the orchestration (`runUiJourney`) and part A (`uiPartA`):
// argument handling, browser/context setup, anonymous sign-in, signup branch,
// disabled-signup route, login, library filters/favorites and the
// authenticated responsive layout. Part B (`uiPartB`, ./ui-reader) continues
// at the project reader stage and part C (`uiPartC`, ./ui-editor) at the
// editor stage through the end of the run.
import { chmodSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { errors } from "@playwright/test";
import type { Locator, Response } from "@playwright/test";
import {
  AcceptanceFailure,
  assertNoHorizontalOverflow,
  check,
  maskFixtureIdentity,
  playwrightErrorCategory,
  responsePath,
  fn,
} from "../lib/common";
import {
  launchBrowser,
  stageMarker,
  type E2eConfig,
  type LiveFixture,
} from "../lib/harness";
import {
  accountSignOutButton,
  assertMobileAppHeader,
  checkMobileLanguageAndScreenshots,
  closeLibraryFilters,
  openAccountFromProfile,
  openLibraryFilters,
  prepareProjectReaderFixture,
  prepareScreenshot,
  visibleHeights,
  waitForLibraryFiltersClosed,
} from "../lib/ui-helpers";
import {
  createUiState,
  observeConsole,
  observePageError,
  observeRequest,
  observeResponse,
  queryIs,
  settleObservers,
  urlPath,
  type UiCtx,
  type UiJourneyOptions,
} from "../lib/ui-state";
import { uiPartB } from "./ui-reader";
import { uiPartC } from "./ui-editor";
import { detailMenuItem } from "../lib/detail-menu";

export type { UiJourneyOptions } from "../lib/ui-state";

/** python json.dumps(value, ensure_ascii=True, sort_keys=True). */
function sortedJson(value: unknown): string {
  const sorted = (input: unknown): unknown => {
    if (Array.isArray(input)) {
      return input.map(sorted);
    }
    if (typeof input === "object" && input !== null) {
      const record = input as Record<string, unknown>;
      return Object.fromEntries(
        Object.keys(record)
          .sort()
          .map((key) => [key, sorted(record[key])]),
      );
    }
    return input;
  };
  return JSON.stringify(sorted(value)).replace(
    /[\u0080-￿]/g,
    (char) => `\\u${char.charCodeAt(0).toString(16).padStart(4, "0")}`,
  );
}

function randomHex(bytes: number): string {
  let out = "";
  for (let index = 0; index < bytes; index += 1) {
    out += Math.floor(Math.random() * 256)
      .toString(16)
      .padStart(2, "0");
  }
  return out;
}

function randomUrlSafe(bytes: number): string {
  return Buffer.from(
    Array.from({ length: bytes }, () => Math.floor(Math.random() * 256)),
  ).toString("base64url");
}

/**
 * python run_acceptance wrapper: builds the shared state, launches the
 * browser, runs parts A, B and C and always closes the browser.
 */
export async function runUiJourney(
  f: LiveFixture,
  cfg: E2eConfig,
  o: UiJourneyOptions,
): Promise<void> {
  const origin = f.origin;
  const email = f.env.STRICKNANI_TEST_EMAIL;
  const password = f.env.STRICKNANI_TEST_PASSWORD;
  const screenshots = f.screenshotDir;
  const stageFile = join(f.privateDir, "browser-stage.txt");
  const stage = stageMarker(f.privateDir);
  const markStage = (value: string): void => stage.mark(value);
  const st = createUiState(o.ocrMode, stage);

  markStage(`launching packaged ${cfg.browser}`);
  const browser = await launchBrowser(cfg);
  try {
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
      reducedMotion: "reduce",
      colorScheme: "light",
      locale: "en-US",
      // Playwright cannot hold or fail requests that a controlling service
      // worker handles in WebKit (the Ctrl+S save hold needs that), so the
      // WebKit journey runs without one; the worker is covered by the PWA
      // journey on Chromium.
      serviceWorkers: cfg.browser === "webkit" ? "block" : "allow",
    });
    const page = await context.newPage();
    if (cfg.browser === "webkit") {
      // Playwright's WebKit screenshot path injects inline <style>; keep the
      // real strict-CSP check enabled and omit captures here.
      Object.assign(page, { screenshot: async () => Buffer.alloc(0) });
    }
    page.on("request", (request) => observeRequest(st, request));
    page.on("response", (response) => observeResponse(st, response));
    page.on("console", (message) => observeConsole(st, message));
    page.on("pageerror", (error) => observePageError(st, error));

    const ctx: UiCtx = {
      page,
      context,
      browser,
      st,
      origin,
      email,
      password,
      screenshots,
      stageFile,
      markStage,
      cfg,
      o,
      projectId: 0,
    };
    try {
      await uiPartA(ctx);
      await uiPartB(ctx);
      await uiPartC(ctx);
    } catch (error) {
      if (error instanceof AcceptanceFailure) {
        throw error;
      }
      // Sanitised policy: no page content, only the stage and error class.
      throw new AcceptanceFailure(
        `Web UI browser acceptance failed during ${stage.current()}; ` +
          `exception type ${playwrightErrorCategory(error)}; ` +
          "diagnostic details were suppressed.",
      );
    }
  } finally {
    try {
      for (const name of readdirSync(screenshots)) {
        if (name.endsWith(".png")) {
          chmodSync(join(screenshots, name), 0o600);
        }
      }
    } catch {
      // screenshots directory is cleaned up by the harness
    }
    await closeBrowser(browser);
  }
}

/** Part A: anonymous sign-in through the authenticated responsive layout. */
export async function uiPartA(ctx: UiCtx): Promise<void> {
  const { page, context, st, origin, email, password, screenshots, o } = ctx;
  const { markStage } = ctx;

  markStage("navigating to anonymous sign-in");
  const response = await page.goto(`${origin}/app/login`, {
    waitUntil: "domcontentloaded",
  });
  if (response === null || response.status() !== 200) {
    throw new AcceptanceFailure("direct login route did not load");
  }
  markStage("waiting for anonymous sign-in form");
  await page.locator("#email").waitFor({ state: "visible", timeout: 10_000 });
  markStage("waiting for sign-in heading");
  await page
    .getByRole("heading", { name: "Sign in" })
    .waitFor({ state: "visible", timeout: 10_000 });
  check(
    (await page.locator(".app-shell").count()) === 0,
    "protected navigation appeared while signed out",
  );
  check(
    (await page.locator(".mobile-navigation").count()) === 0,
    "mobile library navigation appeared while signed out",
  );
  const gaugeLink = page.getByRole("link", {
    name: "Gauge calculator",
    exact: true,
  });
  check(
    (await gaugeLink.count()) === 1 &&
      (await gaugeLink.getAttribute("href")) === "/app/gauge",
    "the signed-out screen did not expose the public gauge tool",
  );

  let browserEmail = email;
  let browserPassword = password;
  if (o.signupEnabled) {
    markStage("checking the capability-gated signup link");
    const signupLink = page.getByRole("link", {
      name: "Create an account",
      exact: true,
    });
    try {
      await signupLink.waitFor({ state: "visible", timeout: 5_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) {
        throw error;
      }
      const capability = await page.evaluate<unknown>(
        fn(`async () => {
          const response = await fetch('/api/v2/session');
          const envelope = response.ok ? await response.json() : null;
          return {
            status: response.status,
            signup: envelope?.data?.capabilities?.signup ?? null,
            signInVisible: !!document.querySelector('#password')
          };
        }`),
      );
      throw new AcceptanceFailure(
        "the signup link did not match the anonymous capability " +
          `(sanitized checks=${sortedJson(capability)}; matching link count=` +
          `${await signupLink.count()})`,
      );
    }
    markStage("selecting German for the signup page");
    await page
      .locator(".auth-footer")
      .getByRole("radio", { name: "German", exact: true })
      .click();
    await page.waitForFunction(
      fn("() => document.documentElement.lang === 'de'"),
      undefined,
      { timeout: 5_000 },
    );
    markStage("clicking the translated signup link");
    await page
      .getByRole("link", { name: "Konto erstellen", exact: true })
      .click();
    markStage("waiting for the translated signup route");
    try {
      await page.waitForURL("**/app/signup", { timeout: 5_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) {
        throw error;
      }
      throw new AcceptanceFailure(
        "the translated signup link did not reach its route " +
          `(path=${urlPath(page.url())}; ` +
          `signup form count=${await page.locator("#signup-email").count()}; ` +
          "page language=" +
          `${await page.locator("html").getAttribute("lang")})`,
      );
    }
    page.setDefaultTimeout(2_000);
    const routeState: Record<string, unknown> = {
      path: urlPath(page.url()),
    };
    const germanHeading = page.getByRole("heading", {
      name: "Konto erstellen",
      exact: true,
    });
    const emailLabel = page.getByLabel("E-Mail", { exact: true });
    const diagnostics: Array<[string, () => Promise<unknown>]> = [
      ["language", () => page.locator("html").getAttribute("lang")],
      [
        "signupPanel",
        async () => (await page.locator(".signup-panel").count()) > 0,
      ],
      [
        "signInPassword",
        async () => (await page.locator("#password").count()) > 0,
      ],
      [
        "englishHeading",
        async () =>
          (await page
            .getByRole("heading", { name: "Create an account", exact: true })
            .count()) > 0,
      ],
      ["germanHeading", async () => (await germanHeading.count()) > 0],
      ["germanHeadingVisible", () => germanHeading.isVisible()],
      ["germanHeadingBox", () => germanHeading.boundingBox()],
      ["signupFormVisible", () => page.locator("#signup-email").isVisible()],
      ["emailLabelCount", () => emailLabel.count()],
      ["emailLabelVisible", () => emailLabel.isVisible()],
      [
        "signupCapability",
        () =>
          page.evaluate<unknown>(
            fn(`async () => {
              const result = await Promise.race([
                fetch('/api/v2/session', {credentials: 'same-origin'})
                  .then(async response => ({
                    available: true,
                    enabled: response.ok &&
                      (await response.json())?.data?.capabilities
                        ?.signup === true
                  })),
                new Promise(resolve => setTimeout(() =>
                  resolve({available: false, enabled: false}), 1500))
              ]);
              return result;
            }`),
          ),
      ],
    ];
    for (const [key, query] of diagnostics) {
      try {
        routeState[key] = await query();
      } catch (diagnosticError) {
        routeState[key] =
          diagnosticError instanceof Error
            ? diagnosticError.constructor.name
            : "Error";
      }
    }
    page.setDefaultTimeout(10_000);
    markStage(
      "waiting for translated signup content; preflight=" +
        sortedJson(routeState),
    );
    try {
      await germanHeading.waitFor({ state: "visible", timeout: 5_000 });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) {
        throw error;
      }
      throw new AcceptanceFailure(
        "translated signup content was not visible; sanitized preflight=" +
          sortedJson(routeState),
      );
    }
    markStage("signup heading visible");
    check(
      (await page.locator(".app-shell").count()) === 0,
      "private navigation appeared on the signup route",
    );
    markStage("waiting for translated signup email label");
    await page.getByLabel("E-Mail", { exact: true }).waitFor({
      state: "visible",
    });
    markStage("signup email label visible");
    check(
      (await page.locator("html").getAttribute("lang")) === "de",
      "the signup page did not retain the chosen German locale",
    );
    await page.setViewportSize({ width: 320, height: 760 });
    markStage("checking signup at 320px");
    await assertNoHorizontalOverflow(page, 320);
    markStage("signup 320px overflow check passed");
    const signupControlHeights = await visibleHeights(
      page,
      "#signup-email, #signup-password, " +
        "#signup-confirmation, .signup-form button",
    );
    check(
      signupControlHeights.length === 4 &&
        Math.min(...signupControlHeights) >= 44,
      "a signup form control is smaller than the 44px target",
    );
    markStage("signup 44px controls passed");
    await page.setViewportSize({ width: 390, height: 844 });
    markStage("filling signup confirmation mismatch");
    const signupEmail = `signup-${randomHex(8)}@example.test`;
    const signupPassword = randomUrlSafe(24);
    markStage("filling signup email");
    await page.locator("#signup-email").fill(signupEmail);
    markStage("signup email filled");
    await page.locator("#signup-password").fill(signupPassword);
    markStage("signup password filled");
    await page.locator("#signup-confirmation").fill("mismatch");
    markStage(
      "signup mismatch filled; page errors=" +
        st.pageErrorTypes.slice(0, 3).join(",") +
        "; signup requests=" +
        String(
          st.authResponses.filter(([path]) => path === "/api/v2/auth/signup")
            .length,
        ),
    );
    const signupSubmit = page.locator(".signup-form button");
    markStage("counting signup submit controls");
    const submitCount = await signupSubmit.count();
    markStage(`signup submit control count=${submitCount}`);
    const submitState: Record<string, unknown> = {
      count: submitCount,
      pageErrors: st.pageErrorTypes.slice(0, 3),
    };
    if (submitCount === 1) {
      submitState.text = await signupSubmit.innerText();
      submitState.disabled = await signupSubmit.evaluate(
        fn("button => button.disabled"),
      );
      submitState.visible = await signupSubmit.isVisible();
    }
    markStage("signup submit state=" + sortedJson(submitState));
    check(
      submitState.visible === true && submitState.disabled === false,
      "signup submit was not actionable after anonymous bootstrap",
    );
    await signupSubmit.click({ timeout: 5_000 });
    markStage("waiting for local password mismatch copy");
    try {
      await page
        .getByText("Die Passwörter stimmen nicht überein.", { exact: true })
        .waitFor({ state: "visible" });
    } catch (error) {
      if (!(error instanceof errors.TimeoutError)) {
        throw error;
      }
      let safeState: unknown;
      try {
        safeState = await page.evaluate<unknown>(
          fn(`() => {
            const button = document.querySelector('.signup-form button');
            return {
              path: location.pathname,
              queryKeys: Array.from(
                new URL(location.href).searchParams.keys()
              ),
              language: document.documentElement.lang,
              signupForm: Boolean(document.querySelector('.signup-form')),
              headingTexts: Array.from(document.querySelectorAll('h1'))
                .map(node => node.textContent?.trim() ?? ''),
              errorTexts: Array.from(document.querySelectorAll(
                '.field-error, .signup-error, [role=alert]'
              )).map(node => node.textContent?.trim() ?? ''),
              buttonText: button?.textContent?.trim() ?? null,
              buttonDisabled: button?.disabled ?? null
            };
          }`),
        );
      } catch (diagnosticError) {
        safeState = {
          browserResponsive: false,
          diagnosticError:
            diagnosticError instanceof Error
              ? diagnosticError.constructor.name
              : "Error",
          pageErrorTypes: st.pageErrorTypes.slice(0, 3),
        };
      }
      markStage("signup mismatch diagnostic=" + sortedJson(safeState));
      throw new AcceptanceFailure(
        "localized password mismatch feedback did not appear",
      );
    }
    check(
      !st.authResponses.some(([path]) => path === "/api/v2/auth/signup"),
      "password confirmation mismatch reached the signup API",
    );
    markStage("local password mismatch verified");
    await page
      .locator(".signup-footer")
      .getByRole("radio", { name: "Englisch", exact: true })
      .click();
    await page.goto(`${origin}/app/signup`, { waitUntil: "networkidle" });
    await page
      .getByRole("heading", { name: "Create an account", exact: true })
      .waitFor({ state: "visible" });
    await page.locator("#signup-email").fill(signupEmail);
    await page.locator("#signup-password").fill(signupPassword);
    await page.locator("#signup-confirmation").fill(signupPassword);
    markStage("creating an account through the real signup API");
    const sessionsBeforeSignup = st.sessionObservations.length;
    const isSignupPost = (candidate: Response) =>
      responsePath(candidate) === "/api/v2/auth/signup" &&
      candidate.request().method() === "POST";
    const [signupResponse] = await Promise.all([
      page.waitForResponse(isSignupPost),
      page.getByRole("button", { name: "Create account", exact: true }).click(),
    ]);
    check(
      signupResponse.status() === 201,
      "account registration did not succeed against the Rust API",
    );
    let signupRequest: unknown;
    let signupEnvelope: unknown;
    try {
      signupRequest = JSON.parse(signupResponse.request().postData() ?? "");
      signupEnvelope = await signupResponse.json();
    } catch {
      throw new AcceptanceFailure(
        "the signup API returned an unreadable result",
      );
    }
    const requestRecord =
      typeof signupRequest === "object" && signupRequest !== null
        ? (signupRequest as Record<string, unknown>)
        : {};
    check(
      Object.keys(requestRecord).length === 2 &&
        requestRecord.email === signupEmail &&
        requestRecord.password === signupPassword,
      "signup sent unexpected fields to the Rust API",
    );
    check(
      st.csrfRequests.some(
        ([path, hasCsrf]) => path === "/api/v2/auth/signup" && hasCsrf,
      ),
      "signup did not send its pre-authentication CSRF header",
    );
    const signupData =
      typeof signupEnvelope === "object" && signupEnvelope !== null
        ? (signupEnvelope as Record<string, unknown>).data
        : null;
    const signupDataRecord =
      typeof signupData === "object" && signupData !== null
        ? (signupData as Record<string, unknown>)
        : null;
    check(
      signupDataRecord !== null &&
        Number.isInteger(signupDataRecord.id) &&
        signupDataRecord.email === signupEmail &&
        signupDataRecord.is_admin === false,
      "signup returned an unexpected member account",
    );
    markStage("waiting for the new member to be signed in");
    await page.waitForURL("**/app/projects", { timeout: 10_000 });
    await page.locator(".app-shell").waitFor({ state: "visible" });
    await page.setViewportSize({ width: 1280, height: 844 });
    await settleObservers(st);
    check(
      st.sessionIdentityObservations
        .slice(sessionsBeforeSignup)
        .some(
          ([status, sessionEmail]) =>
            status === 200 && sessionEmail === signupEmail,
        ),
      "registration did not sign the new member in",
    );
    check(
      st.sessionObservations
        .slice(sessionsBeforeSignup)
        .some(([status, userNull, csrf]) => status === 200 && userNull && csrf),
      "signup did not refresh an anonymous session and CSRF token",
    );
    markStage("signing out before the duplicate registration check");
    await openAccountFromProfile(page);
    await (await accountSignOutButton(page)).click();
    await page.waitForURL("**/app/login", { timeout: 10_000 });
    await page.locator("#email").waitFor({ state: "visible" });
    await page.goto(`${origin}/app/signup`, { waitUntil: "networkidle" });
    await page.locator("#signup-email").fill(signupEmail);
    await page.locator("#signup-password").fill(signupPassword);
    await page.locator("#signup-confirmation").fill(signupPassword);
    markStage("checking duplicate-account recovery copy");
    st.expectedSignupConflictWindow = true;
    let duplicateResponse: Response;
    try {
      [duplicateResponse] = await Promise.all([
        page.waitForResponse(isSignupPost),
        page
          .getByRole("button", { name: "Create account", exact: true })
          .click(),
      ]);
    } finally {
      st.expectedSignupConflictWindow = false;
    }
    check(
      duplicateResponse.status() === 409,
      "a duplicate signup did not return the account conflict",
    );
    await page
      .getByText(
        "The account could not be created. If you already have one, " +
          "sign in instead.",
        { exact: true },
      )
      .waitFor({ state: "visible" });
    await page.goto(`${origin}/app/login?created=1`, {
      waitUntil: "networkidle",
    });
    await page.locator("#email").waitFor({ state: "visible" });
    await page.locator("#email").fill(signupEmail);
    await page.locator("#password").fill(signupPassword);
    markStage("explicitly signing in with the new account");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.locator(".app-shell").waitFor({ state: "visible" });
    await page.setViewportSize({ width: 1280, height: 844 });
    await settleObservers(st);
    check(
      st.sessionIdentityObservations
        .slice(sessionsBeforeSignup)
        .some(
          ([status, sessionEmail]) =>
            status === 200 && sessionEmail === signupEmail,
        ),
      "the explicit sign-in session did not identify the new account",
    );
    markStage("returning to anonymous sign-in after signup check");
    await openAccountFromProfile(page);
    check(
      (await page.locator(".profile__email").innerText()) === signupEmail,
      "the profile did not show the signed-in account identity",
    );
    await (await accountSignOutButton(page)).click();
    await page.waitForURL("**/app/login", { timeout: 10_000 });
    await page.locator("#email").waitFor({ state: "visible" });
    browserEmail = email;
    browserPassword = password;
  } else {
    check(
      (await page
        .getByRole("link", { name: "Create an account", exact: true })
        .count()) === 0,
      "signup was linked while the server capability was disabled",
    );
    markStage("direct route for disabled account registration");
    const disabledResponse = await page.goto(`${origin}/app/signup`, {
      waitUntil: "networkidle",
    });
    check(
      disabledResponse !== null && disabledResponse.status() === 200,
      "direct signup route did not load its unavailable state",
    );
    await page
      .getByRole("heading", {
        name: "Account registration is unavailable.",
        exact: true,
      })
      .waitFor({ state: "visible" });
    check(
      (await page.locator("#signup-password").count()) === 0,
      "the disabled signup route exposed its account form",
    );
    await page.goto(`${origin}/app/login`, { waitUntil: "networkidle" });
    await page.locator("#email").waitFor({ state: "visible" });
  }

  await gaugeLink.click();
  await page.waitForURL("**/app/gauge");
  await page
    .getByRole("heading", { name: "Gauge calculator", exact: true })
    .waitFor({ state: "visible" });
  check(
    (await page.locator(".public-tool-shell").count()) === 1 &&
      (await page.locator(".app-shell").count()) === 0,
    "the signed-out gauge link opened a private shell",
  );
  await page.goto(`${origin}/app/login`, { waitUntil: "networkidle" });
  await page.locator("#email").waitFor({ state: "visible" });

  const policy = response.headers()["content-security-policy"] ?? "";
  check(
    policy.includes("default-src 'self'") &&
      policy.includes("script-src 'self'") &&
      policy.includes("style-src 'self'") &&
      !policy.includes("unsafe-inline") &&
      !policy.includes("unsafe-eval") &&
      policy.includes("sha256-"),
    "strict frontend CSP was not attached to the app shell",
  );
  check(
    st.appAssetStatuses.length > 0 &&
      st.appAssetStatuses.every((status) => status === 200),
    "a bundled app asset failed to load",
  );
  check(
    st.catalogStatuses.length > 0 &&
      st.catalogStatuses.every((status) => status === 200),
    "the public translation catalog failed to load",
  );
  const faviconResponse = await page.request.get(`${origin}/app/favicon.svg`);
  check(faviconResponse.status() === 200, "the app favicon failed to load");
  await settleObservers(st);
  check(
    st.sessionObservations.some(
      ([status, userNull, csrf]) => status === 200 && userNull && csrf,
    ),
    "anonymous session bootstrap did not provide CSRF state",
  );

  markStage("anonymous responsive layout");
  for (const width of [320, 390, 768, 1280]) {
    await page.setViewportSize({ width, height: 844 });
    await assertNoHorizontalOverflow(page, width);
    const formHeights = await visibleHeights(
      page,
      "#email, #password, .auth-form button",
    );
    const signedOutToolHeights = await visibleHeights(
      page,
      ".auth-footer a, .auth-footer .locale-switcher button",
    );
    check(
      formHeights.length === 3 && Math.min(...formHeights) >= 44,
      "a sign-in control is below the 44px target size",
    );
    check(
      signedOutToolHeights.length === 5 &&
        Math.min(...signedOutToolHeights) >= 44,
      "a signed-out tool or language control is below 44px",
    );
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await prepareScreenshot(page);
  await page.screenshot({
    path: join(screenshots, "signed-out-mobile.png"),
    caret: "initial",
  });

  markStage("real password login and session cookie");
  await page.locator("#email").fill(browserEmail);
  await page.locator("#password").fill(browserPassword);
  await page.getByRole("button", { name: "Sign in" }).click();
  markStage("waiting for project library URL after login");
  await page.waitForURL("**/app/projects", { timeout: 10_000 });
  markStage("waiting for authenticated app shell");
  await page.locator(".app-shell").waitFor({ state: "visible" });
  markStage("waiting for authenticated mobile navigation");
  await page.locator(".mobile-navigation").waitFor({ state: "visible" });
  markStage("waiting for rendered project library heading");
  await page
    .getByRole("heading", { name: "Projects", exact: true })
    .waitFor({ state: "visible", timeout: 10_000 });
  await assertMobileAppHeader(page);
  const currentRoute = urlPath(page.url());
  markStage(`checking project route after login at ${currentRoute}`);
  check(
    currentRoute.replace(/\/+$/, "") === "/app/projects",
    "password login did not open the project library route",
  );
  check(
    st.csrfRequests.some(
      ([path, hasCsrf]) => path === "/api/v2/auth/login" && hasCsrf,
    ),
    "login did not send the CSRF header",
  );
  await settleObservers(st);
  check(
    st.sessionObservations.some(
      ([status, , , numeric]) => status === 200 && numeric,
    ),
    "authenticated session did not expose a numeric user ID",
  );
  const loginResponse = st.authResponses.find(
    ([path]) => path === "/api/v2/auth/login",
  )?.[1];
  check(
    loginResponse === 200,
    "password login did not succeed against the Rust API",
  );

  const sessionCookie = (await context.cookies(origin)).find(
    (cookie) => cookie.name === "stricknani_session",
  );
  check(
    sessionCookie !== undefined &&
      sessionCookie.httpOnly === true &&
      sessionCookie.sameSite === "Lax",
    "login did not establish the expected protected browser session cookie",
  );
  markStage("preparing the synthetic project reader fixture");
  ctx.projectId = await prepareProjectReaderFixture(page);
  markStage("opening the authenticated project library");
  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  markStage("waiting for the seeded project card");
  const seededHeading = page.getByRole("heading", {
    name: "Heirloom Baby Blanket",
    exact: true,
  });
  try {
    await seededHeading.waitFor({ state: "visible", timeout: 10_000 });
  } catch (error) {
    if (!(error instanceof errors.TimeoutError)) {
      throw error;
    }
    await settleObservers(st);
    const listStatuses = st.projectResponses
      .filter(([path]) => path === "/api/v2/projects")
      .map(([, status]) => status);
    const apiStatus = listStatuses.length
      ? String(listStatuses[listStatuses.length - 1])
      : "not observed";
    const listSize = st.projectListSizes.length
      ? String(st.projectListSizes[st.projectListSizes.length - 1])
      : "not observed";
    const cardCount = await page.locator(".project-card").count();
    const emptyCount = await page.locator(".empty-state").count();
    const loading = (await page.locator(".loading-grid").count()) > 0;
    const uiErrorVisible = (await page.locator(".notice-error").count()) > 0;
    const libraryCount = await page.locator(".library").count();
    const debugScreenshot = join(screenshots, "project-library-debug.png");
    await maskFixtureIdentity(page);
    await prepareScreenshot(page);
    await page.screenshot({ path: debugScreenshot, caret: "initial" });
    throw new AcceptanceFailure(
      "seeded project did not render " +
        `(API status ${apiStatus}; list items=${listSize}; ` +
        `cards=${cardCount}; empty state=${emptyCount}; ` +
        `still loading=${loading}; UI error visible=${uiErrorVisible}; ` +
        `library component=${libraryCount}; screenshot=${debugScreenshot})`,
    );
  }
  check(
    st.projectResponses.some(
      ([path, status]) => path === "/api/v2/projects" && status === 200,
    ),
    "the real project library API did not load",
  );
  const mobileLinks = page.locator(".mobile-navigation a");
  check(
    (await mobileLinks.count()) === 4,
    "signed-in mobile navigation is incomplete",
  );
  check(
    (await page
      .locator(".mobile-navigation a")
      .filter({ hasText: "Create" })
      .count()) === 0,
    "Create must live under More, not in the phone bar",
  );
  const expectedLinks: Array<[string, string]> = [
    ["Home", "/app"],
    ["Projects", "/app/projects"],
    ["Yarns", "/app/yarns"],
    ["More", "/app/more"],
  ];
  for (const [label, href] of expectedLinks) {
    const link = page
      .locator(".mobile-navigation a")
      .filter({ hasText: label });
    check(
      (await link.count()) === 1 &&
        ((await link.getAttribute("href")) ?? "").replace(/\/+$/, "") === href,
      "a mobile navigation link has the wrong destination",
    );
  }
  markStage("project library search and URL filters");
  const projectCardOf = (): Locator =>
    page.locator(".project-card").filter({
      has: page.getByRole("heading", {
        name: "Heirloom Baby Blanket",
        exact: true,
      }),
    });
  let projectCard = projectCardOf();
  check(
    (await projectCard.count()) === 1,
    "the seeded project card is missing",
  );
  const projectPhoto = projectCard.locator(".library-card__media img");
  await projectPhoto.scrollIntoViewIfNeeded();
  check(
    (await projectPhoto.count()) === 1 &&
      (await projectPhoto.evaluate(
        fn("image => image.src.includes('/media/')"),
      )),
    "the project card did not use the seeded same-origin photo",
  );
  let searchBox = page.getByRole("searchbox", { name: "Search your projects" });
  markStage("searching project library by name");
  const [searchResponse] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        urlPath(candidate.url()) === "/api/v2/projects" &&
        queryIs(candidate.url(), "q", "Heirloom"),
    ),
    searchBox.fill("Heirloom"),
  ]);
  check(
    searchResponse.status() === 200,
    "project-name search did not succeed against the Rust API",
  );
  await page.locator(".project-card").waitFor({ state: "visible" });
  check(
    (await page.locator(".project-card").count()) === 1,
    "project search did not return the matching card",
  );
  markStage("filtering project library by category");
  let selectedCategory: string[] = [];
  let filterDialog = await openLibraryFilters(page);
  const categorySelect = filterDialog.getByLabel("Category", { exact: true });
  try {
    await categorySelect.waitFor({ state: "visible", timeout: 5_000 });
  } catch (error) {
    if (!(error instanceof errors.TimeoutError)) {
      throw error;
    }
    await maskFixtureIdentity(page);
    await prepareScreenshot(page);
    const categoryDebug = await page.evaluate<unknown>(
      fn(`() => ({
        selectCount: document.querySelectorAll('select').length,
        libraryCount: document.querySelectorAll('.library').length,
        visible: document.visibilityState === 'visible',
        hasBody: Boolean(document.body),
      })`),
    );
    const categoryDebugScreenshot = join(
      screenshots,
      "category-stage-debug.png",
    );
    await page.screenshot({ path: categoryDebugScreenshot, caret: "initial" });
    throw new AcceptanceFailure(
      "project category control did not become visible " +
        `(diagnostics=${sortedJson(categoryDebug)}; ` +
        `native select count=${await page.locator("select").count()}; ` +
        `exact Category label count=${await categorySelect.count()})`,
    );
  }
  check((await categorySelect.count()) === 1, "category filter is ambiguous");
  check(
    await categorySelect.evaluate(fn("select => select.labels.length > 0")),
    "the project category filter is not programmatically labeled",
  );
  let categoryFilterResponse: Response;
  try {
    [categoryFilterResponse] = await Promise.all([
      page.waitForResponse(
        (candidate) =>
          urlPath(candidate.url()) === "/api/v2/projects" &&
          queryIs(candidate.url(), "category", "Schal"),
        { timeout: 10_000 },
      ),
      (async () => {
        selectedCategory = await categorySelect.selectOption(
          { label: "Schal" },
          { timeout: 5_000 },
        );
      })(),
    ]);
  } catch (error) {
    if (!(error instanceof errors.TimeoutError)) {
      throw error;
    }
    const categoryInUrl = queryIs(page.url(), "category", "Schal");
    const categoryRequestSeen = st.projectListQueries.some(
      (query) =>
        query.category !== undefined &&
        query.category.length === 1 &&
        query.category[0] === "Schal",
    );
    const categoryValueSelected =
      (await categorySelect.inputValue({ timeout: 1_000 })) === "Schal";
    throw new AcceptanceFailure(
      "category filter did not reach the project API " +
        `(URL updated=${categoryInUrl}; ` +
        `request observed=${categoryRequestSeen}; ` +
        `select returned value=${
          selectedCategory.length === 1 && selectedCategory[0] === "Schal"
        }; selected in DOM=${categoryValueSelected})`,
    );
  }
  check(
    categoryFilterResponse.status() === 200,
    "category filtering did not succeed against the Rust API",
  );
  await closeLibraryFilters(filterDialog);
  await page.locator(".project-card").waitFor({ state: "visible" });
  check(
    st.categoryResponses.some(
      ([path, status]) => path === "/api/v2/categories" && status === 200,
    ),
    "project categories did not load from the Rust API",
  );
  markStage("filtering project library by tag");
  filterDialog = await openLibraryFilters(page);
  const tagFilter = filterDialog.getByLabel("Tag", { exact: true });
  const [tagFilterResponse] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        urlPath(candidate.url()) === "/api/v2/projects" &&
        queryIs(candidate.url(), "tag", "heirloom"),
    ),
    (async () => {
      await tagFilter.fill("heirloom");
      await tagFilter.press("Tab");
    })(),
  ]);
  check(
    tagFilterResponse.status() === 200,
    "tag filtering did not succeed against the Rust API",
  );
  await closeLibraryFilters(filterDialog);
  await page.locator(".project-card").waitFor({ state: "visible" });
  searchBox = page.getByRole("searchbox", { name: "Search your projects" });
  markStage("checking the project no-match state");
  const [noMatchResponse] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        urlPath(candidate.url()) === "/api/v2/projects" &&
        queryIs(candidate.url(), "q", "no such knitting project"),
    ),
    searchBox.fill("no such knitting project"),
  ]);
  check(
    noMatchResponse.status() === 200,
    "no-match search did not succeed against the Rust API",
  );
  await page
    .getByRole("heading", { name: "No projects match those filters." })
    .waitFor({ state: "visible" });
  check(
    (await page.locator(".project-card").count()) === 0,
    "the no-match state still showed a project",
  );
  markStage("clearing project filters");
  const [clearFiltersResponse] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        urlPath(candidate.url()) === "/api/v2/projects" &&
        // The library always sends its scope; no filter parameter may remain.
        [...new URL(candidate.url()).searchParams.keys()].every(
          (key) => key === "scope",
        ),
    ),
    (async () => {
      filterDialog = await openLibraryFilters(page);
      await filterDialog
        .getByRole("button", { name: "Clear filters", exact: true })
        .click();
    })(),
  ]);
  await waitForLibraryFiltersClosed(filterDialog);
  check(
    clearFiltersResponse.status() === 200,
    "clearing filters did not reload the project list",
  );
  await seededHeading.waitFor({ state: "visible" });
  await assertMobileAppHeader(page);
  projectCard = projectCardOf();
  markStage("saving a project favorite");
  const projectDetailHref = await projectCard
    .locator("a.resource-card__link")
    .getAttribute("href");
  check(projectDetailHref !== null, "project detail link is missing");
  await projectCard.locator("a.resource-card__link").click();
  await page.waitForURL(`**${projectDetailHref}`);
  const favoriteButton = await detailMenuItem(page, "Add to favorites");
  const [favoriteResponse] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        urlPath(candidate.url()).endsWith("/favorite") &&
        candidate.request().method() === "PUT",
    ),
    favoriteButton.click(),
  ]);
  check(
    favoriteResponse.status() === 200,
    "saving the project favorite failed against the Rust API",
  );
  await detailMenuItem(page, "Remove from favorites");
  await page.keyboard.press("Escape");
  await page.goBack({ waitUntil: "domcontentloaded" });
  await page.waitForURL("**/app/projects");
  projectCard = projectCardOf();
  await projectCard
    .getByRole("button", { name: "Remove from favorites", exact: true })
    .waitFor({ state: "visible" });
  markStage("filtering project library by favorites");
  filterDialog = await openLibraryFilters(page);
  const [favoriteFilterResponse] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        urlPath(candidate.url()) === "/api/v2/projects" &&
        queryIs(candidate.url(), "favorite", "true"),
    ),
    filterDialog.getByLabel("Favorites only", { exact: true }).check(),
  ]);
  check(
    favoriteFilterResponse.status() === 200,
    "favorite filtering did not succeed against the Rust API",
  );
  await closeLibraryFilters(filterDialog);
  await page.locator(".project-card").waitFor({ state: "visible" });
  check(
    (await page.locator(".project-card").count()) === 1,
    "favorite filtering did not keep the saved project",
  );
  check(
    st.csrfRequests.some(
      ([path, hasCsrf]) => path.endsWith("/favorite") && hasCsrf,
    ),
    "the favorite action did not send CSRF",
  );
  check(
    st.projectResponses.some(
      ([path, status]) => path.endsWith("/favorite") && status === 200,
    ),
    "the favorite action did not persist through the Rust API",
  );
  markStage("checking mobile account language controls");
  await checkMobileLanguageAndScreenshots(page, origin, screenshots, markStage);
  markStage("authenticated responsive layout");
  for (const width of [320, 390, 768, 1280]) {
    await page.setViewportSize({ width, height: 844 });
    await assertNoHorizontalOverflow(page, width);
    check(
      (await page
        .locator(".app-header .brand-lockup .brand-version")
        .count()) === 0,
      `the ${width}px header still shows the version`,
    );
    if (width === 320) {
      check(
        await page
          .locator(".app-header .brand-lockup .brand-title")
          .isVisible(),
        "the brand title is not visible in the 320px header",
      );
    }
    if (width === 1280) {
      const about = page.locator('.desktop-rail a[href$="/about"]');
      const version = about.locator(".app-version");
      await version.waitFor();
      const box = await version.boundingBox();
      const link = await about.boundingBox();
      check(
        box !== null &&
          link !== null &&
          box.x > link.x + link.width / 2 &&
          box.x + box.width <= link.x + link.width + 1 &&
          box.y >= link.y - 1 &&
          box.y + box.height <= link.y + link.height + 1,
        "the version is not at the right end of the rail's About link at 1280px",
      );
    }
    if (width === 768) {
      const metrics = await page.evaluate<unknown>(
        fn(`() => {
          const card = document.querySelector('.project-card');
          const rect = card?.getBoundingClientRect();
          return {
            viewport: window.innerWidth,
            clientWidth: document.documentElement.clientWidth,
            scrollWidth: document.documentElement.scrollWidth,
            cardLeft: rect?.left ?? null,
            cardRight: rect?.right ?? null,
            cardWidth: rect?.width ?? null,
          };
        }`),
      );
      console.log(
        "Project library viewport at 768px: " +
          JSON.stringify(
            Object.fromEntries(
              Object.entries(metrics as Record<string, unknown>).sort(
                ([left], [right]) => (left < right ? -1 : 1),
              ),
            ),
          ),
      );
    }
    const selector =
      width <= 760
        ? ".mobile-navigation a"
        : ".primary-nav a, .secondary-nav a, .rail-account";
    const heights = await visibleHeights(page, selector);
    if (heights.length === 0) {
      st.responsiveFailures.push(
        `no signed-in navigation targets were visible at ${width}px`,
      );
    } else if (Math.min(...heights) < 44) {
      st.responsiveFailures.push(
        `signed-in navigation target measured ${Math.min(...heights).toFixed(1)}px ` +
          `at ${width}px`,
      );
    }
    check(
      await page.evaluate(
        "matchMedia('(prefers-reduced-motion: reduce)').matches",
      ),
      "reduced-motion preference was not honored by the browser context",
    );
    check(
      (await page.evaluate<string>(
        "getComputedStyle(document.documentElement).scrollBehavior",
      )) === "auto",
      "reduced-motion scroll behavior was not applied",
    );
  }
  await page.setViewportSize({ width: 390, height: 844 });
}
