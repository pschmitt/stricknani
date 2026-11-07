<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import AuditMediaPreview from "#lib/features/admin/AuditMediaPreview.svelte";
  import ChangeList from "./ChangeList.svelte";
  import type { HistoryDetail, HistoryEvent } from "./history-api";
  import { fullDate, historyDetailKind } from "./history-summary";

  // The opened details of one entry of "Recent changes": the same field diff and picture/file
  // viewer as the admin audit log, fed by the owner-scoped history routes.
  let {
    event,
    locale,
    id,
    label,
    detail = undefined,
    onretry,
  }: {
    event: HistoryEvent;
    locale: Locale;
    id: string;
    label: string;
    detail?:
      | { state: "loading" }
      | { state: "error" }
      | { state: "ready"; detail: HistoryDetail };
    onretry: () => void;
  } = $props();

  const kind = $derived(historyDetailKind(event));

  function unavailableText(value: HistoryDetail): string {
    switch (value.unavailable_reason) {
      case "not_recorded":
        return t("No details recorded", locale);
      case "too_large":
        return t(
          "The recorded change snapshot is too large to display.",
          locale,
        );
      case "malformed":
        return t("The recorded change snapshot is unavailable.", locale);
      default:
        return t("No changes are available for this event.", locale);
    }
  }
</script>

<div class="entry-detail" {id} role="region" aria-label={label}>
  <p class="entry-date">{fullDate(event.created_at, locale)}</p>
  {#if event.media}
    <div class="entry-media">
      <AuditMediaPreview media={event.media} {locale} variant="note" />
      <AuditMediaPreview media={event.media} {locale} />
    </div>
  {:else if kind === "none"}
    <p class="history-muted" data-history-no-details>
      {t("No details recorded", locale)}
    </p>
  {:else if !detail || detail.state === "loading"}
    <p class="history-muted" aria-live="polite">
      {t("Loading recorded changes…", locale)}
    </p>
  {:else if detail.state === "error"}
    <div class="history-note" role="alert">
      <p>{t("These changes could not be loaded.", locale)}</p>
      <button type="button" class="history-link" onclick={onretry}>
        <NavIcon name="reset" />{t("Try again", locale)}
      </button>
    </div>
  {:else if detail.detail.changes}
    <ChangeList
      changes={detail.detail.changes}
      {locale}
      headingTag="h3"
      media={event.media_thumbnails}
      label={t("Recorded changes", locale)}
    />
  {:else}
    <p class="history-muted">{unavailableText(detail.detail)}</p>
  {/if}
</div>

<style>
  .entry-detail {
    display: grid;
    gap: 0.7rem;
    margin: 0.2rem 0 0.8rem;
  }

  @media (prefers-reduced-motion: no-preference) {
    .entry-detail {
      animation: history-reveal 180ms ease-out;
    }
  }

  @keyframes history-reveal {
    from {
      opacity: 0;
      transform: translateY(-4px);
    }
  }

  .entry-media {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
  }

  .entry-date {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .history-muted {
    margin: 0;
    color: var(--ink-muted);
  }

  .history-note {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.4rem 0.8rem;
  }

  .history-note p {
    margin: 0;
  }

  .history-link {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    min-height: 44px;
    border: 0;
    border-radius: var(--radius-xs);
    padding: 0 0.6rem;
    color: var(--accent-strong);
    background: transparent;
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }

  .history-link:hover {
    background: var(--accent-wash);
  }

  .history-link:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .history-link :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }
</style>
