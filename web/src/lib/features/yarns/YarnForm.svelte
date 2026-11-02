<script lang="ts">
  import { beforeNavigate, goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import YarnPhotoManager from "./YarnPhotoManager.svelte";
  import {
    createYarn,
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

  let {
    yarnId,
  }: {
    yarnId: number | null;
  } = $props();

  let loadState = $state<"loading" | "ready" | "error">("loading");
  let loadedYarn = $state<YarnDetail | null>(null);
  let draft = $state<Draft>(blankDraft());
  let savedSnapshot = $state(JSON.stringify(blankDraft()));
  let expectedUpdatedAt = $state("");
  let conflictYarn = $state<YarnDetail | null>(null);
  let fieldErrors = $state<Record<string, string>>({});
  let serverError = $state("");
  let statusMessage = $state("");
  let loadError = $state("");
  let saving = $state(false);
  let mediaBusy = $state(false);
  let reload = $state(0);
  let routeGeneration = 0;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const isDirty = $derived(savedSnapshot !== JSON.stringify(draft));
  const canManagePhotos = $derived(
    loadedYarn !== null && !isDirty && !saving && !mediaBusy,
  );
  const saveLabel = $derived(
    saving
      ? t("Saving…", locale)
      : yarnId === null
        ? t("Save yarn", locale)
        : t("Save changes", locale),
  );

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: isDirty,
      busy: loadState === "loading" || saving || mediaBusy,
    })),
  );

  $effect(() => {
    const id = yarnId;
    const currentUserId = userId;
    reload;
    const generation = ++routeGeneration;
    saving = false;
    mediaBusy = false;
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
        loadState = "error";
        loadError =
          error instanceof ApiError && error.status === 404
            ? t("This yarn is no longer available.", locale)
            : error instanceof Error
              ? error.message
              : t("The yarn could not be loaded.", locale);
      });
    return () => {
      controller.abort();
      if (routeGeneration === generation) routeGeneration += 1;
    };
  });

  beforeNavigate(({ cancel }) => {
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
    if (Object.keys(fieldErrors).length > 0) return null;
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
      is_ai_enhanced: loadedYarn?.is_ai_enhanced ?? false,
    };
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    const value = makeWrite();
    const currentSession = session.data;
    if (!value || !currentSession?.user || saving) return;
    await save(value, expectedUpdatedAt);
  }

  async function save(value: YarnWrite, version: string) {
    const currentSession = session.data;
    if (!currentSession?.user || saving) return;
    const id = yarnId;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    saving = true;
    serverError = "";
    statusMessage = "";
    try {
      const result =
        id === null
          ? await createYarn(value, currentSession)
          : await updateYarn(id, value, version, currentSession);
      if (!isCurrentRoute(id, ownerId, generation)) return;
      if (id === null) {
        savedSnapshot = JSON.stringify(draft);
        await goto(`${resolve("/yarns")}/${result.id}`);
        return;
      }
      applyLoadedYarn(result);
      statusMessage = t("Yarn changes saved.", locale);
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
      } else if (error instanceof ApiError && error.fields) {
        for (const [field, message] of Object.entries(error.fields)) {
          fieldErrors[field] =
            field === "name"
              ? t("Enter a yarn name.", locale)
              : field === "weight_grams" || field === "length_meters"
                ? t("Enter a whole number of zero or more.", locale)
                : message;
        }
        serverError = error.message;
      } else {
        serverError =
          error instanceof Error
            ? error.message
            : t("The yarn could not be saved.", locale);
      }
    } finally {
      if (isCurrentRoute(id, ownerId, generation)) saving = false;
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
    if (!conflictYarn) return;
    if (
      typeof window !== "undefined" &&
      !window.confirm(
        t("Replace your unsaved edits with the latest saved version?", locale),
      )
    )
      return;
    applyLoadedYarn(conflictYarn);
    serverError = "";
    statusMessage = t("Latest saved version loaded.", locale);
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
{:else if loadState === "error"}
  <section class="form-error" aria-labelledby="form-error-title">
    <h1 id="form-error-title">{loadError}</h1>
    <div class="form-actions">
      <button class="quiet-button" type="button" onclick={() => (reload += 1)}
        ><ActionLabel icon="reset">{t("Try again", locale)}</ActionLabel
        ></button
      >
      <a class="primary-action" href={resolve("/yarns")}
        >{t("Back to yarns", locale)}</a
      >
    </div>
  </section>
{:else}
  <main class="editor" aria-labelledby="editor-title">
    <a
      class="back-link"
      href={yarnId === null
        ? resolve("/yarns")
        : `${resolve("/yarns")}/${yarnId}`}
    >
      ← {yarnId === null ? t("Yarn shelf", locale) : t("Back to yarn", locale)}
    </a>
    <header class="editor-heading">
      <div>
        <p class="section-mark">
          {yarnId === null
            ? t("Your materials", locale)
            : t("Yarn details", locale)}
        </p>
        <h1 id="editor-title">
          {yarnId === null ? t("Add yarn", locale) : t("Edit yarn", locale)}
        </h1>
        <p>
          {t(
            "Record the details you will want when choosing yarn for a project.",
            locale,
          )}
        </p>
      </div>
      {#if isDirty}<span class="unsaved-pill" role="status"
          >{t("Unsaved changes", locale)}</span
        >{/if}
    </header>

    {#if serverError}<div class="notice-error" role="alert">
        {serverError}
      </div>{/if}
    {#if statusMessage}<p
        class="success-message"
        role="status"
        aria-live="polite"
      >
        {statusMessage}
      </p>{/if}

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
          <button
            class="quiet-button"
            type="button"
            disabled={saving}
            onclick={useLatestValues}>{t("Load latest values", locale)}</button
          >
          <button
            class="primary-action"
            type="button"
            disabled={saving}
            onclick={() => {
              const value = makeWrite();
              if (value && conflictYarn)
                void save(value, conflictYarn.updated_at);
            }}
            >{saving
              ? t("Saving…", locale)
              : t("Save my draft over latest", locale)}</button
          >
        </div>
      </section>
    {/if}

    <form class="yarn-form" novalidate onsubmit={(event) => void submit(event)}>
      <fieldset disabled={saving || mediaBusy}>
        <legend>{t("Yarn information", locale)}</legend>
        <div class="field-grid">
          <label class="field wide" for={fieldId("name")}>
            <span>{t("Yarn name", locale)} <b aria-hidden="true">*</b></span>
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
            <span>{t("Brand", locale)}</span>
            <input
              id={fieldId("brand")}
              value={draft.brand}
              autocomplete="organization"
              oninput={(event) => setDraft("brand", event.currentTarget.value)}
            />
          </label>
          <label class="field" for={fieldId("colorway")}>
            <span>{t("Colorway", locale)}</span>
            <input
              id={fieldId("colorway")}
              value={draft.colorway}
              autocomplete="off"
              oninput={(event) =>
                setDraft("colorway", event.currentTarget.value)}
            />
          </label>
          <label class="field" for={fieldId("dye_lot")}>
            <span>{t("Dye lot", locale)}</span>
            <input
              id={fieldId("dye_lot")}
              value={draft.dye_lot}
              autocomplete="off"
              oninput={(event) =>
                setDraft("dye_lot", event.currentTarget.value)}
            />
          </label>
          <label class="field" for={fieldId("fiber_content")}>
            <span>{t("Fiber content", locale)}</span>
            <input
              id={fieldId("fiber_content")}
              value={draft.fiber_content}
              placeholder={t("For example, 80% wool, 20% nylon", locale)}
              oninput={(event) =>
                setDraft("fiber_content", event.currentTarget.value)}
            />
          </label>
          <label class="field" for={fieldId("weight_category")}>
            <span>{t("Yarn weight", locale)}</span>
            <input
              id={fieldId("weight_category")}
              value={draft.weight_category}
              placeholder={t("For example, DK or fingering", locale)}
              oninput={(event) =>
                setDraft("weight_category", event.currentTarget.value)}
            />
          </label>
          <label class="field" for={fieldId("recommended_needles")}>
            <span>{t("Recommended needles", locale)}</span>
            <input
              id={fieldId("recommended_needles")}
              value={draft.recommended_needles}
              oninput={(event) =>
                setDraft("recommended_needles", event.currentTarget.value)}
            />
          </label>
          <label class="field" for={fieldId("weight_grams")}>
            <span>{t("Skein weight in grams", locale)}</span>
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
            <span>{t("Skein length in meters", locale)}</span>
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
            <span>{t("Description", locale)}</span>
            <textarea
              id={fieldId("description")}
              rows="4"
              maxlength="20000"
              value={draft.description}
              oninput={(event) =>
                setDraft("description", event.currentTarget.value)}
            ></textarea>
          </label>
          <label class="field wide" for={fieldId("notes")}>
            <span>{t("Notes", locale)}</span>
            <textarea
              id={fieldId("notes")}
              rows="4"
              maxlength="20000"
              value={draft.notes}
              oninput={(event) => setDraft("notes", event.currentTarget.value)}
            ></textarea>
          </label>
          <label class="field wide" for={fieldId("link")}>
            <span>{t("Yarn source URL", locale)}</span>
            <input
              id={fieldId("link")}
              type="url"
              inputmode="url"
              placeholder="https://"
              value={draft.link}
              aria-invalid={Boolean(fieldErrors.link)}
              aria-describedby={fieldErrors.link
                ? `${fieldId("link")}-error`
                : undefined}
              oninput={(event) => setDraft("link", event.currentTarget.value)}
            />
            {#if fieldErrors.link}<small
                id={`${fieldId("link")}-error`}
                class="field-error">{fieldErrors.link}</small
              >{/if}
          </label>
        </div>
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
          <p>
            {t(
              "Save this yarn first. You can add photos as soon as it has been created.",
              locale,
            )}
          </p>
        </section>
      {/if}

      {#if loadedYarn && !canManagePhotos && !mediaBusy && isDirty}
        <p class="media-hint">
          {t("Save your changes before editing photos.", locale)}
        </p>
      {/if}

      <div class="form-actions">
        <button
          class="primary-action"
          type="submit"
          disabled={saving || mediaBusy}
          ><ActionLabel icon={yarnId === null ? "create" : "save"}
            >{saveLabel}</ActionLabel
          ></button
        >
        <a
          class="quiet-button"
          href={yarnId === null
            ? resolve("/yarns")
            : `${resolve("/yarns")}/${yarnId}`}
          ><ActionLabel icon="cancel">{t("Cancel", locale)}</ActionLabel></a
        >
        {#if isDirty}<span class="draft-note"
            >{t("Your edits stay here if saving fails.", locale)}</span
          >{/if}
      </div>
    </form>
  </main>
{/if}

<style>
  .editor,
  .form-error {
    display: grid;
    max-width: 68rem;
    gap: 1.15rem;
    margin: 0 auto;
  }

  .back-link {
    display: inline-flex;
    min-height: 44px;
    width: fit-content;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 620;
    text-decoration: none;
  }

  .editor-heading {
    display: flex;
    align-items: end;
    justify-content: space-between;
    gap: 1rem;
  }

  .section-mark {
    margin: 0;
    color: var(--accent-strong);
    font-size: 0.85rem;
    font-weight: 650;
  }

  h1 {
    margin: 0.3rem 0 0.35rem;
    font-size: clamp(2rem, 5vw, 2.7rem);
    letter-spacing: -0.055em;
    line-height: 1.05;
  }

  .editor-heading p:last-child,
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
    border-radius: 0.95rem;
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
    min-height: 44px;
    border: 1px solid var(--control-border);
    border-radius: 0.65rem;
    padding: 0.6rem 0.7rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
    font-weight: 400;
  }

  .field textarea {
    min-height: 7rem;
    resize: vertical;
  }

  .field input[aria-invalid="true"] {
    border-color: var(--danger);
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

  .primary-action,
  .quiet-button {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    padding: 0.6rem 0.9rem;
    color: var(--accent-strong);
    background: var(--surface);
    font: inherit;
    font-weight: 630;
    text-decoration: none;
    cursor: pointer;
  }

  .primary-action {
    border-color: var(--accent-strong);
    color: var(--on-accent);
    background: var(--accent-strong);
  }

  .draft-note {
    flex: 1 1 100%;
    font-size: 0.88rem;
  }

  .notice-error,
  .success-message,
  .conflict-panel {
    border: 1px solid var(--line);
    border-radius: 0.85rem;
    padding: 0.9rem 1rem;
    background: var(--surface);
  }

  .notice-error {
    color: var(--danger);
  }

  .success-message {
    margin: 0;
    color: var(--accent-strong);
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

  .form-error h1 {
    font-size: clamp(1.5rem, 4vw, 2rem);
  }

  @media (max-width: 640px) {
    .editor-heading {
      align-items: start;
      flex-direction: column;
    }

    .field-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .wide {
      grid-column: auto;
    }

    .form-actions,
    .conflict-actions {
      display: grid;
      grid-template-columns: 1fr 1fr;
    }

    .form-actions > button,
    .form-actions > a,
    .conflict-actions > button {
      width: 100%;
    }

    .draft-note {
      grid-column: 1 / -1;
    }
  }
</style>
