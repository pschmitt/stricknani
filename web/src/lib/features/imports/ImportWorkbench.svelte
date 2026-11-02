<script lang="ts">
  import { beforeNavigate } from "$app/navigation";
  import { onDestroy } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import ImageChoices from "./ImageChoices.svelte";
  import { createProject } from "#lib/features/projects/project-api.ts";
  import { createYarn } from "#lib/features/yarns/yarn-api.ts";
  import {
    previewImportFile,
    previewImportText,
    previewImportUrl,
    type ImportPreview,
  } from "./import-api";
  import {
    createProjectWrite,
    createYarnWrite,
    importProjectEditor,
    isHttpUrl,
    type ImportProjectEditor,
  } from "./import-draft";
  import {
    isCurrentImportOperation,
    type ImportOperationIdentity,
  } from "./import-operation";
  import { session } from "#lib/session.svelte.ts";
  import { msg, t } from "#lib/i18n.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  let sourceKind = $state<"url" | "text" | "file">("text");
  let sourceUrl = $state("");
  let sourceText = $state("");
  let selectedFile = $state<File | null>(null);
  let useAi = $state(false);
  let preview = $state<ImportPreview | null>(null);
  let draft = $state<ImportProjectEditor | null>(null);
  let busy = $state(false);
  let busyYarn = $state<number | null>(null);
  let errorMessage = $state("");
  let successMessage = $state("");
  let createdProjectId = $state<number | null>(null);
  let generation = 0;
  let mutationGeneration = 0;
  let userGeneration = 0;
  let observedUserId = session.data?.user?.id ?? null;
  let controller: AbortController | null = null;
  let writeController: AbortController | null = null;

  const allowExternal = $derived(
    session.data?.capabilities.external_imports === true,
  );
  const allowAi = $derived(session.data?.capabilities.ai === true);
  const sourceDisabled = $derived(busy || busyYarn !== null);
  const hasDirtyDraft = $derived(
    Boolean(draft && preview && !createdProjectId),
  );
  const documentKindLabels = {
    html: msg("HTML page"),
    plain_text: msg("Plain text"),
    markdown: msg("Markdown file"),
    pdf: msg("PDF document"),
    image: msg("Image"),
  } as const;

  $effect(() => {
    const currentUserId = session.data?.user?.id ?? null;
    if (currentUserId === observedUserId) return;
    observedUserId = currentUserId;
    userGeneration += 1;
    generation += 1;
    mutationGeneration += 1;
    controller?.abort();
    writeController?.abort();
    controller = null;
    writeController = null;
    busy = false;
    busyYarn = null;
    preview = null;
    draft = null;
    createdProjectId = null;
    sourceText = "";
    sourceUrl = "";
    selectedFile = null;
    useAi = false;
    errorMessage = "";
    successMessage = "";
  });

  $effect(() => {
    if (!hasDirtyDraft || typeof window === "undefined") return;
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warnBeforeUnload);
    return () => window.removeEventListener("beforeunload", warnBeforeUnload);
  });

  beforeNavigate(({ cancel }) => {
    if (!hasDirtyDraft || typeof window === "undefined") return;
    if (
      !window.confirm(
        t("Leave this import review? Your changes will be lost.", locale),
      )
    ) {
      cancel();
    }
  });

  onDestroy(() => {
    generation += 1;
    mutationGeneration += 1;
    controller?.abort();
    writeController?.abort();
  });

  function isCurrentOperation(
    captured: ImportOperationIdentity,
    operationGeneration: number,
  ): boolean {
    return isCurrentImportOperation(captured, {
      userId: session.data?.user?.id ?? -1,
      userGeneration,
      operationGeneration,
      draft,
      preview,
    });
  }

  async function runPreview() {
    const currentSession = session.data;
    if (!currentSession?.user || busy || busyYarn !== null) return;
    errorMessage = "";
    if (sourceKind === "url" && !allowExternal) {
      errorMessage = t("URL imports are disabled for this account.", locale);
      return;
    }
    if (sourceKind === "url" && !isHttpUrl(sourceUrl)) {
      errorMessage = t("Enter a valid HTTP or HTTPS URL.", locale);
      return;
    }
    if (sourceKind === "text" && !sourceText.trim()) {
      errorMessage = t("Paste text to preview.", locale);
      return;
    }
    if (sourceKind === "file" && !selectedFile) {
      errorMessage = t("Choose a document to preview.", locale);
      return;
    }
    if (
      hasDirtyDraft &&
      !window.confirm(
        t("Replace this import review? Your edits will be lost.", locale),
      )
    ) {
      return;
    }
    successMessage = "";
    const activeGeneration = ++generation;
    const capturedDraft = draft;
    const capturedPreview = preview;
    const identity: ImportOperationIdentity = {
      userId: currentSession.user.id,
      userGeneration,
      operationGeneration: activeGeneration,
      draft: capturedDraft,
      preview: capturedPreview,
    };
    controller?.abort();
    const activeController = new AbortController();
    controller = activeController;
    busy = true;
    try {
      const result =
        sourceKind === "url"
          ? await previewImportUrl(
              sourceUrl.trim(),
              useAi && allowAi,
              currentSession,
              activeController.signal,
            )
          : sourceKind === "file"
            ? await previewImportFile(
                selectedFile!,
                useAi && allowAi,
                currentSession,
                activeController.signal,
              )
            : await previewImportText(
                sourceText,
                useAi && allowAi,
                currentSession,
                activeController.signal,
              );
      if (!isCurrentOperation(identity, generation)) return;
      preview = result;
      draft = importProjectEditor(result);
      createdProjectId = null;
      busy = false;
    } catch (error) {
      if (!isCurrentOperation(identity, generation) || isAbort(error)) return;
      errorMessage = formatError(error);
    } finally {
      if (isCurrentOperation(identity, generation)) {
        busy = false;
        if (controller === activeController) controller = null;
      }
    }
  }

  async function saveYarn(index: number) {
    const currentSession = session.data;
    const currentDraft = draft;
    const currentPreview = preview;
    if (
      !currentDraft ||
      !currentPreview ||
      !currentSession?.user ||
      busy ||
      busyYarn !== null
    )
      return;
    const yarn = currentDraft.yarns[index];
    if (!yarn || yarn.createdId !== null) return;
    if (!yarn.name.trim()) {
      errorMessage = t("Add a name before creating this yarn.", locale);
      return;
    }
    busyYarn = index;
    errorMessage = "";
    const operation = ++mutationGeneration;
    const identity: ImportOperationIdentity = {
      userId: currentSession.user.id,
      userGeneration,
      operationGeneration: operation,
      draft: currentDraft,
      preview: currentPreview,
    };
    try {
      const saved = await createYarn(
        createYarnWrite(yarn, currentPreview.is_ai_enhanced),
        currentSession,
      );
      if (
        !isCurrentOperation(identity, mutationGeneration) ||
        currentDraft.yarns[index] !== yarn
      )
        return;
      yarn.createdId = saved.id;
      successMessage = t(
        "Yarn added to your library and linked for this project.",
        locale,
      );
    } catch (error) {
      if (isCurrentOperation(identity, mutationGeneration))
        errorMessage = formatError(error);
    } finally {
      if (isCurrentOperation(identity, mutationGeneration)) busyYarn = null;
    }
  }

  async function saveProject() {
    const currentSession = session.data;
    const currentDraft = draft;
    const currentPreview = preview;
    if (
      !currentDraft ||
      !currentPreview ||
      !currentSession?.user ||
      busy ||
      busyYarn !== null
    )
      return;
    if (!currentDraft.name.trim()) {
      errorMessage = t("Add a project name before creating it.", locale);
      return;
    }
    busy = true;
    errorMessage = "";
    successMessage = "";
    const operation = ++mutationGeneration;
    const identity: ImportOperationIdentity = {
      userId: currentSession.user.id,
      userGeneration,
      operationGeneration: operation,
      draft: currentDraft,
      preview: currentPreview,
    };
    const activeController = new AbortController();
    writeController?.abort();
    writeController = activeController;
    try {
      const saved = await createProject(
        createProjectWrite(
          currentDraft,
          currentPreview.is_ai_enhanced,
          allowExternal,
          (number) =>
            t("Step {number}", locale).replace("{number}", String(number)),
        ),
        currentSession,
        activeController.signal,
      );
      if (!isCurrentOperation(identity, mutationGeneration)) return;
      createdProjectId = saved.id;
      successMessage = t(
        "Project created. Your reviewed import is saved.",
        locale,
      );
    } catch (error) {
      if (isCurrentOperation(identity, mutationGeneration))
        errorMessage = formatError(error);
    } finally {
      if (isCurrentOperation(identity, mutationGeneration)) {
        busy = false;
        if (writeController === activeController) writeController = null;
      }
    }
  }

  function formatError(error: unknown): string {
    if (!(error instanceof ApiError))
      return error instanceof Error
        ? error.message
        : t("Import failed. Your source and edits are still here.", locale);
    if (error.status === 429) {
      const wait = error.retryAfterSeconds;
      return wait === undefined
        ? t("Too many requests. Wait a moment, then try again.", locale)
        : t(
            "Too many requests. Try again in {seconds} seconds.",
            locale,
          ).replace("{seconds}", String(wait));
    }
    const message =
      error.message ||
      t("Import failed. Your source and edits are still here.", locale);
    return `${message} ${t("Your source and edits are still here.", locale)}`;
  }

  function isAbort(error: unknown): boolean {
    return error instanceof DOMException && error.name === "AbortError";
  }

  function fileChanged(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    selectedFile = input.files?.[0] ?? null;
  }
