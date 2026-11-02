<script lang="ts">
  import { beforeNavigate, goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { ApiError } from "#lib/api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { listCategories } from "../categories/category-api";
  import { getYarn, listYarns, type YarnSummary } from "../yarns/yarn-api";
  import {
    createProject,
    deleteProject,
    deleteProjectAttachment,
    deleteProjectImage,
    getProject,
    parseProjectDetail,
    updateProject,
    uploadProjectAttachment,
    uploadProjectImage,
    type ProjectCategory,
    type ProjectDetail,
    type ProjectWrite,
  } from "./project-api";
  import {
    blankProjectDraft,
    projectDraftFromDetail,
    projectWriteFromDraft,
    validateProjectDraft,
    type ProjectDraft,
  } from "./project-editor-state";

  type LoadState = "loading" | "ready" | "not-found" | "error" | "signed-out";
  type DraftField = keyof ProjectDraft;

  let { projectId }: { projectId: number | null } = $props();
  let loadState = $state<LoadState>("loading");
  let project = $state<ProjectDetail | null>(null);
  let draft = $state<ProjectDraft>(blankProjectDraft());
  let savedSnapshot = $state(JSON.stringify(blankProjectDraft()));
  let conflictProject = $state<ProjectDetail | null>(null);
  let categories = $state<ProjectCategory[]>([]);
  let categoryError = $state("");
  let yarnResults = $state<YarnSummary[]>([]);
  let selectedYarnNames = $state<Record<number, string>>({});
  let yarnQuery = $state("");
  let yarnSearchInput = $state("");
  let yarnSearchError = $state("");
  let fieldErrors = $state<Partial<Record<DraftField | "steps", string>>>({});
  let loadError = $state("");
  let serverError = $state("");
  let mediaError = $state("");
  let statusMessage = $state("");
  let saving = $state(false);
  let deleting = $state(false);
  let mediaBusy = $state(false);
  let categoryLoading = $state(false);
  let yarnLoading = $state(false);
  let mediaAltText = $state("");
  let mediaStepId = $state("");
  let tagInput = $state("");
  let stepKey = 0;
  let routeGeneration = 0;
  let categoryGeneration = 0;
  let yarnGeneration = 0;
  const operationControllers = new Set<AbortController>();

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const isDirty = $derived(
    savedSnapshot !== JSON.stringify(draft) || tagInput.trim().length > 0,
  );
  const isCreating = $derived(projectId === null);
  const backHref = $derived(
    projectId === null
      ? resolve("/projects")
      : resolve("/projects/[id]", { id: String(projectId) }),
  );
  const mediaAvailable = $derived(
    project !== null &&
      projectId === project.id &&
      !isDirty &&
      !saving &&
      !deleting &&
      !mediaBusy &&
      session.data?.user !== null &&
      session.data?.user !== undefined,
  );
  const saveDisabled = $derived(
    saving || deleting || !isDirty || conflictProject !== null,
  );
  const selectedYarns = $derived(
    draft.yarnIds.map((id) => ({
      id,
      name: selectedYarnNames[id] ?? t("Selected yarn is unavailable.", locale),
    })),
  );

  $effect(() => {
    const id = projectId;
    const ownerId = userId;
    const generation = ++routeGeneration;
    const controller = new AbortController();
    project = null;
    conflictProject = null;
    clearPrivateDraft();
    loadError = "";
    serverError = "";
    statusMessage = "";
    mediaError = "";
    saving = false;
    deleting = false;
    mediaBusy = false;

    if (ownerId === null) {
      loadState = "signed-out";
      return () => cleanupRoute(generation, controller);
    }
    if (id === null) {
      loadState = "ready";
      return () => cleanupRoute(generation, controller);
    }
    if (!Number.isSafeInteger(id) || id < 1) {
      loadState = "not-found";
      return () => cleanupRoute(generation, controller);
    }

    loadState = "loading";
    void loadProject(id, ownerId, generation, controller);
    return () => cleanupRoute(generation, controller);
  });

  $effect(() => {
    const ownerId = userId;
    const generation = ++categoryGeneration;
    if (ownerId === null) {
      categories = [];
      categoryError = "";
      categoryLoading = false;
      return;
    }
    const controller = new AbortController();
    categoryLoading = true;
    categoryError = "";
    void listCategories(controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          categoryGeneration !== generation ||
          userId !== ownerId
        )
          return;
        categories = result;
      })
      .catch((error: unknown) => {
        if (
          controller.signal.aborted ||
          categoryGeneration !== generation ||
          userId !== ownerId
        )
          return;
        categoryError = safeError(
          error,
          t("Categories could not be loaded.", locale),
        );
      })
      .finally(() => {
        if (
          controller.signal.aborted ||
          categoryGeneration !== generation ||
          userId !== ownerId
        )
          return;
        categoryLoading = false;
      });
    return () => controller.abort();
  });

  $effect(() => {
    const ownerId = userId;
    const query = yarnQuery;
    const generation = ++yarnGeneration;
    if (ownerId === null) {
      yarnResults = [];
      yarnSearchError = "";
      yarnLoading = false;
      return;
    }
    const controller = new AbortController();
    yarnLoading = true;
    yarnSearchError = "";
    const params = new URLSearchParams();
    if (query) params.set("q", query);
    void listYarns(params.toString(), controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          yarnGeneration !== generation ||
          userId !== ownerId
        )
          return;
        yarnResults = result.data;
        for (const yarn of result.data) {
          selectedYarnNames[yarn.id] = yarn.name;
        }
      })
      .catch((error: unknown) => {
        if (
          controller.signal.aborted ||
          yarnGeneration !== generation ||
          userId !== ownerId
        )
          return;
        yarnSearchError = safeError(
          error,
          t("Yarn options could not be loaded.", locale),
        );
      })
      .finally(() => {
        if (
          controller.signal.aborted ||
          yarnGeneration !== generation ||
          userId !== ownerId
        )
          return;
        yarnLoading = false;
      });
    return () => controller.abort();
  });

  beforeNavigate(({ cancel }) => {
    if (
      isDirty &&
      typeof window !== "undefined" &&
      !window.confirm(t("Leave without saving this project?", locale))
    )
      cancel();
  });

  $effect(() => {
    if (!isDirty || typeof window === "undefined") return;
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warnBeforeUnload);
    return () => window.removeEventListener("beforeunload", warnBeforeUnload);
  });

  function clearPrivateDraft(): void {
    const empty = blankProjectDraft();
    draft = empty;
    savedSnapshot = JSON.stringify(empty);
    tagInput = "";
    selectedYarnNames = {};
  }

  function cleanupRoute(generation: number, controller: AbortController): void {
    controller.abort();
    for (const pending of operationControllers) pending.abort();
    operationControllers.clear();
    if (routeGeneration === generation) routeGeneration += 1;
  }

  function isCurrentRoute(
    id: number | null,
    ownerId: number,
    generation: number,
  ): boolean {
    return (
      routeGeneration === generation && userId === ownerId && projectId === id
    );
  }

  async function loadProject(
    id: number,
    ownerId: number,
    generation: number,
    controller: AbortController,
  ): Promise<void> {
    try {
      const result = await getProject(id, controller.signal);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      const yarnNames = await Promise.all(
        result.yarn_ids.map(async (yarnId) => {
          try {
            const yarn = await getYarn(yarnId, controller.signal);
            return [yarnId, yarn.name] as const;
          } catch {
            return null;
          }
        }),
      );
      if (!isCurrentRoute(id, ownerId, generation)) return;
      project = result;
      draft = projectDraftFromDetail(result, () => ++stepKey);
      savedSnapshot = JSON.stringify(draft);
      selectedYarnNames = Object.fromEntries(
        yarnNames.filter(
          (entry): entry is readonly [number, string] => entry !== null,
        ),
      );
      loadState = "ready";
    } catch (error) {
      if (controller.signal.aborted || !isCurrentRoute(id, ownerId, generation))
        return;
      loadState =
        error instanceof ApiError && error.status === 404
          ? "not-found"
          : "error";
      loadError = safeError(
        error,
        t("The project could not be loaded.", locale),
      );
      clearPrivateDraft();
    }
  }

  function beginSearch(): void {
    yarnQuery = yarnSearchInput.trim();
  }

  function addYarn(yarn: YarnSummary): void {
    if (draft.yarnIds.includes(yarn.id)) return;
    draft.yarnIds = [...draft.yarnIds, yarn.id];
    selectedYarnNames[yarn.id] = yarn.name;
  }

  function addTag(): void {
    const tag = tagInput.trim();
    if (!tag) return;
    if (!draft.tags.includes(tag)) draft.tags = [...draft.tags, tag];
    tagInput = "";
  }

  function removeTag(index: number): void {
    draft.tags = draft.tags.filter((_, tagIndex) => tagIndex !== index);
  }

  function removeYarn(id: number): void {
    draft.yarnIds = draft.yarnIds.filter((yarnId) => yarnId !== id);
  }

  function addStep(): void {
    draft.steps = [
      ...draft.steps,
      { key: ++stepKey, title: "", description: "" },
    ];
  }

  function moveStep(index: number, offset: -1 | 1): void {
    const target = index + offset;
    if (target < 0 || target >= draft.steps.length) return;
    const steps = [...draft.steps];
    const [step] = steps.splice(index, 1);
    if (!step) return;
    steps.splice(target, 0, step);
    draft.steps = steps;
  }

  function showLocalErrors(): boolean {
    const errors = validateProjectDraft(draft);
    fieldErrors = {};
    if (errors.name === "required")
      fieldErrors.name = t("Enter a project name.", locale);
    if (errors.name === "too_long")
      fieldErrors.name = t("Use 256 characters or fewer.", locale);
    if (errors.link === "invalid_url")
      fieldErrors.link = t("Enter a complete http or https URL.", locale);
    if (errors.steps === "invalid_steps")
      fieldErrors.steps = t(
        "Each step needs a title of 256 characters or fewer.",
        locale,
      );
    return Object.keys(errors).length > 0;
  }

  function makeWrite(isAiEnhanced: boolean): ProjectWrite | null {
    if (showLocalErrors()) return null;
    return projectWriteFromDraft(draft, isAiEnhanced);
  }

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (conflictProject !== null) return;
    addTag();
    const isAiEnhanced = project?.is_ai_enhanced ?? false;
    const value = makeWrite(isAiEnhanced);
    if (!value) return;
    await saveProject(value, project?.updated_at ?? null);
  }

  async function saveDraftOverLatest(): Promise<void> {
    if (!conflictProject) return;
    addTag();
    const value = makeWrite(conflictProject.is_ai_enhanced);
    if (!value) return;
    await saveProject(value, conflictProject.updated_at);
  }

  async function saveProject(
    value: ProjectWrite,
    expectedUpdatedAt: string | null,
  ): Promise<void> {
    const currentSession = session.data;
    if (!currentSession?.user || saving || deleting) return;
    const id = projectId;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    operationControllers.add(controller);
    saving = true;
    serverError = "";
    statusMessage = "";
    fieldErrors = {};
    try {
      const result =
        id === null
          ? await createProject(value, currentSession, controller.signal)
          : expectedUpdatedAt
            ? await updateProject(
                id,
                value,
                expectedUpdatedAt,
                currentSession,
                controller.signal,
              )
            : null;
      if (!isCurrentRoute(id, ownerId, generation)) return;
      if (!result) {
        serverError = t(
          "The project version is missing. Reload the page and try again.",
          locale,
        );
        return;
      }
      applyLoadedProject(result);
      if (id === null) {
        await goto(resolve("/projects/[id]/edit", { id: String(result.id) }));
        return;
      }
      statusMessage = t("Project changes saved.", locale);
    } catch (error) {
      if (controller.signal.aborted || !isCurrentRoute(id, ownerId, generation))
        return;
      if (error instanceof ApiError && error.status === 409) {
        conflictProject = null;
        if (error.current) {
          try {
            const latest = parseProjectDetail(error.current);
            if (latest.id === id) conflictProject = latest;
          } catch {
            conflictProject = null;
          }
        }
        serverError = t(
          "This project changed elsewhere. Your edits are still here.",
          locale,
        );
      } else if (error instanceof ApiError && error.status === 422) {
        mapServerFieldErrors(error.fields);
        serverError = t("Check the highlighted fields and try again.", locale);
      } else {
        serverError = safeError(
          error,
          t("The project could not be saved.", locale),
        );
      }
    } finally {
      operationControllers.delete(controller);
      if (isCurrentRoute(id, ownerId, generation)) saving = false;
    }
  }

  function mapServerFieldErrors(fields?: Record<string, string>): void {
    if (!fields) return;
    const next: Partial<Record<DraftField | "steps", string>> = {};
    for (const key of Object.keys(fields)) {
      const fields: Record<string, DraftField> = {
        name: "name",
        category: "category",
        needles: "needles",
        stitch_sample: "stitchSample",
        description: "description",
        notes: "notes",
        other_materials: "otherMaterials",
        tags: "tags",
        link: "link",
      };
      const field = fields[key];
      if (field) next[field] = t("This value needs attention.", locale);
      else if (key === "steps")
        next.steps = t("Review the project steps.", locale);
    }
    fieldErrors = next;
  }

  function applyLoadedProject(value: ProjectDetail): void {
    project = value;
    draft = projectDraftFromDetail(value, () => ++stepKey);
    savedSnapshot = JSON.stringify(draft);
    conflictProject = null;
    fieldErrors = {};
    selectedYarnNames = Object.fromEntries(
      value.yarn_ids
        .map((id) => [id, selectedYarnNames[id]] as const)
        .filter(
          (entry): entry is readonly [number, string] =>
            typeof entry[1] === "string",
        ),
    );
  }

  function loadConflictLatest(): void {
    if (conflictProject) {
      applyLoadedProject(conflictProject);
      statusMessage = t("Latest saved version loaded.", locale);
      serverError = "";
      return;
    }
    void reloadLatestProject();
  }

  async function reloadLatestProject(): Promise<void> {
    const currentSession = session.data;
    const id = projectId;
    if (!currentSession?.user || id === null) return;
    if (
      !window.confirm(
        t("Replace your unsaved edits with the latest saved version?", locale),
      )
    )
      return;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    operationControllers.add(controller);
    try {
      const latest = await getProject(id, controller.signal);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      applyLoadedProject(latest);
      statusMessage = t("Latest saved version loaded.", locale);
      serverError = "";
    } catch (error) {
      if (controller.signal.aborted || !isCurrentRoute(id, ownerId, generation))
        return;
      serverError = safeError(
        error,
        t("The latest project could not be loaded.", locale),
      );
    } finally {
      operationControllers.delete(controller);
    }
  }

  async function confirmDelete(): Promise<void> {
    const currentSession = session.data;
    const id = projectId;
    if (!currentSession?.user || id === null || !project || isDirty || deleting)
      return;
    if (
      !window.confirm(t("Delete this project? This cannot be undone.", locale))
    )
      return;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    operationControllers.add(controller);
    deleting = true;
    serverError = "";
    try {
      await deleteProject(id, currentSession, controller.signal);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      await goto(resolve("/projects"));
    } catch (error) {
      if (controller.signal.aborted || !isCurrentRoute(id, ownerId, generation))
        return;
      serverError = safeError(
        error,
        t("The project could not be deleted.", locale),
      );
    } finally {
      operationControllers.delete(controller);
      if (isCurrentRoute(id, ownerId, generation)) deleting = false;
    }
  }

  async function uploadImage(event: Event): Promise<void> {
    const input = event.currentTarget;
    if (!(input instanceof HTMLInputElement)) return;
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
    const stepId = mediaStepId ? Number(mediaStepId) : undefined;
    await performMediaMutation((id, currentSession, signal) =>
      uploadProjectImage(
        id,
        file,
        mediaAltText,
        currentSession,
        stepId,
        signal,
      ),
    );
    mediaAltText = "";
  }

  async function uploadAttachment(event: Event): Promise<void> {
    const input = event.currentTarget;
    if (!(input instanceof HTMLInputElement)) return;
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
    await performMediaMutation((id, currentSession, signal) =>
      uploadProjectAttachment(id, file, currentSession, signal),
    );
  }

  async function removeImage(imageId: number): Promise<void> {
    const currentSession = session.data;
    const id = projectId;
    if (!currentSession?.user || id === null || !mediaAvailable) return;
    if (!window.confirm(t("Remove this project photo?", locale))) return;
    await performMediaMutation((projectKey, userSession, signal) =>
      deleteProjectImage(projectKey, imageId, userSession, signal),
    );
  }

  async function removeAttachment(attachmentId: number): Promise<void> {
    const currentSession = session.data;
    const id = projectId;
    if (!currentSession?.user || id === null || !mediaAvailable) return;
    if (!window.confirm(t("Remove this attachment?", locale))) return;
    await performMediaMutation((projectKey, userSession, signal) =>
      deleteProjectAttachment(projectKey, attachmentId, userSession, signal),
    );
  }

  async function performMediaMutation(
    mutation: (
      id: number,
      currentSession: NonNullable<typeof session.data>,
      signal: AbortSignal,
    ) => Promise<unknown>,
  ): Promise<void> {
    const currentSession = session.data;
    const id = projectId;
    if (!currentSession?.user || id === null || !mediaAvailable || mediaBusy)
      return;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    operationControllers.add(controller);
    mediaBusy = true;
    mediaError = "";
    statusMessage = "";
    try {
      await mutation(id, currentSession, controller.signal);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      const latest = await getProject(id, controller.signal);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      applyLoadedProject(latest);
      statusMessage = t("Project media updated.", locale);
    } catch (error) {
      if (controller.signal.aborted || !isCurrentRoute(id, ownerId, generation))
        return;
      mediaError = safeError(
        error,
        t("The project media could not be updated.", locale),
      );
    } finally {
      operationControllers.delete(controller);
      if (isCurrentRoute(id, ownerId, generation)) mediaBusy = false;
    }
  }

  function safeError(error: unknown, fallback: string): string {
    return error instanceof Error ? error.message : fallback;
  }

  function safeExternalUrl(value: string | null): string | null {
    if (!value) return null;
    try {
      const url = new URL(value);
      return url.protocol === "https:" || url.protocol === "http:"
        ? value
        : null;
    } catch {
      return null;
    }
  }

  function beforeUnload(event: BeforeUnloadEvent): void {
    if (!isDirty) return;
    event.preventDefault();
    event.returnValue = "";
  }
