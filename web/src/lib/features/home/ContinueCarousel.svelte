<script lang="ts">
  import { tick } from "svelte";
  import { resolve } from "$app/paths";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import EmptyState from "#lib/components/EmptyState.svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import type { Locale } from "#lib/i18n.svelte.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import type { ProjectSummary } from "#lib/features/projects/project-api.ts";
  import { activeSlide, slideForKey } from "./continue-carousel";
  import type { SectionStatus } from "./home-section.svelte";

  // "Continue knitting": owned and explicitly shared projects in progress as large cards in a
  // row that snaps card by card, with a peek of the next one. The cards are plain links, so Tab,
  // Enter and the arrow keys all work without a widget role.
  let {
    status,
    items,
    locale,
    updatedAgo,
    retry,
  }: {
    status: SectionStatus;
    items: ProjectSummary[];
    locale: Locale;
    updatedAgo: (value: string) => string;
    retry: () => void;
  } = $props();

  const seeAllHref = `${resolve("/projects")}?status=in_progress`;

  let scroller = $state<HTMLElement>();
  let active = $state(0);
  const many = $derived(items.length > 1);

  function slides(): HTMLElement[] {
    return scroller
      ? Array.from(scroller.children as HTMLCollectionOf<HTMLElement>)
      : [];
  }

  function updateActive() {
    if (!scroller) return;
    const origin = slides()[0]?.offsetLeft ?? 0;
    const offsets = slides().map((slide) =>
      Math.abs(slide.offsetLeft - origin),
    );
    active = activeSlide(
      offsets,
      Math.abs(scroller.scrollLeft),
      scroller.clientWidth,
      scroller.scrollWidth,
    );
  }

  let frame = 0;
  function onScroll() {
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      updateActive();
    });
  }

  // The list can shrink (a project was finished on another device): keep the position valid.
  $effect(() => {
    const count = items.length;
    if (active > count - 1) active = Math.max(0, count - 1);
    if (scroller && count > 0) void tick().then(updateActive);
  });

  function reduceMotion(): boolean {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  function show(index: number, focus = false) {
    const slide = slides()[index];
    if (!slide) return;
    slide.scrollIntoView({
      behavior: reduceMotion() ? "auto" : "smooth",
      block: "nearest",
      inline: "start",
    });
    if (focus)
      slide.querySelector<HTMLElement>("a")?.focus({ preventScroll: true });
    active = index;
  }

  function onKeydown(event: KeyboardEvent) {
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey)
      return;
    const current = slides().findIndex((slide) =>
      slide.contains(event.target as Node),
    );
    if (current < 0) return;
    const rtl =
      getComputedStyle(event.currentTarget as HTMLElement).direction === "rtl";
    const next = slideForKey(event.key, current, items.length, rtl);
    if (next === null) return;
    event.preventDefault();
    show(next, true);
  }
</script>

