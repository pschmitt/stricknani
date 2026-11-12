<script lang="ts">
  import { onMount, untrack } from "svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import SectionHeading from "#lib/components/SectionHeading.svelte";
  import UserLabel from "#lib/components/UserLabel.svelte";
  import UserSentence from "#lib/components/UserSentence.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { historyActor, subjectUser } from "#lib/user-label.ts";
  import { isLifecycleAction } from "../trash/trash-state";
  import HistoryEventDetail from "./HistoryEventDetail.svelte";
  import {
    getHistoryDetail,
    historyUnsupported,
    listHistory,
    type HistoryDetail,
    type HistoryEvent,
    type HistoryKind,
  } from "./history-api";
  import {
    actionLabel,
    actionTone,
    eventHeadline,
    fullDate,
    historyDetailKind,
    relativeTime,
    sharingSentence,
  } from "./history-summary";

  // "Recent changes" at the bottom of a project or yarn: a timeline of the newest recorded
  // edits, loaded only once the section scrolls into view. Hidden silently when the server
  // has no history endpoint yet.
  let {
    kind,
    id,
    locale,
  }: {
    kind: HistoryKind;
    id: number;
    locale: Locale;
  } = $props();

  type DetailState =
    | { state: "loading" }
    | { state: "error" }
    | { state: "ready"; detail: HistoryDetail };

  const PREVIEW_COUNT = 3;
  const DETAIL_CONCURRENCY = 3;

  let root = $state<HTMLElement | null>(null);
  let started = $state(false);
  let unsupported = $state(false);
  let loading = $state(false);
  let loadingMore = $state(false);
  let failed = $state(false);
  let loaded = $state(false);
  let events = $state<HistoryEvent[]>([]);
  let page = $state(0);
  let hasMore = $state(false);
  let showAll = $state(false);
  let expanded = $state<Record<number, boolean>>({});
  let details = $state<Record<number, DetailState>>({});
  let now = $state(new Date());
  let generation = 0;
  let controller: AbortController | null = null;
  let queue: number[] = [];
  let active = 0;
  let retry = $state(0);

  const sectionId = $derived(`history-${kind}-${id}`);
  const visible = $derived(showAll ? events : events.slice(0, PREVIEW_COUNT));
  const canShowAll = $derived(events.length > PREVIEW_COUNT || hasMore);

  onMount(() => {
    if (typeof IntersectionObserver === "undefined" || !root) {
      started = true;
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          started = true;
          observer.disconnect();
        }
      },
      { rootMargin: "240px 0px" },
    );
    observer.observe(root);
    return () => observer.disconnect();
  });

  $effect(() => {
    const currentKind = kind;
    const currentId = id;
    retry;
    if (!started) return;
    const request = ++generation;
    controller?.abort();
    const abort = new AbortController();
    controller = abort;
    queue = [];
    active = 0;
    events = [];
    details = {};
    expanded = {};
    page = 0;
    hasMore = false;
    showAll = false;
    unsupported = false;
    failed = false;
    loaded = false;
    loading = true;
    listHistory(currentKind, currentId, 1, abort.signal)
      .then((result) => {
        if (request !== generation) return;
        events = result.data;
        page = result.meta.page;
        hasMore = result.meta.has_more;
        now = new Date();
        loaded = true;
        loading = false;
      })
      .catch((reason: unknown) => {
        if (abort.signal.aborted || request !== generation) return;
        loading = false;
        if (historyUnsupported(reason)) unsupported = true;
        else failed = true;
      });
    return () => abort.abort();
  });

  // Fetch the field details of what is on screen so each row can say what changed.
  $effect(() => {
    for (const event of visible) {
      if (event.has_changes && !event.media)
        untrack(() => requestDetail(event.id));
    }
  });

  function requestDetail(eventId: number, force = false) {
    const existing = details[eventId];
    if (existing && !(force && existing.state === "error")) return;
    details[eventId] = { state: "loading" };
    queue.push(eventId);
    pump();
  }

  function pump() {
    while (active < DETAIL_CONCURRENCY && queue.length > 0) {
      const eventId = queue.shift() as number;
      const request = generation;
      const signal = controller?.signal;
      active += 1;
      getHistoryDetail(kind, id, eventId, signal)
        .then((detail) => {
          if (request !== generation) return;
          details[eventId] = { state: "ready", detail };
        })
        .catch(() => {
          if (request !== generation || signal?.aborted) return;
          details[eventId] = { state: "error" };
        })
        .finally(() => {
          if (request !== generation) return;
          active -= 1;
          pump();
        });
    }
  }

  async function loadMore() {
    if (loadingMore || !hasMore) return;
    const request = generation;
    loadingMore = true;
    try {
      const result = await listHistory(kind, id, page + 1, controller?.signal);
      if (request !== generation) return;
      const seen = new Set(events.map((event) => event.id));
      events = [
        ...events,
        ...result.data.filter((event) => !seen.has(event.id)),
      ];
      page = result.meta.page;
      hasMore = result.meta.has_more;
    } catch {
      if (request === generation) failed = true;
    } finally {
      if (request === generation) loadingMore = false;
    }
  }

  function toggle(event: HistoryEvent) {
    const open = !expanded[event.id];
    expanded[event.id] = open;
    if (open && historyDetailKind(event) === "changes")
      requestDetail(event.id, true);
  }

  /** A sharing event's sentence, with its person as a `UserLabel`. */
  function sharing(event: HistoryEvent) {
    return sharingSentence(event.action, event.subject, locale);
  }

  function headline(event: HistoryEvent): string {
    if (event.media?.name)
      return `${actionLabel(event.action, locale, event.system === true)}: ${event.media.name}`;
    const entry = details[event.id];
    return eventHeadline(
      event.action,
      entry?.state === "ready" ? entry.detail.changes : null,
      locale,
      event.subject,
      event.system === true,
    );
  }

  /** The owner of moved-to-trash, restore and purge entries an administrator or the system caused. */
  function ownerOf(event: HistoryEvent) {
    return isLifecycleAction(event.action)
      ? subjectUser(event.subject ?? null, locale)
      : null;
  }

  /** Who made a change while acting as the actor: "by Ann as Bea". */
  function impersonator(event: HistoryEvent) {
    const admin = event.impersonated_by;
    if (!admin) return null;
    return admin.name
      ? ({ kind: "user", name: admin.name, avatarUrl: null } as const)
      : ({ kind: "admin", name: null, avatarUrl: null } as const);
  }
