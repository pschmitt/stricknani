<script lang="ts">
  import SharedButton from "#lib/components/Button.svelte";
  import FilterChip from "#lib/components/FilterChip.svelte";
  import { goto, snapshot } from "$app/navigation";
  import { page } from "$app/state";
  import { onDestroy, tick } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import AdminTabs from "./AdminTabs.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import UserLabel from "#lib/components/UserLabel.svelte";
  import UserSentence from "#lib/components/UserSentence.svelte";
  import AuditActionBadge from "./AuditActionBadge.svelte";
  import AuditLifecycle from "./AuditLifecycle.svelte";
  import AuditMediaPreview from "./AuditMediaPreview.svelte";
  import ClientIcon from "./ClientIcon.svelte";
  import { clientLabel } from "./audit-client";
  import {
    auditActor,
    auditImpersonatedBy,
    auditUserRoleSentence,
    impersonationTemplate,
  } from "./audit-people";
  import {
    auditActionPresentation,
    auditActionTone,
    knownAuditActions,
  } from "./audit-action";
  import { actionLabel } from "#lib/features/history/history-summary.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { accountUser, userLabelText } from "#lib/user-label.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    auditFiltersFromSearch,
    auditSearchFromFilters,
    listAuditEvents,
    type AuditEntity,
    type AuditEvent,
    type AuditFilterUser,
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
  // The account the list is filtered to, as named by the server's answer.
  let filterUser = $state<AuditFilterUser | null>(null);

  const locale = $derived(session.data?.locale ?? "en");
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
        filterUser = result.user;
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
        filterUser = null;
        loadState = "error";
        loadError =
          error instanceof ApiError && error.status === 403
            ? t("Administrator access is required.", locale)
            : error instanceof ApiError && error.status === 404
              ? t("User was not found.", locale)
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
    user: number | null;
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
      user: filters.user,
    });
  }

  function changeEntity(event: Event) {
    const target = event.currentTarget;
    if (!(target instanceof HTMLSelectElement)) return;
    const value = target.value;
    const entity: AuditEntity =
      value === "project" ||
      value === "yarn" ||
      value === "category" ||
      value === "server"
        ? value
        : "all";
    void updateFilters({
      page: 1,
      entity,
      action: actionDraft.trim(),
      user: filters.user,
    });
  }

  function clearUserFilter() {
    void updateFilters({
      page: 1,
      entity: filters.entity,
      action: actionDraft.trim(),
      user: null,
    });
  }

  /** Whether the row has an owner or trash note to show on its note line. */
  function hasLifecycle(event: AuditEvent): boolean {
    return (
      event.subject !== null ||
      event.action === "step_trashed" ||
      (event.media !== null && event.action === "trashed") ||
      (event.action === "trashed" && event.resource.state !== null)
    );
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

  // Events arrive newest first; group them under one heading per local day.
  const eventDays = $derived.by(() => {
    const groups: { key: string; label: string; events: AuditEvent[] }[] = [];
    for (const event of events) {
      const key = dayKey(event.created_at);
      const last = groups.at(-1);
      if (last?.key === key) last.events.push(event);
      else
        groups.push({
          key,
          label: dayLabel(event.created_at),
          events: [event],
        });
    }
    return groups;
  });

  const knownActionNames = Object.keys(knownAuditActions);

  function dayKey(value: string): string {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.valueOf())) return value;
    return `${parsed.getFullYear()}-${parsed.getMonth()}-${parsed.getDate()}`;
  }

  function dayLabel(value: string): string {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.valueOf())) return value;
    const today = new Date();
    const yesterday = new Date(today);
    yesterday.setDate(today.getDate() - 1);
    if (dayKey(value) === dayKey(today.toISOString()))
      return t("Today", locale);
    if (dayKey(value) === dayKey(yesterday.toISOString()))
      return t("Yesterday", locale);
    return new Intl.DateTimeFormat(locale, {
      weekday: "long",
      day: "numeric",
      month: "long",
      year:
        parsed.getFullYear() === today.getFullYear() ? undefined : "numeric",
    }).format(parsed);
  }

  function eventTime(value: string): string {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.valueOf())) return value;
    return new Intl.DateTimeFormat(locale, { timeStyle: "short" }).format(
      parsed,
    );
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

<svelte:head
  ><title>Stricknani · {t("Admin", locale)} · {t("Audit log", locale)}</title
  ></svelte:head
>

<section
  class="audit-page page-container page-container--wide"
  aria-labelledby="audit-title"
