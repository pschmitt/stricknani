<script lang="ts">
  import { ApiError } from "#lib/api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { recognizeImageText, type OcrLanguage } from "./media-tools-api";

  const idPrefix = $props.id();
  const languageSelectId = `${idPrefix}-language`;

  let {
    src,
    label,
    ownerId,
    locale,
    enabled,
  }: {
    src: string;
    label: string;
    ownerId: number | null;
    locale: "en" | "de";
    enabled: boolean;
  } = $props();

  let language = $state<OcrLanguage>("deu+eng");
  let busy = $state(false);
  let text = $state<string | null>(null);
  let errorMessage = $state("");
  let copied = $state(false);
  let requestGeneration = 0;
  let copyGeneration = 0;
  let controller: AbortController | null = null;

  $effect(() => {
    const imageSrc = src;
    const currentOwner = ownerId;
    const canRecognize = enabled;
    const generation = ++requestGeneration;
    copyGeneration += 1;
    controller?.abort();
    controller = null;
    busy = false;
    text = null;
    errorMessage = "";
    copied = false;
    if (!canRecognize || currentOwner === null || imageSrc.length === 0) return;
    return () => {
      if (generation === requestGeneration) requestGeneration += 1;
      controller?.abort();
      controller = null;
    };
  });

  async function recognize() {
    const currentSession = session.data;
    if (
      !enabled ||
      ownerId === null ||
      currentSession?.user?.id !== ownerId ||
      busy
    )
      return;

    const generation = ++requestGeneration;
    copyGeneration += 1;
    const requestedOwner = ownerId;
    const requestedSrc = src;
    const requestedLanguage = language;
    controller?.abort();
    const nextController = new AbortController();
    controller = nextController;
    busy = true;
    text = null;
    errorMessage = "";
    copied = false;
    try {
      const result = await recognizeImageText(
        requestedSrc,
        requestedLanguage,
        currentSession,
        nextController.signal,
      );
      if (!isCurrent(generation, nextController, requestedOwner, requestedSrc))
        return;
      text = result.text;
    } catch (error) {
      if (!isCurrent(generation, nextController, requestedOwner, requestedSrc))
        return;
      errorMessage = errorText(error);
    } finally {
      if (generation === requestGeneration) {
        busy = false;
        controller = null;
      }
    }
  }

  async function copyText() {
    const copiedText = text;
    const requestedSrc = src;
    const requestedOwner = ownerId;
    if (
      copiedText === null ||
      copiedText.length === 0 ||
      requestedOwner === null
    )
      return;
    const generation = ++copyGeneration;
    copied = false;
    try {
      await navigator.clipboard.writeText(copiedText);
      if (!isCurrentCopy(generation, requestedSrc, requestedOwner, copiedText))
        return;
      copied = true;
    } catch {
      if (!isCurrentCopy(generation, requestedSrc, requestedOwner, copiedText))
        return;
      errorMessage = t(
        "Clipboard access is unavailable. Select and copy the text manually.",
        locale,
      );
    }
  }

  function isCurrentCopy(
    generation: number,
    requestedSrc: string,
    requestedOwner: number,
    requestedText: string,
  ): boolean {
    return (
      generation === copyGeneration &&
      src === requestedSrc &&
      ownerId === requestedOwner &&
      text === requestedText &&
      session.data?.user?.id === requestedOwner
    );
  }

  function isCurrent(
    generation: number,
    requestController: AbortController,
    requestedOwner: number,
    requestedSrc: string,
  ): boolean {
    return (
      generation === requestGeneration &&
      !requestController.signal.aborted &&
      enabled &&
      ownerId === requestedOwner &&
      src === requestedSrc &&
      session.data?.user?.id === requestedOwner
    );
  }

  function errorText(error: unknown): string {
    if (error instanceof ApiError) {
      if (error.code === "rate_limited")
        return t(
          "Too many text recognition requests. Wait a moment and try again.",
          locale,
        );
      if (error.code === "invalid_language")
        return t(
          "This language is not available for recognition. Try another language.",
          locale,
        );
      if (error.code === "network_error")
        return t(
          "Text recognition could not connect. Check your connection and try again.",
          locale,
        );
      return error.message;
    }
    return t(
      "Text recognition could not connect. Check your connection and try again.",
      locale,
    );
  }
</script>

{#if enabled && ownerId !== null}
  <details class="ocr-control">
    <summary>{t("Recognize text from this photo", locale)}</summary>
    <div class="ocr-panel" aria-busy={busy}>
      <label for={languageSelectId}>{t("Recognition language", locale)}</label>
      <select id={languageSelectId} bind:value={language} disabled={busy}>
        <option value="deu+eng">{t("German and English", locale)}</option>
        <option value="deu">{t("German", locale)}</option>
        <option value="eng">{t("English", locale)}</option>
      </select>
      <p class="language-help">
        {t(
          "If recognition fails, try one language at a time. This server may not have both language packs.",
          locale,
        )}
      </p>
      <button type="button" disabled={busy} onclick={() => void recognize()}>
        {busy ? t("Recognizing…", locale) : t("Recognize text", locale)}
      </button>
      {#if errorMessage}<p class="ocr-error" role="alert">
          {errorMessage}
        </p>{/if}
      {#if text !== null}
        <section class="ocr-result" aria-label={label} aria-live="polite">
          <h4>{t("Recognized text", locale)}</h4>
          {#if text.trim().length === 0}
            <p>{t("No text was found in this photo.", locale)}</p>
          {:else}
            <textarea readonly rows="6" value={text} aria-label={label}
            ></textarea>
            <button type="button" onclick={() => void copyText()}>
              {copied
                ? t("Text copied", locale)
                : t("Copy recognized text", locale)}
            </button>
          {/if}
        </section>
      {/if}
    </div>
  </details>
{/if}

<style>
  .ocr-control {
    min-width: 0;
    margin-top: 0.5rem;
    color: var(--ink);
    font-size: 0.86rem;
  }

  .ocr-control summary {
    min-height: 44px;
    display: flex;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 650;
    cursor: pointer;
  }

  .ocr-panel {
    display: grid;
    min-width: 0;
    gap: 0.65rem;
    padding: 0.6rem 0 0.25rem;
  }

  .ocr-panel label {
    display: grid;
    gap: 0.35rem;
    font-weight: 600;
  }

  select,
  button,
  textarea {
    box-sizing: border-box;
    max-width: 100%;
    min-height: 44px;
    border: 1px solid var(--line);
    border-radius: 0.55rem;
    padding: 0.55rem 0.7rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
  }

  button {
    width: fit-content;
    max-width: 100%;
    color: var(--accent-strong);
    font-weight: 650;
    cursor: pointer;
  }

  button:disabled,
  select:disabled {
    cursor: wait;
    opacity: 0.7;
  }

  textarea {
    width: 100%;
    resize: vertical;
    white-space: pre-wrap;
  }

  .language-help,
  .ocr-result p {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.8rem;
  }

  .ocr-result {
    display: grid;
    gap: 0.5rem;
  }

  .ocr-result h4 {
    margin: 0;
    font-size: 0.92rem;
  }

  .ocr-error {
    margin: 0;
    color: var(--danger-strong);
  }

  @media print {
    .ocr-control {
      display: none !important;
    }
  }
</style>
