// Part B of the web UI browser journey (port of ci/stricknani/
// web_ui_browser.py run_acceptance from the "project reader, ordered steps,
// media, and print" stage up to, but excluding, "creating a project through
// the browser editor").
import {
  AcceptanceFailure,
  assertNoHorizontalOverflow,
  check,
  maskFixtureIdentity,
  playwrightErrorCategory,
  fn,
} from "../lib/common";
import {
  HOME_GREETING_NAME,
  assertAndroidAppCard,
  assertHeaderParentLink,
  assertMobileAppHeader,
  assertStepInUsableViewport,
  checkPrintImageFailureAndRetry,
  exerciseGaugeCalculator,
  exerciseProjectServiceTools,
  exerciseYarnServiceTools,
  openReaderActions,
  prepareScreenshot,
  svgShapeSignature,
} from "../lib/ui-helpers";
import { settleObservers, urlPath, type UiCtx } from "../lib/ui-state";
import { detailMenuItem } from "../lib/detail-menu";

interface PrintImageState {
  complete: boolean;
  loaded: boolean;
  sameOrigin: boolean;
  path: boolean;
  currentSrcEmpty: boolean;
  srcEqualsOriginal: boolean;
  currentSrcEqualsOriginal: boolean;
}

interface PrintReferenceState {
  visible: boolean;
  complete: boolean;
  loaded: boolean;
  currentSrcEmpty: boolean;
  srcEqualsOriginal: boolean;
  currentSrcEqualsOriginal: boolean;
}

function sameStrings(left: string[], right: string[]): boolean {
  return (
    left.length === right.length &&
    left.every((value, index) => value === right[index])
  );
}