</script>

<section
  bind:this={root}
  class="history"
  hidden={unsupported}
  aria-labelledby={`${sectionId}-title`}
  aria-busy={loading}
  data-history-section
>
  <div class="history-heading">
    <SectionHeading id={`${sectionId}-title`} icon="history" tone="audit"
      >{t("Recent changes", locale)}</SectionHeading
    >
  </div>

  {#if loading || !started}
    <div class="history-skeleton" aria-hidden="true">
      <span></span><span></span><span></span>
    </div>
    <p class="visually-hidden" aria-live="polite">
      {t("Loading recent changes…", locale)}
    </p>
  {:else if failed && !loaded}
    <div class="history-note" role="alert">
      <p>{t("Recent changes could not be loaded.", locale)}</p>
      <button type="button" class="history-link" onclick={() => (retry += 1)}>
        <NavIcon name="reset" />{t("Try again", locale)}
      </button>
    </div>
  {:else if loaded && events.length === 0}
    <div class="history-empty">
      <Mascot size={64} />
      <p class="history-empty-title">
        {t("No changes recorded yet", locale)}
      </p>
      <p class="history-empty-hint">
        {kind === "projects"
          ? t("Edits to this project will show up here.", locale)
          : t("Edits to this yarn will show up here.", locale)}
      </p>
    </div>
  {:else}
    <ol class="timeline" aria-label={t("Recent changes", locale)}>
      {#each visible as event (event.id)}
        {@const tone = actionTone(event.action)}
        {@const actor = historyActor(event)}
        {@const owner = ownerOf(event)}
        {@const admin = impersonator(event)}
        {@const open = expanded[event.id] === true}
        {@const entry = details[event.id]}
        <li class="entry" data-tone={tone}>
          <span class="dot" aria-hidden="true"></span>
          {#snippet summary()}
            {#if event.media?.exists && event.media.is_image && event.media.thumbnail_url}
              <img
                class="entry-thumb"
                src={event.media.thumbnail_url}
                alt=""
                loading="lazy"
                decoding="async"
                width="40"
                height="40"
              />
            {/if}
            <span class="entry-main">
              <span class="entry-headline"
                >{#if sharing(event)}<UserSentence
                    template={sharing(event)?.template ?? ""}
                    users={sharing(event)?.users ?? {}}
                    {locale}
                  />{:else}{headline(event)}{/if}</span
              >
              <span class="entry-meta">
                <span class="entry-action"
                  >{actionLabel(
                    event.action,
                    locale,
                    event.system === true,
                  )}</span
                >
                <time
                  datetime={event.created_at}
                  title={fullDate(event.created_at, locale)}
                  >{relativeTime(event.created_at, now, locale)}</time
                >
                <span class="entry-actor">
                  {#if admin}
                    <UserSentence
                      template={t("by {admin} as {user}", locale)}
                      users={{ admin, user: actor }}
                      {locale}
                    />
                  {:else}
                    <UserLabel user={actor} {locale} size="inline" by />
                  {/if}
                </span>
                {#if owner}
                  <span class="entry-actor">
                    <UserSentence
                      template={t("Owner: {name}", locale)}
                      users={{ name: owner }}
                      {locale}
                    />
                  </span>
                {/if}
              </span>
            </span>
          {/snippet}
          <button
            type="button"
            class="entry-row"
            aria-expanded={open}
            aria-controls={`${sectionId}-event-${event.id}`}
            onclick={() => toggle(event)}
          >
            {@render summary()}
            <span class="entry-chevron" class:entry-chevron--open={open}
              ><NavIcon name="chevron-down" /></span
            >
          </button>
          {#if open}
            <HistoryEventDetail
              {event}
              {locale}
              id={`${sectionId}-event-${event.id}`}
              label={headline(event)}
              detail={entry}
              onretry={() => requestDetail(event.id, true)}
            />
          {/if}
        </li>
      {/each}
    </ol>

    {#if failed}
      <p class="history-muted" role="alert">
        {t("More changes could not be loaded.", locale)}
      </p>
    {/if}

    {#if canShowAll}
      <div class="history-footer">
        {#if !showAll}
          <button
            type="button"
            class="history-link"
            onclick={() => (showAll = true)}
          >
            {t("Show all changes", locale)}
          </button>
        {:else}
          {#if hasMore}
            <button
              type="button"
              class="history-link"
              disabled={loadingMore}
              onclick={() => void loadMore()}
            >
              {loadingMore
                ? t("Loading…", locale)
                : t("Show older changes", locale)}
            </button>
          {/if}
          <button
            type="button"
            class="history-link"
            onclick={() => (showAll = false)}
          >
            {t("Show fewer", locale)}
          </button>
        {/if}
      </div>
    {/if}
  {/if}
</section>

<style>
  .history {
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: clamp(1rem, 3vw, 1.4rem);
    background: var(--surface);
  }

  .history[hidden] {
    display: none;
  }

  @media print {
    .history {
      display: none;
    }
  }

  .history-heading :global(.section-heading) {
    margin-bottom: 0.9rem;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }

  .history-skeleton {
    display: grid;
    gap: 0.7rem;
  }

  .history-skeleton span {
    height: 2.6rem;
    border-radius: var(--radius-xs);
    background: var(--surface-raised);
  }

  @media (prefers-reduced-motion: no-preference) {
    .history-skeleton span {
      animation: history-pulse 1.4s ease-in-out infinite;
    }
  }

  @keyframes history-pulse {
    50% {
      opacity: 0.5;
    }
  }

  .timeline {
    position: relative;
    display: grid;
    gap: 0;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .entry {
    --tone: var(--ink-muted);
    position: relative;
    min-width: 0;
    padding-left: 1.7rem;
  }

  .entry[data-tone="create"] {
    --tone: var(--success);
  }

  .entry[data-tone="update"] {
    --tone: var(--info);
  }

  .entry[data-tone="delete"] {
    --tone: var(--danger);
  }

  .entry[data-tone="upload"] {
    --tone: var(--warning);
  }

  .entry::before {
    content: "";
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0.45rem;
    width: 2px;
    background: color-mix(in srgb, var(--tone) 28%, var(--line));
  }

  .entry:first-child::before {
    top: 1.35rem;
  }

  .entry:last-child::before {
    bottom: calc(100% - 1.35rem);
  }

  .entry:only-child::before {
    display: none;
  }

  .dot {
    position: absolute;
    top: 1.05rem;
    left: 0;
    width: 0.95rem;
    height: 0.95rem;
    border: 3px solid var(--surface);
    border-radius: 50%;
    background: var(--tone);
    box-shadow: 0 0 0 1.5px var(--tone);
  }

  .entry-row {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    box-sizing: border-box;
    width: 100%;
    min-height: 44px;
    border: 0;
    border-radius: var(--radius-xs);
    padding: 0.55rem 0.6rem;
    color: inherit;
    background: transparent;
    font: inherit;
    text-align: left;
  }

  .entry-row {
    cursor: pointer;
  }

  .entry-row:hover {
    background: var(--surface-raised);
  }

  .entry-row:focus-visible,
  .history-link:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .entry-thumb {
    flex: none;
    width: 2.5rem;
    height: 2.5rem;
    border-radius: 0.6rem;
    object-fit: cover;
    background: var(--surface-raised);
  }

  .entry-main {
    display: grid;
    flex: 1;
    gap: 0.15rem;
    min-width: 0;
  }

  .entry-headline {
    overflow-wrap: anywhere;
    font-weight: 650;
  }

  .entry-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.2rem 0.6rem;
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .entry-action {
    color: var(--tone);
    font-weight: 700;
  }

  .entry-actor {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .entry-chevron {
    display: grid;
    flex: 0 0 1.3rem;
    place-items: center;
    color: var(--ink-muted);
  }

  .entry-chevron :global(svg) {
    width: 1.3rem;
    height: 1.3rem;
  }

  @media (prefers-reduced-motion: no-preference) {
    .entry-chevron {
      transition: transform 160ms ease;
    }
  }

  .entry-chevron--open {
    transform: rotate(180deg);
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

  .history-footer {
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem 1rem;
    margin-top: 0.6rem;
    padding-left: 1.7rem;
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

  .history-link:hover:not(:disabled) {
    background: var(--accent-wash);
  }

  .history-link:disabled {
    cursor: progress;
    opacity: 0.6;
  }

  .history-link :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }

  .history-empty {
    display: grid;
    justify-items: center;
    gap: 0.2rem;
    padding: 0.6rem 0 0.2rem;
    text-align: center;
  }

  .history-empty-title {
    margin: 0.3rem 0 0;
    font-weight: 700;
  }

  .history-empty-hint {
    margin: 0;
    color: var(--ink-muted);
  }
</style>
