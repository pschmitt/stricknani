<script lang="ts">
  import SharedButton from "#lib/components/Button.svelte";
  import { onDestroy } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import AuditActionBadge from "./AuditActionBadge.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    getAuditEventDetail,
    type AuditEventDetail,
    type ChangeValue,
  } from "./admin-api";

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

  function displayValue(value: ChangeValue): string {
    switch (value.type) {
      case "text":
      case "integer":
      case "boolean":
      case "legacy_text":
        return value.value === null
          ? t("Not provided", locale)
          : String(value.value);
      case "text_list":
      case "yarns":
        return value.value.length
          ? value.value.join("\n")
          : t("Empty list", locale);
      case "steps":
        return value.value.length
          ? value.value
              .map(
                (step) =>
                  `${step.number}. ${step.title}${step.description ? `\n${step.description}` : ""}`,
              )
              .join("\n\n")
          : t("Empty list", locale);
      case "link_changed":
        return value.value
          ? t("Link present", locale)
          : t("Link not present", locale);
    }
  }

  function fieldLabel(field: string): string {
    const labels: Record<string, string> = {
      "project.name": "Project name",
      "project.category": "Category",
      "project.yarn": "Yarn",
      "project.yarns": "Linked yarns",
      "project.needles": "Needles",
      "project.stitch_sample": "Stitch sample",
      "project.description": "Description",
      "project.notes": "Notes",
      "project.other_materials": "Other materials",
      "project.tags": "Tags",
      "project.steps": "Steps",
      "project.step_count": "Step count",
      "project.image_count": "Image count",
      "project.attachment_count": "Attachment count",
      "project.is_ai_enhanced": "AI enhanced",
      "project.link": "Original source link",
      "yarn.name": "Yarn name",
      "yarn.description": "Description",
      "yarn.brand": "Brand",
      "yarn.colorway": "Colorway",
      "yarn.dye_lot": "Dye lot",
      "yarn.fiber_content": "Fiber content",
      "yarn.weight_category": "Weight category",
      "yarn.recommended_needles": "Recommended needles",
      "yarn.weight_grams": "Weight",
      "yarn.length_meters": "Length",
      "yarn.notes": "Notes",
      "yarn.photo_count": "Photo count",
      "yarn.is_ai_enhanced": "AI enhanced",
      "yarn.link": "Original source link",
      "category.name": "Category name",
    };
    return t(labels[field] ?? "Recorded field", locale);
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
          name={detail.event.actor_email ?? t("Unknown actor", locale)}
        />
        <span>{detail.event.actor_email ?? t("Unknown actor", locale)}</span>
        <time datetime={detail.event.created_at}
          >{dateLabel(detail.event.created_at)}</time
        >
      </div>
      <AuditActionBadge action={detail.event.action} {locale} />
      <ResourceCard
        kind={resourceKind}
        name={detail.event.resource.name ??
          `${entityLabel(detail.event.entity_type)} #${detail.event.entity_id}`}
        href={detail.event.resource.href === eventDetailHref
          ? null
          : detail.event.resource.href}
        imageUrl={detail.event.resource.preview_url}
        subtitle={!detail.event.resource.exists
          ? t("Resource unavailable", locale)
          : null}
        titleTag="h2"
      />
    </article>

    {#if detail.changes}
      <ol class="change-list" aria-label={t("Recorded changes", locale)}>
        {#each detail.changes.items as item, index (`${index}:${item.field}`)}
          <li>
            <h2>{fieldLabel(item.field)}</h2>
            <div class="change-values">
              <section>
                <h3>{t("Before", locale)}</h3>
                <pre>{displayValue(item.before)}</pre>
              </section>
              <section>
                <h3>{t("After", locale)}</h3>
                <pre>{displayValue(item.after)}</pre>
              </section>
            </div>
          </li>
        {/each}
      </ol>
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
  .event-card,
  .change-list li {
    padding: 1rem;
    border: 1px solid var(--line);
    border-radius: 0.9rem;
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
  .event-card :global(.resource-card__name) {
    margin: 0.9rem 0 0.4rem;
    font-size: 1.1rem;
  }
  .change-list {
    display: grid;
    gap: 0.8rem;
    padding: 0;
    margin: 0;
    list-style: none;
  }
  .change-list h2 {
    margin: 0 0 0.7rem;
    font-size: 1rem;
    overflow-wrap: anywhere;
  }
  .change-values {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.75rem;
  }
  .change-values section {
    min-width: 0;
  }
  .change-values h3 {
    margin: 0 0 0.35rem;
    font-size: 0.9rem;
    color: var(--muted);
  }
  pre {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font: inherit;
    padding: 0.7rem;
    margin: 0;
    min-height: 2.5rem;
    border-radius: 0.55rem;
    background: var(--surface-raised);
  }
  @media (max-width: 38rem) {
    .change-values {
      grid-template-columns: 1fr;
    }
  }
</style>