export async function uiPartB(ctx: UiCtx): Promise<void> {
  const { page, browser, st, origin, email, password, screenshots, o } = ctx;
  const { markStage, projectId } = ctx;

  await page.setViewportSize({ width: 390, height: 844 });
  markStage("project reader, ordered steps, media, and print");
  const fullResolutionRequestsBeforeReader =
    st.originalProjectImageRequests.length;
  const readerResponsePromise = page.waitForResponse(
    (response) =>
      urlPath(response.url()) === `/api/v2/projects/${projectId}` &&
      response.request().method() === "GET",
  );
  await page
    .locator(`.project-card a[href='/app/projects/${projectId}']`)
    .click();
  await page.waitForURL(`**/app/projects/${projectId}`);
  const readerResponse = await readerResponsePromise;
  check(readerResponse.status() === 200, "project detail API failed");
  markStage("checking project reader title and ordered steps");
  await page
    .getByRole("heading", { name: "Heirloom Baby Blanket", exact: true })
    .waitFor({ state: "visible" });
  const stepTitles = await page.locator(".steps-list h3").allInnerTexts();
  check(
    sameStrings(stepTitles, [
      "Cast on and knit the border",
      "Work the textured panel",
    ]),
    "the reader did not display pattern steps in order",
  );
  const readerPayload = (await readerResponse.json()) as {
    data?: {
      steps?: Array<{ id?: unknown }>;
      yarn_ids?: unknown;
      images?: unknown;
    };
  };
  const projectData = readerPayload.data ?? {};
  const readerSteps = projectData.steps ?? [];
  const stepIds = readerSteps.map((step) => step.id);
  check(
    stepIds.length === 2 &&
      stepIds.every((stepId) => typeof stepId === "number"),
    "the reader fixture did not return two numeric step IDs",
  );
  const secondStepId = (stepIds as [number, number])[1];
  const readerTags = await page.locator(".reader-tags li").allInnerTexts();
  check(
    sameStrings(readerTags, ["heirloom", "textured", "textured"]),
    "the project reader collapsed or misrendered repeated tag labels",
  );
  await exerciseProjectServiceTools(
    page,
    origin,
    projectId,
    o.serviceFixtureEnabled,
    o.ocrMode,
    o.archiveMode,
    markStage,
    screenshots,
  );
  const yarnIds = projectData.yarn_ids;
  if (
    !Array.isArray(yarnIds) ||
    yarnIds.length === 0 ||
    typeof yarnIds[0] !== "number"
  ) {
    throw new AcceptanceFailure(
      "the project fixture did not return a linked yarn ID",
    );
  }
  const yarnId: number = yarnIds[0];
  markStage("checking OCR and archive controls on linked yarn photos");
  await page.goto(`${origin}/app/yarns/${yarnId}`, {
    waitUntil: "networkidle",
  });
  await page
    .getByRole("heading", { name: "Riverbend Merino DK", exact: true })
    .waitFor({ state: "visible" });
  await assertMobileAppHeader(page);
  await exerciseYarnServiceTools(
    page,
    origin,
    yarnId,
    o.serviceFixtureEnabled,
    o.ocrMode,
    o.archiveMode,
    markStage,
    screenshots,
  );
  markStage("measuring the yarn library at tablet width");
  await page.goto(`${origin}/app/yarns`, { waitUntil: "networkidle" });
  await page
    .getByRole("heading", { name: "Riverbend Merino DK", exact: true })
    .waitFor({ state: "visible" });
  await page.setViewportSize({ width: 768, height: 844 });
  await assertNoHorizontalOverflow(page, 768);
  const yarnMetrics: unknown = await page.evaluate(() => {
    const card = document.querySelector(".yarn-card");
    const rect = card?.getBoundingClientRect();
    return {
      viewport: window.innerWidth,
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
      cardLeft: rect?.left ?? null,
      cardRight: rect?.right ?? null,
      cardWidth: rect?.width ?? null,
    };
  });
  const sortedMetrics = yarnMetrics as Record<string, unknown>;
  console.log(
    "Yarn library viewport at 768px: " +
      JSON.stringify(
        Object.fromEntries(
          Object.keys(sortedMetrics)
            .sort()
            .map((key) => [key, sortedMetrics[key]]),
        ),
      ),
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${origin}/app/projects/${projectId}`, {
    waitUntil: "networkidle",
  });
  await page
    .getByRole("heading", { name: "Heirloom Baby Blanket", exact: true })
    .waitFor({ state: "visible" });
  markStage("opening a project starts at the top, without a step bar");
  check(
    (await page.locator(".step-navigation").count()) === 0,
    "the reader still shows the previous/next step bar",
  );
  check(
    new URL(page.url()).hash === "",
    "opening a project jumped to a step on its own",
  );
  markStage("opening a direct reader step link");
  await page.goto(`${origin}/app/projects/${projectId}#step-${secondStepId}`, {
    waitUntil: "networkidle",
  });
  await page
    .locator(`#step-${secondStepId}[aria-current="step"]`)
    .waitFor({ state: "visible" });
  await assertStepInUsableViewport(page, secondStepId);
  markStage("returning from the library does not resume the last step");
  await (
    await assertHeaderParentLink(page, "/app/projects", "project reader")
  ).click();
  await page.waitForURL("**/app/projects");
  await page
    .locator(
      ".project-card a.resource-card__link" +
        `[href='/app/projects/${projectId}']`,
    )
    .click();
  await page.waitForURL(`**/app/projects/${projectId}`);
  await page
    .getByRole("heading", { name: "Heirloom Baby Blanket", exact: true })
    .waitFor({ state: "visible" });
  check(
    new URL(page.url()).hash === "" &&
      (await page.locator('.steps-list [aria-current="step"]').count()) === 0,
    "the reader resumed the last step after returning from the library",
  );
  markStage("checking the seeded project-step reference photo");
  const stepPhoto = page.locator(
    ".steps-list .step-image .screen-reference-image",
  );
  await stepPhoto.waitFor({ state: "visible", timeout: 5_000 });
  await stepPhoto.scrollIntoViewIfNeeded();
  await stepPhoto.evaluate((image) => (image as HTMLImageElement).decode());
  check(
    await stepPhoto.evaluate((element) => {
      const image = element as HTMLImageElement;
      return (
        image.complete &&
        image.naturalWidth > 0 &&
        image.currentSrc.includes("/media/thumbnails/projects/")
      );
    }),
    "the project-step reference photo did not load from same-origin media",
  );
  check(
    st.originalProjectImageRequests.length ===
      fullResolutionRequestsBeforeReader,
    "normal pattern reading requested full-resolution project photos",
  );
  markStage("checking sanitized pattern text");
  const description = page.locator(
    ".pattern-section > .reading-copy > .rich-text",
  );
  const sanitizedText = description.locator("strong");
  if (
    (await sanitizedText.count()) !== 1 ||
    (await sanitizedText.innerText()) !== "textured blanket"
  ) {
    const textDebug = await page
      .locator(".pattern-section .rich-text")
      .evaluateAll((nodes) =>
        nodes.map((node) => ({
          strongCount: node.querySelectorAll("strong").length,
          textLength: node.textContent?.length ?? 0,
          hasFixturePhrase:
            node.textContent?.includes("textured blanket") ?? false,
          hasMarkdownMarkers: node.textContent?.includes("**") ?? false,
        })),
      );
    await maskFixtureIdentity(page);
    await prepareScreenshot(page);
    await page.screenshot({
      path: `${screenshots}/reader-text-debug.png`,
      caret: "initial",
    });
    throw new AcceptanceFailure(
      "sanitized description structure did not match " +
        `the fixture (blocks=${JSON.stringify(textDebug)}; ` +
        `strong elements=${await sanitizedText.count()})`,
    );
  }
  check(
    !(await description.innerText()).includes("**"),
    "raw Markdown was displayed in the pattern reader",
  );
  markStage("checking linked yarn API details");
  const materials = page.locator("details.materials-card");
  const materialsSummary = materials.locator("summary");
  await materials.waitFor({ state: "visible", timeout: 5_000 });
  check(
    (await materials.getAttribute("open")) === null,
    "materials disclosure should start collapsed",
  );
  await materialsSummary.focus();
  await page.keyboard.press("Enter");
  check(
    (await materials.getAttribute("open")) !== null,
    "the native Materials summary did not open its disclosure",
  );
  const linkedYarn = materials.locator(
    `a.resource-card__link[href="/app/yarns/${yarnId}"]`,
  );
  await linkedYarn.waitFor({ state: "visible", timeout: 5_000 });
  check(
    (await linkedYarn
      .getByText("Riverbend Merino DK", { exact: true })
      .count()) === 1,
    "the linked yarn name or destination was missing from Materials",
  );
  check(
    st.yarnResponses.some(
      ([path, status]) => path === `/api/v2/yarns/${yarnId}` && status === 200,
    ),
    "linked yarn details did not load from the Rust API",
  );
  const readerImages = projectData.images ?? [];
  check(
    Array.isArray(readerImages),
    "the project reader API did not return its image collection",
  );
  const galleryImages = (readerImages as unknown[]).filter(
    (image) =>
      typeof image === "object" &&
      image !== null &&
      !Array.isArray(image) &&
      ((image as Record<string, unknown>).step_id ?? null) === null,
  );
  check(
    (await page.locator(".reader-overview .photo-strip__tile").count()) ===
      galleryImages.length,
    "the cover or remaining project photos no longer match " +
      "the API image collection",
  );
  await materialsSummary.focus();
  await page.keyboard.press("Enter");
  check(
    (await materials.getAttribute("open")) === null,
    "the native Materials summary did not close its disclosure",
  );
  await page.setViewportSize({ width: 320, height: 844 });
  markStage("opening the single seeded project photo at 320px");
  await page.locator(".photo-strip__open").first().scrollIntoViewIfNeeded();
  await page.locator(".photo-strip__open").first().click();
  const dialog = page.getByRole("dialog", { name: "Project image viewer" });
  await dialog.waitFor({ state: "visible" });
  const lightboxImage = dialog.locator("img");
  await lightboxImage.waitFor({ state: "visible" });
  await lightboxImage.evaluate((image) => (image as HTMLImageElement).decode());
  const lightboxCount = dialog.locator("figcaption span");
  const readerImageCount = (readerImages as unknown[]).length;
  if (readerImageCount > 1) {
    await lightboxCount.waitFor({ state: "visible" });
    check(
      (await lightboxCount.innerText()).endsWith(`/ ${readerImageCount}`) &&
        (await dialog
          .getByRole("button", { name: "Previous image" })
          .isVisible()) &&
        (await dialog.getByRole("button", { name: "Next image" }).isVisible()),
      "the cover lightbox did not retain the full project image collection",
    );
  } else {
    check(
      (await lightboxCount.count()) === 0,
      "the single-image lightbox unexpectedly reported extra photos",
    );
  }
  const imageBounds = await lightboxImage.boundingBox();
  check(
    (await lightboxImage.evaluate((element) => {
      const image = element as HTMLImageElement;
      return image.complete && image.naturalWidth > 0;
    })) &&
      imageBounds !== null &&
      imageBounds.width >= 180 &&
      imageBounds.height >= 140,
    "the seeded photo did not load at a useful size in the 320px viewer",
  );
  markStage("checking Escape close and focus restoration");
  await page.keyboard.press("Escape");
  await dialog.waitFor({ state: "hidden" });
  check(
    await page.evaluate(
      "document.activeElement === document.querySelector('.photo-strip__open')",
    ),
    "closing the image viewer did not restore focus",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  markStage("checking reader text size and print layout");
  const readingCopy = page.locator(".pattern-section > .reading-copy");
  const fontSizeOf = async (): Promise<string> =>
    readingCopy.evaluate((element) => getComputedStyle(element).fontSize);
  const smallFont = parseFloat(await fontSizeOf());
  const readerActions = await openReaderActions(page);
  await readerActions
    .getByRole("button", { name: "Increase text size" })
    .click();
  try {
    await page.waitForFunction(
      (initial) => {
        const element = document.querySelector(
          ".pattern-section > .reading-copy",
        );
        return (
          element !== null &&
          parseFloat(getComputedStyle(element).fontSize) > initial
        );
      },
      smallFont,
      { timeout: 5_000 },
    );
  } catch {
    throw new AcceptanceFailure(
      "reader text-size control did not update the description text " +
        `(class=${await readingCopy.getAttribute("class")}; ` +
        `font size=${await fontSizeOf()})`,
    );
  }
  const largeFont = parseFloat(await fontSizeOf());
  check(largeFont > smallFont, "reader text-size control did not grow text");
  markStage("checking complete pattern instructions in print");
  await checkPrintImageFailureAndRetry(
    browser,
    origin,
    email,
    password,
    projectId,
    markStage,
  );
  const successRequestStart = st.originalProjectImageRequests.length;
  const successResponseStart = st.originalProjectImageResponses.length;
  markStage("installing successful print instrumentation");
  await page.evaluate(
    fn(
      "() => { window.__printDialogCalls = 0; " +
        "window.__printEvents = []; " +
        "window.__printImageExpectedUrls = Array.from(" +
        "document.querySelectorAll('.print-reference-image')).map(image => " +
        "{ try { return new URL(image.dataset.printSrc, location.href).href; } " +
        "catch { return ''; } }); " +
        "window.__printImageState = []; " +
        "window.print = () => { " +
        "window.__printDialogCalls += 1; " +
        "window.__printStack = new Error().stack; " +
        "window.__printImageState = Array.from(document.querySelectorAll(" +
        "'.print-reference-image')).map((image, index) => ({ " +
        "complete: image.complete, loaded: image.naturalWidth > 0, " +
        "sameOrigin: image.currentSrc.startsWith(location.origin + '/'), " +
        "path: image.currentSrc.includes('/media/projects/'), " +
        "currentSrcEmpty: image.currentSrc === '', " +
        "srcEqualsOriginal: image.src === " +
        "window.__printImageExpectedUrls[index], " +
        "currentSrcEqualsOriginal: image.currentSrc === " +
        "window.__printImageExpectedUrls[index] })); }; }",
    ),
  );
  markStage("clicking successful print action");
  await (
    await openReaderActions(page)
  )
    .getByRole("menuitem", { name: "Print pattern", exact: true })
    .click();
  markStage("waiting for successful print call");
  try {
    await page.waitForFunction(
      fn("() => window.__printDialogCalls > 0"),
      undefined,
      { timeout: 20_000 },
    );
  } catch (waitError) {
    const waitErrorType =
      waitError instanceof Error && waitError.name ? waitError.name : "Error";
    const firstLine =
      (waitError instanceof Error
        ? waitError.message
        : String(waitError)
      ).split("\n")[0] ?? "";
    const waitErrorSummary = firstLine
      .replace(/https?:\/\/[^\s]+/g, "<url>")
      .slice(0, 160);
    let successfulPrintDiagnostic: unknown;
    try {
      successfulPrintDiagnostic = await page.evaluate(
        fn(
          "() => ({path: location.pathname, calls: " +
            "window.__printDialogCalls, events: window.__printEvents, " +
            "stack: window.__printStack || '', " +
            "button: (() => { const node = document.querySelector(" +
            "'.print-button'); return node ? {disabled: node.disabled, " +
            "text: node.textContent?.trim() ?? ''} : null; })(), " +
            "alerts: Array.from(document.querySelectorAll(" +
            "'[role=alert]')).map(node => node.textContent?.trim() ?? ''), " +
            "images: Array.from(document.querySelectorAll(" +
            "'.print-reference-image')).map((image, index) => ({hasSource: " +
            "Boolean(image.getAttribute('src')), complete: image.complete, " +
            "loaded: image.naturalWidth > 0, currentSrcEmpty: " +
            "image.currentSrc === '', srcEqualsOriginal: image.src === " +
            "window.__printImageExpectedUrls[index], " +
            "currentSrcEqualsOriginal: image.currentSrc === " +
            "window.__printImageExpectedUrls[index]}))})",
        ),
      );
    } catch (diagnosticError) {
      successfulPrintDiagnostic = {
        pageClosed: page.isClosed(),
        diagnosticError:
          diagnosticError instanceof Error ? diagnosticError.name : "Error",
      };
    }
    await settleObservers(st);
    throw new AcceptanceFailure(
      "successful print did not reach window.print; " +
        `wait_error_type=${waitErrorType}, ` +
        `wait_error_category=${playwrightErrorCategory(waitError)}, ` +
        `wait_error_summary=${JSON.stringify(waitErrorSummary)}, ` +
        `page_error_types=${JSON.stringify(st.pageErrorTypes.slice(0, 3))}, ` +
        JSON.stringify(successfulPrintDiagnostic),
    );
  }
  markStage("checking successful print image state");
  const buttonPrintState = (await page.evaluate(
    "window.__printImageState",
  )) as PrintImageState[];
  check(
    buttonPrintState.length === 2 &&
      buttonPrintState.every(
        (image) =>
          image.complete &&
          image.loaded &&
          image.sameOrigin &&
          image.path &&
          !image.currentSrcEmpty &&
          image.srcEqualsOriginal &&
          image.currentSrcEqualsOriginal,
      ),
    "the print action opened before all original photos loaded",
  );
  const expectedPrintPaths = (await page.evaluate(
    fn(
      "() => window.__printImageExpectedUrls.map(url => new URL(url).pathname)",
    ),
  )) as string[];
  check(
    expectedPrintPaths.length === 2 &&
      expectedPrintPaths.every((path) => path.startsWith("/media/projects/")) &&
      new Set(expectedPrintPaths).size === 2,
    "the reader did not expose two distinct full-resolution photo URLs",
  );
  const successfulPrintRequests =
    st.originalProjectImageRequests.slice(successRequestStart);
  const successfulPrintResponses =
    st.originalProjectImageResponses.slice(successResponseStart);
  const successfulOriginalPaths = new Set(
    st.originalProjectImageResponses
      .filter(([, status]) => status === 200)
      .map(([path]) => path),
  );
  const expectedPathSet = new Set(expectedPrintPaths);
  check(
    expectedPrintPaths.every((path) => successfulOriginalPaths.has(path)) &&
      successfulPrintRequests.every((path) => expectedPathSet.has(path)) &&
      successfulPrintResponses.every(
        ([path, status]) => expectedPathSet.has(path) && status === 200,
      ),
    "each full-resolution photo must have loaded from its exact URL " +
      "with HTTP 200 before printing (expected paths=" +
      `${JSON.stringify([...expectedPrintPaths].sort())}, observed requests=` +
      `${JSON.stringify(successfulPrintRequests)}, observed responses=` +
      `${JSON.stringify(successfulPrintResponses)})`,
  );
  const materialsPrintState = (await page.evaluate(
    fn(
      "() => { const disclosure = document.querySelector(" +
        "'details.materials-card'); const wasOpen = disclosure?.open ?? null; " +
        "window.dispatchEvent(new Event('beforeprint')); return {wasOpen, " +
        "openDuringPrint: disclosure?.open ?? null}; }",
    ),
  )) as { wasOpen: boolean | null; openDuringPrint: boolean | null };
  check(
    materialsPrintState.wasOpen === false &&
      materialsPrintState.openDuringPrint === true,
    "beforeprint did not synchronously open the closed Materials disclosure",
  );
  await page.waitForFunction(
    fn(
      "() => { const images = Array.from(document.querySelectorAll(" +
        "'.print-reference-image')); return images.length === 2 && " +
        "images.every(image => image.complete && image.naturalWidth > 0); }",
    ),
    undefined,
    { timeout: 10_000 },
  );
  await page.emulateMedia({ media: "print" });
  const printReferenceState: PrintReferenceState[] = await page
    .locator(
      ".photo-strip .print-reference-image, .steps-list .print-reference-image",
    )
    .evaluateAll((nodes) =>
      nodes.map((element, index) => {
        const node = element as HTMLImageElement;
        const expected = (
          window as unknown as { __printImageExpectedUrls: string[] }
        ).__printImageExpectedUrls[index];
        return {
          visible: getComputedStyle(node).display !== "none",
          complete: node.complete,
          loaded: node.naturalWidth > 0,
          currentSrcEmpty: node.currentSrc === "",
          srcEqualsOriginal: node.src === expected,
          currentSrcEqualsOriginal: node.currentSrc === expected,
        };
      }),
    );
  const printInteractiveImageState: boolean[] = await page
    .locator(".photo-strip .photo-strip__open, .steps-list .step-image")
    .evaluateAll((nodes) =>
      nodes.map((node) => getComputedStyle(node).display !== "none"),
    );
  const screenImageState: boolean[] = await page
    .locator(
      ".photo-strip .screen-reference-image, .steps-list .screen-reference-image",
    )
    .evaluateAll((nodes) =>
      nodes.map((node) => getComputedStyle(node).display !== "none"),
    );
  check(
    !(await page.locator(".reader-topline").isVisible()) &&
      (await page.locator(".steps-list > li").nth(1).isVisible()) &&
      printInteractiveImageState.length === 2 &&
      printInteractiveImageState.every(Boolean) &&
      printReferenceState.length === 2 &&
      printReferenceState.every(
        (image) =>
          image.visible &&
          image.complete &&
          image.loaded &&
          !image.currentSrcEmpty &&
          image.srcEqualsOriginal &&
          image.currentSrcEqualsOriginal,
      ) &&
      screenImageState.length === 2 &&
      !screenImageState.some(Boolean) &&
      (await page.locator(".photo-strip").isVisible()),
    "print layout omitted instructions or full-resolution reference photos",
  );
  const materialsRestoredClosed = (await page.evaluate(
    fn(
      "() => { const disclosure = document.querySelector(" +
        "'details.materials-card'); " +
        "window.dispatchEvent(new Event('afterprint')); " +
        "return disclosure?.open === false; }",
    ),
  )) as boolean;
  check(
    materialsRestoredClosed && (await materials.getAttribute("open")) === null,
    "afterprint did not restore the previously closed Materials disclosure",
  );
  await page.emulateMedia({ media: "screen" });
  const favoriteButton = await detailMenuItem(page, "Remove from favorites");
  const unfavoritePromise = page.waitForResponse(
    (response) =>
      urlPath(response.url()) === `/api/v2/projects/${projectId}/favorite` &&
      response.request().method() === "DELETE",
  );
  await favoriteButton.click();
  const unfavoriteResponse = await unfavoritePromise;
  check(
    unfavoriteResponse.status() === 200,
    "removing the project favorite failed against the Rust API",
  );
  await detailMenuItem(page, "Add to favorites");
  await page.keyboard.press("Escape");
  await maskFixtureIdentity(page);
  await prepareScreenshot(page);
  await page.screenshot({
    path: `${screenshots}/project-reader-mobile.png`,
    caret: "initial",
  });

  markStage("opening the project editor from the reader");
  const readerEditLink = page.getByRole("link", {
    name: "Edit project",
    exact: true,
  });
  await readerEditLink.waitFor({ state: "visible" });
  check(
    await readerEditLink.evaluate(
      (element) => element.getBoundingClientRect().height >= 44,
    ),
    "the reader edit link is smaller than the 44 px target minimum",
  );
  await readerEditLink.click();
  await page.waitForURL(`**/app/projects/${projectId}/edit`);
  await page
    .getByRole("heading", { name: "Edit project", exact: true })
    .waitFor({ state: "visible" });

  markStage("direct routes and explicit unfinished states");
  const routeHeadings: Array<[string, string | RegExp]> = [
    ["/app/", HOME_GREETING_NAME],
    ["/app/login", "You are already signed in."],
    ["/app/more", "More"],
    ["/app/create", "What would you like to add?"],
    ["/app/admin/users", "You do not have access to this page."],
    ["/app/admin/audit", "You do not have access to this page."],
  ];
  for (const [route, heading] of routeHeadings) {
    markStage(`checking direct route ${route}`);
    const routeResponse = await page.goto(`${origin}${route}`, {
      waitUntil: "networkidle",
    });
    check(
      routeResponse !== null && routeResponse.status() === 200,
      "a supported direct app route did not return 200",
    );
    await page.getByRole("heading", { name: heading }).waitFor({
      state: "visible",
    });
    if (route === "/app/more") {
      await checkMoreRoute(ctx);
    }
    if (route === "/app/create") {
      check(
        (await page
          .locator(".create-options a[href$='/projects/new']")
          .count()) === 1 &&
          (await page
            .locator(".create-options a[href$='/yarns/new']")
            .count()) === 1 &&
          (await page
            .getByText(
              "These creation forms are part of a later preview slice.",
            )
            .count()) === 0,
        "the create page showed stale preview copy or missing creation links",
      );
      // Create has two tiles: there is one way to add things, the editors.
      check(
        (await page.locator(".create-options > .create-card").count()) === 2 &&
          (await page
            .locator(".create-options a[href$='/imports']")
            .count()) === 0,
        "Create does not offer exactly the project and yarn tiles",
      );
      markStage("opening the old Imports link lands in the project editor");
      await page.goto(`${origin}/app/imports`);
      await page.waitForURL("**/app/projects/new");
      await page
        .getByRole("heading", { name: "New project", exact: true })
        .waitFor({ state: "visible" });
    }
  }
  markStage("checking signed-in gauge calculator and exact API result");
  await exerciseGaugeCalculator(page, origin, true, markStage, screenshots);
}