</script>

<svelte:window onbeforeunload={beforeUnload} />

<svelte:head>
  <title>
    Stricknani · {isCreating
      ? t("New project", locale)
      : t("Edit project", locale)}
  </title>
</svelte:head>

<section class="project-editor" aria-labelledby="editor-title">
  <header class="editor-heading">
    <div>
      <p class="eyebrow">{t("Project details", locale)}</p>
      <h1 id="editor-title">
        {isCreating ? t("New project", locale) : t("Edit project", locale)}
      </h1>
      <p>
        {t(
          "Keep the pattern and materials together for your next knitting session.",
          locale,
        )}
      </p>
    </div>
    <a class="back-link" href={backHref}>
      {t("Back to project", locale)}
    </a>
  </header>

  {#if loadState === "signed-out"}
    <div class="notice" role="status">
      <p>{t("Sign in to manage your projects.", locale)}</p>
      <a href={resolve("/login")}>{t("Sign in", locale)}</a>
    </div>
  {:else if loadState === "loading"}
    <div class="loading-panel" aria-busy="true" aria-live="polite">
      {t("Loading project details…", locale)}
    </div>
  {:else if loadState === "not-found"}
    <div class="notice notice-error" role="alert">
      <p>{t("This project could not be found.", locale)}</p>
      <a href={resolve("/projects")}>{t("Back to projects", locale)}</a>
    </div>
  {:else if loadState === "error"}
    <div class="notice notice-error" role="alert">
      <p>{loadError}</p>
      <a href={resolve("/projects")}>{t("Back to projects", locale)}</a>
    </div>
  {:else}
    {#if serverError && !conflictProject}
      <div class="notice notice-error" role="alert"><p>{serverError}</p></div>
    {/if}
    {#if statusMessage}
      <p class="status-message" role="status">{statusMessage}</p>
    {/if}
    {#if conflictProject}
      <section class="conflict-panel" aria-labelledby="conflict-heading">
        <h2 id="conflict-heading">
          {t("This project changed in another window.", locale)}
        </h2>
        <p>
          {t("Your draft is unchanged. Choose which version to keep.", locale)}
        </p>
        <div class="button-row">
          <button
            type="button"
            class="secondary-button"
            onclick={loadConflictLatest}
          >
            {t("Load latest values", locale)}
          </button>
          <button
            type="button"
            class="primary-button"
            disabled={saving}
            onclick={() => void saveDraftOverLatest()}
          >
            {saving
              ? t("Saving…", locale)
              : t("Save my draft over latest", locale)}
          </button>
        </div>
      </section>
    {:else if isCreating === false && project === null}
      <div class="notice notice-error" role="alert">
        <p>{t("This project could not be loaded.", locale)}</p>
      </div>
    {:else}
      <form class="editor-form" novalidate onsubmit={submit}>
        <fieldset
          class="form-fields"
          disabled={saving || deleting || mediaBusy}
        >
          <section class="form-card" aria-labelledby="basics-heading">
            <h2 id="basics-heading">{t("Project basics", locale)}</h2>
            <div class="field-grid">
              <div class="field field-wide">
                <label for="project-name">{t("Project name", locale)}</label>
                <input
                  id="project-name"
                  bind:value={draft.name}
                  maxlength="256"
                  autocomplete="off"
                  required
                  aria-invalid={fieldErrors.name ? "true" : undefined}
                  aria-describedby={fieldErrors.name
                    ? "project-name-error"
                    : undefined}
                />
                {#if fieldErrors.name}<p
                    id="project-name-error"
                    class="field-error"
                  >
                    {fieldErrors.name}
                  </p>{/if}
              </div>
              <div class="field">
                <label for="project-category">{t("Category", locale)}</label>
                <input
                  id="project-category"
                  bind:value={draft.category}
                  list="project-category-options"
                  maxlength="128"
                  autocomplete="off"
                />
                <datalist id="project-category-options">
                  {#each categories as category (category.id)}
                    <option value={category.name}></option>
                  {/each}
                </datalist>
                {#if categoryLoading}<span class="field-hint"
                    >{t("Loading categories…", locale)}</span
                  >{/if}
                {#if categoryError}<span class="field-hint field-error"
                    >{categoryError}</span
                  >{/if}
              </div>
              <div class="field">
                <label for="project-needles">{t("Needles", locale)}</label>
                <input id="project-needles" bind:value={draft.needles} />
              </div>
              <div class="field field-wide">
                <label for="project-stitch-sample"
                  >{t("Stitch sample", locale)}</label
                >
                <input
                  id="project-stitch-sample"
                  bind:value={draft.stitchSample}
                />
              </div>
              <div class="field field-wide">
                <label for="project-tag-entry">{t("Tags", locale)}</label>
                <div class="search-row">
                  <input
                    id="project-tag-entry"
                    bind:value={tagInput}
                    autocomplete="off"
                    onkeydown={(event) => {
                      if (event.key === "Enter") {
                        event.preventDefault();
                        addTag();
                      }
                    }}
                  />
                  <button
                    type="button"
                    class="secondary-button"
                    onclick={addTag}>{t("Add tag", locale)}</button
                  >
                </div>
                <span class="field-hint">
                  {t(
                    "Add each tag separately; commas stay inside a tag.",
                    locale,
                  )}
                </span>
                {#if draft.tags.length}
                  <ul class="tag-list" aria-label={t("Tags", locale)}>
                    {#each draft.tags as tag, index (`${index}:${tag}`)}
                      <li>
                        <span>{tag}</span>
                        <button
                          type="button"
                          class="remove-button"
                          aria-label={`${t("Remove", locale)} ${tag}`}
                          onclick={() => removeTag(index)}
                          >{t("Remove", locale)}</button
                        >
                      </li>
                    {/each}
                  </ul>
                {/if}
              </div>
              <div class="field field-wide">
                <label for="project-link"
                  >{t("Pattern source URL", locale)}</label
                >
                <input
                  id="project-link"
                  bind:value={draft.link}
                  type="url"
                  inputmode="url"
                  autocomplete="url"
                  aria-invalid={fieldErrors.link ? "true" : undefined}
                  aria-describedby={fieldErrors.link
                    ? "project-link-error"
                    : undefined}
                />
                {#if fieldErrors.link}<p
                    id="project-link-error"
                    class="field-error"
                  >
                    {fieldErrors.link}
                  </p>{/if}
                {#if project?.link_archive && safeExternalUrl(project.link_archive)}
                  <a
                    class="archive-link"
                    href={safeExternalUrl(project.link_archive) ?? undefined}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {t("Archived source", locale)} ↗
                  </a>
                {/if}
              </div>
            </div>
            {#if project?.is_ai_enhanced}
              <p class="field-hint">
                {t("This project includes AI-assisted details.", locale)}
              </p>
            {/if}
            {#if project?.yarn}
              <p class="field-hint">
                {t("Previous yarn note", locale)}: {project.yarn}
              </p>
            {/if}
          </section>

          <section class="form-card" aria-labelledby="writing-heading">
            <h2 id="writing-heading">{t("Pattern and notes", locale)}</h2>
            <p class="section-hint">
              {t(
                "Use plain text or Markdown. The reader formats it safely for you.",
                locale,
              )}
            </p>
            <div class="field-grid">
              <div class="field field-wide">
                <label for="project-description">{t("Pattern", locale)}</label>
                <textarea
                  id="project-description"
                  bind:value={draft.description}
                  rows="10"
                ></textarea>
              </div>
              <div class="field field-wide">
                <label for="project-notes">{t("Notes", locale)}</label>
                <textarea id="project-notes" bind:value={draft.notes} rows="5"
                ></textarea>
              </div>
              <div class="field field-wide">
                <label for="project-materials"
                  >{t("Other materials", locale)}</label
                >
                <textarea
                  id="project-materials"
                  bind:value={draft.otherMaterials}
                  rows="4"
                ></textarea>
              </div>
            </div>
          </section>

          <section class="form-card" aria-labelledby="yarn-heading">
            <h2 id="yarn-heading">{t("Yarn for this project", locale)}</h2>
            <div
              class="yarn-search"
              role="search"
              aria-label={t("Search yarn", locale)}
            >
              <label for="project-yarn-search">{t("Find yarn", locale)}</label>
              <div class="search-row">
                <input
                  id="project-yarn-search"
                  bind:value={yarnSearchInput}
                  autocomplete="off"
                  onkeydown={(event) => {
                    if (event.key === "Enter") {
                      event.preventDefault();
                      beginSearch();
                    }
                  }}
                />
                <button
                  type="button"
                  class="secondary-button"
                  onclick={beginSearch}>{t("Search", locale)}</button
                >
              </div>
            </div>
            {#if yarnSearchError}<p class="field-error" role="alert">
                {yarnSearchError}
              </p>{/if}
            {#if yarnLoading}<p class="field-hint" aria-live="polite">
                {t("Loading yarn options…", locale)}
              </p>{/if}
            {#if yarnResults.length}
              <ul class="yarn-results" aria-label={t("Available yarn", locale)}>
                {#each yarnResults as yarn (yarn.id)}
                  <li>
                    <span>
                      <strong>{yarn.name}</strong>
                      {#if yarn.brand}<small>{yarn.brand}</small>{/if}
                      {#if yarn.colorway}<small>{yarn.colorway}</small>{/if}
                    </span>
                    <button
                      type="button"
                      class="secondary-button"
                      disabled={draft.yarnIds.includes(yarn.id)}
                      onclick={() => addYarn(yarn)}
                    >
                      {draft.yarnIds.includes(yarn.id)
                        ? t("Added", locale)
                        : t("Add yarn", locale)}
                    </button>
                  </li>
                {/each}
              </ul>
            {:else if !yarnLoading && yarnQuery}
              <p class="field-hint">
                {t("No yarn matches that search.", locale)}
              </p>
            {/if}
            {#if selectedYarns.length}
              <div class="selected-yarns">
                <h3>{t("Selected yarn", locale)}</h3>
                <ul>
                  {#each selectedYarns as yarn (yarn.id)}
                    <li>
                      <span>{yarn.name}</span>
                      <button
                        type="button"
                        class="remove-button"
                        aria-label={`${t("Remove", locale)} ${yarn.name}`}
                        onclick={() => removeYarn(yarn.id)}
                      >
                        {t("Remove", locale)}
                      </button>
                    </li>
                  {/each}
                </ul>
              </div>
            {:else}
              <p class="field-hint">
                {t("No yarn is linked to this project yet.", locale)}
              </p>
            {/if}
          </section>

          <section class="form-card" aria-labelledby="steps-heading">
            <div class="section-title-row">
              <div>
                <h2 id="steps-heading">{t("Pattern steps", locale)}</h2>
                <p class="section-hint">
                  {t("Steps stay in the order shown here.", locale)}
                </p>
              </div>
              <button type="button" class="secondary-button" onclick={addStep}
                >{t("Add step", locale)}</button
              >
            </div>
            {#if fieldErrors.steps}<p class="field-error" role="alert">
                {fieldErrors.steps}
              </p>{/if}
            {#if draft.steps.length}
              <ol class="step-editor-list">
                {#each draft.steps as step, index (step.key)}
                  <li class="step-editor-card">
                    <div class="step-editor-heading">
                      <h3>{t("Step", locale)} {index + 1}</h3>
                      <div class="step-actions">
                        <button
                          type="button"
                          class="icon-button"
                          aria-label={`${t("Move up", locale)} ${t("Step", locale)} ${index + 1}`}
                          disabled={index === 0}
                          onclick={() => moveStep(index, -1)}>↑</button
                        >
                        <button
                          type="button"
                          class="icon-button"
                          aria-label={`${t("Move down", locale)} ${t("Step", locale)} ${index + 1}`}
                          disabled={index === draft.steps.length - 1}
                          onclick={() => moveStep(index, 1)}>↓</button
                        >
                        <button
                          type="button"
                          class="remove-button"
                          aria-label={`${t("Remove", locale)} ${t("Step", locale)} ${index + 1}`}
                          onclick={() =>
                            (draft.steps = draft.steps.filter(
                              (item) => item.key !== step.key,
                            ))}>{t("Remove", locale)}</button
                        >
                      </div>
                    </div>
                    <div class="field">
                      <label for={`step-title-${step.key}`}
                        >{t("Step title", locale)}</label
                      >
                      <input
                        id={`step-title-${step.key}`}
                        bind:value={step.title}
                        maxlength="256"
                        required
                      />
                    </div>
                    <div class="field">
                      <label for={`step-description-${step.key}`}
                        >{t("Step instructions", locale)}</label
                      >
                      <textarea
                        id={`step-description-${step.key}`}
                        bind:value={step.description}
                        rows="4"
                      ></textarea>
                    </div>
                  </li>
                {/each}
              </ol>
            {:else}
              <p class="field-hint">
                {t("No steps have been added yet.", locale)}
              </p>
            {/if}
          </section>
        </fieldset>

        {#if project}
          <section
            class="form-card media-manager"
            aria-labelledby="media-heading"
          >
            <h2 id="media-heading">{t("Project photos and files", locale)}</h2>
            {#if !mediaAvailable}
              <p class="field-hint">
                {t("Save your changes before editing project media.", locale)}
              </p>
            {/if}
            {#if mediaError}<p class="field-error" role="alert">
                {mediaError}
              </p>{/if}
            {#if project.images.length}
              <ul class="media-list">
                {#each project.images as image (image.id)}
                  <li>
                    <img
                      src={image.thumbnail_url}
                      alt={image.alt_text ?? t("Project photo", locale)}
                      loading="lazy"
                    />
                    <span>
                      {#if image.step_id}
                        {t("Step photo", locale)}
                      {:else if image.is_title_image}
                        {t("Cover photo", locale)}
                      {:else}
                        {t("Project photo", locale)}
                      {/if}
                    </span>
                    <button
                      type="button"
                      class="remove-button"
                      disabled={!mediaAvailable}
                      onclick={() => void removeImage(image.id)}
                      >{t("Remove photo", locale)}</button
                    >
                  </li>
                {/each}
              </ul>
            {:else}
              <p class="field-hint">{t("No project photos yet.", locale)}</p>
            {/if}
            <div class="media-upload-grid">
              <div class="field">
                <label for="project-image-step"
                  >{t("Attach photo to", locale)}</label
                >
                <select
                  id="project-image-step"
                  bind:value={mediaStepId}
                  disabled={!mediaAvailable}
                >
                  <option value="">{t("Project cover", locale)}</option>
                  {#each project.steps as step (step.id)}
                    <option value={String(step.id)}
                      >{t("Step", locale)}
                      {step.step_number}: {step.title}</option
                    >
                  {/each}
                </select>
              </div>
              <div class="field">
                <label for="project-image-alt"
                  >{t("Photo description", locale)}</label
                >
                <input
                  id="project-image-alt"
                  bind:value={mediaAltText}
                  disabled={!mediaAvailable}
                />
              </div>
              <div class="field">
                <label for="project-image-file"
                  >{t("Add project photo", locale)}</label
                >
                <input
                  id="project-image-file"
                  type="file"
                  accept="image/*"
                  disabled={!mediaAvailable}
                  onchange={uploadImage}
                />
              </div>
            </div>
            {#if project.attachments.length}
              <h3>{t("Attachments", locale)}</h3>
              <ul class="attachment-list">
                {#each project.attachments as attachment (attachment.id)}
                  <li>
                    <a href={attachment.url}
                      >{attachment.original_filename ?? attachment.filename}</a
                    >
                    <span
                      >{(attachment.size_bytes / 1024).toLocaleString(locale, {
                        maximumFractionDigits: 1,
                      })} KB</span
                    >
                    <button
                      type="button"
                      class="remove-button"
                      disabled={!mediaAvailable}
                      onclick={() => void removeAttachment(attachment.id)}
                      >{t("Remove attachment", locale)}</button
                    >
                  </li>
                {/each}
              </ul>
            {:else}
              <p class="field-hint">{t("No attachments yet.", locale)}</p>
            {/if}
            <div class="field">
              <label for="project-attachment-file"
                >{t("Add attachment", locale)}</label
              >
              <input
                id="project-attachment-file"
                type="file"
                disabled={!mediaAvailable}
                onchange={uploadAttachment}
              />
            </div>
          </section>
        {/if}

        <div class="form-actions">
          <button class="primary-button" type="submit" disabled={saveDisabled}>
            {saving
              ? t("Saving…", locale)
              : isCreating
                ? t("Create project", locale)
                : t("Save changes", locale)}
          </button>
          {#if project}
            <button
              class="danger-button"
              type="button"
              disabled={isDirty || saving || deleting || mediaBusy}
              onclick={() => void confirmDelete()}
            >
              {deleting ? t("Deleting…", locale) : t("Delete project", locale)}
            </button>
          {/if}
          {#if isDirty}<span class="dirty-note" role="status"
              >{t("Unsaved changes", locale)}</span
            >{/if}
        </div>
      </form>
    {/if}
  {/if}
</section>

<style>
  .project-editor {
    width: min(58rem, 100%);
    margin: 0 auto;
    padding: clamp(1rem, 3vw, 2rem);
  }

  .editor-heading,
  .section-title-row,
  .step-editor-heading,
  .form-actions,
  .button-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
  }

  .editor-heading {
    align-items: flex-start;
    margin-bottom: 1.4rem;
    border-bottom: 1px solid var(--line);
    padding-bottom: 1.15rem;
  }

  h1,
  h2,
  h3,
  p {
    margin-top: 0;
  }

  h1 {
    margin-bottom: 0.35rem;
    font-size: clamp(1.75rem, 5vw, 2.4rem);
    line-height: 1.12;
    letter-spacing: -0.04em;
  }

  .editor-heading p:last-child,
  .section-hint,
  .field-hint,
  .dirty-note {
    color: var(--ink-muted);
  }

  .eyebrow {
    margin-bottom: 0.2rem;
    color: var(--accent-strong);
    font-size: 0.82rem;
    font-weight: 700;
  }

  .back-link,
  .archive-link {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 650;
  }

  .editor-form,
  .form-fields {
    display: grid;
    gap: 1rem;
    min-width: 0;
    margin: 0;
    border: 0;
    padding: 0;
  }

  .form-card,
  .conflict-panel,
  .loading-panel,
  .notice {
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: clamp(1rem, 3vw, 1.4rem);
    background: var(--surface);
  }

  .form-card h2,
  .conflict-panel h2 {
    margin-bottom: 0.8rem;
    font-size: 1.16rem;
  }

  .field-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.9rem;
  }

  .field {
    display: grid;
    min-width: 0;
    gap: 0.35rem;
  }

  .field-wide {
    grid-column: 1 / -1;
  }

  label {
    font-size: 0.9rem;
    font-weight: 650;
  }

  input,
  textarea,
  select {
    width: 100%;
    min-height: 44px;
    border: 1px solid var(--control-border);
    border-radius: 0.7rem;
    padding: 0.65rem 0.75rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
  }

  textarea {
    min-height: 7rem;
    resize: vertical;
    line-height: 1.55;
  }

  input[type="file"] {
    padding: 0.4rem;
  }

  input:focus-visible,
  textarea:focus-visible,
  select:focus-visible,
  button:focus-visible,
  a:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .field-error,
  .notice-error {
    color: var(--danger);
  }

  .field-error {
    margin: 0;
    font-size: 0.86rem;
  }

  .field-hint,
  .section-hint {
    margin: 0;
    font-size: 0.86rem;
  }

  .section-hint {
    margin: -0.35rem 0 0.8rem;
  }

  .primary-button,
  .secondary-button,
  .danger-button,
  .remove-button,
  .icon-button {
    min-height: 44px;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    padding: 0.55rem 0.85rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }

  .primary-button {
    border-color: var(--accent-strong);
    color: var(--surface);
    background: var(--accent-strong);
  }

  .danger-button {
    border-color: color-mix(in srgb, var(--danger) 40%, var(--line));
    color: var(--danger);
    background: var(--surface);
  }

  button:disabled,
  input:disabled,
  select:disabled {
    opacity: 0.62;
    cursor: not-allowed;
  }

  .status-message {
    color: var(--accent-strong);
    font-weight: 650;
  }

  .conflict-panel {
    margin-bottom: 1rem;
    border-color: var(--danger);
  }

  .button-row {
    flex-wrap: wrap;
    justify-content: flex-start;
  }

  .yarn-search {
    display: grid;
    gap: 0.4rem;
  }

  .search-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 0.5rem;
  }

  .yarn-results,
  .tag-list,
  .selected-yarns ul,
  .media-list,
  .attachment-list {
    display: grid;
    gap: 0.5rem;
    margin: 0.8rem 0 0;
    padding: 0;
    list-style: none;
  }

  .yarn-results li,
  .tag-list li,
  .selected-yarns li,
  .attachment-list li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: 0.75rem;
    padding: 0.6rem;
    background: var(--surface-raised);
  }

  .yarn-results li > span,
  .selected-yarns li > span {
    display: grid;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .yarn-results small,
  .attachment-list span {
    color: var(--ink-muted);
  }

  .tag-list li {
    justify-content: flex-start;
    flex-wrap: wrap;
    overflow-wrap: anywhere;
  }

  .tag-list li > span {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .selected-yarns h3,
  .media-manager h3 {
    margin: 1rem 0 0.45rem;
    font-size: 1rem;
  }

  .step-editor-list {
    display: grid;
    gap: 0.8rem;
    margin: 0.8rem 0 0;
    padding-left: 1.4rem;
  }

  .step-editor-card {
    display: grid;
    gap: 0.8rem;
    border: 1px solid var(--line);
    border-radius: 0.8rem;
    padding: 0.8rem;
    background: var(--surface-raised);
  }

  .step-editor-heading h3 {
    margin: 0;
    font-size: 1rem;
  }

  .step-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
  }

  .icon-button {
    width: 44px;
    padding: 0;
    font-size: 1.1rem;
  }

  .remove-button {
    color: var(--danger);
  }

  .media-manager {
    display: grid;
    gap: 0.8rem;
  }

  .media-list li {
    display: grid;
    grid-template-columns: 5rem minmax(0, 1fr) auto;
    align-items: center;
    gap: 0.7rem;
    border: 1px solid var(--line);
    border-radius: 0.75rem;
    padding: 0.5rem;
    background: var(--surface-raised);
  }

  .media-list img {
    display: block;
    width: 5rem;
    height: 4rem;
    border-radius: 0.45rem;
    object-fit: cover;
  }

  .media-upload-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.75rem;
  }

  .media-upload-grid .field:first-child {
    grid-column: 1 / -1;
  }

  .form-actions {
    justify-content: flex-start;
    flex-wrap: wrap;
    padding-bottom: 2rem;
  }

  .dirty-note {
    font-size: 0.88rem;
  }

  @media (max-width: 560px) {
    .editor-heading {
      flex-direction: column;
      align-items: stretch;
    }

    .field-grid,
    .media-upload-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .media-upload-grid .field:first-child,
    .field-wide {
      grid-column: auto;
    }

    .step-editor-heading {
      align-items: flex-start;
      flex-direction: column;
    }

    .media-list li {
      grid-template-columns: 4rem minmax(0, 1fr);
    }

    .media-list img {
      width: 4rem;
      height: 3.25rem;
    }

    .media-list .remove-button {
      grid-column: 1 / -1;
      justify-self: start;
    }

    .yarn-results li,
    .selected-yarns li,
    .attachment-list li {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
