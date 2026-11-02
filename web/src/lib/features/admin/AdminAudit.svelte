<script lang="ts">
  import { goto, snapshot } from "$app/navigation";
  import { page } from "$app/state";
  import { onDestroy, tick } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { auditActionPresentation } from "./audit-action";
  import {
    auditFiltersFromSearch,
    auditSearchFromFilters,
    listAuditEvents,
    type AuditEntity,
    type AuditEvent,
  } from "./admin-api";

  type LoadState = "loading" | "ready" | "error";

  type AuditScrollSnapshot = {
    pathname: string;
    search: string;
    ownerId: number;
    scrollY: number;
  };

  let events = $state<AuditEvent[]>([]);
  let pageMeta = $state({ page: 1, per_page: 50, has_more: false });
  let loadState = $state<LoadState>("loading");
  let loadError = $state("");
  let loadGeneration = 0;
  let ownerGeneration = 0;
  let pendingScroll = $state<AuditScrollSnapshot | null>(null);
  let retry = $state(0);
  let currentOwner: number | null | undefined;
  let actionDraft = $state("");

  const locale = $derived(session.data?.locale ?? "en");
  const legacyUserMessage = msg("Legacy user");
  const userId = $derived(session.data?.user?.id ?? null);
  const filters = $derived(auditFiltersFromSearch(page.url.search));
  const pageUrl = $derived(page.url);

  snapshot<AuditScrollSnapshot | null>({
    id: "admin-audit-scroll",
    capture: () => {
      const ownerId = userId;
      if (typeof window === "undefined" || ownerId === null) return null;
      return {
        pathname: pageUrl.pathname,
        search: pageUrl.search,
        ownerId,
        scrollY: window.scrollY,
      };
    },
    restore: (saved) => {
      const ownerId = userId;
      if (
        !isAuditScrollSnapshot(saved) ||
        !Number.isFinite(saved.scrollY) ||
        saved.scrollY < 0 ||
        saved.pathname !== pageUrl.pathname ||
        saved.search !== pageUrl.search ||
        ownerId === null ||
        saved.ownerId !== ownerId
      ) {
        pendingScroll = null;
        return;
      }
      pendingScroll = saved;
    },
    reset: () => (pendingScroll = null),
  });

  // SvelteKit restores history scroll at route commit, while this list loads afterward.
  // Wait until the matching list is rendered so the browser does not clamp that position.
  $effect(() => {
    const saved = pendingScroll;
    if (
      !saved ||
      loadState !== "ready" ||
      saved.pathname !== pageUrl.pathname ||
      saved.search !== pageUrl.search ||
      saved.ownerId !== userId
    )
      return;

    const request = loadGeneration;
    const ownerRequest = ownerGeneration;
    const ownerId = userId;
    let cancelled = false;
    void tick().then(() => {
      if (
        cancelled ||
        pendingScroll !== saved ||
        loadState !== "ready" ||
        request !== loadGeneration ||
        ownerRequest !== ownerGeneration ||
        userId !== ownerId ||
        ownerId !== saved.ownerId ||
        pageUrl.pathname !== saved.pathname ||
        pageUrl.search !== saved.search
      )
        return;
      window.scrollTo(0, saved.scrollY);
      pendingScroll = null;
    });
    return () => (cancelled = true);
  });

  function isAuditScrollSnapshot(value: unknown): value is AuditScrollSnapshot {
    if (typeof value !== "object" || value === null) return false;
    const candidate = value as Record<string, unknown>;
    return (
      typeof candidate.pathname === "string" &&
      typeof candidate.search === "string" &&
      Number.isSafeInteger(candidate.ownerId) &&
      typeof candidate.scrollY === "number"
    );
  }

  $effect(() => {
    const ownerId = userId;
    const currentFilters = filters;
    const url = pageUrl;
    retry;
    const request = ++loadGeneration;
    if (currentOwner !== ownerId) {
      currentOwner = ownerId;
      ownerGeneration += 1;
      events = [];
    }
    const ownerRequest = ownerGeneration;
    actionDraft = currentFilters.action;
    loadError = "";
    const controller = new AbortController();
    if (ownerId === null || session.data?.user?.is_admin !== true) {
      loadState = "error";
      loadError = t("Administrator access is required.", locale);
      return () => controller.abort();
    }
    loadState = "loading";
    void listAuditEvents(currentFilters, controller.signal)
      .then(async (result) => {
        if (
          controller.signal.aborted ||
          request !== loadGeneration ||
          ownerRequest !== ownerGeneration ||
          userId !== ownerId
        )
          return;
        events = result.data;
        pageMeta = result.meta;
        loadState = "ready";
        if (result.meta.page !== currentFilters.page) {
          const next = auditSearchFromFilters({
            ...currentFilters,
            page: result.meta.page,
          });
          await goto(`${url.pathname}${next ? `?${next}` : ""}`, {
            replaceState: true,
          });
        }
      })
      .catch((error: unknown) => {
        if (
          controller.signal.aborted ||
          request !== loadGeneration ||
          ownerRequest !== ownerGeneration ||
          userId !== ownerId
        )
          return;
        events = [];
        loadState = "error";
        loadError =
          error instanceof ApiError && error.status === 403
            ? t("Administrator access is required.", locale)
            : error instanceof Error
              ? error.message
              : t("Audit events could not be loaded.", locale);
      });
    return () => controller.abort();
  });

  onDestroy(() => {
    loadGeneration += 1;
    ownerGeneration += 1;
  });

  async function updateFilters(next: {
    page: number;
    entity: AuditEntity;
    action: string;
  }) {
    const query = auditSearchFromFilters(next);
    await goto(`${page.url.pathname}${query ? `?${query}` : ""}`, {
      replaceState: true,
    });
  }

  function applyFilters(event: SubmitEvent) {
    event.preventDefault();
    void updateFilters({
      page: 1,
      entity: filters.entity,
      action: actionDraft.trim(),
    });
  }

  function changeEntity(event: Event) {
    const target = event.currentTarget;
    if (!(target instanceof HTMLSelectElement)) return;
    const value = target.value;
    const entity: AuditEntity =
      value === "project" || value === "yarn" || value === "category"
        ? value
        : "all";
    void updateFilters({ page: 1, entity, action: actionDraft.trim() });
  }

  function actorLabel(event: AuditEvent): string {
    if (event.actor_email) return event.actor_email;
    if (event.legacy_actor_user_id !== null)
      return `${t(legacyUserMessage, locale)} #${event.legacy_actor_user_id}`;
    return t("Unknown actor", locale);
  }

  function entityLabel(entity: string): string {
    return entity === "project"
      ? t("Project", locale)
      : entity === "yarn"
        ? t("Yarn", locale)
        : entity === "category"
          ? t("Category", locale)
          : t("Resource", locale);
  }

  function eventDate(value: string): string {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.valueOf())) return value;
    return new Intl.DateTimeFormat(locale, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(parsed);
  }