/** The `/app/more` card checks of the direct-route loop. */
async function checkMoreRoute(ctx: UiCtx): Promise<void> {
  const { page, screenshots } = ctx;
  const moreLinks = page.locator(".more-links");
  const moreCards: Array<[string, string]> = [
    ["Categories", "/categories"],
    ["Gauge calculator", "/gauge"],
  ];
  const adminCards: Array<[string, string]> = [["Admin", "/admin/users"]];
  const adminCardCounts: number[] = [];
  for (const [, href] of adminCards) {
    adminCardCounts.push(await moreLinks.locator(`a[href$='${href}']`).count());
  }
  const lastHref = await moreLinks.locator("a").last().getAttribute("href");
  check(
    adminCardCounts.length === 1 &&
      (adminCardCounts[0] === 0 || adminCardCounts[0] === 1) &&
      (await moreLinks.locator("a[href$='/admin/audit']").count()) === 0 &&
      (await page.locator(".secondary-nav a[href$='/admin/audit']").count()) ===
        0 &&
      lastHref !== null &&
      lastHref.endsWith("/about"),
    "More must show one Admin tile, no Audit tile and About last",
  );
  if (adminCardCounts[0] === 1) {
    moreCards.push(...adminCards);
  }
  moreCards.push(["About", "/about"]);
  let iconFailures = 0;
  for (const [label, href] of moreCards) {
    const card = moreLinks.locator(`a[href$='${href}']`);
    const title = card.locator(".ui-navigation-tile__title");
    const icon = card.locator(
      ".ui-navigation-tile__icon svg[aria-hidden='true'][focusable='false']",
    );
    const sidebarIcon = page.locator(
      `.secondary-nav a[href$='${href}'] .nav-icon svg`,
    );
    const description = card.locator(".ui-navigation-tile__description");
    const cardHref =
      (await card.count()) === 1 ? await card.getAttribute("href") : null;
    if (
      (await card.count()) !== 1 ||
      (await title.count()) !== 1 ||
      (await title.innerText()).trim() !== label ||
      (await description.count()) !== 1 ||
      !(await description.innerText()).trim() ||
      cardHref === null ||
      !cardHref.endsWith(href) ||
      (await icon.count()) !== 1 ||
      (await sidebarIcon.count()) !== 1
    ) {
      iconFailures += 1;
      continue;
    }
    const moreShape = await svgShapeSignature(icon);
    const sidebarShape = await svgShapeSignature(sidebarIcon);
    iconFailures += Number(!moreShape || moreShape !== sidebarShape);
  }
  const accountLink = moreLinks.getByRole("link", {
    name: "Account",
    exact: false,
  });
  const profileLinks =
    (await page
      .locator(".desktop-rail a.rail-account[href$='/app/account']")
      .count()) === 1;
  const duplicateAccounts =
    (await accountLink.count()) +
    (await page
      .locator(".secondary-nav a[href$='/account']:not(.rail-account)")
      .count());
  const staleNotices = await page
    .getByText("This area is not connected yet.")
    .count();
  if (
    (await moreLinks.locator("a").count()) < moreCards.length + 1 ||
    (await moreLinks.locator("a[target='_blank']").count()) !== 0 ||
    iconFailures !== 0 ||
    !profileLinks ||
    duplicateAccounts !== 0 ||
    staleNotices !== 0
  ) {
    await maskFixtureIdentity(page);
    await prepareScreenshot(page);
    await page.screenshot({
      path: `${screenshots}/more-route-debug.png`,
      caret: "initial",
    });
    throw new AcceptanceFailure(
      "More-route cards or profile-only Account links " +
        "did not match expectations " +
        `(icon_failures=${iconFailures}, ` +
        `duplicate_accounts=${duplicateAccounts}, ` +
        `stale_notice=${staleNotices})`,
    );
  }
  // The Android app QR code lives on the About page (desktop sidebar link).
  const viewport = page.viewportSize();
  await page.setViewportSize({ width: 1280, height: 800 });
  await assertAndroidAppCard(page);
  if (viewport) await page.setViewportSize(viewport);
}