>
  <div class="audit-heading">
    <PageHeader title={t("Admin", locale)} titleId="audit-title"></PageHeader>
  </div>
  <AdminTabs active="audit" {locale} />

  <form
    class="audit-filters"
    onsubmit={applyFilters}
    aria-label={t("Filter audit events", locale)}
  >
    <label class="filter-field">
      <span>{t("Entity", locale)}</span>
      <select id="audit-entity" value={filters.entity} onchange={changeEntity}>
        <option value="all">{t("All entities", locale)}</option>
        <option value="project">{t("Project", locale)}</option>
        <option value="yarn">{t("Yarn", locale)}</option>
        <option value="category">{t("Category", locale)}</option>
        <option value="server">{t("Server", locale)}</option>
      </select>
    </label>
    <label class="filter-field filter-field--grow">
      <span>{t("Action", locale)}</span>
      <input
        id="audit-action"
        bind:value={actionDraft}
        maxlength="64"
        autocomplete="off"
        list="audit-action-names"
        placeholder={t("Any action", locale)}
      />
    </label>
    <datalist id="audit-action-names">
      {#each knownActionNames as name (name)}<option value={name}
        ></option>{/each}
    </datalist>
    <SharedButton variant="secondary" type="submit" icon="filter">
      {t("Filter", locale)}
    </SharedButton>
  </form>

  {#if filters.user !== null}
    <div class="audit-user-filter" data-testid="audit-user-filter">
      <FilterChip
        label={t("User: {name}", locale).replace(
          "{name}",
          filterUser
            ? userLabelText(accountUser(filterUser), locale)
            : `#${filters.user}`,
        )}
        selected
        onclick={clearUserFilter}
        testid="audit-user-filter-chip"
      >
        {#snippet leading()}
          <NavIcon name="close" />
        {/snippet}
        {#snippet content()}
          {#if filterUser}
            <UserSentence
              template={t("User: {name}", locale)}
              users={{ name: accountUser(filterUser) }}
              {locale}
            />
          {:else}
            {t("User: {name}", locale).replace("{name}", `#${filters.user}`)}
          {/if}
        {/snippet}
      </FilterChip>
    </div>
  {/if}

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
      <SharedButton
        variant="secondary"
        type="button"
        onclick={() => (retry += 1)}
        icon="refresh"
      >
        {t("Try again", locale)}
      </SharedButton>
    </section>
  {:else if events.length === 0}
    <p class="empty-events">
      {t("No audit events match these filters.", locale)}
    </p>
  {:else}
    <div class="event-days" aria-label={t("Audit events", locale)}>
      {#each eventDays as day (day.key)}
        <section class="event-day" aria-labelledby={`audit-day-${day.key}`}>
          <h2 class="day-heading" id={`audit-day-${day.key}`}>{day.label}</h2>
          <ol class="event-list">
            {#each day.events as event (event.id)}
              {@const actor = auditActor(event, locale)}
              {@const impersonated = auditImpersonatedBy(event, actor, locale)}
              {@const resourceKind = event.resource.kind ?? "resource"}
              <li class="event-row">
                <div
                  class="event-action"
                  data-tone={auditActionTone(event.action, {
                    system: event.system,
                  })}
                  aria-hidden="true"
                >
                  <NavIcon
                    name={auditActionPresentation(event.action, {
                      system: event.system,
                      mediaKind: event.media?.kind ?? null,
                    }).icon}
                  />
                </div>
                <div
                  class="event-resource"
                  class:no-media={!event.media && !hasLifecycle(event)}
                >
                  {#if event.entity_type === "server" && event.server}
                    <p class="impersonation-event" data-audit-server>
                      <strong>{actionLabel(event.action, locale)}</strong>
                      <span>{event.server.backup}</span>
                      <span
                        >{t("Safety copy: {name}", locale).replace(
                          "{name}",
                          event.server.pre_restore,
                        )}
                        ·
                        {event.server.media === "add_missing"
                          ? t("Missing media files were added", locale)
                          : t("Media files were kept", locale)}</span
                      >
                    </p>
                  {:else if event.entity_type === "user"}
                    <p class="impersonation-event">
                      <strong
                        >{#if impersonationTemplate(event.action, locale)}<UserSentence
                            template={impersonationTemplate(
                              event.action,
                              locale,
                            ) ?? ""}
                            users={{ admin: actor }}
                            {locale}
                          />{:else}{actionLabel(
                            event.action,
                            locale,
                          )}{/if}</strong
                      >
                      <span>{event.resource.name ?? `#${event.entity_id}`}</span
                      >
                    </p>
                  {:else}
                    <ResourceCard
                      kind={resourceKind}
                      name={event.resource.name ??
                        `${entityLabel(event.entity_type)} #${event.entity_id}`}
                      href={event.resource.href}
                      imageUrl={event.resource.preview_url}
                    />
                    <!-- Every row reserves this line so rows keep one height, with or without media;
                       rows without media shift the card down by half of it to stay centered. -->
                    <div class="event-note">
                      <AuditLifecycle
                        {event}
                        {locale}
                        onChanged={() => (retry += 1)}
                      />
                      {#if event.media}
                        {#if event.media.exists && event.media.is_image && event.media.thumbnail_url}
                          <img
                            class="event-thumb"
                            src={event.media.thumbnail_url}
                            alt=""
                            loading="lazy"
                            decoding="async"
                            width="28"
                            height="28"
                          />
                        {/if}
                        <AuditMediaPreview
                          media={event.media}
                          {locale}
                          variant="note"
                          thumb={false}
                        />
                      {/if}
                    </div>
                  {/if}
                  <div class="event-action-label">
                    <AuditActionBadge
                      action={event.action}
                      {locale}
                      system={event.system}
                      mediaKind={event.media?.kind ?? null}
                    />
                  </div>
                </div>
                <p class="event-actor">
                  <span class="visually-hidden">{t("By", locale)}</span>
                  <span class="actor-identity" data-testid="audit-actor">
                    {#if impersonated}
                      <UserSentence
                        template={impersonated.template}
                        users={impersonated.users}
                        {locale}
                      />
                    {:else}
                      <UserLabel user={actor} {locale} />
                    {/if}
                  </span>
                  {#if filterUser}
                    {@const role = auditUserRoleSentence(
                      event,
                      filterUser,
                      actor,
                      locale,
                    )}
                    {#if role}
                      <span class="user-role" data-testid="audit-user-role">
                        <UserSentence
                          template={role.template}
                          users={role.users}
                          {locale}
                        />
                      </span>
                    {/if}
                  {/if}
                  <span
                    class="client-chip"
                    data-audit-client={event.client.kind}
                    title={event.client.user_agent ?? undefined}
                  >
                    <ClientIcon kind={event.client.kind} />
                    <span class="client-name"
                      >{clientLabel(event.client, locale)}</span
                    >
                  </span>
                </p>
                <div class="event-meta">
                  <time
                    datetime={event.created_at}
                    title={eventDate(event.created_at)}
                    >{eventTime(event.created_at)}</time
                  >
                  <div class="event-diff-slot">
                    {#if event.media}
                      <AuditMediaPreview media={event.media} {locale} />
                    {:else if !event.has_changes}
                      <span
                        class="no-details"
                        data-audit-no-details
                        title={t("No details recorded", locale)}
                        ><span class="visually-hidden"
                          >{t("No details recorded", locale)}</span
                        ></span
                      >
                    {/if}
                    {#if event.has_changes}
                      <a
                        class="ui-button ui-button--ghost ui-button--sm show-diff"
                        href={`/app/admin/audit/${event.id}`}
                        aria-label={t("Show diff", locale)}
                        title={t("Show diff", locale)}
                      >
                        <NavIcon name="diff" />
                      </a>
                    {/if}
                  </div>
                </div>
              </li>
            {/each}
          </ol>
        </section>
      {/each}
    </div>

    {#if pageMeta.page > 1 || pageMeta.has_more}
      <nav class="page-controls" aria-label={t("Audit pages", locale)}>
        <SharedButton
          variant="secondary"
          type="button"
          disabled={pageMeta.page <= 1}
          onclick={() =>
            void updateFilters({ ...filters, page: pageMeta.page - 1 })}
          icon="previous"
        >
          {t("Previous page", locale)}
        </SharedButton>
        <span>{t("Page", locale)} {pageMeta.page}</span>
        <SharedButton
          variant="secondary"
          type="button"
          disabled={!pageMeta.has_more}
          onclick={() =>
            void updateFilters({ ...filters, page: pageMeta.page + 1 })}
        >
          <span class="button-content">
            <span>{t("Next page", locale)}</span>
            <NavIcon name="next" />
          </span>
        </SharedButton>
      </nav>
    {/if}
  {/if}
</section>

<style>
  .audit-heading {
    margin-bottom: 1rem;
  }

  /* One quiet filter bar instead of a boxed form. */
  .audit-filters {
    display: flex;
    flex-wrap: wrap;
    align-items: end;
    gap: 0.6rem 0.75rem;
    margin: 0 0 1.5rem;
  }
  .audit-user-filter {
    display: flex;
    margin: -0.5rem 0 1rem;
  }
  .user-role {
    display: block;
    min-width: 0;
    margin-top: 0.1rem;
    overflow: hidden;
    color: var(--ink-muted);
    font-size: 0.78rem;
    line-height: 1.2;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .filter-field {
    display: grid;
    gap: 0.3rem;
    min-width: 11rem;
  }
  .filter-field--grow {
    flex: 1 1 14rem;
  }
  .filter-field > span {
    color: var(--ink-muted);
    font-size: 0.8rem;
    font-weight: 650;
  }

  .event-days {
    display: grid;
    gap: 1.4rem;
  }
  .day-heading {
    position: sticky;
    z-index: 1;
    top: var(--app-header-height, 0px);
    margin: 0 0 0.45rem;
    padding: 0.35rem 0;
    color: var(--ink-muted);
    background: var(--canvas);
    font-size: 0.82rem;
    font-weight: 700;
    letter-spacing: 0.02em;
  }
  .event-list {
    display: grid;
    margin: 0;
    padding: 0;
    list-style: none;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
    overflow: hidden;
  }

  /* One line per event: what happened, to what, by whom, when. Every cell keeps a fixed height
     and truncates long names, so all rows are the same height and the times line up. */
  .event-row {
    display: grid;
    grid-template-columns:
      clamp(9.5rem, 17vw, 15rem) minmax(0, 1fr) minmax(0, 14rem)
      auto;
    grid-template-areas: "action resource actor meta";
    align-items: center;
    gap: 1rem;
    min-height: 3.75rem;
    padding: 0.35rem 0.5rem 0.35rem 0.9rem;
    transition: background-color 120ms ease;
  }
  .event-row:hover {
    background: var(--surface-raised);
  }
  .event-row + .event-row {
    border-top: 1px solid var(--line);
  }
  .event-action {
    grid-area: action;
    min-width: 0;
  }
  /* A long action name is cut with an ellipsis (its full text is the tooltip) so every row
     keeps the same height. */
  .event-action :global(.audit-action-badge) {
    white-space: nowrap;
  }
  .event-action :global(.audit-action-badge > span) {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .event-resource {
    grid-area: resource;
    display: grid;
    gap: 0.25rem;
    min-width: 0;
  }
  /* Half of the note line and the gap: the card stays centered without media. */
  .event-resource.no-media :global(.resource-card) {
    transform: translateY(1rem);
  }
  .event-resource :global(.resource-card__link) {
    grid-template-columns: 2rem minmax(0, 1fr);
    gap: 0.6rem;
  }
  .event-resource :global(.resource-card__image) {
    width: 2rem;
    border-radius: 0.45rem;
  }
  .event-resource :global(.resource-card__name) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    overflow-wrap: normal;
  }
  /* Every row reserves the thumbnail's height here, so rows keep one height
     with or without a picture (the admin journey checks this at 320px). */
  .event-note {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    min-height: 1.75rem;
    min-width: 0;
  }
  .event-thumb {
    flex: none;
    width: 1.75rem;
    height: 1.75rem;
    border-radius: 0.45rem;
    object-fit: cover;
    background: var(--surface-raised);
  }
  .impersonation-event {
    display: grid;
    min-width: 0;
    gap: 0.15rem;
    margin: 0;
  }
  .impersonation-event span {
    color: var(--ink-muted);
    overflow-wrap: anywhere;
  }
  .event-actor {
    grid-area: actor;
    min-width: 0;
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }
  .actor-identity {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.45rem;
  }
  .actor-identity :global(.user-avatar) {
    flex: none;
  }
  .client-chip {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.3rem;
    margin-top: 0.1rem;
    font-size: 0.78rem;
    line-height: 1.2;
  }
  .client-name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .event-meta {
    grid-area: meta;
    display: grid;
    /* One icon button (44px); a fixed column keeps the times lined up from row to row. */
    grid-template-columns: 5rem 2.75rem;
    align-items: center;
    gap: 0.25rem;
  }
  .event-meta time {
    white-space: nowrap;
    color: var(--ink-muted);
    font-size: 0.88rem;
    font-variant-numeric: tabular-nums;
    text-align: right;
  }
  .event-diff-slot {
    display: flex;
    min-height: 44px;
    align-items: center;
    justify-content: end;
  }
  .no-details {
    color: var(--ink-muted);
    font-size: 0.8rem;
    text-align: right;
  }
  .show-diff {
    width: 44px;
    min-height: 44px;
    padding: 0;
    justify-content: center;
  }
  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }

  .button-content {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.45rem;
  }
  .button-content :global(svg) {
    flex: 0 0 1rem;
    width: 1rem;
    height: 1rem;
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
    margin: 1.25rem 0;
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
    /* Two short rows: entity, then action with the Filter button beside it. The labels stay
       for screen readers; the select and the placeholder say what each field is. */
    .audit-filters {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.5rem;
      margin-bottom: 1.1rem;
    }
    .filter-field {
      grid-column: 1 / -1;
      min-width: 0;
    }
    .filter-field--grow {
      grid-column: 1;
    }
    .filter-field > span {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    /* Phones: badge and time on top, then the resource, then who and the diff link. The
       badge can shrink with its own ellipsis; time and the diff link keep their columns. */
    .event-row {
      grid-template-columns: minmax(0, 1fr) auto;
      grid-template-areas:
        "action meta"
        "resource resource"
        "actor actor";
      gap: 0.4rem 0.75rem;
      padding: 0.6rem 0.75rem;
    }
    /* Fixed columns keep the time and the diff link at the same spot in every row, with or
       without a diff. */
    .event-meta {
      grid-template-columns: 4.5rem 2.75rem;
      justify-self: end;
    }
    .event-action {
      justify-self: start;
      max-width: 100%;
    }
    .event-actor {
      padding-right: 0;
    }
  }

  @media (max-width: 480px) {
    /* Long action labels get a full row. Keep time and diff together on their own row so
       actor names retain the full width and rows stay aligned. */
    .event-row {
      grid-template-columns: minmax(0, 1fr) auto;
      grid-template-areas:
        "action action"
        "meta meta"
        "resource resource"
        "actor actor";
    }
    .event-meta {
      align-self: center;
    }
  }

  /* Match the activity feed's icon rail while keeping audit details and actions. */
  .event-list {
    border: 0;
    border-radius: 0;
    background: transparent;
    overflow: visible;
  }
  .event-row {
    position: relative;
    grid-template-columns: 2.1rem minmax(0, 1fr) auto;
    grid-template-areas: "action resource meta" "action actor meta";
    align-items: start;
    gap: 0.15rem 0.75rem;
    min-height: 3.4rem;
    padding: 0.3rem 0;
  }
  .event-row + .event-row {
    border-top: 0;
  }
  .event-row::before {
    content: "";
    position: absolute;
    left: 1.05rem;
    top: 0;
    bottom: 0;
    width: 2px;
    background: var(--line);
  }
  .event-row:first-child::before {
    top: 1.35rem;
  }
  .event-row:last-child::before {
    bottom: calc(100% - 1.35rem);
  }
  .event-row:only-child::before {
    display: none;
  }
  .event-action {
    position: relative;
    z-index: 0;
  }
  .event-action {
    --action-color: var(--ink-muted);
    display: grid;
    place-items: center;
    box-sizing: border-box;
    width: 2.1rem;
    height: 2.1rem;
    color: var(--action-color);
    border-radius: 50%;
    background: color-mix(in srgb, var(--action-color) 14%, var(--surface));
  }
  .event-action[data-tone="create"] {
    --action-color: var(--success);
  }
  .event-action[data-tone="update"] {
    --action-color: var(--info);
  }
  .event-action[data-tone="upload"] {
    --action-color: var(--warning);
  }
  .event-action[data-tone="delete"] {
    --action-color: var(--danger);
  }
  .event-action :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }
  .event-action-label :global(.audit-action-badge) {
    border: 0;
    padding: 0;
    background: none;
    font-size: 0.82rem;
    font-weight: 700;
  }
  .event-action-label :global(.audit-action-badge > svg) {
    display: none;
  }
  .event-resource.no-media :global(.resource-card) {
    transform: none;
  }
  .event-note {
    min-height: 0;
  }
  .event-note:empty {
    display: none;
  }
  .event-actor {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.25rem 0.65rem;
    font-size: 0.82rem;
  }
  .client-chip {
    margin-top: 0;
  }
  .event-meta {
    align-self: start;
  }
  @media (max-width: 850px) {
    .event-row {
      grid-template-columns: 2.1rem minmax(0, 1fr) auto;
      grid-template-areas: "action resource meta" "action actor meta";
      padding: 0.3rem 0;
    }
    .event-action {
      justify-self: auto;
    }
    .event-meta {
      grid-template-columns: auto 2.75rem;
    }
  }
</style>
