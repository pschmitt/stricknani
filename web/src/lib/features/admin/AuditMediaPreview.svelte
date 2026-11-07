<script lang="ts">
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import type { AuditMedia } from "./admin-api";

  // The picture or file an upload/removal event was about: a viewer for images, an open/download
  // link for other files, and an explicit "deleted" note once the file is gone.
  let {
    media,
    locale,
    variant = "action",
    thumb = true,
  }: {
    media: AuditMedia;
    locale: Locale;
    /** "action" is the button/link; "note" is the file name with its kind and state. */
    variant?: "action" | "note";
    /** Whether the note shows a small thumbnail of an existing picture. */
    thumb?: boolean;
  } = $props();

  let dialog = $state<HTMLDialogElement | null>(null);

  const isFile = $derived(!media.is_image);
  const kindLabel = $derived(isFile ? t("File", locale) : t("Picture", locale));
  const label = $derived(media.name?.trim() || kindLabel);
  const deletedLabel = $derived(t("deleted", locale));
  const available = $derived(media.exists && media.url !== null);
  const thumbnail = $derived(
    thumb && available && media.is_image ? media.thumbnail_url : null,
  );
</script>

{#if variant === "note"}
  <span
    class="media-note"
    class:media-note--deleted={!available}
    data-audit-media-note
    title={label}
  >
    {#if thumbnail}
      <img
        class="media-note__thumb"
        src={thumbnail}
        alt=""
        loading="lazy"
        decoding="async"
        width="28"
        height="28"
      />
    {/if}
    <span class="media-note__name">{label}</span>
    <span class="media-note__state"
      >· {kindLabel}{available ? "" : ` · ${deletedLabel}`}</span
    >
  </span>
{:else if available && media.url}
  {#if isFile}
    <a
      class="ui-button ui-button--ghost ui-button--sm show-media"
      href={media.url}
      target="_blank"
      rel="noopener noreferrer"
      download={media.name ?? undefined}
      data-audit-media-open
    >
      <ActionLabel icon="download">{t("Open file", locale)}</ActionLabel>
    </a>
  {:else}
    <button
      type="button"
      class="ui-button ui-button--ghost ui-button--sm show-media"
      data-audit-media-show
      onclick={() => dialog?.showModal()}
    >
      <ActionLabel icon="preview">{t("Show picture", locale)}</ActionLabel>
    </button>
    <dialog
      bind:this={dialog}
      class="media-dialog"
      aria-label={label}
      onclick={(event) => {
        if (event.target === dialog) dialog?.close();
      }}
    >
      <figure>
        <img src={media.url} alt={label} />
        <figcaption>{label}</figcaption>
      </figure>
      <button
        type="button"
        class="ui-button ui-button--secondary ui-button--sm"
        onclick={() => dialog?.close()}
      >
        <ActionLabel icon="close">{t("Close", locale)}</ActionLabel>
      </button>
    </dialog>
  {/if}
{:else}
  <span class="media-gone" data-audit-media-deleted>
    {isFile ? t("File deleted", locale) : t("Picture deleted", locale)}
  </span>
{/if}

<style>
  .show-media {
    min-height: 44px;
    white-space: nowrap;
  }
  .media-note {
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 0.3rem;
    color: var(--ink-muted);
    font-size: 0.82rem;
  }
  .media-note__thumb {
    flex: none;
    width: 1.75rem;
    height: 1.75rem;
    border-radius: 0.4rem;
    object-fit: cover;
    background: var(--surface-raised);
  }
  .media-note__name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .media-note__state {
    flex: none;
    white-space: nowrap;
  }
  .media-note--deleted .media-note__name {
    text-decoration: line-through;
  }
  .media-gone {
    color: var(--ink-muted);
    font-size: 0.85rem;
    white-space: nowrap;
  }
  .media-dialog {
    max-width: min(92vw, 60rem);
    max-height: 92vh;
    margin: auto;
    padding: 0.75rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
    color: var(--ink);
  }
  .media-dialog::backdrop {
    background: rgb(0 0 0 / 0.65);
  }
  .media-dialog figure {
    display: grid;
    gap: 0.5rem;
    margin: 0 0 0.75rem;
  }
  .media-dialog img {
    max-width: 100%;
    max-height: 72vh;
    margin: auto;
    object-fit: contain;
  }
  .media-dialog figcaption {
    color: var(--ink-muted);
    overflow-wrap: anywhere;
    text-align: center;
  }
</style>
