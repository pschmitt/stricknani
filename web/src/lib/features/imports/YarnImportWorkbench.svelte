<script lang="ts">
  import { beforeNavigate } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { onDestroy, onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { createYarn } from "#lib/features/yarns/yarn-api.ts";
  import ImageChoices from "./ImageChoices.svelte";
  import { previewImportUrl, type ImportPreview } from "./import-api";
  import {
    createYarnImportWrite,
    fieldHint,
    importWarningMessage,
    isHttpUrl,
    yarnImportEditor,
    type YarnImportEditor,
  } from "./import-draft";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { session } from "#lib/session.svelte.ts";
  import { msg, t } from "#lib/i18n.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  let sourceUrl = $state("");
  let useAi = $state(true);
  let preview = $state<ImportPreview | null>(null);
  let draft = $state<YarnImportEditor | null>(null);
  let busy = $state(false);
  let saving = $state(false);
  let errorMessage = $state("");
  let createdYarnId = $state<number | null>(null);
  let controller: AbortController | null = null;

  const allowExternal = $derived(
    session.data?.capabilities.external_imports === true,
  );
  const allowAi = $derived(session.data?.capabilities.ai === true);
  const allowPreview = $derived(
    session.data?.capabilities.import_preview === true,
  );
  const hasDirtyDraft = $derived(Boolean(draft && !createdYarnId));
  const provenance = $derived(preview?.draft.provenance ?? {});
  const warnings = $derived(
    (preview?.draft.warnings ?? [])
      .map((code) => importWarningMessage(code, locale))
      .filter((message): message is string => message !== null),
  );
  const hintLabels = {
    ai: msg("AI"),
    guessed: msg("Guessed"),
  } as const;

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: hasDirtyDraft || sourceUrl.trim().length > 0,
      busy: busy || saving,
    })),
  );

  beforeNavigate(({ cancel }) => {
    if (!hasDirtyDraft || typeof window === "undefined") return;
    if (
      !window.confirm(
        t("Leave this import review? Your changes will be lost.", locale),
      )
    )
      cancel();
  });

  onDestroy(() => controller?.abort());

  function formatError(error: unknown): string {
    if (error instanceof ApiError) {
      if (error.status === 429)
        return t("Too many requests. Wait a moment, then try again.", locale);
      return error.message;
    }
    return error instanceof Error
      ? error.message
      : t("Import failed. Your source and edits are still here.", locale);
  }

  async function runPreview() {
    const current = session.data;
    if (!current?.user || busy || saving) return;
    errorMessage = "";
    if (!isHttpUrl(sourceUrl.trim())) {
      errorMessage = t("Enter a valid HTTP or HTTPS URL.", locale);
      return;
    }
    if (
      draft &&
      !createdYarnId &&
      !window.confirm(
        t("Replace this import review? Your edits will be lost.", locale),
      )
    )
      return;
    controller?.abort();
    const active = new AbortController();
    controller = active;
    busy = true;
    try {
      const result = await previewImportUrl(
        sourceUrl.trim(),
        useAi && allowAi,
        current,
        active.signal,
        "yarn",
      );
      if (controller !== active) return;
      preview = result;
      draft = yarnImportEditor(result, sourceUrl.trim());
      createdYarnId = null;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") return;
      errorMessage = formatError(error);
    } finally {
      if (controller === active) {
        busy = false;
        controller = null;
      }
    }
  }

  async function saveYarn() {
    const current = session.data;
    if (!draft || !preview || !current?.user || busy || saving) return;
    if (!draft.name.trim()) {
      errorMessage = t("Add a name before creating this yarn.", locale);
      return;
    }
    saving = true;
    errorMessage = "";
    try {
      const saved = await createYarn(
        createYarnImportWrite(draft, preview.is_ai_enhanced, allowExternal),
        current,
      );
      createdYarnId = saved.id;
    } catch (error) {
      errorMessage = formatError(error);
    } finally {
      saving = false;
    }
  }
</script>

<svelte:head
  ><title>Stricknani · {t("Import a yarn", locale)}</title></svelte:head
>

