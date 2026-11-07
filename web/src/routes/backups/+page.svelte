<script lang="ts">
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { onDestroy, onMount } from "svelte";
  import { resolve } from "$app/paths";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";
  import {
    archiveTypes,
    exportUrl,
    readArchiveType,
    RestoreError,
    restoreAccept,
    uploadRestore,
    type RestoreErrorKind,
    type RestoreResult,
    type RestoreUpload,
    writeArchiveType,
    type ArchiveType,
  } from "#lib/backups.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  let archive = $state<ArchiveType>("tar.gz");
  const href = $derived(exportUrl(archive));
  const archiveOptions = archiveTypes.map((type) => ({
    value: type,
    label: `.${type}`,
  }));

  let fileInput = $state<HTMLInputElement>();
  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let pending = $state<File | null>(null);
  let busy = $state(false);
  let percent = $state(0);
  let processing = $state(false);
  let result = $state<RestoreResult | null>(null);
  let failure = $state<RestoreErrorKind | null>(null);
  let upload: RestoreUpload | undefined;
  let destroyed = false;

  onMount(() => {
    archive = readArchiveType();
  });

  onDestroy(() => {
    destroyed = true;
    upload?.abort();
  });

  function chooseFile(event: Event & { currentTarget: HTMLInputElement }) {
    const file = event.currentTarget.files?.[0] ?? null;
    event.currentTarget.value = "";
    if (!file || busy) return;
    pending = file;
    failure = null;
    result = null;
    dialog?.showModal();
  }

  function cancelRestore() {
    if (busy) return;
    dialog?.close();
  }

  function clearPending() {
    if (!busy) pending = null;
  }

  async function restore() {
    const file = pending;
    if (!file || busy) return;
    busy = true;
    percent = 0;
    processing = false;
    failure = null;
    result = null;
    dialog?.close();
    const current = uploadRestore(
      file,
      session.data?.csrf_token ?? "",
      (value, done) => {
        if (upload !== current || destroyed) return;
        percent = value;
        processing = done;
      },
    );
    upload = current;
    try {
      const outcome = await current.promise;
      if (upload !== current || destroyed) return;
      result = outcome;
    } catch (error) {
      if (upload !== current || destroyed) return;
      failure = error instanceof RestoreError ? error.kind : "failed";
    } finally {
      if (upload === current) {
        upload = undefined;
        busy = false;
        processing = false;
        pending = null;
      }
    }
  }

  function chooseArchive(type: ArchiveType) {
    archive = type;
    writeArchiveType(type);
  }
</script>

<svelte:head><title>{t("Backups", locale)} · Stricknani</title></svelte:head>

<section
  class="backups page-container page-container--compact"
  aria-labelledby="backups-title"
