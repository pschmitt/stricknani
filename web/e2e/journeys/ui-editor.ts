// Web UI browser journey, part C (port of ci/stricknani/web_ui_browser.py
// run_acceptance from `creating a project through the browser editor` to the
// end of the run): project editor, library density, Create page, avatar
// propagation, logout, public gauge calculator, the end-of-run assertions on
// the collected observations and the cleanup tail.
import { unlinkSync } from "node:fs";
import { join } from "node:path";
import { expect } from "@playwright/test";
import type { Response, Route } from "@playwright/test";
import {
  AcceptanceFailure,
  assertNoHorizontalOverflow,
  assertStickySaveCancelGeometry,
  check,
  clickAndHandleDialog,
  fillMarkdownField,
  maskFixtureIdentity,
  tinyPngFixture,
  waitTwoAnimationFrames,
} from "../lib/common";
import { REPO_ROOT } from "../lib/fixture";
import {
  ACCOUNT_HEADING_NAME,
  REMOVE_PICTURE_BUTTON_NAME,
  UPLOAD_PICTURE_BUTTON_NAME,
  accountSignOutButton,
  assertAccountLink,
  assertHeaderParentLink,
  assertMobileAppHeader,
  assertShellAvatar,
  clickTileDelete,
  dismissPhotoEditor,
  exerciseGaugeCalculator,
  measureLibraryDensity,
  openAccountFromProfile,
  prepareScreenshot,
  saveRemoteProjectVersion,
  selectAvatarPhoto,
  visibleHeights,
} from "../lib/ui-helpers";
import { settleObservers, urlPath, type UiCtx } from "../lib/ui-state";

/** python json.dumps(value, sort_keys=True) (key order only; log output). */
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
  return JSON.stringify(sorted(value));
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/** python `payload.get("data")` when the payload is a dict, else null. */
async function responseData(
  response: Response,
): Promise<Record<string, unknown> | null> {
  const payload: unknown = await response.json();
  const record = asRecord(payload);
  return record === null ? null : asRecord(record.data);
}

