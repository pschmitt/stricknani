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
    type ChangeItemV1,
    type ChangeValue,
  } from "./admin-api";
  import { wordDiff, type DiffSegment } from "./word-diff";

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

  type ChangeKind = "added" | "removed" | "changed";

  function isEmptyValue(value: ChangeValue): boolean {
    switch (value.type) {
      case "text":
      case "integer":
        return value.value === null;
      case "legacy_text":
        return value.value === "";
      case "text_list":
      case "yarns":
      case "steps":
        return value.value.length === 0;
      default:
        return false;
    }
  }

  function changeKind(item: ChangeItemV1): ChangeKind {
    if (isEmptyValue(item.before) && !isEmptyValue(item.after)) return "added";
    if (!isEmptyValue(item.before) && isEmptyValue(item.after))
      return "removed";
    return "changed";
  }

  function kindLabel(kind: ChangeKind): string {
    return kind === "added"
      ? t("Added", locale)
      : kind === "removed"
        ? t("Removed", locale)
        : t("Changed", locale);
  }

  /** Word-level highlights for both sides, or null when there is nothing to compare. */
  function segments(item: ChangeItemV1): {
    before: DiffSegment[];
    after: DiffSegment[];
  } {
    const before = displayValue(item.before);
    const after = displayValue(item.after);
    const comparable =
      !isEmptyValue(item.before) &&
      !isEmptyValue(item.after) &&
      item.before.type !== "boolean" &&
      item.before.type !== "link_changed";
    if (!comparable) {
      return {
        before: [{ text: before, changed: false }],
        after: [{ text: after, changed: false }],
      };
    }
    return wordDiff(before, after);
  }

  /** "+2" / "−3" for counts and other numbers. */
  function delta(item: ChangeItemV1): string | null {
    if (item.before.type !== "integer" || item.after.type !== "integer")
      return null;
    if (item.before.value === null || item.after.value === null) return null;
    const difference = item.after.value - item.before.value;
    if (difference === 0) return null;
    return difference > 0 ? `+${difference}` : `\u2212${-difference}`;
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
          {@const kind = changeKind(item)}
          {@const parts = segments(item)}
          {@const change = delta(item)}
          <li class="change" data-kind={kind}>
            <header class="change-head">
              <h2>{fieldLabel(item.field)}</h2>
              <span class="change-kind">{kindLabel(kind)}</span>
              {#if change}
                <span class="change-delta">{change}</span>
              {/if}
            </header>
            <div class="change-values">
              <section class="side side--before">
                <h3>
                  <span class="sign" aria-hidden="true">&minus;</span>
                  {t("Before", locale)}
                </h3>
                <!-- prettier-ignore -->
                <p class="value" class:value--empty={isEmptyValue(item.before)}>{#each parts.before as part, partIndex (partIndex)}{#if part.changed}<del>{part.text}</del>{:else}{part.text}{/if}{/each}</p>
              </section>
              <span class="change-arrow" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="18" height="18">
                  <path
                    d="M5 12h12m-5-5 5 5-5 5"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2.2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>
              <section class="side side--after">
                <h3>
                  <span class="sign" aria-hidden="true">+</span>
                  {t("After", locale)}
                </h3>
                <!-- prettier-ignore -->
                <p class="value" class:value--empty={isEmptyValue(item.after)}>{#each parts.after as part, partIndex (partIndex)}{#if part.changed}<ins>{part.text}</ins>{:else}{part.text}{/if}{/each}</p>
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
  .change-list .change {
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
  .event-card :global(.resource-card__name) {
    margin: 0.9rem 0 0.4rem;
    font-size: 1.1rem;
  }
  .change-list {
    display: grid;
    gap: 0.9rem;
    padding: 0;
    margin: 0;
    list-style: none;
    counter-reset: change;
  }
  .change-list .change {
    --change-tone: var(--info);
    position: relative;
    padding: 1rem 1.1rem 1.1rem;
    border-radius: var(--radius-lg, 1.4rem);
    border-color: color-mix(in srgb, var(--change-tone) 22%, var(--line));
    background: linear-gradient(
      180deg,
      color-mix(in srgb, var(--change-tone) 7%, var(--surface)),
      var(--surface) 70%
    );
  }
  .change[data-kind="added"] {
    --change-tone: var(--success);
  }
  .change[data-kind="removed"] {
    --change-tone: var(--danger);
  }
  .change-head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin-bottom: 0.85rem;
  }
  .change-head h2 {
    margin: 0;
    font-size: 1.08rem;
    overflow-wrap: anywhere;
  }
  .change-kind,
  .change-delta {
    border-radius: 999px;
    padding: 0.12rem 0.6rem;
    font-size: 0.8rem;
    font-weight: 700;
    color: var(--change-tone);
    background: color-mix(in srgb, var(--change-tone) 14%, var(--surface));
  }
  .change-delta {
    font-variant-numeric: tabular-nums;
  }
  .change-values {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
    align-items: stretch;
    gap: 0.6rem;
  }
  .side {
    --side-tone: var(--danger);
    min-width: 0;
    display: flex;
    flex-direction: column;
    border-radius: var(--radius-md, 1rem);
    border: 1px dashed color-mix(in srgb, var(--side-tone) 40%, transparent);
    background: color-mix(in srgb, var(--side-tone) 7%, var(--surface));
    padding: 0.55rem 0.8rem 0.75rem;
  }
  .side--after {
    --side-tone: var(--success);
  }
  .side h3 {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    margin: 0 0 0.35rem;
    font-size: 0.82rem;
    color: var(--side-tone);
  }
  .sign {
    display: inline-grid;
    place-items: center;
    width: 1.25rem;
    height: 1.25rem;
    border-radius: 50%;
    font-weight: 800;
    line-height: 1;
    color: var(--surface);
    background: var(--side-tone);
  }
  .value {
    margin: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    line-height: 1.55;
  }
  .value--empty {
    color: var(--muted);
    font-style: italic;
  }
  .value del,
  .value ins {
    border-radius: 0.35rem;
    padding: 0.05rem 0.2rem;
    box-decoration-break: clone;
    -webkit-box-decoration-break: clone;
  }
  .value del {
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 18%, transparent);
    text-decoration: line-through;
    text-decoration-thickness: 2px;
    text-decoration-color: color-mix(in srgb, var(--danger) 70%, transparent);
  }
  .value ins {
    color: var(--success);
    background: color-mix(in srgb, var(--success) 20%, transparent);
    text-decoration: none;
    font-weight: 700;
  }
  .change-arrow {
    align-self: center;
    display: grid;
    place-items: center;
    width: 2rem;
    height: 2rem;
    border-radius: 50%;
    color: var(--change-tone);
    background: color-mix(in srgb, var(--change-tone) 14%, var(--surface));
  }
  @media (max-width: 38rem) {
    .change-values {
      grid-template-columns: 1fr;
    }
    .change-arrow {
      justify-self: center;
      transform: rotate(90deg);
      width: 1.6rem;
      height: 1.6rem;
    }
  }
</style>
