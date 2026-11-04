<script lang="ts">
  import { resolve } from "$app/paths";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import {
    activityAgo,
    activityDetail,
    activitySentence,
    loadActivity,
    mergeActivity,
    type ActivityItem,
  } from "./activity";

  let { ownerId, locale }: { ownerId: number; locale: Locale } = $props();

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
    loadActivity(own.signal)
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
      const page = await loadActivity(own.signal, nextBefore);
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

  const title = $derived(t("Recent activity", locale));
</script>

<section class="activity" aria-labelledby="home-activity-title">
  <div class="activity__heading">
    <h2 id="home-activity-title" class="activity__title">
      <span class="activity__badge"><NavIcon name="audit" /></span>{title}
    </h2>
  </div>

  {#if status === "loading"}
    <ul
      class="activity__list"
      aria-hidden="true"
      data-testid="activity-loading"
    >
      {#each [0, 1, 2] as row (row)}
        <li class="activity__row activity__row--placeholder">
          <span class="activity__thumb"></span>
          <span class="activity__copy">
            <span class="placeholder-line"></span>
            <span class="placeholder-line placeholder-line--short"></span>
          </span>
        </li>
      {/each}
    </ul>
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
    <ul class="activity__list" aria-label={title}>
      {#each items as item (item.id)}
        {@const sentence = activitySentence(item, locale)}
        {@const detail = activityDetail(item, locale)}
        {@const ago = activityAgo(item.created_at, locale)}
        {#snippet row()}
          <span class="activity__thumb" data-kind={item.entity_type}>
            {#if item.thumbnail_url}
              <img src={item.thumbnail_url} alt="" loading="lazy" />
            {:else}
              <NavIcon
                name={item.entity_type === "project" ? "projects" : "yarns"}
              />
            {/if}
          </span>
          <span class="activity__copy">
            <span class="activity__sentence"
              >{sentence.before}{#if sentence.name !== null}<strong
                  class="activity__name">{sentence.name}</strong
                >{/if}{sentence.after}</span
            >
            {#if detail || !item.exists}
              <span class="activity__detail"
                >{#if !item.exists}<span class="activity__gone"
                    >{t("deleted", locale)}</span
                  >{#if detail}
                    ·
                  {/if}{/if}{detail}</span
              >
            {/if}
          </span>
          <time class="activity__time" datetime={item.created_at}>{ago}</time>
        {/snippet}
        <li class="activity__item" data-deleted={!item.exists ? "" : undefined}>
          {#if item.exists && item.href}
            <a
              class="activity__row"
              href={item.entity_type === "project"
                ? resolve("/projects/[id]", { id: String(item.entity_id) })
                : resolve("/yarns/[id]", { id: String(item.entity_id) })}
              >{@render row()}</a
            >
          {:else}
            <div class="activity__row">{@render row()}</div>
          {/if}
        </li>
      {/each}
    </ul>
    {#if moreFailed}
      <div class="activity__error" role="alert">
        <p>{t("Recent activity could not be loaded.", locale)}</p>
        <Button icon="reset" onclick={showMore}>
          {t("Try again", locale)}
        </Button>
      </div>
    {:else if hasMore}
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
    gap: 0.7rem;
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
  .activity__list {
    display: grid;
    gap: 0.45rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .activity__row {
    display: grid;
    grid-template-columns: 2.75rem minmax(0, 1fr) auto;
    align-items: center;
    gap: 0.8rem;
    min-height: 44px;
    border-radius: var(--radius-md);
    padding: 0.5rem 0.8rem 0.5rem 0.5rem;
    color: var(--ink);
    background: var(--surface);
    text-decoration: none;
  }
  a.activity__row:hover {
    background: color-mix(in srgb, var(--surface) 88%, var(--accent) 12%);
  }
  a.activity__row:focus-visible {
    outline: 3px solid var(--accent);
    outline-offset: 2px;
  }
  .activity__thumb {
    display: grid;
    width: 2.75rem;
    height: 2.75rem;
    place-items: center;
    overflow: hidden;
    border-radius: 0.8rem;
    color: var(--on-primary-container);
    background: var(--primary-container);
  }
  .activity__thumb[data-kind="yarn"] {
    color: var(--on-lagoon-container);
    background: var(--lagoon-container);
  }
  .activity__thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .activity__thumb :global(svg) {
    width: 1.35rem;
    height: 1.35rem;
  }
  .activity__copy {
    display: grid;
    min-width: 0;
    gap: 0.1rem;
  }
  .activity__sentence {
    overflow-wrap: anywhere;
    font-size: 0.97rem;
    line-height: 1.3;
  }
  .activity__name {
    font-weight: 800;
  }
  .activity__detail {
    color: var(--ink-muted);
    font-size: 0.85rem;
    line-height: 1.3;
    overflow-wrap: anywhere;
  }
  .activity__gone {
    font-weight: 700;
  }
  .activity__time {
    align-self: start;
    padding-top: 0.1rem;
    color: var(--ink-muted);
    font-size: 0.8rem;
    white-space: nowrap;
  }
  .activity__item[data-deleted] .activity__name {
    text-decoration: line-through;
    text-decoration-thickness: 1.5px;
  }
  .activity__item[data-deleted] .activity__row {
    color: var(--ink-muted);
    background: color-mix(in srgb, var(--surface) 55%, transparent);
  }
  .activity__item[data-deleted] .activity__thumb {
    opacity: 0.6;
    filter: grayscale(0.6);
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
  .activity__row--placeholder .activity__thumb,
  .placeholder-line {
    background: color-mix(in srgb, var(--ink-muted) 18%, transparent);
  }
  .placeholder-line {
    display: block;
    width: 70%;
    height: 0.95rem;
    border-radius: 0.4rem;
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
  @media (min-width: 900px) {
    .activity__list {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
  @media (max-width: 420px) {
    .activity__row {
      grid-template-columns: 2.5rem minmax(0, 1fr);
      row-gap: 0.1rem;
    }
    .activity__thumb {
      grid-row: span 2;
    }
    .activity__time {
      grid-column: 2;
      padding-top: 0;
    }
    .activity__thumb {
      width: 2.5rem;
      height: 2.5rem;
    }
  }
</style>