<section class="continue" aria-labelledby="home-continue-title">
  <div class="continue__heading">
    <h2 id="home-continue-title" class="continue__title">
      <span class="continue__badge"><NavIcon name="projects" /></span>{t(
        "Continue knitting",
        locale,
      )}
    </h2>
    {#if status === "ready" && many}
      <span class="continue__count" aria-hidden="true"
        >{active + 1} / {items.length}</span
      >
      <span class="continue__arrows">
        <button
          type="button"
          class="continue__arrow"
          aria-label={t("Previous project", locale)}
          disabled={active === 0}
          onclick={() => show(active - 1)}
        >
          <span class="continue__arrow-icon continue__arrow-icon--back"
            ><NavIcon name="next" /></span
          >
        </button>
        <button
          type="button"
          class="continue__arrow"
          aria-label={t("Next project", locale)}
          disabled={active === items.length - 1}
          onclick={() => show(active + 1)}
        >
          <NavIcon name="next" />
        </button>
      </span>
    {/if}
    {#if status === "ready" && items.length > 0}
      <a class="see-all" href={seeAllHref}
        >{t("See all", locale)}<NavIcon name="next" /></a
      >
    {/if}
  </div>

  {#if status === "loading"}
    <div class="continue-card continue-card--placeholder" aria-hidden="true">
      <span class="continue-card__image"></span>
      <span class="continue-card__copy">
        <span class="placeholder-line"></span>
        <span class="placeholder-line placeholder-line--short"></span>
      </span>
    </div>
    <p class="visually-hidden" role="status">
      {t("Loading ongoing projects…", locale)}
    </p>
  {:else if status === "error"}
    <div class="continue__error" role="alert">
      <p>{t("Ongoing projects could not be loaded.", locale)}</p>
      <Button icon="reset" onclick={retry}>{t("Try again", locale)}</Button>
    </div>
  {:else if items.length > 0}
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <ul
      class="continue__track"
      class:continue__track--single={!many}
      aria-label={t("Continue knitting", locale)}
      bind:this={scroller}
      onscroll={onScroll}
      onkeydown={onKeydown}
    >
      {#each items as item, index (item.id)}
        <li class="continue__slide">
          <a
            class="continue-card"
            href={resolve("/projects/[id]", { id: String(item.id) })}
          >
            <span class="continue-card__image">
              {#if item.preview_url}
                <img
                  src={item.preview_url}
                  alt=""
                  loading={index === 0 ? "eager" : "lazy"}
                  draggable="false"
                />
              {:else}
                <Mascot size={72} interactive="decorative" />
              {/if}
            </span>
            <span class="continue-card__copy">
              <span class="continue-card__name">{item.name}</span>
              <span class="continue-card__meta">
                {#if item.category}<span class="pill">{item.category}</span
                  >{/if}
                <span class="pill"
                  >{t("Updated", locale)} {updatedAgo(item.updated_at)}</span
                >
              </span>
            </span>
            <span class="continue-card__go" aria-hidden="true">
              <NavIcon name="next" />
            </span>
          </a>
        </li>
      {/each}
    </ul>
  {:else}
    <EmptyState
      icon="projects"
      tone="projects"
      headingTag="p"
      title={t("Nothing on the needles yet", locale)}
      hint={t("No projects in progress.", locale)}
    >
      <a
        class="ui-button ui-button--primary ui-button--md"
        href={resolve("/create")}
      >
        <ActionLabel icon="create">{t("New project", locale)}</ActionLabel>
      </a>
      <a
        class="ui-button ui-button--secondary ui-button--md"
        href={resolve("/projects")}
      >
        <ActionLabel icon="projects">{t("Browse projects", locale)}</ActionLabel
        >
      </a>
    </EmptyState>
  {/if}
</section>

<style>
  .continue {
    display: grid;
    min-width: 0;
    gap: 0.7rem;
  }

  .continue__heading {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .continue__title {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    margin: 0 auto 0 0;
    font-size: 1.35rem;
    line-height: 1.3;
  }
  .continue__badge {
    display: grid;
    width: 1.9rem;
    height: 1.9rem;
    flex: none;
    place-items: center;
    border-radius: 50%;
    color: var(--tone-home);
    background: var(--tone-home-wash);
  }
  .continue__badge :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }
  .continue__count {
    flex: none;
    color: var(--ink-muted);
    white-space: nowrap;
    font-size: 0.95rem;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
  }
  .continue__arrows {
    display: none;
    gap: 0.35rem;
  }
  /* Mouse users have no swipe: two arrows beside the counter. Phones go without. */
  @media (hover: hover) and (pointer: fine) and (min-width: 600px) {
    .continue__arrows {
      display: inline-flex;
    }
  }
  .continue__arrow {
    display: grid;
    width: 2.75rem;
    height: 2.75rem;
    place-items: center;
    border: 0;
    border-radius: 999px;
    color: var(--accent-strong);
    background: var(--surface);
    cursor: pointer;
  }
  .continue__arrow:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .continue__arrow :global(svg) {
    width: 1.2rem;
    height: 1.2rem;
  }
  .continue__arrow-icon--back {
    display: grid;
    transform: scaleX(-1);
  }

  .see-all {
    display: inline-flex;
    flex: none;
    align-items: center;
    gap: 0.15rem;
    min-height: 44px;
    color: var(--accent-strong);
    padding-inline: 0.5rem;
    font-size: 0.95rem;
    font-weight: 800;
    text-decoration: none;
  }
  .see-all:hover,
  .see-all:focus-visible {
    text-decoration: underline;
    text-underline-offset: 0.18em;
  }
  .see-all :global(svg) {
    width: 1rem;
    height: 1rem;
  }

  /* The row of cards. The padding leaves room for the hover lift and the focus ring, which the
     scroll container would otherwise clip; the negative margin gives the space back. */
  .continue__track {
    display: flex;
    gap: 0.9rem;
    margin: -0.5rem 0;
    padding: 0.5rem 0;
    list-style: none;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scroll-snap-type: x mandatory;
    scrollbar-width: none;
  }
  .continue__track::-webkit-scrollbar {
    display: none;
  }
  /* The next card sticks out by a few rem: that is what says "swipe". */
  .continue__slide {
    display: grid;
    flex: 0 0 min(calc(100% - 3rem), 34rem);
    min-width: 0;
    scroll-snap-align: start;
  }
  .continue__track--single .continue__slide {
    flex-basis: 100%;
  }
  .continue__error {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.7rem;
  }
  .continue__error p {
    margin: 0;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }

  /* The card: a patch sewn onto the page. */
  .continue-card {
    position: relative;
    display: grid;
    grid-template-columns: 8.5rem minmax(0, 1fr) auto;
    align-items: center;
    gap: 1.4rem;
    border-radius: var(--radius-lg);
    padding: 1.25rem 1.5rem 1.25rem 1.25rem;
    color: var(--on-primary-container);
    background: var(--primary-container);
    text-decoration: none;
    transition: transform 220ms cubic-bezier(0.3, 1.6, 0.5, 1);
  }
  /* The running stitch: a dashed line sewn just inside the edge. */
  .continue-card::after {
    position: absolute;
    inset: 7px;
    border: 1.6px dashed var(--stitch);
    border-radius: 21px;
    content: "";
    pointer-events: none;
  }
  a.continue-card:hover {
    transform: translateY(-2px);
  }
  a.continue-card:hover .continue-card__image {
    transform: rotate(-1deg) scale(1.03);
  }
  a.continue-card:active {
    transform: scale(0.97);
  }
  a.continue-card:focus-visible {
    outline-offset: 3px;
  }
  .continue-card__image {
    position: relative;
    display: grid;
    aspect-ratio: 1;
    place-items: center;
    overflow: hidden;
    border-radius: 18px;
    color: var(--accent-strong);
    background: var(--surface);
    box-shadow: 0 6px 16px rgb(63 20 80 / 18%);
    transform: rotate(-4deg);
    transition: transform 220ms cubic-bezier(0.3, 1.6, 0.5, 1);
  }
  .continue-card__image img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .continue-card__image :global(svg) {
    width: 2.5rem;
    height: 2.5rem;
  }
  .continue-card__copy {
    display: grid;
    min-width: 0;
    gap: 0.4rem;
  }
  .continue-card__name {
    font-family: var(--font-display);
    font-size: clamp(1.4rem, 1.1rem + 1.2vw, 1.9rem);
    font-weight: 700;
    line-height: 1.15;
    font-variation-settings:
      "SOFT" 100,
      "WONK" 1;
    overflow-wrap: anywhere;
  }
  .continue-card__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.2rem 0.8rem;
    font-size: 0.85rem;
    font-weight: 800;
  }
  .pill {
    border-radius: 999px;
    padding: 0.15rem 0.7rem;
    background: color-mix(in srgb, var(--surface) 72%, transparent);
    color: var(--ink);
  }
  .continue-card__go {
    display: grid;
    place-items: center;
    width: 2.5rem;
    height: 2.5rem;
    border-radius: 999px;
    color: var(--on-accent);
    background: var(--accent);
  }
  .continue-card__go :global(svg) {
    width: 1.2rem;
    height: 1.2rem;
  }
  .continue-card--placeholder .continue-card__image,
  .placeholder-line {
    background: color-mix(in srgb, var(--surface) 60%, transparent);
  }
  .placeholder-line {
    display: block;
    width: 60%;
    height: 1.2rem;
    border-radius: 0.4rem;
  }
  .placeholder-line--short {
    width: 35%;
    height: 0.9rem;
  }

  @media (max-width: 600px) {
    .continue-card {
      grid-template-columns: 6.5rem minmax(0, 1fr);
      gap: 0.9rem;
      padding: 1rem;
    }
    .continue-card__go {
      display: none;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .continue-card,
    .continue-card__image {
      transition: none;
    }
    a.continue-card:hover,
    a.continue-card:active {
      transform: none;
    }
    a.continue-card:hover .continue-card__image {
      transform: rotate(-4deg);
    }
  }
</style>