</script>

<svelte:head><title>Stricknani · {t("Audit log", locale)}</title></svelte:head>

<section
  class="audit-page page-container page-container--wide"
  aria-labelledby="audit-title"
>
  <div class="audit-heading">
    <PageHeader title={t("Audit log", locale)} titleId="audit-title">
      {#snippet context()}
        <p>{t("Review account and library changes in time order.", locale)}</p>
      {/snippet}
    </PageHeader>
  </div>

  <form class="audit-filters" onsubmit={applyFilters}>
    <label for="audit-entity">{t("Entity", locale)}</label>
    <select id="audit-entity" value={filters.entity} onchange={changeEntity}>
      <option value="all">{t("All entities", locale)}</option>
      <option value="project">{t("Project", locale)}</option>
      <option value="yarn">{t("Yarn", locale)}</option>
      <option value="category">{t("Category", locale)}</option>
    </select>
    <label for="audit-action">{t("Action", locale)}</label>
    <div class="action-search">
      <input
        id="audit-action"
        bind:value={actionDraft}
        maxlength="64"
        autocomplete="off"
      />
      <button class="primary-button" type="submit">
        <span class="button-content">
          <NavIcon name="filter" />
          <span>{t("Filter", locale)}</span>
        </span>
      </button>
    </div>
  </form>

  {#if loadState === "loading"}
    <p class="notice-panel" aria-live="polite">
      {t("Loading audit events…", locale)}
    </p>
  {:else if loadState === "error"}
    <section
      class="notice-panel"
      role="alert"
      aria-labelledby="audit-error-title"
    >
      <h2 id="audit-error-title">{loadError}</h2>
      <button
        class="secondary-button"
        type="button"
        onclick={() => (retry += 1)}
      >
        <span class="button-content">
          <NavIcon name="refresh" />
          <span>{t("Try again", locale)}</span>
        </span>
      </button>
    </section>
  {:else}
    <ol class="event-list" aria-label={t("Audit events", locale)}>
      {#each events as event (event.id)}
        {@const actor = actorLabel(event)}
        {@const action = auditActionPresentation(event.action)}
        {@const resourceKind = event.resource.kind ?? "resource"}
        <li class="event-row">
          <div class="event-main">
            <div class="event-summary">
              <span class="event-action">
                <NavIcon name={action.icon} />
                <strong>
                  {action.known ? t(action.messageKey, locale) : action.label}
                </strong>
              </span>
            </div>
            <ResourceCard
              kind={resourceKind}
              name={event.resource.name ??
                `${entityLabel(event.entity_type)} #${event.entity_id}`}
              href={event.resource.href}
              imageUrl={event.resource.preview_url}
            />
            <p>
              {t("By", locale)}
              <span class="actor-identity">
                <UserAvatar avatarUrl={event.actor_avatar_url} name={actor} />
                <span>{actor}</span>
              </span>
            </p>
          </div>
          <div class="event-meta">
            {#if event.has_changes}
              <a class="show-diff" href={`/app/admin/audit/${event.id}`}>
                <span class="button-content"
                  ><NavIcon name="preview" />{t("Show diff", locale)}</span
                >
              </a>
            {/if}
            <time datetime={event.created_at}
              >{eventDate(event.created_at)}</time
            >
          </div>
        </li>
      {:else}
        <li class="empty-events">
          {t("No audit events match these filters.", locale)}
        </li>
      {/each}
    </ol>

    <nav class="page-controls" aria-label={t("Audit pages", locale)}>
      <button
        class="secondary-button"
        type="button"
        disabled={pageMeta.page <= 1}
        onclick={() =>
          void updateFilters({ ...filters, page: pageMeta.page - 1 })}
      >
        <span class="button-content">
          <NavIcon name="previous" />
          <span>{t("Previous page", locale)}</span>
        </span>
      </button>
      <span>{t("Page", locale)} {pageMeta.page}</span>
      <button
        class="secondary-button"
        type="button"
        disabled={!pageMeta.has_more}
        onclick={() =>
          void updateFilters({ ...filters, page: pageMeta.page + 1 })}
      >
        <span class="button-content">
          <span>{t("Next page", locale)}</span>
          <NavIcon name="next" />
        </span>
      </button>
    </nav>
  {/if}
</section>

<style>
  .audit-heading {
    margin-bottom: 1.3rem;
  }
  .audit-filters {
    display: grid;
    grid-template-columns: auto minmax(8rem, 0.8fr) auto minmax(16rem, 1.5fr);
    align-items: center;
    gap: 0.55rem 0.75rem;
    margin: 0 0 1rem;
    padding: 0.8rem;
    border: 1px solid var(--line);
    border-radius: 0.8rem;
    background: var(--surface);
  }
  .audit-filters label {
    font-weight: 650;
  }
  .audit-filters select,
  .audit-filters input {
    min-height: 44px;
    border: 1px solid var(--control-border);
    border-radius: 0.55rem;
    padding: 0.55rem 0.7rem;
    color: var(--ink);
    background: var(--surface);
    font: inherit;
  }
  .action-search {
    display: flex;
    gap: 0.5rem;
  }
  .action-search input {
    flex: 1;
    min-width: 0;
  }
  .primary-button,
  .secondary-button {
    min-height: 44px;
    border-radius: 0.65rem;
    padding: 0.55rem 0.8rem;
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }
  .button-content {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.45rem;
  }
  .button-content :global(svg),
  .event-action :global(svg) {
    flex: 0 0 1rem;
    width: 1rem;
    height: 1rem;
  }
  .primary-button {
    border: 1px solid var(--accent-strong);
    color: var(--on-accent);
    background: var(--accent-strong);
  }
  .secondary-button {
    border: 1px solid var(--line);
    color: var(--ink);
    background: var(--surface);
  }
  button:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
  .event-list {
    margin: 0;
    padding: 0;
    list-style: none;
    border: 1px solid var(--line);
    border-radius: 0.85rem;
    background: var(--surface);
  }
  .event-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(10rem, auto);
    align-items: start;
    gap: 0.75rem;
    padding: 1rem;
  }
  .event-row + .event-row {
    border-top: 1px solid var(--line);
  }
  .event-summary {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem 0.7rem;
    align-items: baseline;
    overflow-wrap: anywhere;
  }
  .event-action {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
  }
  .event-main p,
  .event-row time {
    color: var(--ink-muted);
    font-size: 0.9rem;
  }
  .show-diff {
    color: var(--accent-strong);
    font-size: 0.9rem;
    overflow-wrap: anywhere;
  }
  .show-diff,
  .event-meta {
    justify-self: start;
  }
  .event-meta {
    display: grid;
    justify-items: end;
    gap: 0.2rem;
  }
  .show-diff {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
  }
  .event-main p {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.3rem 0.45rem;
    margin: 0.3rem 0 0;
    overflow-wrap: anywhere;
  }
  .actor-identity {
    display: inline-flex;
    min-width: 0;
    align-items: center;
    gap: 0.4rem;
    color: var(--ink);
  }
  .event-row time {
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
  .empty-events,
  .notice-panel {
    padding: 1.2rem;
    color: var(--ink-muted);
  }
  .notice-panel h2 {
    color: var(--ink);
  }
  .page-controls {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 1rem;
    margin: 1rem 0;
  }
  .page-controls span {
    min-width: 5rem;
    text-align: center;
    font-variant-numeric: tabular-nums;
  }
  :focus-visible {
    outline: 3px solid #e2a61f;
    outline-offset: 3px;
  }
  @media (max-width: 850px) {
    .audit-filters {
      grid-template-columns: minmax(0, 1fr);
    }
    .audit-filters label:not(:first-child) {
      margin-top: 0.35rem;
    }
    .event-row {
      grid-template-columns: minmax(0, 1fr);
    }
    .event-meta {
      justify-items: start;
    }
    .event-row time {
      grid-column: 1;
      text-align: left;
    }
    .page-controls {
      gap: 0.4rem;
    }
    .page-controls button {
      padding-inline: 0.55rem;
    }
  }
</style>