>
  <PageHeader title={t("Backups", locale)} titleId="backups-title" />

  <section class="card" aria-labelledby="export-title">
    <header class="card__header">
      <span class="card__icon" aria-hidden="true"
        ><NavIcon name="download" /></span
      >
      <h2 id="export-title">{t("Your data", locale)}</h2>
    </header>
    <p class="card__intro">
      {t(
        "Projects, yarns, categories, photos and attachments in one archive, including items shared with you.",
        locale,
      )}
    </p>
    <SegmentedControl
      options={archiveOptions}
      value={archive}
      label={t("Archive type", locale)}
      onchange={chooseArchive}
    />
    <div class="download-row">
      <a class="ui-button ui-button--primary ui-button--md" {href} download>
        <ActionLabel icon="download">{t("Download", locale)}</ActionLabel>
      </a>
      <span class="format">{t("Format version 1", locale)}</span>
    </div>
  </section>

  <section class="card" aria-labelledby="restore-title">
    <header class="card__header">
      <span class="card__icon" aria-hidden="true"
        ><NavIcon name="upload" /></span
      >
      <h2 id="restore-title">{t("Restore from a file", locale)}</h2>
    </header>
    <p class="card__intro">
      {t(
        "Adds the projects, yarns and photos from a Stricknani download as new items. Nothing you already have is changed.",
        locale,
      )}
    </p>
    <input
      bind:this={fileInput}
      class="file-input"
      type="file"
      accept={restoreAccept}
      tabindex="-1"
      aria-hidden="true"
      disabled={busy}
      onchange={chooseFile}
    />
    <div class="download-row">
      <Button
        variant="primary"
        icon="upload"
        disabled={busy}
        onclick={() => fileInput?.click()}
      >
        {t("Choose file", locale)}
      </Button>
    </div>

    {#if busy}
      <div class="progress" role="status" aria-live="polite">
        {#if processing}
          <p>{t("Restoring…", locale)}</p>
          <progress aria-label={t("Restoring…", locale)}></progress>
        {:else}
          <p>{percent}%</p>
          <progress max="100" value={percent} aria-label={`${percent}%`}
          ></progress>
        {/if}
      </div>
    {/if}

    {#if failure}
      <div class="notice-error" role="alert">
        <p>
          {#if failure === "invalid_archive"}
            {t("This file is not a Stricknani download.", locale)}
          {:else if failure === "unsupported_format_version"}
            {t(
              "This download is from a newer version of Stricknani. Update the server first.",
              locale,
            )}
          {:else if failure === "payload_too_large"}
            {t("The file is too large.", locale)}
          {:else if failure === "rate_limited"}
            {t("A restore is already running.", locale)}
          {:else}
            {t("Restore failed.", locale)}
          {/if}
        </p>
        <Button
          size="sm"
          icon="refresh"
          onclick={() => {
            failure = null;
            fileInput?.click();
          }}
        >
          {t("Try again", locale)}
        </Button>
      </div>
    {/if}

    {#if result}
      <div class="result" role="status">
        <h3>{t("Restore finished", locale)}</h3>
        <dl>
          <dt>{t("Projects", locale)}</dt>
          <dd>{result.projects}</dd>
          <dt>{t("Yarns", locale)}</dt>
          <dd>{result.yarns}</dd>
          <dt>{t("Photos and files", locale)}</dt>
          <dd>{result.mediaFiles}</dd>
          {#if result.mediaSkipped > 0}
            <dt>{t("Missing in the file", locale)}</dt>
            <dd>{result.mediaSkipped}</dd>
          {/if}
        </dl>
        <div class="download-row">
          <a
            class="ui-button ui-button--secondary ui-button--md"
            href={resolve("/projects")}
          >
            <ActionLabel icon="projects">{t("Projects", locale)}</ActionLabel>
          </a>
          <a
            class="ui-button ui-button--secondary ui-button--md"
            href={resolve("/yarns")}
          >
            <ActionLabel icon="yarns">{t("Yarns", locale)}</ActionLabel>
          </a>
        </div>
      </div>
    {/if}
  </section>

  <ConfirmDialog
    bind:this={dialog}
    titleId="restore-dialog-title"
    title={t("Restore this file?", locale)}
    {busy}
    onclose={clearPending}
  >
    <p class="file-name">{pending?.name ?? ""}</p>
    <p>
      {t(
        "Everything in it is added as new items, also if you restored it before.",
        locale,
      )}
    </p>
    {#snippet actions()}
      <Button variant="ghost" icon="cancel" onclick={cancelRestore}>
        {t("Cancel", locale)}
      </Button>
      <Button variant="primary" icon="upload" disabled={busy} onclick={restore}>
        {t("Restore", locale)}
      </Button>
    {/snippet}
  </ConfirmDialog>
</section>

<style>
  .backups {
    max-width: 40rem;
    display: grid;
    gap: 0.85rem;
  }

  .card {
    display: grid;
    min-width: 0;
    gap: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    padding: 1rem;
    background: var(--surface);
  }

  .card__header {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.75rem;
  }

  h2 {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 660;
    letter-spacing: -0.025em;
  }

  .card__icon {
    display: inline-flex;
    width: 2.5rem;
    height: 2.5rem;
    flex: none;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    font-size: 1.35rem;
    color: var(--tone-categories);
    background: var(--tone-categories-wash);
  }

  .card__icon :global(svg) {
    width: 1.2em;
    height: 1.2em;
  }

  .card__intro {
    margin: -0.25rem 0 0;
    color: var(--ink-muted);
    line-height: 1.5;
  }

  .card :global(.segmented) {
    justify-self: start;
  }

  .download-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.75rem;
  }

  .format {
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .file-input {
    display: none;
  }

  .progress {
    display: grid;
    gap: 0.4rem;
  }

  .progress p,
  .notice-error p {
    margin: 0;
  }

  .progress progress {
    width: 100%;
  }

  .notice-error {
    display: grid;
    justify-items: start;
    gap: 0.6rem;
    border-left: 3px solid var(--danger);
    padding: 0.55rem 0.75rem;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  .result {
    display: grid;
    gap: 0.75rem;
  }

  .result h3 {
    margin: 0;
    font-size: 1.05rem;
  }

  .result dl {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 0.35rem 1rem;
    margin: 0;
  }

  .result dd {
    margin: 0;
    font-weight: 600;
    text-align: end;
  }

  .file-name {
    overflow-wrap: anywhere;
    font-weight: 600;
  }
</style>
