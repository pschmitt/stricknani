<script lang="ts">
  import SharedButton from "#lib/components/Button.svelte";
  import { onDestroy } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import ChangeList from "#lib/features/history/ChangeList.svelte";
  import AuditActionBadge from "./AuditActionBadge.svelte";
  import AuditLifecycle from "./AuditLifecycle.svelte";
  import AuditMediaPreview from "./AuditMediaPreview.svelte";
  import ClientIcon from "./ClientIcon.svelte";
  import { clientLabel } from "./audit-client";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { getAuditEventDetail, type AuditEventDetail } from "./admin-api";

  let { eventId }: { eventId: number } = $props();
  let detail = $state<AuditEventDetail | null>(null);
  let loading = $state(true);
  let error = $state("");
  let retry = $state(0);
  let generation = 0;
  let ownerGeneration = 0;
  let currentOwner: number | null | undefined;
  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);

  $effect(() => {
    const ownerId = userId;
    const isAdmin = session.data?.user?.is_admin === true;
    retry;
    const request = ++generation;
    if (currentOwner !== ownerId) {
      currentOwner = ownerId;
      ownerGeneration += 1;
      detail = null;
    }
    const ownerRequest = ownerGeneration;
    const controller = new AbortController();
    error = "";
    if (
      ownerId === null ||
      !isAdmin ||
      !Number.isSafeInteger(eventId) ||
      eventId <= 0
    ) {
      loading = false;
      error = t("Administrator access is required.", locale);
      return () => controller.abort();
    }
    loading = true;
    void getAuditEventDetail(eventId, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          request !== generation ||
          ownerRequest !== ownerGeneration ||
          userId !== ownerId
        )
          return;
        detail = result;
        loading = false;
      })
      .catch((reason: unknown) => {
        if (
          controller.signal.aborted ||
          request !== generation ||
          ownerRequest !== ownerGeneration ||
          userId !== ownerId
        )
          return;
        detail = null;
        loading = false;
        error =
          reason instanceof ApiError && reason.status === 403
            ? t("Administrator access is required.", locale)
            : reason instanceof Error
              ? reason.message
              : t("Audit events could not be loaded.", locale);
      });
    return () => controller.abort();
  });

  onDestroy(() => {
    generation += 1;
    ownerGeneration += 1;
  });

  function entityLabel(entity: string): string {
    return entity === "project"
      ? t("Project", locale)
      : entity === "yarn"
        ? t("Yarn", locale)
        : entity === "category"
          ? t("Category", locale)
          : t("Resource", locale);
  }

  function dateLabel(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.valueOf())
      ? value
      : new Intl.DateTimeFormat(locale, {
          dateStyle: "medium",
          timeStyle: "short",
        }).format(date);
  }
</script>

<svelte:head
  ><title>Stricknani · {t("Recorded changes", locale)}</title></svelte:head
>

<section
  class="diff-page page-container page-container--standard"
  aria-labelledby="diff-title"
