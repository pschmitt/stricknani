<script lang="ts">
  import LabelIcon from "#lib/components/LabelIcon.svelte";
  import StickyNoteField from "#lib/components/StickyNoteField.svelte";
  import UrlField from "#lib/components/UrlField.svelte";
  import ErrorPanel from "#lib/components/ErrorPanel.svelte";
  import { beforeNavigate, goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import { onMount, tick, untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ForeignOwnerBanner from "#lib/components/ForeignOwnerBanner.svelte";
  import { foreignOwner } from "#lib/owner.ts";
  import {
    isAccessLost,
    isAccessLowered,
    mayEdit,
    mayManage,
  } from "#lib/access.ts";
  import StickyActionBar from "#lib/components/StickyActionBar.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import ImageChoices from "../imports/ImageChoices.svelte";
  import ImportCard from "../imports/ImportCard.svelte";
  import type { ImportPreview } from "../imports/import-api";
  import { importAvailability } from "../imports/import-availability";
  import {
    importWarningMessage,
    yarnImportEditor,
    type ImportImageChoice,
  } from "../imports/import-draft";
  import {
    deleteImportDraft,
    draftIdFromSearch,
  } from "../imports/import-drafts-api";
  import {
    autoStartFromSearch,
    importStartQuery,
    prefillUrlFromSearch,
  } from "../imports/shared-link";
  import YarnPhotoManager from "./YarnPhotoManager.svelte";
  import {
    createYarn,
    deleteYarn,
    getYarn,
    parseYarnDetail,
    updateYarn,
    type YarnDetail,
    type YarnWrite,
  } from "./yarn-api";

  type Draft = {
    name: string;
    brand: string;
    colorway: string;
    dye_lot: string;
    fiber_content: string;
    weight_category: string;
    recommended_needles: string;
    weight_grams: string;
    length_meters: string;
    description: string;
    notes: string;
    link: string;
  };

  const blankDraft = (): Draft => ({
    name: "",
    brand: "",
    colorway: "",
    dye_lot: "",
    fiber_content: "",
    weight_category: "",
    recommended_needles: "",
    weight_grams: "",
    length_meters: "",
    description: "",
    notes: "",
    link: "",
  });

  const optionalDraftFields: (keyof Draft)[] = [
    "dye_lot",
    "fiber_content",
    "weight_category",
    "recommended_needles",
    "weight_grams",
    "length_meters",
    "description",
    "notes",
    "link",
  ];

  let {
    yarnId,
  }: {
    yarnId: number | null;
  } = $props();

  let loadState = $state<"loading" | "ready" | "not-found" | "error">(
    "loading",
  );
  let loadedYarn = $state<YarnDetail | null>(null);
  let draft = $state<Draft>(blankDraft());
  let savedSnapshot = $state(JSON.stringify(blankDraft()));
  let expectedUpdatedAt = $state("");
  let conflictYarn = $state<YarnDetail | null>(null);
  let fieldErrors = $state<Record<string, string>>({});
  let serverError = $state("");
  let statusMessage = $state("");
  let loadError = $state("");
  let deleteError = $state("");
  let saving = $state(false);
  let mediaBusy = $state(false);
  let deleting = $state(false);
  let deleteDialog = $state<ConfirmDialog | undefined>(undefined);
  let conflictDialog = $state<ConfirmDialog | undefined>(undefined);
  let conflictDialogBusy = $state(false);
  let conflictDialogError = $state("");
  let yarnForm = $state<HTMLFormElement | undefined>(undefined);
  let optionalDetailsOpen = $state(false);
  let reload = $state(0);
  let routeGeneration = 0;
  /** What an import added to a new yarn: pictures to choose from and where it came from. */
  let importImages = $state<ImportImageChoice[]>([]);
  let importDraftId = $state<number | null>(null);
  let importAi = $state(false);
  let importWarnings = $state<string[]>([]);
  let importBusy = $state(false);

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const allowExternalImages = $derived(
    importAvailability(session.data?.capabilities).url,
  );
  const resumeDraftId = $derived(
    yarnId === null ? draftIdFromSearch(page.url.search) : null,
  );
  const sharedLink = $derived(
    yarnId === null ? (prefillUrlFromSearch(page.url.search) ?? "") : "",
  );
  const sharedAutoStart = $derived(
    yarnId === null && autoStartFromSearch(page.url.search),
  );
  const isDirty = $derived(savedSnapshot !== JSON.stringify(draft));
  const canManagePhotos = $derived(
    loadedYarn !== null && !isDirty && !saving && !mediaBusy && !deleting,
  );
  const saveLabel = $derived(
    saving
      ? t("Saving…", locale)
      : yarnId === null
        ? t("Save yarn", locale)
        : t("Save changes", locale),
  );
  const saveDisabled = $derived(
    (yarnId !== null && !isDirty) ||
      saving ||
      mediaBusy ||
      deleting ||
      conflictYarn !== null,
  );

  onMount(() => {
    const unregister = registerPwaUpdateGuard(() => ({
      dirty: isDirty,
      busy:
        loadState === "loading" ||
        saving ||
        mediaBusy ||
        deleting ||
        importBusy,
    }));
    const onKeydown = (event: KeyboardEvent) => {
      if (
        event.defaultPrevented ||
        event.isComposing ||
        event.repeat ||
        !(event.ctrlKey || event.metaKey) ||
        event.altKey ||
        event.shiftKey ||
        event.key.toLowerCase() !== "s" ||
        !yarnForm ||
        loadState !== "ready" ||
        document.querySelector("dialog[open]")
      )
        return;
      event.preventDefault();
      if (!isDirty || saving || mediaBusy || deleting || conflictYarn !== null)
        return;
      yarnForm.requestSubmit();
    };
    window.addEventListener("keydown", onKeydown);
    return () => {
      unregister();
      window.removeEventListener("keydown", onKeydown);
    };
  });

  $effect(() => {
    const id = yarnId;
    const currentUserId = userId;
    reload;
    const generation = ++routeGeneration;
    optionalDetailsOpen = id !== null;
    saving = false;
    mediaBusy = false;
    deleting = false;
    deleteError = "";
    untrack(() => deleteDialog?.close());
    untrack(() => conflictDialog?.close());
    conflictDialogBusy = false;
    conflictDialogError = "";
    if (id === null) {
      loadedYarn = null;
      loadState = "ready";
      loadError = "";
      return () => {
        if (routeGeneration === generation) routeGeneration += 1;
      };
    }
    if (currentUserId === null) {
      loadedYarn = null;
      loadState = "error";
      loadError = t("Sign in to view your yarn.", locale);
      return () => {
        if (routeGeneration === generation) routeGeneration += 1;
      };
    }
    const controller = new AbortController();
    loadState = "loading";
    loadError = "";
    serverError = "";
    conflictYarn = null;
    void getYarn(id, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        applyLoadedYarn(result);
        loadState = "ready";
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 404) {
          loadState = "not-found";
          return;
        }
        loadState = "error";
        loadError =
          error instanceof Error
            ? error.message
            : t("The yarn could not be loaded.", locale);
      });
    return () => {
      controller.abort();
      if (routeGeneration === generation) routeGeneration += 1;
    };
  });

  beforeNavigate(({ cancel, willUnload }) => {
    if (willUnload) return;
    if (
      isDirty &&
      typeof window !== "undefined" &&
      !window.confirm(t("Leave without saving your yarn changes?", locale))
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

  function draftFromYarn(yarn: YarnDetail): Draft {
    return {
      name: yarn.name,
      brand: yarn.brand ?? "",
      colorway: yarn.colorway ?? "",
      dye_lot: yarn.dye_lot ?? "",
      fiber_content: yarn.fiber_content ?? "",
      weight_category: yarn.weight_category ?? "",
      recommended_needles: yarn.recommended_needles ?? "",
      weight_grams: yarn.weight_grams === null ? "" : String(yarn.weight_grams),
      length_meters:
        yarn.length_meters === null ? "" : String(yarn.length_meters),
      description: yarn.description ?? "",
      notes: yarn.notes ?? "",
      link: yarn.link ?? "",
    };
  }

  function applyLoadedYarn(yarn: YarnDetail) {
    const nextDraft = draftFromYarn(yarn);
    loadedYarn = yarn;
    draft = nextDraft;
    expectedUpdatedAt = yarn.updated_at;
    savedSnapshot = JSON.stringify(nextDraft);
    fieldErrors = {};
    conflictYarn = null;
  }

  /** Fill the form from a finished import. */
  function applyImport(result: {
    preview: ImportPreview;
    draftId: number | null;
  }): void {
    const editor = yarnImportEditor(result.preview, draft.link);
    draft = {
      ...blankDraft(),
      name: editor.name,
      brand: editor.brand,
      colorway: editor.colorway,
      fiber_content: editor.fiberContent,
      weight_category: editor.weightCategory,
      recommended_needles: editor.recommendedNeedles,
      weight_grams: editor.weightGrams,
      length_meters: editor.lengthMeters,
      description: editor.description,
      notes: editor.notes,
      link: editor.link,
    };
    importImages = editor.images;
    importDraftId = result.draftId;
    importAi = result.preview.is_ai_enhanced;
    importWarnings = result.preview.draft.warnings;
    fieldErrors = {};
    optionalDetailsOpen = true;
  }

  const importNotices = $derived(
    importWarnings
      .map((code) => ({ code, message: importWarningMessage(code, locale) }))
      .filter(
        (entry): entry is { code: string; message: string } =>
          entry.message !== null,
      ),
  );

  function optionalText(value: string): string | null {
    const cleaned = value.trim();
    return cleaned ? cleaned : null;
  }

  function parseMeasurement(value: string, field: string): number | null {
    const cleaned = value.trim();
    if (!cleaned) return null;
    const number = Number(cleaned);
    if (!Number.isSafeInteger(number) || number < 0) {
      fieldErrors[field] = t("Enter a whole number of zero or more.", locale);
      return null;
    }
    return number;
  }

  function makeWrite(): YarnWrite | null {
    fieldErrors = {};
    serverError = "";
    statusMessage = "";
    const name = draft.name.trim();
    if (!name) fieldErrors.name = t("Enter a yarn name.", locale);
    else if (name.length > 256)
      fieldErrors.name = t("Use 256 characters or fewer.", locale);
    const weightGrams = parseMeasurement(draft.weight_grams, "weight_grams");
    const lengthMeters = parseMeasurement(draft.length_meters, "length_meters");
    const link = optionalText(draft.link);
    if (link) {
      try {
        const url = new URL(link);
        if (url.protocol !== "http:" && url.protocol !== "https:")
          throw new Error("scheme");
      } catch {
        fieldErrors.link = t("Enter a complete http or https URL.", locale);
      }
    }
    revealErroredOptionalFields();
    if (Object.keys(fieldErrors).length > 0) return null;
    const imageUrls =
      yarnId === null && allowExternalImages
        ? importImages
            .filter((image) => image.selected)
            .map((image) => image.url.trim())
            .filter(Boolean)
        : [];
    return {
      name,
      description: optionalText(draft.description),
      brand: optionalText(draft.brand),
      colorway: optionalText(draft.colorway),
      dye_lot: optionalText(draft.dye_lot),
      fiber_content: optionalText(draft.fiber_content),
      weight_category: optionalText(draft.weight_category),
      recommended_needles: optionalText(draft.recommended_needles),
      weight_grams: weightGrams,
      length_meters: lengthMeters,
      notes: optionalText(draft.notes),
      link,
      is_ai_enhanced: loadedYarn?.is_ai_enhanced ?? importAi,
      ...(imageUrls.length ? { image_urls: imageUrls } : {}),
    };
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    const currentSession = session.data;
    if (
      !currentSession?.user ||
      saving ||
      mediaBusy ||
      deleting ||
      conflictYarn !== null
    )
      return;
    const id = yarnId;
    const generation = routeGeneration;
    const value = makeWrite();
    if (!value) {
      await focusFirstValidationError(id, currentSession.user.id, generation);
      if (!isCurrentRoute(id, currentSession.user.id, generation)) return;
      publishFeedback({
        ownerId: currentSession.user.id,
        kind: "error",
        message: t("Check the highlighted fields and try again.", locale),
      });
      return;
    }
    await save(value, expectedUpdatedAt);
  }

  async function save(value: YarnWrite, version: string) {
    const currentSession = session.data;
    if (!currentSession?.user || saving || mediaBusy || deleting) return;
    const id = yarnId;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    let focusServerErrors = false;
    const importedDraftId = id === null ? importDraftId : null;
    saving = true;
    serverError = "";
    statusMessage = "";
    try {
      const result =
        id === null
          ? await createYarn(value, currentSession)
          : await updateYarn(id, value, version, currentSession);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      applyLoadedYarn(result);
      const successMessage =
        id === null
          ? t("Yarn created.", locale)
          : t("Yarn changes saved.", locale);
      publishFeedback({
        ownerId,
        kind: "success",
        message: successMessage,
      });
      // The import is done with: take it off the "Waiting for review" list.
      if (importedDraftId !== null) {
        try {
          await deleteImportDraft(importedDraftId, currentSession);
        } catch {
          // The draft expires on its own.
        }
      }
      await goto(`${resolve("/yarns")}/${result.id}`);
    } catch (error) {
      if (!isCurrentRoute(id, ownerId, generation)) return;
      if (error instanceof ApiError && error.code === "conflict") {
        conflictYarn = null;
        if (error.current) {
          try {
            conflictYarn = parseYarnDetail(error.current);
          } catch {
            conflictYarn = null;
          }
        }
        serverError = t(
          "This yarn changed elsewhere. Your edits are still here.",
          locale,
        );
      } else if (isAccessLost(error)) {
        serverError = t(
          "You no longer have access to this yarn. Your changes are still here.",
          locale,
        );
      } else if (isAccessLowered(error)) {
        serverError = t(
          "You can no longer edit this yarn. Your changes are still here.",
          locale,
        );
      } else if (error instanceof ApiError && error.fields) {
        for (const [field, message] of Object.entries(error.fields)) {
          fieldErrors[field] =
            field === "name"
              ? t("Enter a yarn name.", locale)
              : field === "weight_grams" || field === "length_meters"
                ? t("Enter a whole number of zero or more.", locale)
                : message;
        }
        revealErroredOptionalFields();
        focusServerErrors = true;
        serverError = error.message;
      } else {
        serverError =
          error instanceof Error
            ? error.message
            : t("The yarn could not be saved.", locale);
      }
      if (serverError && userId === ownerId)
        publishFeedback({ ownerId, kind: "error", message: serverError });
    } finally {
      if (isCurrentRoute(id, ownerId, generation)) saving = false;
    }
    if (focusServerErrors && isCurrentRoute(id, ownerId, generation))
      await focusFirstValidationError(id, ownerId, generation);
  }

  function openDeleteDialog() {
    const currentSession = session.data;
    const currentYarn = loadedYarn;
    if (
      !currentSession?.user ||
      !currentYarn ||
      yarnId !== currentYarn.id ||
      isDirty ||
      saving ||
      mediaBusy ||
      deleting
    )
      return;
    deleteError = "";
    deleteDialog?.showModal();
  }

  async function removeYarn() {
    const currentSession = session.data;
    const currentYarn = loadedYarn;
    const id = yarnId;
    if (
      !currentSession?.user ||
      !currentYarn ||
      id === null ||
      currentYarn.id !== id ||
      isDirty ||
      saving ||
      mediaBusy ||
      deleting
    )
      return;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    deleting = true;
    deleteError = "";
    try {
      await deleteYarn(id, currentSession);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      publishFeedback({
        ownerId,
        kind: "success",
        message: t("Yarn deleted.", locale),
      });
      untrack(() => deleteDialog?.close());
      await goto(resolve("/yarns"));
    } catch (error) {
      if (!isCurrentRoute(id, ownerId, generation)) return;
      deleteError =
        error instanceof Error
          ? error.message
          : t("The yarn could not be deleted.", locale);
    } finally {
      if (isCurrentRoute(id, ownerId, generation)) deleting = false;
    }
  }

  function isCurrentRoute(
    id: number | null,
    ownerId: number,
    generation: number,
  ): boolean {
    return (
      yarnId === id && userId === ownerId && routeGeneration === generation
    );
  }

  function useLatestValues() {
    if (!conflictYarn || conflictDialogBusy) return;
    conflictDialogError = "";
    conflictDialog?.showModal();
  }

  async function confirmLoadLatestValues() {
    const currentSession = session.data;
    const id = yarnId;
    const conflict = conflictYarn;
    if (!currentSession?.user || id === null || !conflict || conflictDialogBusy)
      return;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    if (!isCurrentRoute(id, ownerId, generation) || conflictYarn !== conflict)
      return;
    conflictDialogBusy = true;
    conflictDialogError = "";
    try {
      const latest = await getYarn(id);
      if (!isCurrentRoute(id, ownerId, generation) || conflictYarn !== conflict)
        return;
      if (latest.id !== id)
        throw new Error(t("The yarn could not be loaded.", locale));
      applyLoadedYarn(latest);
      serverError = "";
      statusMessage = t("Latest saved version loaded.", locale);
      publishFeedback({ ownerId, kind: "success", message: statusMessage });
      untrack(() => conflictDialog?.close());
    } catch (error) {
      if (!isCurrentRoute(id, ownerId, generation) || conflictYarn !== conflict)
        return;
      conflictDialogError =
        error instanceof Error
          ? error.message
          : t("The yarn could not be loaded.", locale);
    } finally {
      if (isCurrentRoute(id, ownerId, generation)) conflictDialogBusy = false;
    }
  }

  async function reloadAfterMediaChange(id: number, ownerId: number) {
    const generation = routeGeneration;
    if (!isCurrentRoute(id, ownerId, generation)) return;
    if (isDirty) {
      throw new Error(
        t("Save your yarn changes before editing photos.", locale),
      );
    }
    const currentId = yarnId;
    if (currentId === null || currentId !== id) return;
    const fresh = await getYarn(id);
    if (!isCurrentRoute(id, ownerId, generation)) return;
    if (isDirty) {
      throw new Error(
        t(
          "Your edits changed while the photo was uploading. Save before continuing.",
          locale,
        ),
      );
    }
    applyLoadedYarn(fresh);
  }

  function setDraft(field: keyof Draft, value: string) {
    draft[field] = value;
    delete fieldErrors[field];
    statusMessage = "";
  }

  function revealErroredOptionalFields(): void {
    if (optionalDraftFields.some((field) => fieldErrors[field]))
      optionalDetailsOpen = true;
  }

  async function focusFirstValidationError(
    id: number | null,
    ownerId: number,
    generation: number,
  ): Promise<void> {
    revealErroredOptionalFields();
    await tick();
    if (!isCurrentRoute(id, ownerId, generation)) return;
    const target = yarnForm?.querySelector<
      HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
    >('[aria-invalid="true"]');
    if (target?.isConnected) target.focus();
  }

  function fieldId(field: keyof Draft): string {
    return `yarn-${field}`;
  }
</script>

<svelte:head>
  <title
    >Stricknani · {yarnId === null
      ? t("New yarn", locale)
      : t("Edit yarn", locale)}</title
  >
</svelte:head>

{#if loadState === "loading"}
  <main class="form-loading" aria-busy="true" aria-live="polite">
    <p>{t("Loading yarn details…", locale)}</p>
  </main>
{:else if loadState === "not-found"}
  <div class="page-container page-container--standard">
    <ErrorPanel
      kind="yarn-missing"
      seed={String(yarnId)}
      backHref={resolve("/yarns")}
      backLabel={msg("Back to yarns")}
    />
  </div>
{:else if loadState === "error"}
  <section class="form-error" aria-labelledby="form-error-title">
    <h1 id="form-error-title">{loadError}</h1>
    <div class="form-actions">
      <Button
        class="quiet-button"
        variant="secondary"
        icon="reset"
        type="button"
        onclick={() => (reload += 1)}
      >
        {t("Try again", locale)}
      </Button>
      <a
        class="primary-action ui-button ui-button--primary ui-button--md"
        href={resolve("/yarns")}>{t("Back to yarns", locale)}</a
      >
    </div>
  </section>
{:else if loadedYarn && !mayEdit(loadedYarn.access)}
  <section class="form-error" aria-labelledby="form-readonly-title">
    <h1 id="form-readonly-title">
      {t("You can view this yarn but not edit it.", locale)}
    </h1>
    <div class="form-actions">
      <a
        class="primary-action ui-button ui-button--primary ui-button--md"
        href={`${resolve("/yarns")}/${loadedYarn.id}`}
        >{t("Back to the yarn", locale)}</a
      >
    </div>
  </section>
{:else}
  <main
    class="editor page-container page-container--standard"
    aria-labelledby="editor-title"
  >
    <PageHeader
      title={yarnId === null ? t("Add yarn", locale) : t("Edit yarn", locale)}
      titleId="editor-title"
    >
      {#snippet actions()}
        {#if isDirty}<span class="unsaved-pill" role="status"
            >{t("Unsaved changes", locale)}</span
          >{/if}
      {/snippet}
    </PageHeader>

    {#if foreignOwner(loadedYarn?.owner, userId)}
      {@const foreign = foreignOwner(loadedYarn?.owner, userId)}
      {#if foreign}
        <ForeignOwnerBanner kind="yarn" owner={foreign} {locale} />
      {/if}
    {/if}

    {#if yarnId === null}
      <ImportCard
        target="yarn"
        initialUrl={sharedLink}
        autoStart={sharedAutoStart}
        {resumeDraftId}
        replaceNeedsConfirm={isDirty}
        disabled={saving}
        bind:busy={importBusy}
        onReady={applyImport}
      />
      {#each importNotices as notice (notice.code)}<p
          class="import-notice"
          role="status"
        >
          {notice.message}
          {#if notice.code === "looks_like_pattern_page" && draft.link}
            <a
              href={`${resolve("/projects/new")}${importStartQuery(draft.link)}`}
              >{t("New project", locale)}</a
            >
          {/if}
        </p>{/each}
    {/if}

    {#if conflictYarn}
      <section class="conflict-panel" aria-labelledby="conflict-title">
        <h2 id="conflict-title">{t("A newer version was saved", locale)}</h2>
        <p>
          {t(
            "Your draft is unchanged. Compare the latest details, then choose whether to load them or save your draft over that version.",
            locale,
          )}
        </p>
        <div class="latest-summary">
          <strong>{conflictYarn.name}</strong>
          <span
            >{[
              conflictYarn.brand,
              conflictYarn.colorway,
              conflictYarn.weight_category,
            ]
              .filter(Boolean)
              .join(" · ")}</span
          >
          <time datetime={conflictYarn.updated_at}
            >{t("Saved", locale)}
            {new Intl.DateTimeFormat(locale, {
              dateStyle: "medium",
              timeStyle: "short",
            }).format(new Date(conflictYarn.updated_at))}</time
          >
        </div>
        <div class="conflict-actions">
          <Button
            class="quiet-button"
            variant="secondary"
            icon="refresh"
            type="button"
            disabled={saving || mediaBusy || deleting}
            onclick={useLatestValues}
          >
            {t("Load latest values", locale)}
          </Button>
          <Button
            class="primary-action"
            variant="primary"
            icon="save"
            type="button"
            disabled={saving || mediaBusy || deleting}
            onclick={() => {
              const value = makeWrite();
              if (value && conflictYarn)
                void save(value, conflictYarn.updated_at);
            }}
          >
            {saving
              ? t("Saving…", locale)
              : t("Save my draft over latest", locale)}
          </Button>
        </div>
      </section>
    {/if}

    <form
      bind:this={yarnForm}
      class="yarn-form"
      novalidate
      onsubmit={(event) => void submit(event)}
    >
      <fieldset disabled={saving || mediaBusy || deleting}>
        <legend>{t("Yarn information", locale)}</legend>
        <div class="field-grid">
          <label class="field wide" for={fieldId("name")}>
            <span
              ><LabelIcon name="yarns" />{t("Yarn name", locale)}
              <b aria-hidden="true">*</b></span
            >
            <input
              id={fieldId("name")}
              value={draft.name}
              maxlength="256"
              required
              autocomplete="off"
              aria-invalid={Boolean(fieldErrors.name)}
              aria-describedby={fieldErrors.name
                ? `${fieldId("name")}-error`
                : undefined}
              oninput={(event) => setDraft("name", event.currentTarget.value)}
            />
            {#if fieldErrors.name}<small
                id={`${fieldId("name")}-error`}
                class="field-error">{fieldErrors.name}</small
              >{/if}
          </label>
          <label class="field" for={fieldId("brand")}>
            <span><LabelIcon name="storefront" />{t("Brand", locale)}</span>
            <input
              id={fieldId("brand")}
              value={draft.brand}
              autocomplete="organization"
              oninput={(event) => setDraft("brand", event.currentTarget.value)}
            />
          </label>
          <label class="field" for={fieldId("colorway")}>
            <span><LabelIcon name="palette" />{t("Colorway", locale)}</span>
            <input
              id={fieldId("colorway")}
              value={draft.colorway}
              autocomplete="off"
              oninput={(event) =>
                setDraft("colorway", event.currentTarget.value)}
            />
          </label>
        </div>
        <details class="optional-details" bind:open={optionalDetailsOpen}>
          <summary>{t("More yarn details", locale)}</summary>
          <div class="field-grid">
            <label class="field" for={fieldId("dye_lot")}>
              <span><LabelIcon name="palette" />{t("Dye lot", locale)}</span>
              <input
                id={fieldId("dye_lot")}
                value={draft.dye_lot}
                autocomplete="off"
                oninput={(event) =>
                  setDraft("dye_lot", event.currentTarget.value)}
              />
            </label>
            <label class="field" for={fieldId("fiber_content")}>
              <span><LabelIcon name="leaf" />{t("Fiber content", locale)}</span>
              <input
                id={fieldId("fiber_content")}
                value={draft.fiber_content}
                placeholder={t("For example, 80% wool, 20% nylon", locale)}
                oninput={(event) =>
                  setDraft("fiber_content", event.currentTarget.value)}
              />
            </label>
            <label class="field" for={fieldId("weight_category")}>
              <span><LabelIcon name="scales" />{t("Yarn weight", locale)}</span>
              <input
                id={fieldId("weight_category")}
                value={draft.weight_category}
                placeholder={t("For example, DK or fingering", locale)}
                oninput={(event) =>
                  setDraft("weight_category", event.currentTarget.value)}
              />
            </label>
            <label class="field" for={fieldId("recommended_needles")}>
              <span
                ><LabelIcon name="needle" />{t(
                  "Recommended needles",
                  locale,
                )}</span
              >
              <input
                id={fieldId("recommended_needles")}
                value={draft.recommended_needles}
                oninput={(event) =>
                  setDraft("recommended_needles", event.currentTarget.value)}
              />
            </label>
            <label class="field" for={fieldId("weight_grams")}>
              <span
                ><LabelIcon name="scales" />{t(
                  "Skein weight in grams",
                  locale,
                )}</span
              >
              <input
                id={fieldId("weight_grams")}
                type="number"
                min="0"
                step="1"
                inputmode="numeric"
                value={draft.weight_grams}
                aria-invalid={Boolean(fieldErrors.weight_grams)}
                aria-describedby={fieldErrors.weight_grams
                  ? `${fieldId("weight_grams")}-error`
                  : undefined}
                oninput={(event) =>
                  setDraft("weight_grams", event.currentTarget.value)}
              />
              {#if fieldErrors.weight_grams}<small
                  id={`${fieldId("weight_grams")}-error`}
                  class="field-error">{fieldErrors.weight_grams}</small
                >{/if}
            </label>
            <label class="field" for={fieldId("length_meters")}>
              <span
                ><LabelIcon name="gauge" />{t(
                  "Skein length in meters",
                  locale,
                )}</span
              >
              <input
                id={fieldId("length_meters")}
                type="number"
                min="0"
                step="1"
                inputmode="numeric"
                value={draft.length_meters}
                aria-invalid={Boolean(fieldErrors.length_meters)}
                aria-describedby={fieldErrors.length_meters
                  ? `${fieldId("length_meters")}-error`
                  : undefined}
                oninput={(event) =>
                  setDraft("length_meters", event.currentTarget.value)}
              />
              {#if fieldErrors.length_meters}<small
                  id={`${fieldId("length_meters")}-error`}
                  class="field-error">{fieldErrors.length_meters}</small
                >{/if}
            </label>
            <label class="field wide" for={fieldId("description")}>
              <span
                ><LabelIcon name="text-align" />{t("Description", locale)}</span
              >
              <textarea
                id={fieldId("description")}
                rows="4"
                maxlength="20000"
                value={draft.description}
                oninput={(event) =>
                  setDraft("description", event.currentTarget.value)}
              ></textarea>
            </label>
            <div class="field wide">
              <StickyNoteField
                id={fieldId("notes")}
                label={t("Notes", locale)}
                icon="note"
                rows={4}
                maxlength={20000}
                value={draft.notes}
                onInput={(value) => setDraft("notes", value)}
              />
            </div>
            <div class="field wide">
              <label for={fieldId("link")}
                ><LabelIcon name="link" />{t("Yarn source URL", locale)}</label
              >
              <UrlField
                id={fieldId("link")}
                value={draft.link}
                {locale}
                placeholder="https://"
                invalid={Boolean(fieldErrors.link)}
                describedby={fieldErrors.link
                  ? `${fieldId("link")}-error`
                  : undefined}
                oninput={(value) => setDraft("link", value)}
              />
              {#if fieldErrors.link}<small
                  id={`${fieldId("link")}-error`}
                  class="field-error">{fieldErrors.link}</small
                >{/if}
            </div>
          </div>
        </details>
      </fieldset>

      {#if loadedYarn}
        <section class="photo-section" aria-labelledby="yarn-photos-title">
          <YarnPhotoManager
            yarnId={loadedYarn.id}
            photos={loadedYarn.photos}
            {locale}
            canEdit={canManagePhotos}
            onResourceUpdated={reloadAfterMediaChange}
            onMediaBusyChange={(busy, resourceId, ownerId) => {
              if (resourceId === yarnId && ownerId === userId) mediaBusy = busy;
            }}
          />
        </section>
      {:else}
        <section class="photo-placeholder" aria-labelledby="yarn-photos-title">
          <h2 id="yarn-photos-title">{t("Photos", locale)}</h2>
          {#if importImages.length}
            <ImageChoices
              title={t("Photos to add", locale)}
              images={importImages}
              disabled={!allowExternalImages || saving}
              external={allowExternalImages}
              {locale}
            />
          {/if}
          {#if !importImages.length}
            <p>
              {t(
                "Save this yarn first. You can add photos as soon as it has been created.",
                locale,
              )}
            </p>
          {/if}
        </section>
      {/if}

      {#if loadedYarn && !canManagePhotos && !mediaBusy && isDirty}
        <p class="media-hint">
          {t("Save your changes before editing photos.", locale)}
        </p>
      {/if}

      <StickyActionBar>
        {#snippet save()}
          <Button
            class="primary-action"
            variant="primary"
            icon={yarnId === null ? "create" : "save"}
            type="submit"
            disabled={saveDisabled}
          >
            {saveLabel}
          </Button>
        {/snippet}
        {#snippet cancel()}
          <a
            class="quiet-button ui-button ui-button--secondary ui-button--md"
            href={yarnId === null
              ? resolve("/yarns")
              : `${resolve("/yarns")}/${yarnId}`}
            ><ActionLabel icon="cancel">{t("Cancel", locale)}</ActionLabel></a
          >
        {/snippet}
        {#snippet status()}
          {#if serverError}
            <span aria-hidden="true" class="action-status action-status--error"
              >{serverError}</span
            >
          {:else if statusMessage}
            <span aria-hidden="true" class="action-status">{statusMessage}</span
            >
          {:else if isDirty}
            <span class="draft-note">
              {t("Your edits stay here if saving fails.", locale)}
            </span>
          {/if}
        {/snippet}
      </StickyActionBar>
    </form>
    {#if loadedYarn && mayManage(loadedYarn.access)}
      <section class="danger-zone" aria-labelledby="yarn-danger-zone-title">
        <div>
          <h2 id="yarn-danger-zone-title">{t("Delete yarn", locale)}</h2>
          <p>{t("This cannot be undone.", locale)}</p>
        </div>
        <Button
          class="danger-button"
          variant="danger"
          icon="action-delete"
          type="button"
          disabled={isDirty || saving || mediaBusy || deleting}
          onclick={openDeleteDialog}
        >
          {t("Delete yarn", locale)}
        </Button>
      </section>
    {/if}
  </main>
  <ConfirmDialog
    bind:this={conflictDialog}
    titleId="load-latest-yarn-title"
    title={t(
      "Replace your unsaved edits with the latest saved version?",
      locale,
    )}
    busy={conflictDialogBusy}
  >
    {#snippet children()}
      {#if conflictDialogError}<p class="error-text" role="alert">
          {conflictDialogError}
        </p>{/if}
    {/snippet}
    {#snippet actions()}
      <Button
        variant="secondary"
        icon="cancel"
        type="button"
        disabled={conflictDialogBusy}
        onclick={() => {
          conflictDialogError = "";
          untrack(() => conflictDialog?.close());
        }}
      >
        {t("Cancel", locale)}
      </Button>
      <Button
        variant="primary"
        icon="refresh"
        type="button"
        disabled={conflictDialogBusy || !conflictYarn}
        onclick={confirmLoadLatestValues}
      >
        {t("Load latest values", locale)}
      </Button>
    {/snippet}
  </ConfirmDialog>
  {#if loadedYarn}
    {@const yarnToDelete = loadedYarn}
    <ConfirmDialog
      bind:this={deleteDialog}
      titleId="delete-yarn-title"
      title={t("Delete yarn?", locale)}
      busy={deleting}
    >
      {#snippet children()}
        <p>
          {t("Delete", locale)} <strong>{yarnToDelete.name}</strong>? {t(
            "This cannot be undone.",
            locale,
          )}
        </p>
        {#if deleteError}<p class="error-text" role="alert">
            {deleteError}
          </p>{/if}
      {/snippet}
      {#snippet actions()}
        <Button
          class="quiet-button"
          variant="secondary"
          icon="cancel"
          type="button"
          disabled={deleting}
          onclick={() => {
            deleteError = "";
            untrack(() => deleteDialog?.close());
          }}
        >
          {t("Keep yarn", locale)}
        </Button>
        <Button
          class="danger-button"
          variant="danger"
          icon="action-delete"
          type="button"
          disabled={isDirty || saving || mediaBusy || deleting}
          onclick={() => void removeYarn()}
        >
          {deleting ? t("Deleting…", locale) : t("Delete yarn", locale)}
        </Button>
      {/snippet}
    </ConfirmDialog>
  {/if}
{/if}

<style>
  .editor,
  .form-error {
    display: grid;
    gap: 1.15rem;
  }

  .photo-placeholder p,
  .media-hint,
  .draft-note {
    margin: 0;
    color: var(--ink-muted);
  }

  .unsaved-pill {
    min-height: 44px;
    align-content: center;
    border-radius: 999px;
    padding: 0.4rem 0.75rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 0.86rem;
    font-weight: 620;
    white-space: nowrap;
  }

  .yarn-form {
    display: grid;
    gap: 1rem;
  }

  fieldset,
  .photo-section,
  .photo-placeholder,
  .conflict-panel {
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: clamp(1rem, 3vw, 1.35rem);
    background: var(--surface);
  }

  fieldset:disabled {
    opacity: 0.85;
  }

  legend {
    padding: 0 0.45rem;
    color: var(--accent-strong);
    font-size: 1.05rem;
    font-weight: 650;
  }

  .field-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.85rem;
  }

  .field {
    display: grid;
    min-width: 0;
    gap: 0.35rem;
    color: var(--ink-muted);
    font-size: 0.86rem;
    font-weight: 620;
  }

  .field b {
    color: var(--danger);
  }

  .field input,
  .field textarea {
    width: 100%;
    min-width: 0;
  }

  .field textarea {
    resize: vertical;
  }

  .wide {
    grid-column: 1 / -1;
  }

  .field-error {
    color: var(--danger);
    font-size: 0.84rem;
    font-weight: 500;
  }

  .photo-placeholder h2 {
    margin-top: 0;
  }

  .form-actions,
  .conflict-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.6rem;
  }

  .optional-details {
    min-width: 0;
    margin-top: 0.75rem;
  }

  .optional-details > summary {
    min-height: 44px;
    align-content: center;
    color: var(--accent-strong);
    cursor: pointer;
    font-weight: 650;
  }

  .optional-details .field-grid {
    padding-top: 0.75rem;
  }

  .danger-zone {
    display: flex;
    min-width: 0;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 0.75rem;
    border: 1px solid color-mix(in srgb, var(--danger) 45%, var(--line));
    border-radius: var(--radius-sm);
    padding: 1rem;
    background: var(--surface);
  }

  .danger-zone h2,
  .danger-zone p {
    margin: 0;
  }

  .danger-zone p {
    margin-top: 0.35rem;
    color: var(--ink-muted);
  }

  .error-text {
    color: var(--danger);
  }

  .draft-note {
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .action-status {
    font-size: 0.88rem;
  }

  .action-status--error {
    color: var(--danger);
  }

  .conflict-panel {
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.9rem 1rem;
    background: var(--surface);
  }

  .conflict-panel h2 {
    margin: 0 0 0.5rem;
    font-size: 1.15rem;
  }

  .conflict-panel > p {
    max-width: 60ch;
    margin-top: 0;
    color: var(--ink-muted);
  }

  .latest-summary {
    display: grid;
    gap: 0.2rem;
    border-left: 3px solid var(--accent);
    padding: 0.65rem 0.8rem;
    margin-bottom: 0.8rem;
    background: var(--surface-raised);
  }

  .latest-summary span,
  .latest-summary time {
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .form-loading {
    min-height: 14rem;
    align-content: center;
    color: var(--ink-muted);
    text-align: center;
  }

  .form-error {
    color: var(--danger);
  }

  @media (max-width: 640px) {
    .field-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .wide {
      grid-column: auto;
    }

    .conflict-actions {
      display: grid;
      grid-template-columns: 1fr 1fr;
    }

    .conflict-actions > :global(.ui-button) {
      width: 100%;
    }

    .danger-zone {
      align-items: stretch;
    }

    .danger-zone > :global(.ui-button) {
      width: 100%;
    }
  }

  @media (max-width: 420px) {
    .conflict-actions {
      grid-template-columns: minmax(0, 1fr);
    }
  }
  .import-notice {
    margin: 0;
    padding: 0.75rem 0.9rem;
    border-left: 3px solid var(--accent);
    border-radius: var(--radius-xs);
    background: var(--surface-raised);
  }

  .import-notice a {
    color: var(--accent-strong);
    font-weight: 650;
  }
</style>