{#snippet hint(key: string)}
  {@const kind = fieldHint(provenance, key)}
  {#if kind}<small
      class="hint hint-{kind}"
      title={t("Check this value", locale)}>{t(hintLabels[kind], locale)}</small
    >{/if}
{/snippet}

<section
  class="imports-page page-container page-container--standard"
  aria-labelledby="yarn-import-title"
>
  <div class="page-heading">
    <PageHeader
      title={t("Import a yarn", locale)}
      titleId="yarn-import-title"
    />
  </div>

  <section class="source-card" aria-labelledby="yarn-source-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">{t("Step 1", locale)}</p>
        <h2 id="yarn-source-title">{t("Paste a link to the yarn", locale)}</h2>
      </div>
      {#if allowAi && allowPreview}
        <label class="ai-toggle"
          ><input type="checkbox" bind:checked={useAi} disabled={busy} /><span
            >{t("Ask AI to improve extraction", locale)}</span
          ></label
        >
      {/if}
    </div>
    <label class="field"
      ><span>{t("Yarn page URL", locale)}</span><input
        type="url"
        bind:value={sourceUrl}
        disabled={!allowExternal || busy}
        placeholder="https://shop.example/yarn"
        autocomplete="url"
        onkeydown={(event) => event.key === "Enter" && runPreview()}
      /></label
    >
    <p class="supporting">
      {t(
        "The server reads the page securely: shop data, Ravelry and common yarn fields are filled in for you to review.",
        locale,
      )}
    </p>
    {#if !allowExternal}<p class="supporting">
        {t("URL imports are disabled for this account.", locale)}
      </p>{/if}
    <Button
      class="primary"
      variant="primary"
      icon="preview"
      disabled={busy || saving || !allowExternal || !allowPreview}
      onclick={runPreview}
    >
      {busy ? t("Preparing preview…", locale) : t("Preview and review", locale)}
    </Button>
    {#if busy}<p class="status" role="status" aria-live="polite">
        {t("Reading the source and preparing a draft…", locale)}
      </p>{/if}
  </section>

  {#if errorMessage}<p class="feedback error" role="alert">
      {errorMessage}
    </p>{/if}

  {#if preview && draft}
    <section class="review-card" aria-labelledby="yarn-review-title">
      <div class="section-heading">
        <div>
          <p class="eyebrow">{t("Step 2", locale)}</p>
          <h2 id="yarn-review-title">{t("Review and correct", locale)}</h2>
        </div>
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
      {#each warnings as warning (warning)}<p class="notice" role="status">
          {warning}
          {#if warning === importWarningMessage("looks_like_pattern_page", locale)}
            <a href={resolve("/imports")}>{t("Import a project", locale)}</a>
          {/if}
        </p>{/each}
      {#if preview.ai_fallback}<p class="notice" role="status">
          {t(
            "AI enhancement was unavailable, so this preview uses the original extraction.",
            locale,
          )}
        </p>{/if}
      <fieldset
        class="review-fields"
        disabled={saving || createdYarnId !== null}
      >
        <div class="form-grid">
          <label class="field wide"
            ><span
              >{t("Yarn name", locale)} <b aria-hidden="true">*</b>
              {@render hint("yarn.name")}</span
            ><input
              bind:value={draft.name}
              required
              autocomplete="off"
            /></label
          >
          <label class="field"
            ><span>{t("Brand", locale)} {@render hint("yarn.brand")}</span
            ><input bind:value={draft.brand} /></label
          >
          <label class="field"
            ><span>{t("Colorway", locale)} {@render hint("yarn.colorway")}</span
            ><input bind:value={draft.colorway} /></label
          >
          <label class="field"
            ><span
              >{t("Fiber content", locale)}
              {@render hint("yarn.fiberContent")}</span
            ><input bind:value={draft.fiberContent} /></label
          >
          <label class="field"
            ><span
              >{t("Weight category", locale)}
              {@render hint("yarn.weightCategory")}</span
            ><input bind:value={draft.weightCategory} /></label
          >
          <label class="field"
            ><span
              >{t("Weight in grams", locale)}
              {@render hint("yarn.weightGrams")}</span
            ><input
              type="number"
              min="0"
              step="1"
              bind:value={draft.weightGrams}
            /></label
          >
          <label class="field"
            ><span
              >{t("Length in meters", locale)}
              {@render hint("yarn.lengthMeters")}</span
            ><input
              type="number"
              min="0"
              step="1"
              bind:value={draft.lengthMeters}
            /></label
          >
          <label class="field"
            ><span
              >{t("Recommended needles", locale)}
              {@render hint("yarn.recommendedNeedles")}</span
            ><input bind:value={draft.recommendedNeedles} /></label
          >
          <label class="field"
            ><span>{t("Link", locale)}</span><input
              bind:value={draft.link}
              inputmode="url"
            /></label
          >
          <label class="field wide"
            ><span
              >{t("Description", locale)}
              {@render hint("yarn.description")}</span
            ><textarea bind:value={draft.description} rows="3"
            ></textarea></label
          >
          <label class="field wide"
            ><span>{t("Notes", locale)}</span><textarea
              bind:value={draft.notes}
              rows="3"
            ></textarea></label
          >
        </div>
        {#if draft.images.length}<ImageChoices
            title={t("Photos to add", locale)}
            images={draft.images}
            disabled={!allowExternal || saving}
            {locale}
          />{/if}
      </fieldset>
      <div class="review-actions">
        {#if createdYarnId}
          <a
            class="ui-button ui-button--primary primary link-button"
            href={resolve("/yarns/[id]", { id: String(createdYarnId) })}
            ><ActionLabel icon="next"
              >{t("Open created yarn", locale)}</ActionLabel
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
              createdYarnId = null;
              sourceUrl = "";
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
            disabled={saving || busy}
            onclick={saveYarn}
          >
            {saving ? t("Saving yarn…", locale) : t("Create yarn", locale)}
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
  p {
    margin-top: 0;
  }
  h2 {
    margin-bottom: 0;
    font-size: 1.25rem;
    letter-spacing: -0.02em;
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
  .review-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.65rem;
    margin-top: 1.3rem;
    padding-top: 1rem;
    border-top: 1px solid var(--line);
  }
  @media (max-width: 40rem) {
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