</script>

<svelte:head><title>Stricknani · {t("Imports", locale)}</title></svelte:head>

<section class="imports-page" aria-labelledby="import-page-title">
  <header class="page-heading">
    <p class="eyebrow">{t("Bring a pattern into your library", locale)}</p>
    <h1 id="import-page-title">{t("Import a project", locale)}</h1>
    <p>
      {t(
        "Choose a source, review every extracted detail, then create a normal project.",
        locale,
      )}
    </p>
  </header>

  <section class="source-card" aria-labelledby="source-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">{t("Step 1", locale)}</p>
        <h2 id="source-title">{t("Choose a source", locale)}</h2>
      </div>
      {#if allowAi}
        <label class="ai-toggle"
          ><input
            type="checkbox"
            bind:checked={useAi}
            disabled={sourceDisabled}
          /><span>{t("Ask AI to improve extraction", locale)}</span></label
        >
      {/if}
    </div>
    <div
      class="source-tabs"
      role="group"
      aria-label={t("Import source", locale)}
    >
      <button
        class:active={sourceKind === "text"}
        type="button"
        aria-pressed={sourceKind === "text"}
        disabled={sourceDisabled}
        onclick={() => (sourceKind = "text")}>{t("Paste text", locale)}</button
      >
      <button
        class:active={sourceKind === "file"}
        type="button"
        aria-pressed={sourceKind === "file"}
        disabled={sourceDisabled}
        onclick={() => (sourceKind = "file")}>{t("Upload file", locale)}</button
      >
      <button
        class:active={sourceKind === "url"}
        type="button"
        aria-pressed={sourceKind === "url"}
        disabled={!allowExternal || sourceDisabled}
        title={!allowExternal
          ? t("URL imports are disabled for this account.", locale)
          : undefined}
        onclick={() => (sourceKind = "url")}>{t("From a link", locale)}</button
      >
    </div>
    {#if sourceKind === "text"}
      <label class="field"
        ><span>{t("Pattern text or instructions", locale)}</span><textarea
          bind:value={sourceText}
          disabled={sourceDisabled}
          rows="7"
          maxlength="5242880"
          placeholder={t(
            "Paste pattern instructions, notes, or a recipe…",
            locale,
          )}
        ></textarea><small
          >{t("Text and files are limited to 5 MiB.", locale)}</small
        ></label
      >
    {:else if sourceKind === "file"}
      <label class="file-picker"
        ><span class="file-icon" aria-hidden="true">↑</span><strong
          >{selectedFile?.name ?? t("Choose a file", locale)}</strong
        ><span
          >{t(
            "HTML, text, Markdown, PDF, or image · up to 5 MiB",
            locale,
          )}</span
        ><input
          type="file"
          disabled={sourceDisabled}
          accept=".html,.htm,.txt,.md,.markdown,.pdf,image/*"
          onchange={fileChanged}
        /></label
      >
    {:else}
      <label class="field"
        ><span>{t("Pattern page URL", locale)}</span><input
          type="url"
          bind:value={sourceUrl}
          disabled={sourceDisabled}
          placeholder="https://example.com/pattern"
          autocomplete="url"
        /></label
      >
      <p class="supporting">
        {t(
          "The server reads the page securely. Suggested image links are never fetched by this browser.",
          locale,
        )}
      </p>
    {/if}
    {#if !allowAi}<p class="supporting">
        {t(
          "AI enhancement is unavailable. You can still preview and review the extracted draft.",
          locale,
        )}
      </p>{/if}
    {#if preview?.ai_fallback}<p class="notice" role="status">
        {t(
          "AI enhancement was unavailable, so this preview uses the original extraction.",
          locale,
        )}
      </p>{/if}
    <button class="primary" disabled={sourceDisabled} onclick={runPreview}
      >{busy
        ? t("Preparing preview…", locale)
        : t("Preview and review", locale)}</button
    >
    {#if busy}<p class="status" role="status" aria-live="polite">
        {t("Reading the source and preparing a draft…", locale)}
      </p>{/if}
  </section>

  {#if errorMessage}<p class="feedback error" role="alert">
      {errorMessage}
    </p>{/if}
  {#if successMessage}<p class="feedback success" role="status">
      {successMessage}
    </p>{/if}

  {#if preview && draft}
    {@const imageFacts = preview.draft.image}
    <section class="review-card" aria-labelledby="review-title">
      <div class="section-heading">
        <div>
          <p class="eyebrow">{t("Step 2", locale)}</p>
          <h2 id="review-title">{t("Review and correct", locale)}</h2>
        </div>
        <span class="source-kind"
          >{t(documentKindLabels[preview.draft.kind], locale)}</span
        >
      </div>
      <p class="supporting">
        {preview.is_ai_enhanced
          ? t(
              "AI-assisted extraction. Check every detail before saving.",
              locale,
            )
          : t(
              "Review the extracted fields. Nothing is created until you choose a save action.",
              locale,
            )}
      </p>
      {#if imageFacts}
        <p class="notice" role="status">
          {t("Source image details", locale)}:
          {imageFacts.format.toUpperCase()},
          {imageFacts.width} × {imageFacts.height}
          {t("pixels", locale)}
        </p>
      {/if}
      <fieldset class="review-fields" disabled={busy || busyYarn !== null}>
        <div class="form-grid">
          <label class="field wide"
            ><span>{t("Project name", locale)} <b aria-hidden="true">*</b></span
            ><input
              bind:value={draft.name}
              required
              autocomplete="off"
            /></label
          >
          <label class="field"
            ><span>{t("Category", locale)}</span><input
              bind:value={draft.category}
            /></label
          >
          <label class="field"
            ><span>{t("Pattern source", locale)}</span><input
              bind:value={draft.link}
              inputmode="url"
            /></label
          >
          <label class="field wide"
            ><span>{t("Description", locale)}</span><textarea
              bind:value={draft.description}
              rows="3"
            ></textarea></label
          >
          <label class="field"
            ><span>{t("Needles", locale)}</span><input
              bind:value={draft.needles}
            /></label
          >
          <label class="field"
            ><span>{t("Stitch sample", locale)}</span><input
              bind:value={draft.stitchSample}
            /></label
          >
          <label class="field wide"
            ><span>{t("Other materials", locale)}</span><textarea
              bind:value={draft.otherMaterials}
              rows="2"
            ></textarea></label
          >
        </div>

        {#if draft.steps.length}
          <div class="subsection">
            <h3>{t("Instructions", locale)}</h3>
            {#each draft.steps as step, index (index)}
              <fieldset class="step-card">
                <legend
                  >{t("Step {number}", locale).replace(
                    "{number}",
                    String(step.stepNumber),
                  )}</legend
                >
                <label class="field"
                  ><span>{t("Step title", locale)}</span><input
                    bind:value={step.title}
                  /></label
                >
                <label class="field"
                  ><span>{t("Instructions", locale)}</span><textarea
                    bind:value={step.description}
                    rows="4"
                  ></textarea></label
                >
                {#if step.images.length}<ImageChoices
                    title={t("Suggested step images", locale)}
                    images={step.images}
                    disabled={!allowExternal || busy || busyYarn !== null}
                    {locale}
                  />{/if}
              </fieldset>
            {/each}
          </div>
        {/if}

        {#if draft.imageUrls.length}<ImageChoices
            title={t("Suggested project images", locale)}
            images={draft.imageUrls}
            disabled={!allowExternal || busy || busyYarn !== null}
            {locale}
          />{/if}

        {#if draft.yarns.length}
          <div class="subsection">
            <h3>{t("Yarn from this import", locale)}</h3>
            <p class="supporting">
              {t(
                "Create yarn separately. It will be linked to this project only after it is saved.",
                locale,
              )}
            </p>
            {#each draft.yarns as yarn, index (index)}
              <fieldset class="step-card yarn-card">
                <legend>{t("Imported yarn", locale)}</legend>
                <label class="field"
                  ><span>{t("Yarn name", locale)}</span><input
                    bind:value={yarn.name}
                  /></label
                >
                <div class="form-grid compact">
                  <label class="field"
                    ><span>{t("Brand", locale)}</span><input
                      bind:value={yarn.brand}
                    /></label
                  >
                  <label class="field"
                    ><span>{t("Colorway", locale)}</span><input
                      bind:value={yarn.colorway}
                    /></label
                  >
                  <label class="field"
                    ><span>{t("Fiber content", locale)}</span><input
                      bind:value={yarn.fiberContent}
                    /></label
                  >
                  <label class="field"
                    ><span>{t("Weight category", locale)}</span><input
                      bind:value={yarn.weightCategory}
                    /></label
                  >
                  <label class="field"
                    ><span>{t("Weight in grams", locale)}</span><input
                      type="number"
                      min="0"
                      step="1"
                      bind:value={yarn.weightGrams}
                    /></label
                  >
                  <label class="field"
                    ><span>{t("Length in meters", locale)}</span><input
                      type="number"
                      min="0"
                      step="1"
                      bind:value={yarn.lengthMeters}
                    /></label
                  >
                </div>
                {#if yarn.source.imageUrl}<p class="supporting">
                    {t("Image URL suggestion (not fetched):", locale)}
                    <code>{yarn.source.imageUrl}</code>
                  </p>{/if}
                {#if yarn.createdId !== null}<p class="notice" role="status">
                    {t("Saved and ready to link to this project.", locale)}
                  </p>
                {:else}<button
                    class="secondary"
                    disabled={busyYarn !== null || !yarn.name.trim()}
                    onclick={() => saveYarn(index)}
                    >{busyYarn === index
                      ? t("Saving yarn…", locale)
                      : t("Save yarn to library", locale)}</button
                  >{/if}
              </fieldset>
            {/each}
          </div>
        {/if}

        <label class="field"
          ><span>{t("Notes", locale)}</span><textarea
            bind:value={draft.notes}
            rows="3"
          ></textarea></label
        >
        {#if draft.additionalDetails}<details class="details">
            <summary>{t("Other extracted details", locale)}</summary><label
              class="field"
              ><span
                >{t(
                  "Review and edit these details; they will be kept in project notes.",
                  locale,
                )}</span
              ><textarea bind:value={draft.additionalDetails} rows="5"
              ></textarea></label
            >
          </details>{/if}
      </fieldset>

      <div class="review-actions">
        {#if createdProjectId}
          <a
            class="primary link-button"
            href={`../projects/${createdProjectId}`}
            >{t("Open created project", locale)} →</a
          >
          <button
            class="secondary"
            onclick={() => {
              preview = null;
              draft = null;
              createdProjectId = null;
              successMessage = "";
            }}>{t("Start another import", locale)}</button
          >
        {:else}
          <button
            class="primary"
            disabled={busy || busyYarn !== null}
            onclick={saveProject}
            >{busy
              ? t("Creating project…", locale)
              : t("Create project", locale)}</button
          >
        {/if}
      </div>
    </section>
  {/if}
</section>

<style>
  .imports-page {
    width: min(100% - 2rem, 58rem);
    margin: 0 auto;
    padding: 2rem 0 6rem;
  }
  .page-heading {
    margin-bottom: 1.4rem;
  }
  h1,
  h2,
  h3,
  p {
    margin-top: 0;
  }
  h1 {
    margin-bottom: 0.4rem;
    font-size: clamp(1.8rem, 6vw, 2.5rem);
    letter-spacing: -0.04em;
  }
  h2 {
    margin-bottom: 0;
    font-size: 1.25rem;
    letter-spacing: -0.02em;
  }
  h3 {
    margin-bottom: 0.25rem;
    font-size: 1rem;
  }
  .page-heading > p:last-child,
  .supporting {
    color: var(--ink-muted);
  }
  .eyebrow {
    margin-bottom: 0.35rem;
    color: var(--accent-strong);
    font-size: 0.76rem;
    font-weight: 750;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .source-card,
  .review-card {
    margin: 1rem 0;
    padding: clamp(1rem, 4vw, 1.5rem);
    border: 1px solid var(--line);
    border-radius: 1.1rem;
    background: var(--surface);
    box-shadow: var(--shadow);
  }
  .section-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 1rem;
    margin-bottom: 1rem;
  }
  .ai-toggle {
    display: flex;
    min-height: 2.75rem;
    align-items: center;
    gap: 0.55rem;
    padding: 0.5rem 0.75rem;
    border: 1px solid var(--line);
    border-radius: 0.75rem;
    font-size: 0.9rem;
  }
  input[type="checkbox"] {
    width: 1.2rem;
    height: 1.2rem;
    accent-color: var(--accent);
  }
  .source-tabs {
    display: flex;
    gap: 0.5rem;
    overflow-x: auto;
    margin-bottom: 1rem;
    padding-bottom: 0.15rem;
  }
  .source-tabs button,
  .secondary {
    min-height: 2.75rem;
    padding: 0.55rem 0.9rem;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    background: var(--surface-raised);
    font-weight: 650;
    cursor: pointer;
  }
  .source-tabs button.active {
    border-color: var(--accent);
    color: var(--accent-strong);
    background: var(--accent-wash);
  }
  .source-tabs button:disabled {
    cursor: not-allowed;
  }
  .field {
    display: grid;
    gap: 0.4rem;
    margin: 0.85rem 0;
    font-weight: 620;
  }
  .field input,
  .field textarea {
    width: 100%;
    min-width: 0;
    min-height: 2.75rem;
    padding: 0.7rem 0.75rem;
    border: 1px solid var(--control-border);
    border-radius: 0.65rem;
    color: var(--ink);
    background: var(--surface);
    font-weight: 400;
  }
  .field textarea {
    resize: vertical;
    line-height: 1.55;
  }
  .field small {
    color: var(--ink-muted);
    font-weight: 400;
  }
  .file-picker {
    position: relative;
    display: grid;
    min-height: 8rem;
    justify-items: center;
    align-content: center;
    gap: 0.35rem;
    border: 1px dashed var(--accent);
    border-radius: 0.9rem;
    background: var(--surface-raised);
    text-align: center;
    cursor: pointer;
  }
  .file-picker input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    opacity: 0;
    cursor: pointer;
  }
  .file-icon {
    display: grid;
    width: 2rem;
    aspect-ratio: 1;
    place-items: center;
    border-radius: 50%;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 1.25rem;
  }
  .file-picker span:last-of-type {
    color: var(--ink-muted);
    font-size: 0.85rem;
  }
  .primary {
    display: inline-flex;
    min-height: 2.85rem;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    padding: 0.65rem 1rem;
    border: 0;
    border-radius: 0.75rem;
    color: var(--surface);
    background: var(--accent-strong);
    font: inherit;
    font-weight: 720;
    text-decoration: none;
    cursor: pointer;
  }
  .primary:disabled,
  .secondary:disabled {
    opacity: 0.65;
    cursor: wait;
  }
  .status,
  .notice,
  .feedback {
    padding: 0.75rem 0.9rem;
    border-radius: 0.7rem;
    background: var(--surface-raised);
  }
  .notice {
    margin: 0.8rem 0;
    border-left: 3px solid var(--accent);
  }
  .feedback {
    margin: 1rem 0;
  }
  .error {
    border-left: 3px solid var(--danger);
    color: var(--danger);
  }
  .success {
    border-left: 3px solid var(--accent);
  }
  .source-kind {
    padding: 0.25rem 0.6rem;
    border-radius: 99px;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 0.8rem;
  }
  .review-fields {
    min-width: 0;
    margin: 0;
    padding: 0;
    border: 0;
  }
  .form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 1rem;
  }
  .wide {
    grid-column: 1 / -1;
  }
  .subsection {
    margin: 1.5rem 0;
  }
  .step-card {
    min-width: 0;
    margin: 0.8rem 0;
    padding: 1rem;
    border: 1px solid var(--line);
    border-radius: 0.85rem;
  }
  .step-card legend {
    padding: 0 0.4rem;
    color: var(--accent-strong);
    font-weight: 720;
  }
  .details {
    margin: 1rem 0;
  }
  .details summary {
    min-height: 2.75rem;
    padding: 0.65rem 0;
    font-weight: 650;
    cursor: pointer;
  }
  .review-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.65rem;
    margin-top: 1.3rem;
    padding-top: 1rem;
    border-top: 1px solid var(--line);
  }
  @media (max-width: 40rem) {
    .imports-page {
      width: min(100% - 1.25rem, 58rem);
      padding-top: 1.2rem;
    }
    .section-heading {
      flex-direction: column;
    }
    .ai-toggle {
      width: 100%;
    }
    .form-grid {
      grid-template-columns: minmax(0, 1fr);
    }
    .wide {
      grid-column: auto;
    }
    .primary,
    .secondary {
      min-height: 2.9rem;
    }
    .review-actions > * {
      flex: 1 1 100%;
    }
  }
</style>
