<script lang="ts">
  import type { Snippet } from "svelte";
  import { resolve } from "$app/paths";
  import { publishFeedback } from "#lib/feedback.ts";
  import { session } from "#lib/session.svelte.ts";
  import { restoreTrashItem } from "../trash/trash-api";
  import { restoreErrorMessage } from "../trash/trash-undo";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import UserLabel from "#lib/components/UserLabel.svelte";
  import UserSentence from "#lib/components/UserSentence.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { auditActionTone } from "#lib/features/admin/audit-action.ts";
  import {
    ACTIVITY_FULL_PAGE_SIZE,
    ACTIVITY_PAGE_SIZE,
    activityDetail,
    activityHeadline,
    activityIcon,
    activitySharing,
    activityItemActor,
    activityOwner,
    activityResourceName,
    activityStateNote,
    activityTime,
    groupActivity,
    loadActivity,
    mergeActivity,
    type ActivityItem,
  } from "./activity";

  // Home shows the newest page only and links to the full page (`seeAll`, rendered next to the
  // heading while there is activity). The full page (`full`) pages with "Show more" and leaves
  // its heading to the page header.
  let {
    ownerId,
    locale,
    full = false,
    seeAll,
  }: {
    ownerId: number;
    locale: Locale;
    full?: boolean;
    seeAll?: Snippet;
  } = $props();
  const pageSize = $derived(
    full ? ACTIVITY_FULL_PAGE_SIZE : ACTIVITY_PAGE_SIZE,
  );

  let items = $state<ActivityItem[]>([]);
  let status = $state<"loading" | "ready" | "error">("loading");
  let hasMore = $state(false);
  let nextBefore = $state<number | null>(null);
  let loadingMore = $state(false);
  let moreFailed = $state(false);
  let attempt = $state(0);
  let controller: AbortController | null = null;

  // First page: reloaded when the user changes or on retry.
  $effect(() => {
    void ownerId;
    void attempt;
    const own = new AbortController();
    controller?.abort();
    controller = own;
    status = "loading";
    items = [];
    hasMore = false;
    nextBefore = null;
    moreFailed = false;
    loadActivity(own.signal, null, pageSize)
      .then((page) => {
        if (own.signal.aborted) return;
        items = page.items;
        hasMore = page.has_more;
        nextBefore = page.next_before;
        status = "ready";
      })
      .catch(() => {
        if (!own.signal.aborted) status = "error";
      });
    return () => own.abort();
  });

  async function showMore() {
    if (loadingMore || nextBefore === null) return;
    const own = controller;
    if (!own) return;
    loadingMore = true;
    moreFailed = false;
    try {
      const page = await loadActivity(own.signal, nextBefore, pageSize);
      if (own.signal.aborted) return;
      items = mergeActivity(items, page.items);
      hasMore = page.has_more;
      nextBefore = page.next_before;
    } catch {
      if (!own.signal.aborted) moreFailed = true;
    } finally {
      loadingMore = false;
    }
  }

  let restoringId = $state<number | null>(null);

  async function restoreItem(item: ActivityItem): Promise<void> {
    const currentSession = session.data;
    if (!currentSession?.user || restoringId !== null) return;
    restoringId = item.id;
    try {
      await restoreTrashItem(item.entity_type, item.entity_id, currentSession);
      publishFeedback({
        ownerId: currentSession.user.id,
        kind: "restored",
        message: t("Restored from the trash.", locale),
      });
      attempt += 1;
    } catch (error) {
      publishFeedback({
        ownerId: currentSession.user.id,
        kind: "error",
        message: restoreErrorMessage(error, locale),
      });
    } finally {
      restoringId = null;
    }
  }

  const title = $derived(t(full ? "My activity" : "Recent activity", locale));
  const days = $derived(groupActivity(items, locale));
</script>

<section
  class="activity"
  aria-labelledby={full ? undefined : "home-activity-title"}
  aria-label={full ? title : undefined}
