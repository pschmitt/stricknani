<script lang="ts">
  import { onDestroy, onMount, untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import ImportProgress from "./ImportProgress.svelte";
  import { importAvailability } from "./import-availability.ts";
  import {
    previewImportFile,
    type ImportPreview,
    type ImportTarget,
  } from "./import-api";
  import { isHttpUrl } from "./import-draft";
  import { extractSharedUrl } from "./shared-link";
  import {
    IMPORT_DRAFT_POLL_MS,
    createImportDraft,
    deleteImportDraft,
    draftFailureMessage,
    getImportDraft,
    importStartMessage,
  } from "./import-drafts-api";

  /**
   * "Have a pattern? Paste a link, upload a file or paste text": the one way
   * to bring something in. It sits at the top of the new-project and new-yarn
   * editors. A link or text becomes a server job (a draft) that keeps running
   * when the user leaves; a file is read at once. The finished preview goes to
   * the editor, which fills its own fields from it.
   */
  let {
    target,
    initialUrl = "",
    autoStart = false,
    resumeDraftId = null,
    replaceNeedsConfirm = false,
    disabled = false,
    busy = $bindable(false),
    onReady,
  }: {
    target: ImportTarget;
    /** A link to prefill (a shared link or a link typed into the editor). */
    initialUrl?: string;
    /** Start the import with `initialUrl` as soon as the session is there. */
    autoStart?: boolean;
    /** A draft to pick up: shown while it runs, handed over when ready. */
    resumeDraftId?: number | null;
    /** The editor holds edits that a new import would replace. */
    replaceNeedsConfirm?: boolean;
    disabled?: boolean;
    /** True while an import is being started or is running. */
    busy?: boolean;
    onReady: (result: {
      preview: ImportPreview;
      draftId: number | null;
    }) => void;
  } = $props();

  type Phase = "idle" | "running" | "failed" | "done";

  const locale = $derived(session.data?.locale ?? "en");
  const availability = $derived(importAvailability(session.data?.capabilities));
  const allowExternal = $derived(availability.url);
  const allowAi = $derived(availability.ai);
  const allowPreview = $derived(
    session.data?.capabilities.import_preview === true,
  );
  const forYarn = $derived(target === "yarn");

  let sourceKind = $state<"url" | "text" | "file">("url");
  let sourceUrl = $state("");
  const uid = $props.id();
  const urlInputId = `import-url-${uid}`;
  /** The clipboard API needs a secure context; without it the button stays hidden. */
  const canPaste =
    typeof navigator !== "undefined" &&
    typeof navigator.clipboard?.readText === "function";
  let pasteRefused = $state(false);

  /** Fills the link from the clipboard: the first web link in it, as a share would. */
  async function pasteLink() {
    pasteRefused = false;
    try {
      const text = await navigator.clipboard.readText();
      const link = extractSharedUrl(text) ?? text.trim();
      if (link) {
        sourceUrl = link;
        document.getElementById(urlInputId)?.focus();
      }
    } catch {
      pasteRefused = true;
    }
  }
  let sourceText = $state("");
  let selectedFile = $state<File | null>(null);
  let useAi = $state(true);
  let phase = $state<Phase>("idle");
  let message = $state("");
  let runningKind = $state<"url" | "text" | "file">("url");
  let runningAi = $state(false);
  let runningSince = $state<number | null>(null);
  let runningDraftId = $state<number | null>(null);
  let pendingAutoStart = $state(false);
  let generation = 0;
  let controller: AbortController | null = null;
  let observedUserId = session.data?.user?.id ?? null;

  const locked = $derived(disabled || phase === "running");
  const canStart = $derived(allowPreview && !locked);

  onMount(() => {
    if (initialUrl) {
      sourceKind = "url";
      sourceUrl = initialUrl;
      pendingAutoStart = autoStart;
    }
  });

  // A draft from `?draft=` (a notification, a card): pick it up once.
  let resumed: number | null = null;
  $effect(() => {
    const id = resumeDraftId;
    if (id === null || resumed === id || !session.data?.user) return;
    resumed = id;
    untrack(() => void track(id, null));
  });

  $effect(() => {
    if (!pendingAutoStart || !session.data?.user) return;
    pendingAutoStart = false;
    untrack(() => void start());
  });

  // Link import is the default; fall back to text without it.
  $effect(() => {
    if (session.data && !allowExternal && !forYarn && sourceKind === "url")
      sourceKind = "text";
  });

  $effect(() => {
    const currentUserId = session.data?.user?.id ?? null;
    if (currentUserId === observedUserId) return;
    observedUserId = currentUserId;
    untrack(reset);
    sourceUrl = "";
    sourceText = "";
    selectedFile = null;
    useAi = true;
  });

  $effect(() => {
    busy = phase === "running";
  });

  onDestroy(() => {
    generation += 1;
    controller?.abort();
  });

  function reset(): void {
    generation += 1;
    controller?.abort();
    controller = null;
    phase = "idle";
    message = "";
    runningDraftId = null;
    runningSince = null;
  }

  /** Start with a link (the editor's "Import this pattern?" prompt). */
  export function startWithUrl(url: string): void {
    sourceKind = "url";
    sourceUrl = url;
    void start();
  }

  function isAbort(error: unknown): boolean {
    return error instanceof DOMException && error.name === "AbortError";
  }

  function sleep(ms: number, signal: AbortSignal): Promise<void> {
    return new Promise((done) => {
      const timer = setTimeout(done, ms);
      signal.addEventListener(
        "abort",
        () => {
          clearTimeout(timer);
          done();
        },
        { once: true },
      );
    });
  }

  async function start(): Promise<void> {
    const current = session.data;
    if (!current?.user || !canStart) return;
    message = "";
    const kind: "url" | "text" | "file" = forYarn ? "url" : sourceKind;
    if (kind === "url" && !allowExternal) {
      message = t("Link imports are turned off on this server.", locale);
      return;
    }
    if (kind === "url" && !isHttpUrl(sourceUrl.trim())) {
      message = t("Enter a valid HTTP or HTTPS URL.", locale);
      return;
    }
    if (kind === "text" && !sourceText.trim()) {
      message = t("Paste text to preview.", locale);
      return;
    }
    if (kind === "file" && !selectedFile) {
      message = t("Choose a document to preview.", locale);
      return;
    }
    if (
      replaceNeedsConfirm &&
      !window.confirm(
        t("Replace this import review? Your edits will be lost.", locale),
      )
    )
      return;
    const ai = useAi && allowAi;
    const active = ++generation;
    controller?.abort();
    const ctl = new AbortController();
    controller = ctl;
    phase = "running";
    runningKind = kind;
    runningAi = ai;
    runningSince = Date.now();
    runningDraftId = null;
    try {
      if (kind === "file") {
        const preview = await previewImportFile(
          selectedFile!,
          ai,
          current,
          ctl.signal,
        );
        if (active !== generation) return;
        phase = "done";
        onReady({ preview, draftId: null });
        return;
      }
      const draft = await createImportDraft(
        kind === "url"
          ? { type: "url", url: sourceUrl.trim(), use_ai: ai, target }
          : { type: "text", text: sourceText, use_ai: ai },
        locale,
        current,
        ctl.signal,
      );
      if (active !== generation) return;
      runningDraftId = draft.id;
      await follow(draft.id, active, ctl);
    } catch (error) {
      if (active !== generation || isAbort(error)) return;
      phase = "failed";
      message = importStartMessage(error, locale);
    }
  }

  /** Follow a draft that is already running (or finished) on the server. */
  async function track(id: number, since: number | null): Promise<void> {
    const active = ++generation;
    controller?.abort();
    const ctl = new AbortController();
    controller = ctl;
    phase = "running";
    runningDraftId = id;
    runningSince = since;
    await follow(id, active, ctl);
  }

  async function follow(
    id: number,
    active: number,
    ctl: AbortController,
  ): Promise<void> {
    let failures = 0;
    while (active === generation) {
      try {
        const draft = await getImportDraft(id, ctl.signal);
        if (active !== generation) return;
        failures = 0;
        if (draft.created_at) runningSince = draft.created_at * 1000;
        runningKind = draft.source_kind;
        runningAi = draft.use_ai;
        if (draft.status === "running") {
          await sleep(IMPORT_DRAFT_POLL_MS, ctl.signal);
          continue;
        }
        if (draft.status === "ready" && draft.preview) {
          phase = "done";
          onReady({ preview: draft.preview, draftId: draft.id });
          return;
        }
        phase = "failed";
        message = draftFailureMessage(draft, locale);
        return;
      } catch (error) {
        if (active !== generation || isAbort(error)) return;
        if (error instanceof ApiError && error.status === 404) {
          phase = "failed";
          runningDraftId = null;
          message = t("This import is gone. It may have been removed.", locale);
          return;
        }
        failures += 1;
        if (failures >= 5) {
          phase = "failed";
          message = importStartMessage(error, locale);
          return;
        }
        await sleep(IMPORT_DRAFT_POLL_MS, ctl.signal);
      }
    }
  }

  /** Throw a failed import away and try again. */
  async function dismissFailed(): Promise<void> {
    const id = runningDraftId;
    const current = session.data;
    reset();
    if (id !== null && current?.user) {
      try {
        await deleteImportDraft(id, current);
      } catch {
        // The draft expires on its own.
      }
    }
  }

  function again(): void {
    if (
      replaceNeedsConfirm &&
      !window.confirm(
        t("Replace this import review? Your edits will be lost.", locale),
      )
    )
      return;
    reset();
  }

  function fileChanged(event: Event): void {
    const input = event.currentTarget as HTMLInputElement;
    if (!allowPreview) {
      input.value = "";
      return;
    }
    selectedFile = input.files?.[0] ?? null;
  }
</script>

{#if allowPreview}
  <section
    class="import-card"
    aria-labelledby="import-card-title"
    data-testid="import-card"
  >
    <div class="import-card__head">
      <h2 id="import-card-title">
        {#if forYarn}
          {t("Have a link to the yarn? Paste it here", locale)}
        {:else}
          {t(
            "Have a pattern? Paste a link, upload a file or paste text",
            locale,
          )}
        {/if}
      </h2>
    </div>

    {#if phase === "done"}
      <p class="import-card__done" role="status">
        {forYarn
          ? t("The yarn was read. Check the fields below.", locale)
          : t("The pattern was read. Check the fields below.", locale)}
      </p>
      <Button
        variant="secondary"
        size="sm"
        icon="reset"
        type="button"
        {disabled}
        onclick={again}
      >
        {t("Import something else", locale)}
      </Button>
    {:else if phase === "running"}
      {#if runningSince !== null}
        <ImportProgress
          kind={runningKind}
          ai={runningAi}
          {locale}
          since={runningSince}
        />
      {/if}
      {#if runningDraftId !== null}
        <p class="supporting">
          {t(
            "You can leave this page. The import waits for you under Waiting for review.",
            locale,
          )}
        </p>
      {/if}
    {:else}
      {#if !forYarn}
        <div
          class="source-tabs"
          role="group"
          aria-label={t("Import source", locale)}
        >
          <Button
            variant={sourceKind === "url" ? "primary" : "secondary"}
            size="sm"
            icon="link"
            type="button"
            aria-pressed={sourceKind === "url"}
            disabled={!allowExternal || locked}
            title={!allowExternal
              ? t("Link imports are turned off on this server.", locale)
              : undefined}
            onclick={() => (sourceKind = "url")}
          >
            {t("From a link", locale)}
          </Button>
          <Button
            variant={sourceKind === "file" ? "primary" : "secondary"}
            size="sm"
            icon="upload"
            type="button"
            aria-pressed={sourceKind === "file"}
            disabled={locked}
            onclick={() => (sourceKind = "file")}
          >
            {t("Upload file", locale)}
          </Button>
          <Button
            variant={sourceKind === "text" ? "primary" : "secondary"}
            size="sm"
            icon="clipboard"
            type="button"
            aria-pressed={sourceKind === "text"}
            disabled={locked}
            onclick={() => (sourceKind = "text")}
          >
            {t("Paste text", locale)}
          </Button>
        </div>
      {/if}
      {#if !allowExternal}<p class="supporting">
          {t("Link imports are turned off on this server.", locale)}
        </p>{/if}

      {#if forYarn || sourceKind === "url"}
        <div class="field">
          <label for={urlInputId}
            >{forYarn
              ? t("Yarn page URL", locale)
              : t("Pattern page URL", locale)}</label
          >
          <div class="url-row">
            <input
              id={urlInputId}
              type="url"
              bind:value={sourceUrl}
              disabled={!allowExternal || locked}
              placeholder={forYarn
                ? "https://shop.example/yarn"
                : "https://example.com/pattern"}
              autocomplete="url"
              onkeydown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  void start();
                }
              }}
            />
            {#if canPaste}
              <Button
                type="button"
                variant="secondary"
                icon="clipboard"
                disabled={!allowExternal || locked}
                onclick={pasteLink}
              >
                {t("Paste", locale)}
              </Button>
            {/if}
          </div>
          {#if pasteRefused}<small class="supporting"
              >{t(
                "Your browser did not allow reading the clipboard. Paste with Ctrl+V or a long press.",
                locale,
              )}</small
            >{/if}
        </div>
      {:else if sourceKind === "text"}
        <label class="field"
          ><span>{t("Pattern text or instructions", locale)}</span><textarea
            bind:value={sourceText}
            disabled={locked}
            rows="5"
            maxlength="5242880"
            placeholder={t(
              "Paste pattern instructions, notes, or a recipe…",
              locale,
            )}></textarea><small
            >{t("Text and files are limited to 5 MiB.", locale)}</small
          ></label
        >
      {:else}
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
            disabled={locked}
            accept=".html,.htm,.txt,.md,.markdown,.pdf,image/*"
            onchange={fileChanged}
          /></label
        >
      {/if}

      <div class="import-card__actions">
        <label class="ai-toggle" class:ai-toggle--off={!allowAi}
          ><input
            type="checkbox"
            checked={allowAi && useAi}
            onchange={(event) => (useAi = event.currentTarget.checked)}
            disabled={locked || !allowAi}
          /><span class="ai-toggle__text"
            >{t("Ask AI to improve extraction", locale)}{#if !allowAi}<small
                class="ai-toggle__reason"
                >{t("AI import is turned off on this server.", locale)}</small
              >{/if}</span
          ></label
        >
        <Button
          variant="primary"
          icon="preview"
          type="button"
          disabled={!canStart}
          onclick={() => void start()}
        >
          {t("Start import", locale)}
        </Button>
      </div>
      {#if message}
        <p class="feedback error" role="alert">
          {message}
        </p>
      {/if}
      {#if phase === "failed"}
        <Button
          variant="ghost"
          size="sm"
          icon="close"
          type="button"
          onclick={() => void dismissFailed()}
        >
          {t("Dismiss", locale)}
        </Button>
      {/if}
    {/if}
  </section>
{/if}

<style>
  .import-card {
    display: grid;
    gap: 0.75rem;
    padding: clamp(0.85rem, 3.5vw, 1.25rem);
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    background: var(--accent-wash, var(--surface));
  }

  h2 {
    margin: 0;
    font-size: 1.05rem;
    letter-spacing: -0.01em;
  }

  .supporting {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .import-card__done {
    margin: 0;
    font-weight: 600;
  }

  .source-tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .source-tabs :global(.ui-button) {
    flex: 1 1 7rem;
  }

  .field {
    display: grid;
    gap: 0.35rem;
    font-weight: 620;
  }

  .url-row {
    display: flex;
    gap: 0.5rem;
    align-items: stretch;
  }

  .url-row input {
    flex: 1;
  }

  .url-row :global(.ui-button) {
    flex: none;
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
    min-height: 6rem;
    justify-items: center;
    align-content: center;
    gap: 0.3rem;
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

  .import-card__actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem;
  }

  .ai-toggle {
    display: flex;
    min-height: 2.75rem;
    align-items: center;
    gap: 0.55rem;
    padding: 0.4rem 0.7rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    background: var(--surface);
    font-size: 0.9rem;
  }

  .ai-toggle--off {
    opacity: 0.7;
  }

  .ai-toggle__text {
    display: grid;
    gap: 0.1rem;
  }

  .ai-toggle__reason {
    color: var(--ink-muted);
    font-size: 0.8rem;
  }

  input[type="checkbox"] {
    width: 1.2rem;
    height: 1.2rem;
    accent-color: var(--accent);
  }

  .feedback {
    margin: 0;
    padding: 0.7rem 0.9rem;
    border-radius: var(--radius-xs);
    background: var(--surface-raised);
  }

  .error {
    border-left: 3px solid var(--danger);
    color: var(--danger);
  }

  @media (max-width: 40rem) {
    .import-card__actions {
      flex-direction: column;
      align-items: stretch;
    }

    .import-card__actions :global(.ui-button) {
      width: 100%;
    }
  }
</style>
