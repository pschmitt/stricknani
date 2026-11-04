<script lang="ts">
  import { importAvailability } from "./import-availability.ts";
  import { beforeNavigate } from "$app/navigation";
  import { onDestroy, onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import AssetChoices from "./AssetChoices.svelte";
  import ImageChoices from "./ImageChoices.svelte";
  import { createProject } from "#lib/features/projects/project-api.ts";
  import { getYarn, type YarnSummary } from "#lib/features/yarns/yarn-api.ts";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import StickyNoteField from "#lib/components/StickyNoteField.svelte";
  import YarnStashPicker from "./YarnStashPicker.svelte";
  import {
    previewImportFile,
    previewImportText,
    previewImportUrl,
    importImageSrc,
    type ImportPreview,
  } from "./import-api";
  import {
    createProjectWrite,
    fieldHint,
    importProjectEditor,
    importWarningMessage,
    isHttpUrl,
    linkedStashIds,
    type ImportProjectEditor,
  } from "./import-draft";
  import {
    isCurrentImportOperation,
    type ImportOperationIdentity,
  } from "./import-operation";
  import { resolve } from "$app/paths";
  import { session } from "#lib/session.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { msg, t } from "#lib/i18n.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  let sourceKind = $state<"url" | "text" | "file">("url");
  let sourceUrl = $state("");
  let sourceText = $state("");
  let selectedFile = $state<File | null>(null);
  let useAi = $state(true);
  let preview = $state<ImportPreview | null>(null);
  let draft = $state<ImportProjectEditor | null>(null);
  /** Stash yarns shown on the review (suggested matches and picked ones), by id. */
  let stashYarns = $state<Record<number, YarnSummary>>({});
  let busy = $state(false);
  let errorMessage = $state("");
  let successMessage = $state("");
  let createdProjectId = $state<number | null>(null);
  let generation = 0;
  let mutationGeneration = 0;
  let userGeneration = 0;
  let observedUserId = session.data?.user?.id ?? null;
  let controller: AbortController | null = null;
  let writeController: AbortController | null = null;

  const availability = $derived(importAvailability(session.data?.capabilities));
  const allowExternal = $derived(availability.url);
  const allowAi = $derived(availability.ai);
  const allowPreview = $derived(
    session.data?.capabilities.import_preview === true,
  );
  const sourceDisabled = $derived(busy || !allowPreview);
  const hasDirtyDraft = $derived(
    Boolean(draft && preview && !createdProjectId),
  );
  const hasSourceDraft = $derived(
    sourceText.trim().length > 0 ||
      sourceUrl.trim().length > 0 ||
      selectedFile !== null,
  );
  const provenance = $derived(preview?.draft.provenance ?? {});
  const browsed = $derived(
    preview?.draft.warnings.includes("ai_browsed") ?? false,
  );
  const warnings = $derived(
    (preview?.draft.warnings ?? [])
      .map((code) => ({ code, message: importWarningMessage(code, locale) }))
      .filter(
        (entry): entry is { code: string; message: string } =>
          entry.message !== null,
      ),
  );
  const hintLabels = {
    ai: msg("AI"),
    guessed: msg("Guessed"),
  } as const;
  const choiceLabels = {
    stash: msg("Use a yarn from my stash"),
    new: msg("Create a new yarn"),
    skip: msg("Don't add this yarn"),
  } as const;
  const documentKindLabels = {
    html: msg("HTML page"),
    plain_text: msg("Plain text"),
    markdown: msg("Markdown file"),
    pdf: msg("PDF document"),
    image: msg("Image"),
  } as const;

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: hasDirtyDraft || hasSourceDraft,
      busy: busy,
    })),
  );

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
    preview = null;
    draft = null;
    createdProjectId = null;
    sourceText = "";
    sourceUrl = "";
    selectedFile = null;
    useAi = true;
    errorMessage = "";
    successMessage = "";
  });

  $effect(() => {
    // Link import is the default; fall back to pasted text when it is unavailable.
    if (session.data && !allowExternal && sourceKind === "url")
      sourceKind = "text";
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
    if (!currentSession?.user || busy) return;
    errorMessage = "";
    if (!allowPreview) {
      errorMessage = t(
        "Import preview is unavailable right now. Try again later.",
        locale,
      );
      return;
    }
    if (sourceKind === "url" && !allowExternal) {
      errorMessage = t("Link imports are turned off on this server.", locale);
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
      const editor = importProjectEditor(result);
      draft = editor;
      stashYarns = {};
      createdProjectId = null;
      busy = false;
      void loadSuggestedYarns(editor, activeController.signal);
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

  /** Show the suggested stash yarns as cards; a suggestion that cannot be loaded is dropped. */
  async function loadSuggestedYarns(
    editor: ImportProjectEditor,
    signal: AbortSignal,
  ) {
    const ids = [
      ...new Set(
        editor.yarns
          .map((yarn) => yarn.suggestedId)
          .filter((id): id is number => id !== null),
      ),
    ];
    await Promise.all(
      ids.map(async (id) => {
        try {
          const yarn = await getYarn(id, signal);
          if (draft === editor) stashYarns[id] = yarn;
        } catch (error) {
          if (isAbort(error) || draft !== editor) return;
          for (const entry of editor.yarns) {
            if (entry.suggestedId === id) {
              entry.suggestedId = null;
              entry.stashId = null;
              entry.choice = "new";
            }
          }
        }
      }),
    );
  }

  async function saveProject() {
    const currentSession = session.data;
    const currentDraft = draft;
    const currentPreview = preview;
    if (!currentDraft || !currentPreview || !currentSession?.user || busy)
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
          t("Amount", locale),
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
    if (!allowPreview) {
      input.value = "";
      return;
    }
    selectedFile = input.files?.[0] ?? null;
  }
</script>

<svelte:head><title>Stricknani · {t("Imports", locale)}</title></svelte:head>

{#snippet hint(key: string)}
  {@const kind = fieldHint(provenance, key, browsed)}
  {#if kind}<small
      class="hint hint-{kind}"
      title={t("Check this value", locale)}>{t(hintLabels[kind], locale)}</small
    >{/if}
{/snippet}

<section
  class="imports-page page-container page-container--standard"
  aria-labelledby="import-page-title"
>
  <div class="page-heading">
    <PageHeader
      title={t("Import a project", locale)}
      titleId="import-page-title"
    />
  </div>

  <section class="source-card" aria-labelledby="source-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">{t("Step 1", locale)}</p>
        <h2 id="source-title">{t("Choose a source", locale)}</h2>
      </div>
      <label class="ai-toggle" class:ai-toggle--off={!allowAi}
        ><input
          type="checkbox"
          checked={allowAi && useAi}
          onchange={(event) => (useAi = event.currentTarget.checked)}
          disabled={sourceDisabled || !allowAi}
        /><span class="ai-toggle__text"
          >{t("Ask AI to improve extraction", locale)}{#if !allowAi}<small
              class="ai-toggle__reason"
              >{t("AI import is turned off on this server.", locale)}</small
            >{/if}</span
        ></label
      >
    </div>
    <div
      class="source-tabs"
      role="group"
      aria-label={t("Import source", locale)}
    >
      <Button
        variant={sourceKind === "text" ? "primary" : "secondary"}
        size="sm"
        icon="clipboard"
        type="button"
        aria-pressed={sourceKind === "text"}
        disabled={sourceDisabled}
        onclick={() => (sourceKind = "text")}
      >
        {t("Paste text", locale)}
      </Button>
      <Button
        variant={sourceKind === "file" ? "primary" : "secondary"}
        size="sm"
        icon="upload"
        type="button"
        aria-pressed={sourceKind === "file"}
        disabled={sourceDisabled}
        onclick={() => (sourceKind = "file")}
      >
        {t("Upload file", locale)}
      </Button>
      <Button
        variant={sourceKind === "url" ? "primary" : "secondary"}
        size="sm"
        icon="link"
        type="button"
        aria-pressed={sourceKind === "url"}
        disabled={!allowExternal || sourceDisabled}
        title={!allowExternal
          ? t("Link imports are turned off on this server.", locale)
          : undefined}
        onclick={() => (sourceKind = "url")}
      >
        {t("From a link", locale)}
      </Button>
    </div>
    {#if !allowExternal && allowPreview}<p class="supporting">
        {t("Link imports are turned off on this server.", locale)}
      </p>{/if}
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
          )}></textarea><small
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
          disabled={!allowExternal || sourceDisabled}
          placeholder="https://example.com/pattern"
          autocomplete="url"
        /></label
      >
      <p class="supporting">
        {t(
          "The server reads the page securely and shows thumbnails of the pictures it found; this browser never contacts the site.",
          locale,
        )}
      </p>
    {/if}
    {#if !allowPreview}
      <p class="supporting">
        {t("Import preview is unavailable right now. Try again later.", locale)}
      </p>
    {/if}
    {#if preview?.ai_fallback}<p class="notice" role="status">
        {t(
          "AI enhancement was unavailable, so this preview uses the original extraction.",
          locale,
        )}
      </p>{/if}
    <Button
      class="primary"
      variant="primary"
      icon="preview"
      disabled={sourceDisabled}
      onclick={runPreview}
    >
      {busy ? t("Preparing preview…", locale) : t("Preview and review", locale)}
    </Button>
    {#if busy}<p class="status" role="status" aria-live="polite">
        {t("Reading the source and preparing a draft…", locale)}
      </p>{/if}
    {#if busy && sourceKind === "url" && useAi && allowAi}<p
        class="status"
        role="status"
      >
        {t(
          "Reading the page… some pages take a minute when the AI opens them in a browser.",
          locale,
        )}
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
      {#each warnings as warning (warning.code)}<p class="notice" role="status">
          {warning.message}
          {#if warning.code === "looks_like_yarn_page"}
            <a href={resolve("/imports/yarn")}>{t("Import a yarn", locale)}</a>
          {/if}
        </p>{/each}
      {#if imageFacts}
        <p class="notice" role="status">
          {t("Source image details", locale)}:
          {imageFacts.format.toUpperCase()},
          {imageFacts.width} × {imageFacts.height}
          {t("pixels", locale)}
        </p>
      {/if}
      <fieldset class="review-fields" disabled={busy}>
        <div class="form-grid">
          <label class="field wide"
            ><span
              >{t("Project name", locale)} <b aria-hidden="true">*</b>
              {@render hint("project.name")}</span
            ><input
              bind:value={draft.name}
              required
              autocomplete="off"
            /></label
          >
          <label class="field"
            ><span
              >{t("Category", locale)} {@render hint("project.category")}</span
            ><input bind:value={draft.category} /></label
          >
          <label class="field"
            ><span>{t("Pattern source", locale)}</span><input
              bind:value={draft.link}
              inputmode="url"
            /></label
          >
          <label class="field wide"
            ><span
              >{t("Description", locale)}
              {@render hint("project.description")}</span
            ><textarea bind:value={draft.description} rows="3"
            ></textarea></label
          >
          <label class="field"
            ><span
              >{t("Needles", locale)} {@render hint("project.needles")}</span
            ><input bind:value={draft.needles} /></label
          >
          <label class="field"
            ><span
              >{t("Stitch sample", locale)}
              {@render hint("project.stitchSample")}</span
            ><input bind:value={draft.stitchSample} /></label
          >
          <label class="field wide"
            ><span
              >{t("Other materials", locale)}
              {@render hint("project.otherMaterials")}</span
            ><textarea bind:value={draft.otherMaterials} rows="2"
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
                    rows="4"></textarea></label
                >
                {#if step.images.length}<ImageChoices
                    title={t("Suggested step images", locale)}
                    images={step.images}
                    disabled={!allowExternal || busy}
                    external={allowExternal}
                    {locale}
                  />{/if}
              </fieldset>
            {/each}
          </div>
        {/if}

        {#if draft.imageUrls.length}<ImageChoices
            title={t("Suggested project images", locale)}
            images={draft.imageUrls}
            disabled={!allowExternal || busy}
            external={allowExternal}
            {locale}
          />{/if}

        {#if draft.assets.length}<AssetChoices
            assets={draft.assets}
            disabled={!allowExternal || busy}
            external={allowExternal}
            {locale}
          />{/if}

        <div class="subsection" aria-labelledby="import-yarn-heading">
          <h3 id="import-yarn-heading">{t("Yarn for this project", locale)}</h3>
          {#if draft.yarns.length}
            <p class="supporting">
              {t(
                "For each yarn the import found, use one from your stash, create a new one, or leave it out.",
                locale,
              )}
            </p>
          {/if}
          {#each draft.yarns as yarn, index (index)}
            {@const cardId = `import-yarn-${index}`}
            <fieldset class="step-card yarn-card">
              <legend>{yarn.name || t("Imported yarn", locale)}</legend>
              {#if yarn.amount}<p class="supporting">
                  {t("Amount", locale)}: {yarn.amount}
                </p>{/if}
              <div class="choices">
                {#each ["stash", "new", "skip"] as const as value (value)}
                  <label class="choice">
                    <input
                      type="radio"
                      name={`${cardId}-choice`}
                      {value}
                      checked={yarn.choice === value}
                      onchange={() => (yarn.choice = value)}
                    />
                    <span>{t(choiceLabels[value], locale)}</span>
                  </label>
                {/each}
              </div>
              {#if yarn.choice === "stash"}
                {#if yarn.stashId !== null && stashYarns[yarn.stashId]}
                  <div class="picked">
                    <ResourceCard
                      kind="yarn"
                      href={null}
                      imageUrl={stashYarns[yarn.stashId].preview_url}
                      name={stashYarns[yarn.stashId].name}
                      subtitle={[
                        stashYarns[yarn.stashId].brand,
                        stashYarns[yarn.stashId].colorway,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                      badge={yarn.stashId === yarn.suggestedId
                        ? t("Suggested match", locale)
                        : null}
                    />
                    {#if yarn.stashId === yarn.suggestedId}<p
                        class="supporting"
                      >
                        {t("Suggested match", locale)}
                      </p>{/if}
                  </div>
                {:else if yarn.stashId === null}
                  <p class="supporting">
                    {t("Pick a yarn from your stash.", locale)}
                  </p>
                {/if}
                <YarnStashPicker
                  {locale}
                  inputId={`${cardId}-search`}
                  label={yarn.stashId === null
                    ? t("Find a yarn in your stash", locale)
                    : t("Pick a different yarn", locale)}
                  exclude={yarn.stashId === null ? [] : [yarn.stashId]}
                  disabled={busy}
                  onPick={(picked) => {
                    stashYarns[picked.id] = picked;
                    yarn.stashId = picked.id;
                  }}
                />
              {:else if yarn.choice === "new"}
                <p class="supporting">
                  {t(
                    "This yarn is created with the project and linked to it.",
                    locale,
                  )}
                </p>
                {#if yarn.imageUrl && allowExternal}
                  <img
                    class="yarn-photo"
                    src={importImageSrc(yarn.imageUrl)}
                    alt=""
                    loading="lazy"
                  />
                {/if}
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
              {:else}
                <p class="supporting">
                  {t("This yarn will not be added to the project.", locale)}
                </p>
              {/if}
            </fieldset>
          {/each}

          <fieldset class="step-card yarn-card">
            <legend>{t("More yarn from my stash", locale)}</legend>
            {#each draft.extraStashIds as id (id)}
              <div class="picked">
                {#if stashYarns[id]}<ResourceCard
                    kind="yarn"
                    href={null}
                    imageUrl={stashYarns[id].preview_url}
                    name={stashYarns[id].name}
                    subtitle={[stashYarns[id].brand, stashYarns[id].colorway]
                      .filter(Boolean)
                      .join(" · ")}
                  />{/if}
                <Button
                  type="button"
                  variant="ghost"
                  icon="action-delete"
                  disabled={busy}
                  aria-label={`${t("Remove", locale)} ${stashYarns[id]?.name ?? ""}`}
                  onclick={() =>
                    (draft!.extraStashIds = draft!.extraStashIds.filter(
                      (other) => other !== id,
                    ))}
                >
                  {t("Remove", locale)}
                </Button>
              </div>
            {/each}
            <YarnStashPicker
              {locale}
              inputId="import-extra-yarn-search"
              label={t("Add another yarn from my stash", locale)}
              exclude={linkedStashIds(draft)}
              disabled={busy}
              onPick={(picked) => {
                stashYarns[picked.id] = picked;
                draft!.extraStashIds = [...draft!.extraStashIds, picked.id];
              }}
            />
          </fieldset>
        </div>

        <StickyNoteField
          id="import-notes"
          label={t("Notes", locale)}
          bind:value={draft.notes}
          rows={5}
          disabled={busy}
        />
      </fieldset>

      <div class="review-actions">
        {#if createdProjectId}
          <a
            class="ui-button ui-button--primary primary link-button"
            href={`../projects/${createdProjectId}`}
            ><ActionLabel icon="next"
              >{t("Open created project", locale)}</ActionLabel
            ></a
          >
          <Button
            class="secondary"
            variant="secondary"
            icon="reset"
            type="button"
            onclick={() => {
              preview = null;
              draft = null;
              createdProjectId = null;
              successMessage = "";
            }}
          >
            {t("Start another import", locale)}
          </Button>
        {:else}
          <Button
            class="primary"
            variant="primary"
            icon="create"
            type="button"
            disabled={busy}
            onclick={saveProject}
          >
            {busy
              ? t("Creating project…", locale)
              : t("Create project", locale)}
          </Button>
        {/if}
      </div>
    </section>
  {/if}
</section>

<style>
  .imports-page {
    padding-bottom: 6rem;
  }
  .page-heading {
    margin-bottom: 0.85rem;
  }
  h2,
  h3,
  p {
    margin-top: 0;
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
    border-radius: var(--radius-md);
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
  .ai-toggle__text {
    display: grid;
    gap: 0.1rem;
  }
  .ai-toggle__reason {
    color: var(--ink-muted);
    font-size: 0.8rem;
  }
  .ai-toggle--off {
    opacity: 0.7;
  }
  .ai-toggle {
    display: flex;
    min-height: 2.75rem;
    align-items: center;
    gap: 0.55rem;
    padding: 0.5rem 0.75rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    font-size: 0.9rem;
  }
  input[type="checkbox"] {
    width: 1.2rem;
    height: 1.2rem;
    accent-color: var(--accent);
  }
  .source-tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin-bottom: 1rem;
    padding-bottom: 0.15rem;
  }
  .source-tabs :global(.ui-button) {
    flex: 1 1 8rem;
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
  }
  .field textarea {
    resize: vertical;
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
    border-radius: var(--radius-sm);
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
  .status,
  .notice,
  .feedback {
    padding: 0.75rem 0.9rem;
    border-radius: var(--radius-xs);
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
  .hint {
    display: inline-block;
    margin-left: 0.35rem;
    padding: 0.05rem 0.45rem;
    border-radius: 99px;
    font-size: 0.7rem;
    font-weight: 650;
    vertical-align: middle;
  }
  .hint-ai {
    color: var(--accent-strong);
    background: var(--accent-wash);
  }
  .hint-guessed {
    color: var(--ink-muted);
    background: var(--surface-raised);
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
    border-radius: var(--radius-sm);
  }
  .step-card legend {
    padding: 0 0.4rem;
    color: var(--accent-strong);
    font-weight: 720;
  }
  .choices {
    display: grid;
    gap: 0.35rem;
    margin: 0.4rem 0 0.8rem;
  }
  .choice {
    display: flex;
    min-height: 44px;
    align-items: center;
    gap: 0.6rem;
    font-weight: 620;
  }
  .choice input {
    width: 1.25rem;
    height: 1.25rem;
    flex: none;
  }
  .picked {
    display: grid;
    gap: 0.4rem;
    margin: 0.4rem 0 0.8rem;
  }
  .yarn-photo {
    width: 4.5rem;
    height: 4.5rem;
    border-radius: var(--radius-xs);
    object-fit: cover;
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
    .review-actions :global(.ui-button) {
      flex: 1 1 100%;
    }
  }
</style>