>
  {#if !full}
    <div class="activity__heading">
      <h2 id="home-activity-title" class="activity__title">
        <span class="activity__badge"><NavIcon name="audit" /></span>{title}
      </h2>
      {#if seeAll && status === "ready" && items.length > 0}
        {@render seeAll()}
      {/if}
    </div>
  {/if}

  {#if status === "loading"}
    <ol class="tl" aria-hidden="true" data-testid="activity-loading">
      <li class="tl__day">
        <div class="tl__daylabel tl__daylabel--placeholder">
          <span class="placeholder-line placeholder-line--label"></span>
        </div>
        <ol class="tl__entries">
          {#each [0, 1, 2] as row (row)}
            <li class="tl__entry">
              <div class="tl__row">
                <span class="tl__thumb tl__thumb--placeholder"></span>
                <span class="tl__copy">
                  <span class="placeholder-line"></span>
                  <span class="placeholder-line placeholder-line--short"></span>
                </span>
              </div>
            </li>
          {/each}
        </ol>
      </li>
    </ol>
    <p class="visually-hidden" role="status">
      {t("Loading recent activity…", locale)}
    </p>
  {:else if status === "error"}
    <div class="activity__error" role="alert">
      <p>{t("Recent activity could not be loaded.", locale)}</p>
      <Button icon="reset" onclick={() => (attempt += 1)}>
        {t("Try again", locale)}
      </Button>
    </div>
  {:else if items.length === 0}
    <p class="activity__empty">
      {t("Nothing yet - your changes will show up here.", locale)}
    </p>
  {:else}
    <ol class="tl" aria-label={title}>
      {#each days as day (day.key)}
        <li class="tl__day">
          {#if day.label}
            <h3 class="tl__daylabel">{day.label}</h3>
          {/if}
          <ol class="tl__entries">
            {#each day.items as item, index (item.id)}
              {@const name = activityResourceName(item, locale)}
              {@const headline = activityHeadline(item, locale)}
              {@const sharing = activitySharing(item, locale)}
              {@const actor = activityItemActor(item)}
              {@const owner = activityOwner(item, locale)}
              {@const detail = activityDetail(item, locale)}
              {@const note = activityStateNote(item, locale)}
              {@const tone = auditActionTone(item.action)}
              {@const icon = activityIcon(item.action)}
              {#snippet row()}
                {#if item.thumbnail_url}
                  <img
                    class="tl__thumb"
                    src={item.thumbnail_url}
                    alt=""
                    loading="lazy"
                    width="40"
                    height="40"
                  />
                {/if}
                <span class="tl__copy">
                  <span class="tl__sentence">{name}</span>
                  <span class="tl__meta">
                    <span class="tl__action"
                      >{#if sharing}<UserSentence
                          template={sharing.template}
                          users={sharing.users}
                          {locale}
                        />{:else}{headline}{/if}</span
                    >
                    <time datetime={item.created_at}
                      >{activityTime(item.created_at, locale)}</time
                    >
                    <span class="tl__actor"
                      ><UserLabel
                        user={actor}
                        {locale}
                        size="inline"
                        by
                      /></span
                    >
                    {#if detail}<span>{detail}</span>{/if}
                    {#if note}<span>{note}</span>{/if}
                    {#if owner}
                      <UserSentence
                        template={t("Owner: {name}", locale)}
                        users={{ name: owner }}
                        {locale}
                      />
                    {/if}
                  </span>
                </span>
              {/snippet}
              <li
                class="tl__entry"
                data-tone={tone}
                data-deleted={!item.exists && item.state !== "in_trash"
                  ? ""
                  : undefined}
                style:--i={Math.min(index, 8)}
              >
                <span class="tl__dot" aria-hidden="true">
                  {#if icon === "sparkle"}
                    <svg
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.8"
                      ><path
                        d="m12 3.5 1.9 5.6 5.6 1.9-5.6 1.9-1.9 5.6-1.9-5.6L4.5 11l5.6-1.9z"
                      /></svg
                    >
                  {:else}<NavIcon name={icon} />{/if}
                </span>
                {#if item.exists && item.href}
                  <a
                    class="tl__row"
                    href={item.entity_type === "project"
                      ? resolve("/projects/[id]", {
                          id: String(item.entity_id),
                        })
                      : resolve("/yarns/[id]", { id: String(item.entity_id) })}
                    >{@render row()}</a
                  >
                {:else}
                  <div class="tl__row">{@render row()}</div>
                {/if}
                {#if item.action === "trashed" && item.state === "in_trash" && item.trash !== null}
                  <p class="tl__actions">
                    <a
                      href={resolve("/trash")}
                      data-testid="activity-open-trash"
                      >{t("Open trash", locale)}</a
                    >
                    {#if item.trash?.can_restore}
                      <button
                        type="button"
                        class="tl__restore"
                        data-testid="activity-restore"
                        disabled={restoringId !== null}
                        onclick={() => void restoreItem(item)}
                      >
                        {restoringId === item.id
                          ? t("Restoring…", locale)
                          : t("Restore", locale)}
                      </button>
                    {/if}
                  </p>
                {/if}
              </li>
            {/each}
          </ol>
        </li>
      {/each}
    </ol>
    {#if moreFailed}
      <div class="activity__error" role="alert">
        <p>{t("Recent activity could not be loaded.", locale)}</p>
        <Button icon="reset" onclick={showMore}>
          {t("Try again", locale)}
        </Button>
      </div>
    {:else if full && hasMore}
      <div class="activity__more">
        <Button icon="expand" onclick={showMore} disabled={loadingMore}>
          {t("Show more", locale)}
        </Button>
      </div>
    {/if}
  {/if}
</section>

<style>
  .activity {
    display: grid;
    min-width: 0;
    max-width: 34rem;
    gap: 0.7rem;
  }
  .activity__heading {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.75rem;
  }
  .activity__title {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    margin: 0;
    font-size: 1.35rem;
    line-height: 1.3;
  }
  .activity__badge {
    display: grid;
    width: 1.9rem;
    height: 1.9rem;
    flex: none;
    place-items: center;
    border-radius: 50%;
    color: var(--tone-home);
    background: var(--tone-home-wash);
  }
  .activity__badge :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }

  /* The timeline, as in a project's "Recent changes": a rail with a dot coloured by what
     happened, entries grouped under day labels. */
  .tl {
    --rail: 0.45rem;
    --indent: 1.7rem;
    position: relative;
    margin: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: clamp(1rem, 3vw, 1.4rem);
    list-style: none;
    background: var(--surface);
  }
  /* The rail is cut into pieces (one per entry and day label) so it follows
     rows of any height; the first and last piece stop at their dot. */
  .tl__entry,
  .tl__daylabel {
    position: relative;
  }
  .tl__entry {
    --tone: var(--ink-muted);
    padding-left: var(--indent);
  }
  .tl__entry[data-tone="create"] {
    --tone: var(--success);
  }
  .tl__entry[data-tone="update"] {
    --tone: var(--info);
  }
  .tl__entry[data-tone="upload"] {
    --tone: var(--warning);
  }
  .tl__entry[data-tone="delete"] {
    --tone: var(--danger);
  }
  .tl__entry::before,
  .tl__daylabel::before {
    content: "";
    position: absolute;
    top: 0;
    bottom: 0;
    left: var(--rail);
    width: 2px;
    background: color-mix(
      in srgb,
      var(--tone, var(--ink-muted)) 28%,
      var(--line)
    );
  }
  .tl__day:first-child .tl__daylabel::before {
    display: none;
  }
  .tl__day:first-child .tl__entry:first-child::before {
    top: 1.35rem;
  }
  .tl__day:last-child .tl__entry:last-child::before {
    bottom: calc(100% - 1.35rem);
  }
  .tl__day:only-child .tl__entry:only-child::before {
    display: none;
  }
  .tl__dot {
    position: absolute;
    top: 1.05rem;
    left: 0;
    display: grid;
    place-items: center;
    width: 1.5rem;
    height: 1.5rem;
    transform: translate(-0.275rem, -0.275rem);
    color: var(--tone);
    border: 2px solid var(--surface);
    border-radius: 50%;
    background: var(--surface);
    box-shadow: 0 0 0 1px var(--tone);
  }
  .tl__dot :global(svg),
  .tl__dot svg {
    width: 1rem;
    height: 1rem;
  }
  .tl__entries {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .tl__daylabel {
    margin: 0;
    padding: 0.7rem 0 0.15rem var(--indent);
    color: var(--ink-muted);
    font-family: inherit;
    font-size: 0.78rem;
    font-weight: 700;
    letter-spacing: 0.04em;
    line-height: 1.1rem;
  }
  .tl__day:first-child .tl__daylabel {
    padding-top: 0;
  }
  .tl__daylabel--placeholder {
    padding-bottom: 0.3rem;
  }
  .tl__row {
    position: relative;
    display: flex;
    align-items: center;
    gap: 0.6rem;
    box-sizing: border-box;
    min-height: 44px;
    border-radius: var(--radius-xs);
    padding: 0.55rem 0.6rem;
    color: var(--ink);
    text-decoration: none;
  }
  a.tl__row:hover {
    background: var(--surface-raised);
  }
  a.tl__row:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }
  .tl__copy {
    display: grid;
    flex: 1;
    min-width: 0;
    gap: 0.15rem;
  }
  .tl__sentence {
    overflow-wrap: anywhere;
    font-weight: 650;
  }
  .tl__meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.2rem 0.6rem;
    color: var(--ink-muted);
    font-size: 0.85rem;
    overflow-wrap: anywhere;
  }
  .tl__action {
    color: var(--tone);
    font-weight: 700;
  }
  .tl__actor {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    min-width: 0;
  }
  .tl__meta time {
    font-variant-numeric: tabular-nums;
  }
  .tl__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.25rem 1rem;
    margin: 0;
    padding-left: 0.6rem;
    font-size: 0.88rem;
  }
  .tl__actions a,
  .tl__restore {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    border: 0;
    padding: 0;
    color: var(--accent-strong);
    background: none;
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }
  .tl__thumb {
    flex: none;
    width: 2.5rem;
    height: 2.5rem;
    border-radius: 0.6rem;
    object-fit: cover;
    background: var(--surface-raised);
  }
  .tl__entry[data-deleted] .tl__sentence {
    text-decoration: line-through;
    text-decoration-thickness: 1.5px;
  }
  .tl__entry[data-deleted] .tl__row {
    color: var(--ink-muted);
  }
  .tl__entry[data-deleted] .tl__thumb {
    opacity: 0.55;
    filter: grayscale(0.7);
  }
  .activity__empty {
    margin: 0;
    border-radius: var(--radius-xs);
    padding: 0.75rem 1rem;
    color: var(--ink-muted);
    background: var(--surface);
    font-size: 0.92rem;
  }
  .activity__more {
    display: flex;
    justify-content: center;
  }
  .activity__error {
    display: flex;
    min-width: 0;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.7rem;
  }
  .activity__error p {
    margin: 0;
  }
  .tl__thumb--placeholder,
  .placeholder-line {
    background: color-mix(in srgb, var(--ink-muted) 18%, transparent);
  }
  .placeholder-line {
    display: block;
    width: 70%;
    height: 0.95rem;
    border-radius: 0.4rem;
  }
  .placeholder-line--label {
    width: 3.5rem;
    height: 0.7rem;
  }
  .placeholder-line--short {
    width: 40%;
    height: 0.75rem;
    margin-top: 0.35rem;
  }
  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }
  @media (prefers-reduced-motion: no-preference) {
    .tl::before {
      transform-origin: top;
      animation: tl-rail 600ms ease-out both;
    }
    .tl__entry {
      animation: tl-in 260ms ease-out both;
      animation-delay: calc(var(--i, 0) * 35ms);
    }
    @keyframes tl-rail {
      from {
        transform: scaleY(0);
      }
    }
    @keyframes tl-in {
      from {
        opacity: 0;
        transform: translateY(6px);
      }
    }
  }
</style>
