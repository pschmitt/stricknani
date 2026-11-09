<script lang="ts">
  import ErrorPanel from "#lib/components/ErrorPanel.svelte";
  import CategorySelect from "../categories/CategorySelect.svelte";
  import LabelIcon from "#lib/components/LabelIcon.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import MarkdownEditor from "#lib/components/MarkdownEditor.svelte";
  import StickyNoteField from "#lib/components/StickyNoteField.svelte";
  import { beforeNavigate, goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import { onMount, tick, untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import { fileDrop } from "#lib/components/file-drop.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import NewBanner from "#lib/components/NewBanner.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import StickyActionBar from "#lib/components/StickyActionBar.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { listCategories } from "../categories/category-api";
  import { getYarn, listYarns, type YarnSummary } from "../yarns/yarn-api";
  import UrlField from "#lib/components/UrlField.svelte";
  import AssetChoices from "../imports/AssetChoices.svelte";
  import ImageChoices from "../imports/ImageChoices.svelte";
  import ImportCard from "../imports/ImportCard.svelte";
  import ImportedYarns from "../imports/ImportedYarns.svelte";
  import { importAvailability } from "../imports/import-availability";
  import { importWarningMessage, isHttpUrl } from "../imports/import-draft";
  import {
    deleteImportDraft,
    draftIdFromSearch,
  } from "../imports/import-drafts-api";
  import {
    importedProjectWrite,
    projectFromImport,
    type ImportEditorState,
  } from "../imports/import-editor";
  import type { ImportPreview } from "../imports/import-api";
  import {
    autoStartFromSearch,
    importStartQuery,
    prefillUrlFromSearch,
  } from "../imports/shared-link";
  import YarnPicker from "./YarnPicker.svelte";
  import TagChipInput from "./TagChipInput.svelte";
  import ForeignOwnerBanner from "#lib/components/ForeignOwnerBanner.svelte";
  import { foreignOwner } from "#lib/owner.ts";
  import {
    isAccessLost,
    isAccessLowered,
    mayEdit,
    mayManage,
  } from "#lib/access.ts";
  import { listYarnCandidates } from "#lib/features/sharing/sharing-api.ts";
  import PhotoEditor from "../media/PhotoEditor.svelte";
  import { projectImageEditPath } from "../media/photo-editor";
  import ReorderableMedia from "../media/ReorderableMedia.svelte";
  import { groupProjectImages } from "./project-images";
  import AddMediaControl from "#lib/components/AddMediaControl.svelte";
  import {
    uploadSequentially,
    type UploadProgress,
    type UploadQueueResult,
  } from "../media/upload-queue";
  import {
    applyOptimisticReorder,
    projectStepOrderPath,
    projectTitleOrderPath,
    putOrder,
    replaceGroup,
  } from "../media/media-reorder";
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
    type ProjectImage,
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
  type ConfirmedAction =
    | { kind: "project" }
    | { kind: "image"; imageId: number }
    | { kind: "attachment"; attachmentId: number };

  let { projectId }: { projectId: number | null } = $props();
  let loadState = $state<LoadState>("loading");
  let project = $state<ProjectDetail | null>(null);
  let draft = $state<ProjectDraft>(blankProjectDraft());
  let savedSnapshot = $state(JSON.stringify(blankProjectDraft()));
  let conflictProject = $state<ProjectDetail | null>(null);
  let categories = $state<ProjectCategory[]>([]);
  let categoryError = $state("");
  let yarnResults = $state<YarnSummary[]>([]);
  let selectedYarnNames = $state<Record<number, YarnSummary>>({});
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
  let uploadProgress = $state<UploadProgress | null>(null);
  let uploadKind = $state<"photo" | "file">("photo");
  let photoEditImage = $state<ProjectImage | null>(null);
  let photoEditOpen = $state(false);
  let confirmationDialog = $state<ConfirmDialog | undefined>(undefined);
  let conflictDialog = $state<ConfirmDialog | undefined>(undefined);
  /** A new project's pasted link: offer to import the pattern from it instead. */
  let importOfferDialog = $state<ConfirmDialog | undefined>(undefined);
  let importOfferUrl = $state("");
  let declinedImportUrl = "";
  /** What the import added to a new project: pictures to choose, yarns, step pictures. */
  let importState = $state<ImportEditorState | null>(null);
  let importBusy = $state(false);
  let importCard = $state<ImportCard | undefined>(undefined);
  let conflictLoadBusy = $state(false);
  let conflictLoadError = $state("");
  let confirmedAction = $state<ConfirmedAction | null>(null);
  let confirmationOwnerId = 0;
  let confirmationProjectId = 0;
  let confirmationGeneration = 0;
  let editorForm = $state<HTMLFormElement | undefined>(undefined);
  let optionalDetailsOpen = $state(false);
  let tagInput = $state("");
  let tagChips = $state<ReturnType<typeof TagChipInput> | null>(null);
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
  const resumeDraftId = $derived(
    isCreating ? draftIdFromSearch(page.url.search) : null,
  );
  const sharedLink = $derived(
    isCreating ? (prefillUrlFromSearch(page.url.search) ?? "") : "",
  );
  const sharedAutoStart = $derived(
    isCreating && autoStartFromSearch(page.url.search),
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
  const mediaUploadAvailable = $derived(
    mediaAvailable && session.data?.capabilities.media_upload === true,
  );
  const mediaDeleteAvailable = $derived(
    mediaAvailable && session.data?.capabilities.media_delete === true,
  );
  const imageGroups = $derived(
    groupProjectImages(
      project?.images ?? [],
      project?.steps.map((step) => step.id) ?? [],
    ),
  );
  const titleImages = $derived(imageGroups.gallery);
  const stepImageGroups = $derived(
    (project?.steps ?? [])
      .map((step) => ({
        step,
        images:
          project?.images.filter((image) => image.step_id === step.id) ?? [],
      }))
      .filter((group) => group.images.length > 0),
  );
  function imagesOfStep(stepId: number | undefined): ProjectImage[] {
    if (stepId === undefined) return [];
    return project?.images.filter((image) => image.step_id === stepId) ?? [];
  }
  const otherImages = $derived(imageGroups.orphans);
  const saveDisabled = $derived(
    saving || deleting || (!isCreating && !isDirty) || conflictProject !== null,
  );
  const selectedYarns = $derived(
    draft.yarnIds.map((id) => {
      const yarn = selectedYarnNames[id];
      return {
        id,
        name: yarn?.name ?? t("Selected yarn is unavailable.", locale),
        brand: yarn?.brand ?? null,
        colorway: yarn?.colorway ?? null,
        preview_url: yarn?.preview_url ?? null,
        weight_category: yarn?.weight_category ?? null,
      };
    }),
  );
  const confirmationTitle = $derived(
    confirmedAction?.kind === "project"
      ? t("Delete this project? This cannot be undone.", locale)
      : confirmedAction?.kind === "image"
        ? t("Remove this project photo?", locale)
        : t("Remove this attachment?", locale),
  );
  const confirmationBusy = $derived(
    confirmedAction?.kind === "project" ? deleting : mediaBusy,
  );

  onMount(() => {
    const unregister = registerPwaUpdateGuard(() => ({
      dirty: isDirty,
      busy:
        loadState === "loading" ||
        saving ||
        deleting ||
        mediaBusy ||
        importBusy ||
        categoryLoading ||
        yarnLoading,
    }));
    const onKeydown = (event: KeyboardEvent) => {
      if (
        event.defaultPrevented ||
        event.isComposing ||
        event.repeat ||
        !(event.ctrlKey || event.metaKey) ||
        event.shiftKey ||
        event.altKey ||
        event.key.toLowerCase() !== "s" ||
        !editorForm ||
        loadState !== "ready" ||
        document.querySelector("dialog[open]")
      )
        return;
      event.preventDefault();
      if (
        saving ||
        deleting ||
        mediaBusy ||
        conflictProject !== null ||
        (!isCreating && !isDirty)
      )
        return;
      if (Object.keys(validateProjectDraft(draft)).length === 0) {
        editorForm.requestSubmit();
      } else {
        showLocalErrors();
        const ownerId = userId;
        if (ownerId !== null)
          void focusFirstValidationError(projectId, ownerId, routeGeneration);
      }
    };
    window.addEventListener("keydown", onKeydown);
    return () => {
      unregister();
      window.removeEventListener("keydown", onKeydown);
    };
  });

  $effect(() => {
    const id = projectId;
    const ownerId = userId;
    const generation = ++routeGeneration;
    const controller = new AbortController();
    untrack(() => {
      confirmationDialog?.close();
      conflictDialog?.close();
      confirmedAction = null;
      conflictLoadBusy = false;
      conflictLoadError = "";
    });
    optionalDetailsOpen = id !== null;
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

  // An administrator may edit another user's project. Its category and yarn choices belong to
  // that owner, never to the administrator.
  const foreignProjectOwner = $derived(foreignOwner(project?.owner, userId));
  const foreignProjectOwnerId = $derived(foreignProjectOwner?.id ?? null);
  // An editor of a shared project links only the owner's yarns, through a dedicated list.
  const candidateProjectId = $derived(
    project?.access?.role === "editor" ? project.id : null,
  );

  $effect(() => {
    const ownerId = userId;
    const foreignId = foreignProjectOwnerId;
    const generation = ++categoryGeneration;
    if (ownerId === null || foreignId !== null) {
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
    const foreignId = foreignProjectOwnerId;
    const candidatesFor = candidateProjectId;
    const generation = ++yarnGeneration;
    if (ownerId === null) {
      yarnResults = [];
      yarnSearchError = "";
      yarnLoading = false;
      return;
    }
    if (!query.trim()) {
      yarnResults = [];
      yarnSearchError = "";
      yarnLoading = false;
      return;
    }
    const controller = new AbortController();
    yarnLoading = true;
    yarnSearchError = "";
    const params = new URLSearchParams({ q: query.trim() });
    const search: Promise<YarnSummary[]> =
      candidatesFor !== null
        ? listYarnCandidates(
            candidatesFor,
            query.trim(),
            1,
            controller.signal,
          ).then((page) =>
            page.items.map((item) => ({
              id: item.id,
              name: item.name,
              brand: item.brand,
              colorway: item.colorway,
              weight_category: item.weight_category,
              is_favorite: false,
              updated_at: "",
              preview_url: null,
            })),
          )
        : listYarns(
            params.toString(),
            controller.signal,
            foreignId === null
              ? { scope: "mine" }
              : { scope: "all", ownerId: foreignId },
          ).then((page) => page.data);
    void search
      .then((items) => {
        if (
          controller.signal.aborted ||
          yarnGeneration !== generation ||
          userId !== ownerId
        )
          return;
        yarnResults = items;
        for (const yarn of items) {
          selectedYarnNames[yarn.id] = yarn;
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

  const linkImports = $derived(importAvailability(session.data?.capabilities));

  /** Offers the import once per link, only while creating and when the server imports links. */
  function offerImport(value: string): void {
    const url = value.trim();
    if (!isCreating || !linkImports.url || !isHttpUrl(url)) return;
    if (url === declinedImportUrl || importOfferDialog === undefined) return;
    importOfferUrl = url;
    untrack(() => importOfferDialog?.showModal());
  }

  /** The import runs in place: it fills this editor, no other page opens. */
  function acceptImport(): void {
    untrack(() => importOfferDialog?.close());
    importCard?.startWithUrl(importOfferUrl);
    document.getElementById("import-card-title")?.scrollIntoView({
      block: "center",
    });
  }

  function stepFallbackTitle(number: number): string {
    return t("Step {number}", locale).replace("{number}", String(number));
  }

  /** Fill the editor's own fields from a finished import. */
  function applyImport(result: {
    preview: ImportPreview;
    draftId: number | null;
  }): void {
    const filled = projectFromImport(
      result.preview,
      result.draftId,
      () => ++stepKey,
      stepFallbackTitle,
    );
    draft = filled.draft;
    importState = filled.state;
    selectedYarnNames = {};
    fieldErrors = {};
    serverError = "";
    optionalDetailsOpen = true;
  }

  const importWarnings = $derived(
    (importState?.warnings ?? [])
      .map((code) => ({ code, message: importWarningMessage(code, locale) }))
      .filter(
        (entry): entry is { code: string; message: string } =>
          entry.message !== null,
      ),
  );

  function declineImport(): void {
    declinedImportUrl = importOfferUrl;
    untrack(() => importOfferDialog?.close());
  }

  beforeNavigate(({ cancel, willUnload }) => {
    if (willUnload) return;
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
    importState = null;
    draft = empty;
    savedSnapshot = JSON.stringify(empty);
    tagInput = "";
    selectedYarnNames = {};
    yarnQuery = "";
    yarnSearchInput = "";
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
            return [yarnId, yarn as YarnSummary] as const;
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
          (entry): entry is readonly [number, YarnSummary] => entry !== null,
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
    selectedYarnNames[yarn.id] = yarn;
  }

  /** Takes the tag text still being typed as tags. */
  function addTag(): void {
    tagChips?.commit();
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
    if (isCreating && importState)
      return importedProjectWrite(
        draft,
        importState,
        linkImports.url,
        t("Amount", locale),
      );
    return projectWriteFromDraft(draft, isAiEnhanced);
  }

  async function focusFirstValidationError(
    id: number | null,
    ownerId: number,
    generation: number,
  ): Promise<void> {
    const invalidStep = draft.steps.find((step) => {
      const title = step.title.trim();
      return !title || title.length > 256;
    });
    if (
      invalidStep ||
      Object.entries(fieldErrors).some(
        ([key, value]) => key !== "name" && Boolean(value),
      )
    )
      optionalDetailsOpen = true;
    await tick();
    if (!isCurrentRoute(id, ownerId, generation)) return;
    const firstField = [
      ["name", "project-name"],
      ["category", "project-category"],
      ["needles", "project-needles"],
      ["stitchSample", "project-stitch-sample"],
      ["tags", "project-tag-entry"],
      ["link", "project-link"],
      ["description", "project-description"],
      ["notes", "project-notes"],
      ["otherMaterials", "project-materials"],
    ].find(([field]) => Boolean(fieldErrors[field as DraftField]));
    const targetId =
      firstField?.[1] ??
      (invalidStep
        ? `step-title-${invalidStep.key}`
        : fieldErrors.steps
          ? draft.steps[0]
            ? `step-title-${draft.steps[0].key}`
            : "add-project-step"
          : null);
    const target = targetId ? document.getElementById(targetId) : null;
    if (
      (target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        target instanceof HTMLButtonElement) &&
      target.isConnected
    )
      target.focus();
  }

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (
      saving ||
      deleting ||
      mediaBusy ||
      conflictProject !== null ||
      (!isCreating && !isDirty)
    )
      return;
    const id = projectId;
    const ownerId = session.data?.user?.id;
    const generation = routeGeneration;
    addTag();
    const isAiEnhanced = project?.is_ai_enhanced ?? false;
    const value = makeWrite(isAiEnhanced);
    if (!value) {
      if (ownerId !== undefined)
        await focusFirstValidationError(id, ownerId, generation);
      return;
    }
    await saveProject(value, project?.updated_at ?? null);
  }

  async function saveDraftOverLatest(): Promise<void> {
    if (!conflictProject) return;
    const id = projectId;
    const ownerId = session.data?.user?.id;
    const generation = routeGeneration;
    addTag();
    const value = makeWrite(conflictProject.is_ai_enhanced);
    if (!value) {
      if (ownerId !== undefined)
        await focusFirstValidationError(id, ownerId, generation);
      return;
    }
    await saveProject(value, conflictProject.updated_at);
  }

  async function saveProject(
    value: ProjectWrite,
    expectedUpdatedAt: string | null,
  ): Promise<void> {
    const currentSession = session.data;
    if (!currentSession?.user || saving || deleting || mediaBusy) return;
    const id = projectId;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    let focusServerErrors = false;
    const importedDraftId = id === null ? (importState?.draftId ?? null) : null;
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
        publishFeedback({ ownerId, kind: "error", message: serverError });
        return;
      }
      applyLoadedProject(result);
      statusMessage =
        id === null
          ? t("Project created.", locale)
          : t("Project changes saved.", locale);
      publishFeedback({ ownerId, kind: "success", message: statusMessage });
      // The import is done with: take it off the "Waiting for review" list.
      if (importedDraftId !== null) {
        try {
          await deleteImportDraft(importedDraftId, currentSession);
        } catch {
          // The draft expires on its own.
        }
      }
      await goto(resolve("/projects/[id]", { id: String(result.id) }));
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
      } else if (isAccessLost(error)) {
        serverError = t(
          "You no longer have access to this project. Your changes are still here.",
          locale,
        );
      } else if (isAccessLowered(error)) {
        serverError = t(
          "You can no longer edit this project. Your changes are still here.",
          locale,
        );
      } else if (error instanceof ApiError && error.status === 422) {
        mapServerFieldErrors(error.fields);
        serverError = t("Check the highlighted fields and try again.", locale);
        focusServerErrors = Object.keys(fieldErrors).length > 0;
      } else {
        serverError = safeError(
          error,
          t("The project could not be saved.", locale),
        );
      }
      if (serverError && userId === ownerId) {
        publishFeedback({ ownerId, kind: "error", message: serverError });
      }
    } finally {
      operationControllers.delete(controller);
      if (isCurrentRoute(id, ownerId, generation)) saving = false;
    }
    if (focusServerErrors && isCurrentRoute(id, ownerId, generation))
      await focusFirstValidationError(id, ownerId, generation);
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
          (entry): entry is readonly [number, YarnSummary] =>
            entry[1] !== undefined,
        ),
    );
  }

  function loadConflictLatest(): void {
    if (!conflictProject || conflictLoadBusy) return;
    conflictLoadError = "";
    conflictDialog?.showModal();
  }

  async function confirmLoadConflictLatest(): Promise<void> {
    const currentSession = session.data;
    const id = projectId;
    const currentConflict = conflictProject;
    if (
      !currentSession?.user ||
      id === null ||
      currentConflict === null ||
      conflictLoadBusy
    )
      return;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    operationControllers.add(controller);
    conflictLoadBusy = true;
    conflictLoadError = "";
    try {
      const latest = await getProject(id, controller.signal);
      if (
        !isCurrentRoute(id, ownerId, generation) ||
        conflictProject !== currentConflict
      )
        return;
      if (latest.id !== id)
        throw new Error(t("The latest project could not be loaded.", locale));
      applyLoadedProject(latest);
      statusMessage = t("Latest saved version loaded.", locale);
      serverError = "";
      publishFeedback({ ownerId, kind: "success", message: statusMessage });
      untrack(() => conflictDialog?.close());
    } catch (error) {
      if (
        controller.signal.aborted ||
        !isCurrentRoute(id, ownerId, generation) ||
        conflictProject !== currentConflict
      )
        return;
      conflictLoadError = safeError(
        error,
        t("The latest project could not be loaded.", locale),
      );
    } finally {
      operationControllers.delete(controller);
      if (isCurrentRoute(id, ownerId, generation)) conflictLoadBusy = false;
    }
  }

  function openConfirmation(action: ConfirmedAction): void {
    const currentSession = session.data;
    const id = projectId;
    if (
      !currentSession?.user ||
      id === null ||
      !project ||
      project.id !== id ||
      (action.kind === "project" &&
        (isDirty || saving || mediaBusy || deleting)) ||
      (action.kind !== "project" && !mediaDeleteAvailable)
    )
      return;
    confirmedAction = action;
    mediaError = "";
    confirmationOwnerId = currentSession.user.id;
    confirmationProjectId = id;
    confirmationGeneration = routeGeneration;
    confirmationDialog?.showModal();
  }

  async function confirmAction(): Promise<void> {
    const currentSession = session.data;
    const id = projectId;
    const action = confirmedAction;
    const ownerId = confirmationOwnerId;
    const generation = confirmationGeneration;
    if (
      !action ||
      !currentSession?.user ||
      currentSession.user.id !== ownerId ||
      id === null ||
      id !== confirmationProjectId ||
      !isCurrentRoute(id, ownerId, generation) ||
      !project ||
      project.id !== id
    )
      return;
    const isProjectDelete = action.kind === "project";
    if (
      isProjectDelete
        ? isDirty || saving || mediaBusy || deleting
        : !mediaDeleteAvailable || mediaBusy
    )
      return;
    if (
      action.kind === "image" &&
      !project.images.some((image) => image.id === action.imageId)
    )
      return;
    if (
      action.kind === "attachment" &&
      !project.attachments.some(
        (attachment) => attachment.id === action.attachmentId,
      )
    )
      return;
    if (!isProjectDelete) {
      const mutation =
        action.kind === "image"
          ? (
              key: number,
              current: NonNullable<typeof session.data>,
              signal: AbortSignal,
            ) => deleteProjectImage(key, action.imageId, current, signal)
          : (
              key: number,
              current: NonNullable<typeof session.data>,
              signal: AbortSignal,
            ) =>
              deleteProjectAttachment(
                key,
                action.attachmentId,
                current,
                signal,
              );
      const succeeded = await performMediaMutation(mutation, "media_delete");
      if (
        succeeded &&
        isCurrentRoute(id, ownerId, generation) &&
        confirmedAction === action
      ) {
        untrack(() => confirmationDialog?.close());
        confirmedAction = null;
      }
      return;
    }
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
      publishFeedback({ ownerId, kind: "error", message: serverError });
    } finally {
      operationControllers.delete(controller);
      if (isCurrentRoute(id, ownerId, generation)) {
        deleting = false;
        if (confirmedAction === action) {
          untrack(() => confirmationDialog?.close());
          confirmedAction = null;
        }
      }
    }
  }

  function openPhotoEditor(image: ProjectImage): void {
    if (!mediaUploadAvailable) return;
    photoEditImage = image;
    photoEditOpen = true;
  }

  /** Reload the project after a photo edit; the edited file has new URLs. */
  async function reloadAfterPhotoEdit(): Promise<void> {
    const currentSession = session.data;
    const id = projectId;
    if (!currentSession?.user || id === null) return;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    operationControllers.add(controller);
    try {
      const latest = await getProject(id, controller.signal);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      applyLoadedProject(latest);
    } finally {
      operationControllers.delete(controller);
    }
    statusMessage = t("Project media updated.", locale);
    publishFeedback({ ownerId, kind: "success", message: statusMessage });
  }

  /**
   * Photos go to the project (title images) or, with `stepId`, to that step. Shared by the pickers
   * and by files dropped on the gallery or on a step.
   */
  async function addImages(files: File[], stepId?: number): Promise<void> {
    await addFiles(
      files,
      (id, file, currentSession, signal) =>
        uploadProjectImage(id, file, "", currentSession, stepId, signal),
      "photo",
    );
  }

  async function addAttachments(files: File[]): Promise<void> {
    await addFiles(
      files,
      (id, file, currentSession, signal) =>
        uploadProjectAttachment(id, file, currentSession, signal),
      "file",
    );
  }

  /** Upload one file after the other (one per request), then reload the project once. */
  async function addFiles(
    files: File[],
    uploadOne: (
      id: number,
      file: File,
      currentSession: NonNullable<typeof session.data>,
      signal: AbortSignal,
    ) => Promise<unknown>,
    kind: "photo" | "file",
  ): Promise<void> {
    if (!mediaUploadAvailable || files.length === 0) return;
    let result: UploadQueueResult = { uploaded: 0, failures: [] };
    const ok = await performMediaMutation(
      async (id, currentSession, signal) => {
        result = await uploadSequentially(
          files,
          (file) => uploadOne(id, file, currentSession, signal),
          (progress) => {
            uploadProgress = progress.done < progress.total ? progress : null;
            uploadKind = kind;
          },
          () => signal.aborted,
        );
        if (result.uploaded === 0 && result.failures.length > 0)
          throw result.failures[0]?.error;
      },
      "media_upload",
    );
    uploadProgress = null;
    if (ok && result.failures.length > 0) {
      const names = result.failures.map((entry) => entry.file.name).join(", ");
      const first = result.failures[0]?.error;
      mediaError = `${t("Not uploaded:", locale)} ${names}${
        first instanceof Error ? `. ${first.message}` : ""
      }`;
    }
  }

  async function removeImage(imageId: number): Promise<void> {
    openConfirmation({ kind: "image", imageId });
  }

  async function removeAttachment(attachmentId: number): Promise<void> {
    openConfirmation({ kind: "attachment", attachmentId });
  }

  async function performMediaMutation(
    mutation: (
      id: number,
      currentSession: NonNullable<typeof session.data>,
      signal: AbortSignal,
    ) => Promise<unknown>,
    capability: "media_upload" | "media_delete",
  ): Promise<boolean> {
    const currentSession = session.data;
    const id = projectId;
    if (
      !currentSession?.user ||
      id === null ||
      !mediaAvailable ||
      currentSession.capabilities[capability] !== true ||
      mediaBusy
    )
      return false;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    operationControllers.add(controller);
    mediaBusy = true;
    mediaError = "";
    statusMessage = "";
    try {
      await mutation(id, currentSession, controller.signal);
      if (!isCurrentRoute(id, ownerId, generation)) return false;
      const latest = await getProject(id, controller.signal);
      if (!isCurrentRoute(id, ownerId, generation)) return false;
      applyLoadedProject(latest);
      statusMessage = t("Project media updated.", locale);
      publishFeedback({ ownerId, kind: "success", message: statusMessage });
      return true;
    } catch (error) {
      if (controller.signal.aborted || !isCurrentRoute(id, ownerId, generation))
        return false;
      mediaError = safeError(
        error,
        t("The project media could not be updated.", locale),
      );
      return false;
    } finally {
      operationControllers.delete(controller);
      if (isCurrentRoute(id, ownerId, generation)) mediaBusy = false;
    }
  }

  /** Reorder one image group (title images or one step) optimistically. */
  async function reorderImages(
    stepId: number | null,
    from: number,
    to: number,
  ): Promise<boolean> {
    const currentSession = session.data;
    const id = projectId;
    if (!currentSession?.user || id === null || !mediaAvailable || !project)
      return false;
    const group =
      stepId === null
        ? titleImages
        : (stepImageGroups.find((entry) => entry.step.id === stepId)?.images ??
          []);
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    const controller = new AbortController();
    operationControllers.add(controller);
    mediaBusy = true;
    mediaError = "";
    const reloadProject = async (): Promise<void> => {
      const latest = await getProject(id, controller.signal);
      if (isCurrentRoute(id, ownerId, generation)) applyLoadedProject(latest);
    };
    try {
      const ok = await applyOptimisticReorder({
        items: group,
        from,
        to,
        setItems: (next) => {
          if (project && isCurrentRoute(id, ownerId, generation))
            project = {
              ...project,
              images: replaceGroup(project.images, group, next),
            };
        },
        send: (ids) =>
          putOrder(
            stepId === null
              ? projectTitleOrderPath(id)
              : projectStepOrderPath(id, stepId),
            ids,
            currentSession,
            controller.signal,
          ),
        refetch: reloadProject,
        onError: (error) => {
          if (
            controller.signal.aborted ||
            !isCurrentRoute(id, ownerId, generation)
          )
            return;
          mediaError = safeError(
            error,
            t("The photo order could not be saved.", locale),
          );
          publishFeedback({ ownerId, kind: "error", message: mediaError });
        },
      });
      // Reordering bumps updated_at and the canonical cover; reload both.
      if (ok) await reloadProject();
      return ok;
    } catch (error) {
      if (!controller.signal.aborted && isCurrentRoute(id, ownerId, generation))
        mediaError = safeError(
          error,
          t("The project media could not be updated.", locale),
        );
      return false;
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

<section
  class="project-editor page-container page-container--standard"
  aria-labelledby="editor-title"
>
  <PageHeader
    title={isCreating ? t("New project", locale) : t("Edit project", locale)}
    titleId="editor-title"
  />

  {#if foreignProjectOwner && loadState === "ready"}
    <ForeignOwnerBanner kind="project" owner={foreignProjectOwner} {locale} />
  {/if}

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
    <ErrorPanel
      kind="project-missing"
      seed={String(projectId)}
      backHref={resolve("/projects")}
      backLabel={msg("Back to projects")}
    />
  {:else if loadState === "error"}
    <div class="notice notice-error" role="alert">
      <p>{loadError}</p>
      <a href={resolve("/projects")}>{t("Back to projects", locale)}</a>
    </div>
  {:else if project && !mayEdit(project.access)}
    <div class="notice" role="status">
      <p>{t("You can view this project but not edit it.", locale)}</p>
      <a href={resolve("/projects/[id]", { id: String(project.id) })}
        >{t("Back to the project", locale)}</a
      >
    </div>
  {:else}
    {#if conflictProject}
      <section class="conflict-panel" aria-labelledby="conflict-heading">
        <h2 id="conflict-heading">
          {t("This project changed in another window.", locale)}
        </h2>
        <p>
          {t("Your draft is unchanged. Choose which version to keep.", locale)}
        </p>
        <div class="button-row">
          <Button
            type="button"
            class="secondary-button"
            variant="secondary"
            icon="refresh"
            onclick={loadConflictLatest}
          >
            {t("Load latest values", locale)}
          </Button>
          <Button
            type="button"
            class="primary-button"
            variant="primary"
            icon="save"
            disabled={saving}
            onclick={() => void saveDraftOverLatest()}
          >
            {saving
              ? t("Saving…", locale)
              : t("Save my draft over latest", locale)}
          </Button>
        </div>
      </section>
    {:else if isCreating === false && project === null}
      <div class="notice notice-error" role="alert">
        <p>{t("This project could not be loaded.", locale)}</p>
      </div>
    {:else}
      <form
        bind:this={editorForm}
        class="editor-form"
        novalidate
        onsubmit={submit}
      >
        {#if isCreating}
          <NewBanner
            kind="project"
            title={t("Start something cosy", locale)}
            subtitle={t(
              "Give it a name and cast on your first stitches.",
              locale,
            )}
          />
          <ImportCard
            bind:this={importCard}
            target="project"
            initialUrl={sharedLink}
            autoStart={sharedAutoStart}
            {resumeDraftId}
            replaceNeedsConfirm={importState !== null && isDirty}
            disabled={saving}
            bind:busy={importBusy}
            onReady={applyImport}
          />
          {#if importState}
            {#if importState.aiFallback}<p class="notice" role="status">
                {t(
                  "AI enhancement was unavailable, so this preview uses the original extraction.",
                  locale,
                )}
              </p>{/if}
            {#each importWarnings as warning (warning.code)}<p
                class="notice"
                role="status"
              >
                {warning.message}
                {#if warning.code === "looks_like_yarn_page" && draft.link}
                  <a
                    href={`${resolve("/yarns/new")}${importStartQuery(draft.link)}`}
                    >{t("New yarn", locale)}</a
                  >
                {/if}
              </p>{/each}
          {/if}
        {/if}
        <!-- Same order as the project page: the name (its title), photos and files, details,
             materials, pattern and notes, steps. -->
        <fieldset
          class="form-fields"
          disabled={saving || deleting || mediaBusy}
        >
          <section class="form-card">
            <div class="field-grid">
              <div class="field field-wide">
                <label for="project-name"
                  ><LabelIcon name="projects" />{t(
                    "Project name",
                    locale,
                  )}</label
                >
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
            </div>
          </section>
        </fieldset>

        {#if isCreating && importState && (importState.imageUrls.length || importState.assets.length)}
          <fieldset class="form-fields" disabled={saving}>
            <section
              class="form-card import-media"
              aria-labelledby="import-media-heading"
            >
              <h2 id="import-media-heading">
                {t("Project photos and files", locale)}
              </h2>
              <p class="section-hint">
                {t(
                  "Choose the pictures and files to keep. They are saved with the project.",
                  locale,
                )}
              </p>
              {#if importState.imageUrls.length}<ImageChoices
                  title={t("Suggested project images", locale)}
                  images={importState.imageUrls}
                  disabled={!linkImports.url || saving}
                  external={linkImports.url}
                  {locale}
                />{/if}
              {#if importState.assets.length}<AssetChoices
                  assets={importState.assets}
                  disabled={!linkImports.url || saving}
                  external={linkImports.url}
                  {locale}
                />{/if}
            </section>
          </fieldset>
        {/if}

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
            {#if mediaAvailable && (!mediaUploadAvailable || !mediaDeleteAvailable)}
              <p class="field-hint">
                {t("Media storage is unavailable. Try again later.", locale)}
              </p>
            {/if}
            {#if mediaError && confirmedAction?.kind !== "image" && confirmedAction?.kind !== "attachment"}<p
                class="field-error"
                role="alert"
              >
                {mediaError}
              </p>{/if}
            {#if uploadProgress}
              <div class="upload-state" role="status" aria-live="polite">
                <progress
                  aria-label={t("Uploading photo", locale)}
                  max={uploadProgress.total}
                  value={uploadProgress.done}
                ></progress>
                <span
                  >{uploadKind === "photo"
                    ? t("Uploading photo", locale)
                    : t("Uploading file", locale)}
                  {uploadProgress.current}/{uploadProgress.total}…</span
                >
              </div>
            {/if}
            <div
              class="media-drop"
              use:fileDrop={{
                enabled: mediaUploadAvailable,
                accept: "image/*",
                multiple: true,
                label: t("Drop to add photos", locale),
                onFiles: (files) => void addImages(files),
                onRejected: () =>
                  (mediaError = t("Choose a supported image file.", locale)),
              }}
            >
              <h3>{t("Photos", locale)}</h3>
              {#if !titleImages.length && !otherImages.length}
                <AddMediaControl
                  id="project-image-file"
                  {locale}
                  empty
                  camera
                  variant="primary"
                  label={t("Add photos", locale)}
                  inputLabel={t("Choose project photos", locale)}
                  dropHint={t("or drop images here", locale)}
                  disabled={!mediaUploadAvailable}
                  hint={t("Save your changes first to add photos.", locale)}
                  onFiles={(files) => void addImages(files)}
                />
              {:else}
                <AddMediaControl
                  id="project-image-file"
                  {locale}
                  camera
                  label={t("Add photos", locale)}
                  inputLabel={t("Choose project photos", locale)}
                  disabled={!mediaUploadAvailable}
                  hint={t("Save your changes first to add photos.", locale)}
                  onFiles={(files) => void addImages(files)}
                />
                {#if titleImages.length}
                  <ReorderableMedia
                    items={titleImages}
                    {locale}
                    label={t("Project cover", locale)}
                    fallbackAlt={t("Project photo", locale)}
                    showCover
                    disabled={!mediaAvailable}
                    onMove={(from, to) => reorderImages(null, from, to)}
                    onDelete={(image) => void removeImage(image.id)}
                    deleteDisabled={!mediaDeleteAvailable}
                    onEdit={openPhotoEditor}
                    editDisabled={!mediaUploadAvailable}
                  >
                    {#snippet badge(image)}
                      {#if image.is_stitch_sample}{t(
                          "Stitch sample",
                          locale,
                        )}{/if}
                    {/snippet}
                  </ReorderableMedia>
                {/if}
                {#if otherImages.length}
                  <h3>{t("Project photo", locale)}</h3>
                  <ReorderableMedia
                    items={otherImages}
                    {locale}
                    label={t("Project photo", locale)}
                    fallbackAlt={t("Project photo", locale)}
                    reorderable={false}
                    onMove={async () => false}
                    onDelete={(image) => void removeImage(image.id)}
                    deleteDisabled={!mediaDeleteAvailable}
                    onEdit={openPhotoEditor}
                    editDisabled={!mediaUploadAvailable}
                  >
                    {#snippet badge(image)}
                      {#if image.is_stitch_sample}{t(
                          "Stitch sample",
                          locale,
                        )}{/if}
                    {/snippet}
                  </ReorderableMedia>
                {/if}
              {/if}
              {#if project.steps.length}
                <p class="field-hint">
                  {t(
                    "Photos of a step are added in that step further down.",
                    locale,
                  )}
                </p>
              {/if}
            </div>
            <div
              class="media-drop"
              use:fileDrop={{
                enabled: mediaUploadAvailable,
                multiple: true,
                label: t("Drop to attach a file", locale),
                onFiles: (files) => void addAttachments(files),
              }}
            >
              {#if project.attachments.length}
                <h3>{t("Attachments", locale)}</h3>
                <ul class="attachment-list">
                  {#each project.attachments as attachment (attachment.id)}
                    <li>
                      <a href={attachment.url}
                        >{attachment.original_filename ??
                          attachment.filename}</a
                      >
                      <span
                        >{(attachment.size_bytes / 1024).toLocaleString(
                          locale,
                          {
                            maximumFractionDigits: 1,
                          },
                        )} KB</span
                      >
                      <Button
                        type="button"
                        class="remove-button"
                        variant="ghost"
                        icon="action-delete"
                        disabled={!mediaDeleteAvailable}
                        onclick={() => void removeAttachment(attachment.id)}
                      >
                        {t("Remove attachment", locale)}
                      </Button>
                    </li>
                  {/each}
                </ul>
              {/if}
              <AddMediaControl
                id="project-attachment-file"
                {locale}
                accept=""
                empty={!project.attachments.length}
                label={t("Add attachment", locale)}
                inputLabel={t("Choose attachments", locale)}
                dropHint={t("or drop files here", locale)}
                disabled={!mediaUploadAvailable}
                hint={t("Save your changes first to add attachments.", locale)}
                onFiles={(files) => void addAttachments(files)}
              />
            </div>
          </section>
        {/if}

        <fieldset
          class="form-fields"
          disabled={saving || deleting || mediaBusy}
        >
          <section class="form-card" aria-labelledby="basics-heading">
            <h2 id="basics-heading">{t("Project details", locale)}</h2>
            <div class="field-grid">
              <div class="field">
                <label for="project-category"
                  ><LabelIcon name="folder" />{t("Category", locale)}</label
                >
                <CategorySelect
                  id="project-category"
                  bind:value={draft.category}
                  {categories}
                  loading={categoryLoading}
                  {locale}
                />
                {#if categoryLoading}<span class="field-hint"
                    >{t("Loading categories…", locale)}</span
                  >{/if}
                {#if categoryError}<span class="field-hint field-error"
                    >{categoryError}</span
                  >{/if}
              </div>
              <div class="field">
                <label for="project-needles"
                  ><LabelIcon name="needle" />{t("Needles", locale)}</label
                >
                <input id="project-needles" bind:value={draft.needles} />
              </div>
              <div class="field field-wide">
                <label
                  for="project-stitch-sample"
                  id="project-stitch-sample-label"
                  ><LabelIcon name="gauge" />{t("Stitch sample", locale)}</label
                >
                <MarkdownEditor
                  id="project-stitch-sample"
                  labelledBy="project-stitch-sample-label"
                  {locale}
                  lines
                  bind:value={draft.stitchSample}
                  rows={3}
                />
              </div>
              <div class="field field-wide">
                <label for="project-tag-entry"
                  ><LabelIcon name="categories" />{t("Tags", locale)}</label
                >
                <TagChipInput
                  bind:this={tagChips}
                  id="project-tag-entry"
                  bind:tags={draft.tags}
                  bind:input={tagInput}
                  {locale}
                />
              </div>
              <div class="field field-wide">
                <label for="project-link"
                  ><LabelIcon name="link" />{t(
                    "Pattern source URL",
                    locale,
                  )}</label
                >
                <UrlField
                  id="project-link"
                  bind:value={draft.link}
                  {locale}
                  onblur={offerImport}
                  invalid={Boolean(fieldErrors.link)}
                  describedby={fieldErrors.link
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

          <details bind:open={optionalDetailsOpen} class="optional-details">
            <summary>{t("More project details", locale)}</summary>
            <div class="optional-details__content">
              <section class="form-card" aria-labelledby="yarn-heading">
                <h2 id="yarn-heading">{t("Materials", locale)}</h2>
                {#if importState && importState.yarns.length}
                  <p class="section-hint">
                    {t(
                      "For each yarn the import found, use one from your stash, create a new one, or leave it out.",
                      locale,
                    )}
                  </p>
                  <ImportedYarns
                    bind:yarns={importState.yarns}
                    {locale}
                    disabled={saving}
                    allowExternal={linkImports.url}
                  />
                  <h3 class="more-yarn-heading">
                    {t("More yarn from my stash", locale)}
                  </h3>
                {/if}
                <YarnPicker
                  {locale}
                  query={yarnSearchInput}
                  results={yarnResults}
                  selected={selectedYarns}
                  loading={yarnLoading}
                  error={yarnSearchError}
                  onQueryChange={(value) => (yarnSearchInput = value)}
                  onSearch={beginSearch}
                  onAdd={addYarn}
                  onRemove={removeYarn}
                />
                <div class="field field-wide yarn-other-materials">
                  <label for="project-materials" id="project-materials-label"
                    ><LabelIcon name="scissors" />{t(
                      "Other materials",
                      locale,
                    )}</label
                  >
                  <MarkdownEditor
                    id="project-materials"
                    labelledBy="project-materials-label"
                    {locale}
                    lines
                    bind:value={draft.otherMaterials}
                    rows={4}
                  />
                </div>
              </section>

              <section class="form-card" aria-labelledby="writing-heading">
                <h2 id="writing-heading">{t("Pattern", locale)}</h2>
                <p class="section-hint">
                  {t(
                    "Use plain text or Markdown. The reader formats it safely for you.",
                    locale,
                  )}
                </p>
                <div class="field-grid">
                  <div class="field field-wide">
                    <label
                      for="project-description"
                      id="project-description-label"
                      ><LabelIcon name="text-align" />{t(
                        "Pattern",
                        locale,
                      )}</label
                    >
                    <MarkdownEditor
                      id="project-description"
                      labelledBy="project-description-label"
                      {locale}
                      bind:value={draft.description}
                      rows={10}
                    />
                  </div>
                  <div class="field field-wide">
                    <StickyNoteField
                      id="project-notes"
                      label={t("Notes", locale)}
                      icon="note"
                      bind:value={draft.notes}
                      rows={5}
                    />
                  </div>
                </div>
              </section>

              <section class="form-card" aria-labelledby="steps-heading">
                <div class="section-title-row">
                  <div>
                    <h2 id="steps-heading">{t("Pattern steps", locale)}</h2>
                    <p class="section-hint">
                      {t("Steps stay in the order shown here.", locale)}
                    </p>
                  </div>
                  <Button
                    id="add-project-step"
                    type="button"
                    class="secondary-button"
                    variant="secondary"
                    icon="add"
                    onclick={addStep}
                  >
                    {t("Add step", locale)}
                  </Button>
                </div>
                {#if fieldErrors.steps}<p
                    id="project-steps-error"
                    class="field-error"
                    role="alert"
                  >
                    {fieldErrors.steps}
                  </p>{/if}
                {#if draft.steps.length}
                  <ol class="step-editor-list">
                    {#each draft.steps as step, index (step.key)}
                      {@const stepPhotos = imagesOfStep(step.id)}
                      {@const stepLabel = `${t("Step", locale)} ${index + 1}: ${step.title}`}
                      <li class="step-editor-card">
                        <div class="step-editor-heading">
                          <h3>{t("Step", locale)} {index + 1}</h3>
                          <div class="step-actions">
                            <Button
                              type="button"
                              class="icon-button"
                              variant="ghost"
                              size="sm"
                              icon="move-up"
                              aria-label={`${t("Move up", locale)} ${t("Step", locale)} ${index + 1}`}
                              disabled={index === 0}
                              onclick={() => moveStep(index, -1)}
                            />
                            <Button
                              type="button"
                              class="icon-button"
                              variant="ghost"
                              size="sm"
                              icon="move-down"
                              aria-label={`${t("Move down", locale)} ${t("Step", locale)} ${index + 1}`}
                              disabled={index === draft.steps.length - 1}
                              onclick={() => moveStep(index, 1)}
                            />
                            <Button
                              type="button"
                              class="remove-button"
                              variant="ghost"
                              icon="action-delete"
                              aria-label={`${t("Remove", locale)} ${t("Step", locale)} ${index + 1}`}
                              onclick={() =>
                                (draft.steps = draft.steps.filter(
                                  (item) => item.key !== step.key,
                                ))}
                            >
                              {t("Remove", locale)}
                            </Button>
                          </div>
                        </div>
                        <div class="field">
                          <label for={`step-title-${step.key}`}
                            ><LabelIcon name="list-numbers" />{t(
                              "Step title",
                              locale,
                            )}</label
                          >
                          <input
                            id={`step-title-${step.key}`}
                            bind:value={step.title}
                            maxlength="256"
                            required
                            aria-invalid={fieldErrors.steps &&
                            (!step.title.trim() ||
                              step.title.trim().length > 256)
                              ? "true"
                              : undefined}
                            aria-describedby={fieldErrors.steps &&
                            (!step.title.trim() ||
                              step.title.trim().length > 256)
                              ? "project-steps-error"
                              : undefined}
                          />
                        </div>
                        <div class="field">
                          <label
                            for={`step-description-${step.key}`}
                            id={`step-description-${step.key}-label`}
                            ><LabelIcon name="text-align" />{t(
                              "Step instructions",
                              locale,
                            )}</label
                          >
                          <MarkdownEditor
                            id={`step-description-${step.key}`}
                            labelledBy={`step-description-${step.key}-label`}
                            {locale}
                            bind:value={step.description}
                            rows={4}
                          />
                        </div>
                        {#if project}
                          <div
                            class="step-photos"
                            use:fileDrop={{
                              enabled:
                                mediaUploadAvailable && step.id !== undefined,
                              accept: "image/*",
                              multiple: true,
                              label: t("Drop to add photos", locale),
                              onFiles: (files) =>
                                void addImages(files, step.id),
                              onRejected: () =>
                                (mediaError = t(
                                  "Choose a supported image file.",
                                  locale,
                                )),
                            }}
                          >
                            <h4>{t("Step photos", locale)}</h4>
                            <AddMediaControl
                              id={`step-photo-file-${step.key}`}
                              {locale}
                              camera
                              label={t("Add photos", locale)}
                              inputLabel={`${t("Choose step photos", locale)} ${index + 1}`}
                              disabled={!mediaUploadAvailable ||
                                step.id === undefined}
                              hint={t(
                                "Save your changes first to add photos.",
                                locale,
                              )}
                              onFiles={(files) =>
                                void addImages(files, step.id)}
                            />
                            {#if stepPhotos.length}
                              <ReorderableMedia
                                items={stepPhotos}
                                {locale}
                                label={stepLabel}
                                fallbackAlt={t("Step photo", locale)}
                                disabled={!mediaAvailable}
                                onMove={(from, to) =>
                                  reorderImages(step.id ?? null, from, to)}
                                onDelete={(image) => void removeImage(image.id)}
                                deleteDisabled={!mediaDeleteAvailable}
                                onEdit={openPhotoEditor}
                                editDisabled={!mediaUploadAvailable}
                              />
                            {/if}
                          </div>
                        {/if}
                        {#if importState?.stepImages[step.key]?.length}
                          <ImageChoices
                            title={t("Suggested step images", locale)}
                            images={importState.stepImages[step.key]}
                            disabled={!linkImports.url || saving}
                            external={linkImports.url}
                            {locale}
                          />
                        {/if}
                      </li>
                    {/each}
                  </ol>
                {:else}
                  <p class="field-hint">
                    {t("No steps have been added yet.", locale)}
                  </p>
                {/if}
              </section>
            </div>
          </details>
        </fieldset>

        {#if project && mayManage(project.access)}
          <section class="danger-zone" aria-labelledby="danger-zone-heading">
            <div>
              <h2 id="danger-zone-heading">{t("Danger zone", locale)}</h2>
            </div>
            <Button
              class="danger-button"
              variant="danger"
              icon="action-delete"
              type="button"
              disabled={isDirty || saving || deleting || mediaBusy}
              onclick={() => openConfirmation({ kind: "project" })}
            >
              {deleting ? t("Deleting…", locale) : t("Delete project", locale)}
            </Button>
          </section>
        {/if}

        <StickyActionBar>
          {#snippet save()}
            <Button
              class="primary-button"
              variant="primary"
              icon={isCreating ? "create" : "save"}
              type="submit"
              disabled={saveDisabled}
            >
              {saving
                ? t("Saving…", locale)
                : isCreating
                  ? t("Create project", locale)
                  : t("Save changes", locale)}
            </Button>
          {/snippet}
          {#snippet cancel()}
            <a
              class="quiet-button ui-button ui-button--secondary ui-button--md"
              href={projectId === null
                ? resolve("/projects")
                : resolve("/projects/[id]", { id: String(projectId) })}
              ><ActionLabel icon="cancel">{t("Cancel", locale)}</ActionLabel></a
            >
          {/snippet}
          {#snippet status()}
            {#if serverError}
              <span
                aria-hidden="true"
                class="action-status action-status--error">{serverError}</span
              >
            {:else if statusMessage}
              <span aria-hidden="true" class="action-status"
                >{statusMessage}</span
              >
            {:else if isDirty}
              <span class="dirty-note" role="status"
                >{t("Unsaved changes", locale)}</span
              >
            {/if}
          {/snippet}
        </StickyActionBar>
      </form>
      {#if photoEditImage && projectId !== null}
        <PhotoEditor
          open={photoEditOpen}
          src={photoEditImage.url}
          alt={photoEditImage.alt_text ?? t("Project photo", locale)}
          altText={photoEditImage.alt_text ?? ""}
          endpoint={projectImageEditPath(projectId, photoEditImage.id)}
          {locale}
          onSaved={reloadAfterPhotoEdit}
          onClose={() => (photoEditOpen = false)}
        />
      {/if}
      <ConfirmDialog
        bind:this={confirmationDialog}
        titleId="project-confirmation-title"
        title={confirmationTitle}
        busy={confirmationBusy}
      >
        {#snippet children()}
          {#if confirmedAction?.kind === "project"}
            <p>{t("This cannot be undone.", locale)}</p>
          {:else if mediaError}
            <p class="field-error" role="alert">{mediaError}</p>
          {/if}
        {/snippet}
        {#snippet actions()}
          <Button
            type="button"
            variant="secondary"
            icon="cancel"
            disabled={confirmationBusy}
            onclick={() => {
              untrack(() => confirmationDialog?.close());
              if (confirmedAction?.kind !== "project") mediaError = "";
              confirmedAction = null;
            }}
          >
            {t("Cancel", locale)}
          </Button>
          <Button
            type="button"
            variant="danger"
            icon="action-delete"
            disabled={confirmationBusy}
            onclick={() => void confirmAction()}
          >
            {confirmedAction?.kind === "project"
              ? t("Delete project", locale)
              : t("Remove", locale)}
          </Button>
        {/snippet}
      </ConfirmDialog>
    {/if}
    <ConfirmDialog
      bind:this={importOfferDialog}
      titleId="import-offer-title"
      title={t("Import this pattern?", locale)}
    >
      {#snippet children()}
        {#if linkImports.ai}
          <p>
            {t(
              "Stricknani can read the pattern behind this link with the AI importer and fill in the project for you.",
              locale,
            )}
          </p>
        {:else}
          <p>
            {t(
              "Stricknani can read the pattern behind this link and fill in the project for you.",
              locale,
            )}
          </p>
        {/if}
      {/snippet}
      {#snippet actions()}
        <Button
          type="button"
          variant="secondary"
          icon="cancel"
          onclick={declineImport}
        >
          {t("Not now", locale)}
        </Button>
        <Button
          type="button"
          variant="primary"
          icon="link"
          onclick={acceptImport}
        >
          {t("Import", locale)}
        </Button>
      {/snippet}
    </ConfirmDialog>
    <ConfirmDialog
      bind:this={conflictDialog}
      titleId="load-latest-project-title"
      title={t(
        "Replace your unsaved edits with the latest saved version?",
        locale,
      )}
      busy={conflictLoadBusy}
    >
      {#snippet children()}
        {#if conflictLoadError}<p class="field-error" role="alert">
            {conflictLoadError}
          </p>{/if}
      {/snippet}
      {#snippet actions()}
        <Button
          type="button"
          variant="secondary"
          icon="cancel"
          disabled={conflictLoadBusy}
          onclick={() => {
            conflictLoadError = "";
            untrack(() => conflictDialog?.close());
          }}
        >
          {t("Cancel", locale)}
        </Button>
        <Button
          type="button"
          variant="primary"
          icon="refresh"
          disabled={conflictLoadBusy || !conflictProject}
          onclick={() => void confirmLoadConflictLatest()}
        >
          {t("Load latest values", locale)}
        </Button>
      {/snippet}
    </ConfirmDialog>
  {/if}
</section>

<style>
  .project-editor {
    display: grid;
    gap: 1.4rem;
  }

  .section-title-row,
  .step-editor-heading,
  .button-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
  }

  /* Longer (German) labels must wrap under the heading instead of pushing the page wider than
     the phone, which also drags the fixed bottom bar off-screen in Chrome. */
  .section-title-row {
    flex-wrap: wrap;
  }

  .section-title-row > div {
    min-width: 0;
  }

  h2,
  h3,
  p {
    margin-top: 0;
  }

  .section-hint,
  .field-hint,
  .dirty-note {
    color: var(--ink-muted);
  }

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
    border-radius: var(--radius-sm);
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

  .yarn-other-materials {
    margin-top: 1rem;
  }

  .more-yarn-heading {
    margin: 1.2rem 0 0.4rem;
    font-size: 1rem;
  }

  .notice a {
    color: var(--accent-strong);
    font-weight: 650;
  }

  label {
    font-size: var(--ui-label-font-size);
    font-weight: var(--ui-label-font-weight);
  }

  input {
    width: 100%;
  }

  input:focus-visible,
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

  input:disabled {
    opacity: 0.62;
    cursor: not-allowed;
  }

  .action-status {
    color: var(--accent-strong);
    font-size: 0.88rem;
  }

  .action-status--error {
    color: var(--danger);
  }

  .conflict-panel {
    margin-bottom: 1rem;
    border-color: var(--danger);
  }

  .button-row {
    flex-wrap: wrap;
    justify-content: flex-start;
  }

  .attachment-list {
    display: grid;
    gap: 0.5rem;
    margin: 0.8rem 0 0;
    padding: 0;
    list-style: none;
  }

  .attachment-list li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0.6rem;
    background: var(--surface-raised);
  }

  .attachment-list li > a {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .attachment-list span {
    color: var(--ink-muted);
  }

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
    border-radius: var(--radius-xs);
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

  :global(.icon-button) {
    width: 44px;
    padding: 0;
    font-size: 1.1rem;
  }

  :global(.remove-button) {
    color: var(--danger);
  }

  .media-manager {
    display: grid;
    gap: 0.8rem;
  }

  .step-photos {
    display: grid;
    gap: 0.6rem;
  }

  .step-photos h4 {
    margin: 0;
    font-size: 0.95rem;
  }

  .upload-state {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .optional-details {
    min-width: 0;
  }

  .optional-details > summary {
    min-height: 44px;
    padding: 0.7rem 0;
    color: var(--accent-strong);
    cursor: pointer;
    font-weight: 700;
  }

  .optional-details__content {
    display: grid;
    min-width: 0;
    gap: 1rem;
    padding-top: 0.5rem;
  }

  .danger-zone {
    display: flex;
    min-width: 0;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 0.75rem;
    border: 1px solid var(--danger);
    border-radius: var(--radius-sm);
    padding: 1rem;
  }

  .danger-zone h2 {
    margin: 0;
    font-size: 1rem;
  }

  .dirty-note {
    font-size: 0.88rem;
  }

  @media (max-width: 560px) {
    .field-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .field-wide {
      grid-column: auto;
    }

    .step-editor-heading {
      align-items: flex-start;
      flex-direction: column;
    }

    .attachment-list li {
      align-items: flex-start;
      flex-direction: column;
    }
  }
</style>
