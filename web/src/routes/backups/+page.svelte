<script lang="ts">
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { onMount } from "svelte";
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";
  import {
    archiveTypes,
    exportUrl,
    readArchiveType,
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

  onMount(() => {
    archive = readArchiveType();
  });

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
        "Projects, yarns, categories, photos and attachments in one archive (.tar.gz), including items shared with you.",
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

  <p class="note">
    {t("Restoring from a download is not available yet.", locale)}
  </p>
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

  .note {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }
</style>