>
  <PageHeader title={t("Recorded changes", locale)} titleId="diff-title" />

  {#if loading}
    <p class="notice-panel" aria-live="polite">
      {t("Loading recorded changes…", locale)}
    </p>
  {:else if error}
    <section class="notice-panel" role="alert">
      <h2>{error}</h2>
      <SharedButton
        variant="secondary"
        type="button"
        onclick={() => (retry += 1)}
        icon="reset"
        >{t("Try again", locale)}
        ></SharedButton
      >
    </section>
  {:else if detail}
    {@const resourceKind = detail.event.resource.kind ?? "resource"}
    {@const eventDetailHref = `/app/admin/audit/${detail.event.id}`}
    <article class="event-card">
      <div class="event-actor">
        <UserAvatar
          avatarUrl={detail.event.actor_avatar_url}
          name={detail.event.actor_email ??
            (detail.event.system
              ? t("System", locale)
              : t("Unknown actor", locale))}
        />
        <span
          >{detail.event.actor_email ??
            (detail.event.system
              ? t("System", locale)
              : t("Unknown actor", locale))}</span
        >
        <time datetime={detail.event.created_at}
          >{dateLabel(detail.event.created_at)}</time
        >
      </div>
      <AuditActionBadge
        action={detail.event.action}
        {locale}
        system={detail.event.system}
        mediaKind={detail.event.media?.kind ?? null}
      />
      <ResourceCard
        kind={resourceKind}
        name={detail.event.resource.name ??
          `${entityLabel(detail.event.entity_type)} #${detail.event.entity_id}`}
        href={detail.event.resource.href === eventDetailHref
          ? null
          : detail.event.resource.href}
        imageUrl={detail.event.resource.preview_url}
        subtitle={detail.event.resource.state === "in_trash"
          ? t("In the trash", locale)
          : !detail.event.resource.exists
            ? t("Resource unavailable", locale)
            : null}
        titleTag="h2"
      />
      <AuditLifecycle
        event={detail.event}
        {locale}
        onChanged={() => (retry += 1)}
      />
      {#if detail.event.media}
        <div class="event-media">
          <AuditMediaPreview
            media={detail.event.media}
            {locale}
            variant="note"
          />
          <AuditMediaPreview media={detail.event.media} {locale} />
        </div>
      {/if}
      <dl class="client-details" data-audit-client-details>
        <dt>{t("Client", locale)}</dt>
        <dd class="client-kind">
          <ClientIcon kind={detail.event.client.kind} />
          <span>{clientLabel(detail.event.client, locale)}</span>
        </dd>
        {#if detail.event.client.version}
          <dt>{t("Version", locale)}</dt>
          <dd>{detail.event.client.version}</dd>
        {/if}
        {#if detail.event.client.user_agent}
          <dt>{t("User agent", locale)}</dt>
          <dd class="client-agent">{detail.event.client.user_agent}</dd>
        {/if}
      </dl>
    </article>

    {#if detail.changes}
      <ChangeList
        changes={detail.changes}
        {locale}
        media={detail.event.media_thumbnails}
      />
    {:else if detail.event.media}
      <!-- The file above is the whole record of an upload or removal. -->
    {:else}
      <p class="notice-panel">
        {#if detail.unavailable_reason === "not_recorded"}
          {t("No change snapshot was recorded for this event.", locale)}
        {:else if detail.unavailable_reason === "too_large"}
          {t("The recorded change snapshot is too large to display.", locale)}
        {:else if detail.unavailable_reason === "malformed"}
          {t("The recorded change snapshot is unavailable.", locale)}
        {:else}
          {t("No changes are available for this event.", locale)}
        {/if}
      </p>
    {/if}
  {/if}
</section>

<style>
  .diff-page {
    display: grid;
    gap: 1rem;
    padding-bottom: 3rem;
  }
  .event-card {
    padding: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
  }
  .event-actor {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    flex-wrap: wrap;
  }
  .event-actor time {
    margin-left: auto;
    color: var(--muted);
  }
  .event-media {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    margin-top: 0.5rem;
  }
  .client-details {
    display: grid;
    grid-template-columns: max-content minmax(0, 1fr);
    gap: 0.25rem 0.9rem;
    margin: 0.9rem 0 0;
    padding-top: 0.75rem;
    border-top: 1px solid var(--line);
    font-size: 0.9rem;
  }
  .client-details dt {
    color: var(--muted);
  }
  .client-details dd {
    margin: 0;
    min-width: 0;
  }
  .client-kind {
    display: flex;
    align-items: center;
    gap: 0.4rem;
  }
  .client-agent {
    overflow-wrap: anywhere;
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: 0.82rem;
  }
  .event-card :global(.resource-card__name) {
    margin: 0.9rem 0 0.4rem;
    font-size: 1.1rem;
  }
</style>