/** Part C: project editor through logout, final assertions and cleanup. */
export async function uiPartC(ctx: UiCtx): Promise<void> {
  const { page, context, st, origin, screenshots, stageFile, markStage } = ctx;

  const isResponseFor =
    (path: string, method: string) =>
    (candidate: Response): boolean =>
      urlPath(candidate.url()) === path &&
      candidate.request().method() === method;
  const lastMutation = (): [string, string] | undefined =>
    st.projectMutationRequests[st.projectMutationRequests.length - 1];
  const statusFeedback = (text: string) =>
    page.locator(".action-feedback[role='status']").filter({ hasText: text });

  markStage("checking signed-in gauge calculator and exact API result");
  await exerciseGaugeCalculator(page, origin, true, markStage, screenshots);
  markStage("creating a project through the browser editor");
  const libraryResponse = await page.goto(`${origin}/app/projects`, {
    waitUntil: "networkidle",
  });
  check(
    libraryResponse !== null && libraryResponse.status() === 200,
    "the project library did not load before project creation",
  );
  await page
    .getByRole("heading", { name: "Projects", exact: true })
    .waitFor({ state: "visible" });
  const desktopNewProject = page.locator(".desktop-create");
  check(
    (await desktopNewProject.count()) === 1,
    "the project library desktop creation link is missing",
  );
  if (await desktopNewProject.isVisible()) {
    await desktopNewProject.click();
  } else {
    // Phones: Create lives under the More tab, as its first entry.
    const moreTab = page.locator(".mobile-navigation a.more-tab");
    check(
      (await moreTab.count()) === 1 && (await moreTab.isVisible()),
      "mobile More navigation is missing before project creation",
    );
    await moreTab.click();
    await page.waitForURL("**/app/more");
    const createNavigation = page.locator(".more-links a[href='/app/create']");
    check(
      (await createNavigation.count()) === 1 &&
        (await createNavigation.isVisible()),
      "mobile Create entry is missing from More before project creation",
    );
    await createNavigation.click();
    await page.waitForURL("**/app/create");
    const mobileNewProject = page.locator(
      ".create-options a[href$='/projects/new']",
    );
    await mobileNewProject.waitFor({ state: "visible" });
    check(
      (await mobileNewProject.count()) === 1 &&
        (await mobileNewProject.isVisible()),
      "mobile project creation tile is missing",
    );
    await mobileNewProject.click();
  }
  await page.waitForURL("**/app/projects/new");
  await page
    .getByRole("heading", { name: "New project", exact: true })
    .waitFor({ state: "visible" });
  let projectOptionalDetails = page.locator(".optional-details");
  check(
    (await projectOptionalDetails.count()) === 1 &&
      (await projectOptionalDetails.getAttribute("open")) === null,
    "new project details should start collapsed",
  );
  await assertStickySaveCancelGeometry(page, "Create project");
  markStage("filling the new project form");
  markStage("filling the project name");
  await page
    .getByLabel("Project name", { exact: true })
    .fill("Soft winter throw");
  markStage("filling the project category");
  await page
    .getByLabel("Category", { exact: true })
    .selectOption({ label: "New category…" });
  const newCategory = page.getByLabel("New category", { exact: true });
  await newCategory.fill("Blankets");
  await newCategory.press("Enter");
  await page
    .getByLabel("Category", { exact: true })
    .evaluate((el: HTMLSelectElement) => {
      if (el.value !== "Blankets") throw new Error("category not selected");
    });
  markStage("filling the project needles");
  await page
    .getByLabel("Needles", { exact: true })
    .fill("4 mm circular needles");
  markStage("filling the project stitch sample");
  await fillMarkdownField(
    page.getByRole("textbox", { name: "Stitch sample", exact: true }),
    "18 stitches over 10 cm",
  );
  markStage("filling the project tags");
  await page.locator("#project-tag-entry").fill("color, texture");
  await page.locator("#project-tag-entry").press("Enter");
  await page.locator("#project-tag-entry").fill("winter");
  await page.locator("#project-tag-entry").press("Enter");
  await page
    .getByRole("button", { name: "Remove tag winter", exact: true })
    .waitFor();
  markStage("filling the project pattern");
  await projectOptionalDetails.locator("summary").click();
  check(
    (await projectOptionalDetails.getAttribute("open")) !== null,
    "More project details did not open when needed",
  );
  await fillMarkdownField(
    page.getByRole("textbox", { name: "Pattern", exact: true }),
    "Work an even textured panel.",
  );
  markStage("filling the project notes");
  const notesField = page.getByRole("textbox", { name: "Notes", exact: true });
  await fillMarkdownField(notesField, "Check the width after each repeat.");
  await page.keyboard.press("Enter");
  await page.keyboard.type("Measure the next repeat.");
  await waitTwoAnimationFrames(page);
  const notesLayout = await page
    .locator(".sticky-field .md-editor__content")
    .evaluate((element) => {
      const paragraphs = Array.from(element.children).filter(
        (child) => child.tagName === "P",
      );
      const first = paragraphs[0]?.getBoundingClientRect();
      const second = paragraphs[1]?.getBoundingClientRect();
      return {
        backgroundImage: getComputedStyle(element).backgroundImage,
        paragraphCount: paragraphs.length,
        paragraphGap: first && second ? second.top - first.bottom : Number.NaN,
        firstParagraphMargin:
          paragraphs[0] instanceof HTMLElement
            ? getComputedStyle(paragraphs[0]).marginBottom
            : "missing",
      };
    });
  check(
    notesLayout.backgroundImage === "none" &&
      notesLayout.paragraphCount === 2 &&
      Math.abs(notesLayout.paragraphGap) <= 1 &&
      notesLayout.firstParagraphMargin === "0px",
    `project notes editor has unexpected paragraph styling (${notesLayout.backgroundImage}, ${notesLayout.paragraphGap}px gap, ${notesLayout.firstParagraphMargin} margin)`,
  );
  markStage("filling the project materials");
  await fillMarkdownField(
    page.getByRole("textbox", { name: "Other materials", exact: true }),
    "Two stitch markers.",
  );
  markStage("adding the first project step");
  await page.getByRole("button", { name: "Add step", exact: true }).click();
  markStage("checking client-side project validation");
  const firstStepTitle = page.getByLabel("Step title", { exact: true }).first();
  await firstStepTitle.fill("");
  await fillMarkdownField(
    page.getByLabel("Step instructions", { exact: true }).first(),
    "Cast on and establish the border.",
  );
  await page
    .getByRole("button", { name: "Create project", exact: true })
    .click();
  await page
    .getByText("Each step needs a title of 256 characters or fewer.", {
      exact: true,
    })
    .waitFor({ state: "visible" });
  const firstStepTitleId = await firstStepTitle.getAttribute("id");
  check(
    firstStepTitleId !== null,
    "the first project step title has no stable DOM id",
  );
  await page.waitForFunction(
    (id: string) => document.activeElement?.id === id,
    firstStepTitleId,
    { timeout: 5_000 },
  );
  check(
    (await firstStepTitle.getAttribute("aria-invalid")) === "true",
    "validation did not mark the first invalid step title",
  );
  check(
    (await firstStepTitle.getAttribute("aria-describedby")) ===
      "project-steps-error" &&
      (await page.locator("#project-steps-error").innerText()) ===
        "Each step needs a title of 256 characters or fewer.",
    "the invalid step title is not associated with its validation error",
  );
  await firstStepTitle.fill("Cast on");
  markStage("searching for a yarn to associate");
  await page.getByLabel("Find yarn", { exact: true }).fill("Riverbend");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await page
    .getByText("Riverbend Merino DK", { exact: true })
    .waitFor({ state: "visible", timeout: 5_000 });
  await page.getByRole("button", { name: "Add yarn", exact: true }).click();
  markStage("selecting a photo before creating the project");
  const projectPhoto = join(REPO_ROOT, "demo_assets", "demo_image_3.jpg");
  await page
    .getByLabel("Choose project photos", { exact: true })
    .setInputFiles(projectPhoto);
  const queuedProjectPhoto = page.locator(".queued-photos li img");
  await queuedProjectPhoto.waitFor({ state: "visible" });
  await expect
    .poll(() =>
      queuedProjectPhoto.evaluate(
        (image) => (image as HTMLImageElement).naturalWidth,
      ),
    )
    .toBeGreaterThan(0);
  check(
    await queuedProjectPhoto.evaluate(
      (image) => (image as HTMLImageElement).naturalWidth > 0,
    ),
    "the selected project photo preview did not load",
  );
  markStage("submitting the new project");
  const [createProjectResponse, projectPhotoResponse] = await Promise.all([
    page.waitForResponse(isResponseFor("/api/v2/projects", "POST")),
    page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        /^\/api\/v2\/projects\/\d+\/images\/title$/.test(
          urlPath(response.url()),
        ),
    ),
    page.getByRole("button", { name: "Create project", exact: true }).click(),
  ]);
  check(
    createProjectResponse.status() === 201,
    "project creation did not succeed against the Rust API",
  );
  check(
    projectPhotoResponse.status() === 201,
    "the selected project photo did not upload during creation",
  );
  const createdProject = await responseData(createProjectResponse);
  if (createdProject === null) {
    throw new AcceptanceFailure(
      "project creation returned an invalid resource",
    );
  }
  // Typing splits at commas and spaces: "color, texture" becomes two tags.
  const expectedTags = ["color", "texture", "winter"];
  check(
    JSON.stringify(createdProject.tags) === JSON.stringify(expectedTags),
    "project creation did not split typed tags at the comma",
  );
  const createdId = createdProject.id;
  if (typeof createdId !== "number" || !Number.isInteger(createdId)) {
    throw new AcceptanceFailure(
      "project creation did not return a numeric resource ID",
    );
  }
  const editorProjectId = createdId;
  const editorPath = `/app/projects/${editorProjectId}`;
  const projectApiPath = `/api/v2/projects/${editorProjectId}`;
  const editHeading = page.getByRole("heading", {
    name: "Edit project",
    exact: true,
  });
  const detailHeading = page.getByRole("heading", {
    name: "Soft winter throw",
    exact: true,
  });
  const editLink = page.getByRole("link", {
    name: "Edit project",
    exact: true,
  });
  const openSavedEditor = async (): Promise<void> => {
    await editLink.click();
    await page.waitForURL(`**${editorPath}/edit`);
    await editHeading.waitFor({ state: "visible" });
  };

  markStage("waiting for the newly created project detail route");
  await page.waitForURL(`**${editorPath}`);
  await detailHeading.waitFor({ state: "visible" });
  await statusFeedback("Project created.").waitFor({ state: "visible" });
  markStage("checking the photo on the newly created project");
  const renderedProjectPhoto = page.locator(
    ".photo-strip .screen-reference-image",
  );
  await renderedProjectPhoto.waitFor({ state: "visible" });
  await expect
    .poll(() =>
      renderedProjectPhoto.evaluate(
        (image) => (image as HTMLImageElement).naturalWidth,
      ),
    )
    .toBeGreaterThan(0);
  check(
    await renderedProjectPhoto.evaluate(
      (image) => (image as HTMLImageElement).naturalWidth > 0,
    ),
    "the saved project photo was not rendered",
  );
  markStage("opening the saved project editor");
  await openSavedEditor();
  await page.getByLabel("Project name").waitFor({ state: "visible" });
  projectOptionalDetails = page.locator(".optional-details");
  check(
    (await projectOptionalDetails.count()) === 1 &&
      (await projectOptionalDetails.getAttribute("open")) !== null,
    "More project details should be expanded while editing",
  );
  await page.locator("#project-image-file").waitFor({ state: "attached" });
  await page
    .getByRole("button", { name: "Add photos", exact: true })
    .first()
    .waitFor({ state: "visible" });
  check(
    await page.locator("#project-image-file").isEnabled(),
    "clean saved projects did not enable media management",
  );

  markStage("removing the creation photo before subsequent editor checks");
  await clickTileDelete(page, page.locator(".reorder-item").first());
  // The removal is staged: no dialog, no request, and Save becomes available.
  await page.getByTestId("removed-media").waitFor({ state: "visible" });
  await page.locator(".reorder-item img").waitFor({ state: "detached" });
  const creationPhotoSave = page.getByRole("button", {
    name: "Save changes",
    exact: true,
  });
  check(
    (await creationPhotoSave.isEnabled()) &&
      !st.projectMutationRequests.some(
        ([method, path]) =>
          method === "DELETE" && path.startsWith(`${projectApiPath}/images/`),
      ),
    "removing the creation photo reached the server before Save",
  );
  await creationPhotoSave.click();
  await page.waitForURL(`**${editorPath}`);
  await statusFeedback("What you removed is in the trash").waitFor({
    state: "visible",
  });
  await openSavedEditor();
  await page.locator(".reorder-item img").waitFor({ state: "detached" });
  await page.getByTestId("saved-state").waitFor({ state: "visible" });

  markStage("checking project editor Cancel dirty guard");
  let projectName = page.getByLabel("Project name", { exact: true });
  const cancelLink = page.getByRole("link", { name: "Cancel", exact: true });
  check(
    (await cancelLink.getAttribute("href")) === editorPath,
    "project editor Cancel does not target the saved project",
  );
  await projectName.fill("Canceled project draft");
  await clickAndHandleDialog(page, cancelLink, { accept: false });
  check(
    urlPath(page.url()).endsWith(`${editorPath}/edit`) &&
      (await projectName.inputValue()) === "Canceled project draft",
    "canceling dirty ProjectEditor navigation did not retain the draft",
  );
  await clickAndHandleDialog(page, cancelLink, { accept: true });
  await page.waitForURL(`**${editorPath}`);
  await detailHeading.waitFor({ state: "visible" });
  await openSavedEditor();

  markStage("preserving tags during an unrelated edit");
  await page
    .getByLabel("Needles", { exact: true })
    .fill("5 mm circular needles");
  const saveChangesButton = page.getByRole("button", {
    name: "Save changes",
    exact: true,
  });
  await expect(saveChangesButton).toBeEnabled();
  await waitTwoAnimationFrames(page);
  const projectSaveUrl = `**/api/v2/projects/${editorProjectId}`;
  const heldProjectSaves: Route[] = [];
  const holdProjectSave = async (route: Route): Promise<void> => {
    if (route.request().method() === "PUT") {
      heldProjectSaves.push(route);
    } else {
      await route.continue();
    }
  };
  await page.route(projectSaveUrl, holdProjectSave);
  let saveRequestCount = st.projectMutationRequests.length;
  markStage("holding a shortcut save to verify busy state and draft retention");
  await page.keyboard.press("Control+s");
  const saveButton = page
    .locator(".sticky-action-bar .ui-button")
    .filter({ hasText: "Saving" });
  await saveButton.waitFor({ state: "visible" });
  const heldMutation = lastMutation();
  check(
    heldProjectSaves.length === 1 &&
      st.projectMutationRequests.length === saveRequestCount + 1 &&
      heldMutation !== undefined &&
      heldMutation[0] === "PUT" &&
      heldMutation[1] === projectApiPath &&
      (await saveButton.count()) === 1 &&
      (await saveButton.isDisabled()) &&
      (await page.getByLabel("Needles", { exact: true }).inputValue()) ===
        "5 mm circular needles",
    "Ctrl+S did not start one disabled, draft-preserving project save",
  );
  await page.keyboard.press("Control+s");
  await waitTwoAnimationFrames(page);
  check(
    st.projectMutationRequests.length === saveRequestCount + 1,
    "a second Ctrl+S submitted a duplicate while project save was pending",
  );
  await heldProjectSaves[0].fulfill({
    status: 503,
    contentType: "application/json",
    body:
      '{"error":{"code":"internal_error",' +
      '"message":"The server could not complete this request. ' +
      'Try again."}}',
  });
  await page.unroute(projectSaveUrl, holdProjectSave);
  await page
    .getByRole("alert")
    .filter({
      hasText: "The server could not complete this request. Try again.",
    })
    .first()
    .waitFor({ state: "visible" });
  await expect(saveChangesButton).toBeEnabled();
  check(
    (await page.getByLabel("Needles", { exact: true }).inputValue()) ===
      "5 mm circular needles" && (await saveChangesButton.isEnabled()),
    "a failed project save lost its draft or kept Save changes disabled",
  );
  saveRequestCount = st.projectMutationRequests.length;
  const [tagPreservationResponse] = await Promise.all([
    page.waitForResponse(isResponseFor(projectApiPath, "PUT")),
    page.getByRole("button", { name: "Save changes", exact: true }).click(),
  ]);
  check(
    tagPreservationResponse.status() === 200,
    "an unrelated project edit did not save",
  );
  check(
    st.projectMutationRequests.length === saveRequestCount + 1,
    "project save retry produced more than one mutation request",
  );
  const tagPreservedProject = await responseData(tagPreservationResponse);
  check(
    tagPreservedProject !== null &&
      JSON.stringify(tagPreservedProject.tags) === JSON.stringify(expectedTags),
    "an unrelated edit split or otherwise changed an existing tag label",
  );
  await page.waitForURL(`**${editorPath}`);
  await detailHeading.waitFor({ state: "visible" });
  await statusFeedback("Project changes saved.").waitFor({ state: "visible" });
  await openSavedEditor();
  projectOptionalDetails = page.locator(".optional-details");
  check(
    (await projectOptionalDetails.getAttribute("open")) !== null,
    "More project details closed after returning to Edit project",
  );

  markStage("preserving editor drafts through dirty navigation and conflict");
  projectName = page.getByLabel("Project name", { exact: true });
  await projectName.fill("Chosen local draft");
  markStage("checking that media controls are disabled for an unsaved draft");
  check(
    await page.locator("#project-image-file").isDisabled(),
    "media management stayed enabled with an unsaved project draft",
  );
  markStage("checking the dirty navigation confirmation");
  await clickAndHandleDialog(
    page,
    await assertHeaderParentLink(page, editorPath, "project editor"),
    { accept: false },
  );
  markStage("checking that a canceled navigation preserves the draft");
  check(
    urlPath(page.url()).endsWith(`${editorPath}/edit`) &&
      (await projectName.inputValue()) === "Chosen local draft",
    "the dirty-navigation guard did not keep the local project draft",
  );
  await page.setViewportSize({ width: 1280, height: 844 });
  await projectName.fill("Account navigation draft");
  const accountEntry = await assertAccountLink(page, "desktop");
  markStage("checking the dirty-editor guard on Account navigation");
  await clickAndHandleDialog(page, accountEntry, { accept: false });
  check(
    urlPath(page.url()).endsWith(`${editorPath}/edit`) &&
      (await projectName.inputValue()) === "Account navigation draft",
    "canceled Account navigation did not preserve the project draft",
  );
  await projectName.fill("Chosen local draft");
  markStage("saving the concurrent project version");
  await saveRemoteProjectVersion(
    page,
    editorProjectId,
    "Concurrent saved version",
  );
  markStage("checking stale project save conflict");
  st.expectedConflictWindow = true;
  let conflictResponse: Response;
  try {
    [conflictResponse] = await Promise.all([
      page.waitForResponse(isResponseFor(projectApiPath, "PUT")),
      page.getByRole("button", { name: "Save changes", exact: true }).click(),
    ]);
  } finally {
    st.expectedConflictWindow = false;
  }
  check(
    conflictResponse.status() === 409,
    "a stale project edit did not return a conflict",
  );
  markStage("checking that the conflict retains the project draft");
  await maskFixtureIdentity(page);
  await prepareScreenshot(page);
  await page.screenshot({
    path: join(screenshots, "project-editor-conflict-debug.png"),
    caret: "initial",
  });
  if ((await page.locator(".conflict-panel").count()) !== 1) {
    throw new AcceptanceFailure(
      "a 409 response did not show the project conflict choices",
    );
  }
  const conflictHeading = page.getByRole("heading", {
    name: "This project changed in another window.",
    exact: true,
  });
  if ((await conflictHeading.count()) !== 1) {
    throw new AcceptanceFailure(
      "the conflict choices did not include their expected heading",
    );
  }
  await conflictHeading.waitFor({ state: "visible" });
  markStage("explicitly saving the project draft over latest");
  const [keepDraftResponse] = await Promise.all([
    page.waitForResponse(isResponseFor(projectApiPath, "PUT")),
    page
      .getByRole("button", { name: "Save my draft over latest", exact: true })
      .click(),
  ]);
  check(
    keepDraftResponse.status() === 200,
    "explicitly saving the project draft over latest did not succeed",
  );
  await page.waitForURL(`**${editorPath}`);
  await statusFeedback("Project changes saved.").waitFor({ state: "visible" });
  await openSavedEditor();
  projectName = page.getByLabel("Project name", { exact: true });
  check(
    (await projectName.inputValue()) === "Chosen local draft",
    "saving the editor draft over latest changed its contents",
  );

  markStage("preparing a second project conflict");
  await projectName.fill("Draft to discard");
  await saveRemoteProjectVersion(
    page,
    editorProjectId,
    "Latest server version",
  );
  markStage("checking the second stale project save");
  st.expectedConflictWindow = true;
  let secondConflictResponse: Response;
  try {
    [secondConflictResponse] = await Promise.all([
      page.waitForResponse(isResponseFor(projectApiPath, "PUT")),
      page.getByRole("button", { name: "Save changes", exact: true }).click(),
    ]);
  } finally {
    st.expectedConflictWindow = false;
  }
  check(
    secondConflictResponse.status() === 409,
    "a second stale project edit did not return a conflict",
  );
  markStage("loading latest project values by explicit choice");
  await page
    .getByRole("button", { name: "Load latest values", exact: true })
    .click();
  const loadLatestDialog = page.getByRole("dialog", {
    name: "Replace your unsaved edits with the latest saved version?",
    exact: true,
  });
  await loadLatestDialog.waitFor({ state: "visible" });
  await loadLatestDialog
    .getByRole("button", { name: "Load latest values", exact: true })
    .click();
  await loadLatestDialog.waitFor({ state: "hidden" });
  check(
    (await projectName.inputValue()) === "Latest server version",
    "explicitly loading latest values did not replace the saved draft",
  );

  markStage("saving ordered project steps and refreshing project media");
  await page.getByRole("button", { name: "Add step", exact: true }).click();
  await page.getByLabel("Step title").nth(1).fill("Finish the border");
  await fillMarkdownField(
    page.getByLabel("Step instructions", { exact: true }).nth(1),
    "Bind off loosely and weave in the ends.",
  );
  await page
    .getByRole("button", { name: "Move up Step 2", exact: true })
    .click();
  const stepTitleInputs = page.getByLabel("Step title", { exact: true });
  check(
    (await stepTitleInputs.nth(0).inputValue()) === "Finish the border" &&
      (await stepTitleInputs.nth(1).inputValue()) === "Cast on",
    "project step reordering did not update the draft order",
  );
  const [orderedStepsResponse] = await Promise.all([
    page.waitForResponse(isResponseFor(projectApiPath, "PUT")),
    page.getByRole("button", { name: "Save changes", exact: true }).click(),
  ]);
  check(
    orderedStepsResponse.status() === 200,
    "ordered project steps did not save",
  );
  const orderedProject = (await responseData(orderedStepsResponse)) ?? {};
  const orderedSteps: unknown[] = Array.isArray(orderedProject.steps)
    ? orderedProject.steps
    : [];
  check(
    JSON.stringify(orderedSteps.map((step) => asRecord(step)?.title)) ===
      JSON.stringify(["Finish the border", "Cast on"]),
    "the Rust API did not persist the reordered project steps",
  );
  await page.waitForURL(`**${editorPath}`);

  markStage("opening a Recent changes entry to see its recorded diff");
  const historySection = page.locator("[data-history-section]");
  await historySection.scrollIntoViewIfNeeded();
  const historyRows = historySection.locator(
    'li.entry[data-tone="update"] button.entry-row',
  );
  markStage("history: waiting for the latest update entry");
  await historyRows.first().waitFor({ state: "visible" });
  const latestEntryText = (
    (await historyRows.first().textContent()) ?? ""
  ).replace(/\s+/g, " ");
  const latestActor = historyRows.first().locator(".entry-actor .user-label");
  check(
    (await latestActor.locator(".user-label__name").textContent()) === "You" &&
      (await latestActor.locator(".user-avatar").count()) === 1,
    `a Recent changes entry did not show its actor avatar and name: ${latestEntryText.slice(0, 80)}`,
  );
  await historyRows.first().click();
  check(
    (await historyRows.first().getAttribute("aria-expanded")) === "true",
    "a Recent changes entry did not open when clicked",
  );
  markStage("history: reading the opened entry");
  const historyDetail = historySection.getByRole("region").first();
  await historyDetail.waitFor({ state: "visible" });
  await historyDetail
    .getByText(/Before/)
    .first()
    .waitFor({ state: "visible" });
  const historyText = ((await historyDetail.textContent()) ?? "").replace(
    /\s+/g,
    " ",
  );
  check(
    /Before/.test(historyText) && /After/.test(historyText),
    `an opened Recent changes entry did not show a before/after diff: ${historyText.slice(0, 80)}`,
  );
  await historyRows.first().click();
  check(
    (await historyRows.first().getAttribute("aria-expanded")) === "false",
    "a Recent changes entry did not close when clicked again",
  );

  await openSavedEditor();

  const imageAsset = join(REPO_ROOT, "demo_assets", "demo_image_3.jpg");
  const [editorMediaResponse] = await Promise.all([
    page.waitForResponse(
      isResponseFor(`${projectApiPath}/images/title`, "POST"),
    ),
    page.locator("#project-image-file").setInputFiles(imageAsset),
  ]);
  check(
    editorMediaResponse.status() === 201,
    "the clean project editor could not upload a project photo",
  );
  await page
    .locator("#main-content .action-status")
    .filter({ hasText: "Project media updated." })
    .waitFor({ state: "visible" });
  await page.locator(".reorder-item img").waitFor({ state: "visible" });
  check(
    (await page.locator(".reorder-item img").count()) === 1,
    "the project media list did not refresh after upload",
  );
  // The new photo opens its editor right away; close it again.
  await dismissPhotoEditor(page);

  markStage("staging a project photo removal until Save");
  const projectPhotoRemove = page.getByRole("button", {
    name: "Remove photo",
    exact: true,
  });
  check(
    (await projectPhotoRemove.count()) === 1,
    "the uploaded project photo has no unique removal action",
  );
  const imageDeleteCount = st.projectMutationRequests.length;
  await clickTileDelete(page, page.locator(".reorder-item").first());
  const removedMedia = page.getByTestId("removed-media");
  await removedMedia.waitFor({ state: "visible" });
  await page.locator(".reorder-item img").waitFor({ state: "detached" });
  check(
    st.projectMutationRequests.length === imageDeleteCount,
    "removing a project photo sent a request before Save",
  );
  await removedMedia.getByTestId("removed-media-restore").click();
  await page.locator(".reorder-item img").waitFor({ state: "visible" });
  await removedMedia.waitFor({ state: "hidden" });
  await page.getByTestId("saved-state").waitFor({ state: "visible" });
  await clickTileDelete(page, page.locator(".reorder-item").first());
  await removedMedia.waitFor({ state: "visible" });

  const projectImageDeleteUrl = `**/api/v2/projects/${editorProjectId}/images/*`;
  const heldProjectImageDeletes: Route[] = [];
  const holdProjectImageDelete = async (route: Route): Promise<void> => {
    if (route.request().method() === "DELETE") {
      heldProjectImageDeletes.push(route);
    } else {
      await route.continue();
    }
  };
  await page.route(projectImageDeleteUrl, holdProjectImageDelete);
  const saveWithRemoval = page.getByRole("button", {
    name: "Saving…",
    exact: true,
  });
  await page.getByRole("button", { name: "Save changes", exact: true }).click();
  await expect.poll(() => heldProjectImageDeletes.length).toBe(1);
  check(
    (await saveWithRemoval.count()) === 1 &&
      (await saveWithRemoval.isDisabled()),
    "saving a staged project photo removal did not hold the Save button busy",
  );
  await page.keyboard.press("Control+s");
  await waitTwoAnimationFrames(page);
  check(
    heldProjectImageDeletes.length === 1,
    "a busy save allowed a duplicate photo removal request",
  );
  await heldProjectImageDeletes[0].continue();
  await page.unroute(projectImageDeleteUrl, holdProjectImageDelete);
  await page.waitForURL(`**${editorPath}`);
  await statusFeedback("What you removed is in the trash").waitFor({
    state: "visible",
  });
  check(
    st.projectResponses.some(
      ([path, status]) =>
        path.startsWith(`${projectApiPath}/images/`) && status === 204,
    ),
    "the saved photo removal did not reach the server",
  );
  await openSavedEditor();
  await page.locator(".reorder-item img").waitFor({ state: "detached" });
  await page
    .getByText("or drop images here", { exact: true })
    .waitFor({ state: "visible" });

  markStage("confirming project deletion through the editor");
  await page
    .getByRole("button", { name: "Delete project", exact: true })
    .click();
  const projectDeleteDialog = page.getByRole("dialog", {
    name: "Delete this project?",
    exact: true,
  });
  await projectDeleteDialog.waitFor({ state: "visible" });
  await page.keyboard.press("Escape");
  await projectDeleteDialog.waitFor({ state: "hidden" });
  await page
    .getByRole("button", { name: "Delete project", exact: true })
    .click();
  await projectDeleteDialog.waitFor({ state: "visible" });
  await projectDeleteDialog
    .getByRole("button", { name: "Cancel", exact: true })
    .click();
  await projectDeleteDialog.waitFor({ state: "hidden" });
  await page
    .getByRole("button", { name: "Delete project", exact: true })
    .click();
  await projectDeleteDialog.waitFor({ state: "visible" });
  const [editorDeleteResponse] = await Promise.all([
    page.waitForResponse(isResponseFor(projectApiPath, "DELETE")),
    projectDeleteDialog
      .getByRole("button", { name: "Delete project", exact: true })
      .click(),
  ]);
  check(
    editorDeleteResponse.status() === 204,
    "project deletion did not complete with 204",
  );
  await page.locator(".library-grid").waitFor({ state: "visible" });
  check(
    (await page.getByRole("heading", { name: "Soft winter throw" }).count()) ===
      0,
    "the deleted editor project remained in the library",
  );

  markStage("checking unknown route rejection");
  st.expected404Window = true;
  let unknownStatus: number;
  try {
    const unknown = await page.request.get(`${origin}${st.expected404Path}`);
    unknownStatus = unknown.status();
  } finally {
    st.expected404Window = false;
  }
  check(unknownStatus === 404, "unknown app route was not rejected with 404");

  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  await page.locator(".app-shell").waitFor({ state: "visible" });
  markStage("checking project and yarn library density");
  const libraryDensityCases: Array<[string, string, string, string]> = [
    ["projects", "/app/projects", ".project-card", "Projects"],
    ["yarns", "/app/yarns", ".yarn-card", "Yarns"],
  ];
  for (const [
    routeName,
    routePath,
    rowSelector,
    heading,
  ] of libraryDensityCases) {
    await page.goto(`${origin}${routePath}`, { waitUntil: "networkidle" });
    await page
      .getByRole("heading", { name: heading, exact: true })
      .waitFor({ state: "visible" });
    await page.locator(rowSelector).first().waitFor({ state: "visible" });
    for (const width of [390, 1280]) {
      await page.setViewportSize({ width, height: 844 });
      await assertNoHorizontalOverflow(page, width);
      const metrics = await measureLibraryDensity(page, {
        rowSelector,
        routeName,
        width,
      });
      console.log(
        "Library density: " + sortedJson({ route: routeName, ...metrics }),
      );
    }
  }

  markStage("checking Create page icons and navigation affordances");
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${origin}/app/create`, { waitUntil: "networkidle" });
  await page
    .getByRole("heading", { name: "What would you like to add?", exact: true })
    .waitFor({ state: "visible" });
  // Two tiles: New project and New yarn. Importing happens inside those
  // editors, so Create has no separate import tiles.
  const createCards = page.locator(".create-options > .create-card");
  check(
    (await createCards.count()) === 2 &&
      (await page
        .locator(".create-options .create-card__icon svg[aria-hidden='true']")
        .count()) === 2,
    "Create choices are missing the tiles or decorative icons",
  );
  check(
    (await createCards.nth(0).getAttribute("href")) === "/app/projects/new" &&
      (await createCards.nth(1).getAttribute("href")) === "/app/yarns/new" &&
      (await visibleHeights(page, ".create-options > .create-card")).every(
        (height) => height >= 44,
      ),
    "Create choices do not expose both destinations as touch targets",
  );

  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  await page.locator(".app-shell").waitFor({ state: "visible" });
  await page.setViewportSize({ width: 1280, height: 844 });
  markStage("checking profile avatar propagation in app shell");
  await openAccountFromProfile(page);
  await selectAvatarPhoto(page, {
    name: "synthetic-avatar.png",
    mimeType: "image/png",
    buffer: tinyPngFixture(),
  });
  const [avatarUploadResponse] = await Promise.all([
    page.waitForResponse(isResponseFor("/api/v2/account/avatar", "POST")),
    page.getByRole("button", { name: UPLOAD_PICTURE_BUTTON_NAME }).click(),
  ]);
  check(
    avatarUploadResponse.status() === 200,
    "the synthetic profile picture upload did not succeed",
  );
  await page.locator(".avatar__portrait img").waitFor({ state: "visible" });
  await assertShellAvatar(page, { mobile: false, present: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await assertMobileAppHeader(page);
  await assertShellAvatar(page, { mobile: true, present: true });
  await page.reload({ waitUntil: "networkidle" });
  await page.locator(".app-shell").waitFor({ state: "visible" });
  await page
    .getByRole("heading", { name: ACCOUNT_HEADING_NAME })
    .waitFor({ state: "visible" });
  await assertShellAvatar(page, { mobile: true, present: true });

  await page.setViewportSize({ width: 1280, height: 844 });
  await assertShellAvatar(page, { mobile: false, present: true });
  const [avatarDeleteResponse] = await Promise.all([
    page.waitForResponse(isResponseFor("/api/v2/account/avatar", "DELETE")),
    page.getByRole("button", { name: REMOVE_PICTURE_BUTTON_NAME }).click(),
  ]);
  check(
    avatarDeleteResponse.status() === 204,
    "removing the synthetic profile picture did not succeed",
  );
  await page.locator(".avatar__portrait img").waitFor({ state: "detached" });
  await assertShellAvatar(page, { mobile: false, present: false });

  await page.setViewportSize({ width: 390, height: 844 });
  await assertMobileAppHeader(page);
  await assertShellAvatar(page, { mobile: true, present: false });
  await page.reload({ waitUntil: "networkidle" });
  await page.locator(".app-shell").waitFor({ state: "visible" });
  await page
    .getByRole("heading", { name: ACCOUNT_HEADING_NAME })
    .waitFor({ state: "visible" });
  await assertShellAvatar(page, { mobile: true, present: false });

  await page.goto(`${origin}/app/projects`, { waitUntil: "networkidle" });
  await page.locator(".app-shell").waitFor({ state: "visible" });
  markStage("logout and anonymous session restoration");
  await page.setViewportSize({ width: 1280, height: 844 });
  await openAccountFromProfile(page);
  const logoutButton = await accountSignOutButton(page);
  await logoutButton.click();
  await page.waitForURL("**/app/login", { timeout: 10_000 });
  await page.locator("#email").waitFor({ state: "visible" });
  await page.getByRole("heading", { name: "Sign in" }).waitFor({
    state: "visible",
  });
  check(
    st.csrfRequests.some(
      ([path, hasCsrf]) => path === "/api/v2/auth/logout" && hasCsrf,
    ),
    "logout did not send the CSRF header",
  );
  check(
    st.authResponses.some(
      ([path, status]) => path === "/api/v2/auth/logout" && status === 204,
    ),
    "logout did not complete with 204",
  );
  const remainingCookies = await context.cookies(origin);
  check(
    !remainingCookies.some((cookie) => cookie.name === "stricknani_session"),
    "logout did not clear the browser session cookie",
  );
  await page.waitForFunction(
    () => document.querySelector(".app-shell") === null,
  );
  await settleObservers(st);
  check(
    st.sessionObservations.some(
      ([status, userNull, csrf]) => status === 200 && userNull && csrf,
    ),
    "anonymous session was not restored after logout",
  );
  await page.locator("#email").fill("");
  await page.locator("#password").fill("");
  await page.setViewportSize({ width: 390, height: 844 });
  await prepareScreenshot(page);
  await page.screenshot({
    path: join(screenshots, "signed-out-after-logout.png"),
    caret: "initial",
  });
  markStage("checking public gauge calculator after logout");
  await exerciseGaugeCalculator(page, origin, false, markStage, screenshots);

  await settleObservers(st);
  check(
    st.cspErrors.length === 0,
    "CSP console violation from: " +
      [...new Set(st.cspErrors)].sort().join(", ") +
      "; details=" +
      sortedJson(st.cspEventSummaries.slice(0, 10)),
  );
  check(
    st.pageErrors.length === 0,
    `the browser reported a page error: ${st.pageErrorTypes.slice(0, 3).join(" | ")}`,
  );
  const expectedConsoleErrors = new Set([
    "expected_unknown_route_404",
    "expected_project_conflict_409",
    "expected_project_save_failure_503",
    "expected_signup_conflict_409",
    "expected_ocr_failure_5xx",
    "expected_print_image_abort",
  ]);
  check(
    st.consoleErrors.every((error) => expectedConsoleErrors.has(error)),
    "the browser reported an unexpected console error: " +
      [...new Set(st.consoleErrors)].sort().join(", ") +
      "; path/status=" +
      [...new Set(st.consoleErrorLocations)].sort().join(", "),
  );
  check(
    st.responsiveFailures.length === 0,
    st.responsiveFailures.length > 0
      ? st.responsiveFailures[0]
      : "responsive target failure",
  );
  try {
    unlinkSync(stageFile);
  } catch {
    // missing_ok
  }
  await context.close();
}
