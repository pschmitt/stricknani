<script lang="ts">
  import type { Snippet } from "svelte";
  import { MediaQuery } from "svelte/reactivity";
  import { headerSlot } from "#lib/header-slot.svelte.ts";

  const generatedId = $props.id();

  let {
    title,
    titleId = generatedId,
    context,
    actions,
    stackedActions = false,
    headerActions = false,
  }: {
    title: string;
    titleId?: string;
    context?: Snippet;
    actions?: Snippet;
    stackedActions?: boolean;
    /** On phones, show the actions in the app header bar instead. */
    headerActions?: boolean;
  } = $props();

  // Same breakpoint as the layout's rail: without it the header bar spans the
  // page and has room on its right for the page's actions.
  const phone = new MediaQuery("max-width: 760px");
  let headerEl = $state<HTMLElement>();
  let actionsEl = $state<HTMLDivElement>();
  const inBar = $derived(
    headerActions && phone.current && headerSlot.target !== null,
  );

  // Moves the one actions element (with its state, open menus and dialogs)
  // between the bar and this header; it is the only node of its block, so
  // Svelte still removes it wherever it is.
  $effect(() => {
    const element = actionsEl;
    const target = headerSlot.target;
    const home = headerEl;
    if (!element || !home || !target || !inBar) return;
    target.append(element);
    return () => {
      if (element.parentNode === target) home.append(element);
    };
  });
</script>

<header
  class="page-header"
  class:page-header--has-actions={actions && !inBar}
  class:page-header--stacked={stackedActions}
  aria-labelledby={titleId}
  bind:this={headerEl}
>
  <h1 id={titleId}>{title}</h1>
  {#if context}
    <div class="page-header__context">{@render context()}</div>
  {/if}
  {#if actions}
    <div class="page-header__actions" bind:this={actionsEl}>
      {@render actions()}
    </div>
  {/if}
</header>

<style>
  .page-header {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, auto);
    align-items: start;
    gap: 0.4rem 1.25rem;
    text-align: left;
  }

  h1 {
    grid-column: 1;
    grid-row: 1;
    min-width: 0;
    margin: 0;
    font-size: 2.5rem;
    font-weight: 700;
    letter-spacing: -0.02em;
    line-height: 1.1;
    text-align: left;
    overflow-wrap: anywhere;
  }

  .page-header__context {
    grid-column: 1 / -1;
    grid-row: 2;
    min-width: 0;
    max-width: 70ch;
    color: var(--ink-muted);
  }

  .page-header__context :global(p) {
    margin: 0;
  }

  .page-header__actions {
    grid-column: 2;
    grid-row: 1;
    display: flex;
    min-width: 0;
    max-width: 100%;
    align-items: center;
    justify-content: flex-end;
    flex-wrap: wrap;
    gap: 0.6rem;
  }

  :global(.page-header__actions > *) {
    flex: 0 0 auto;
    min-width: 0;
    max-width: 100%;
  }

  :global(.page-header__actions :where(a, button)) {
    max-width: 100%;
  }

  @media (max-width: 760px) {
    .page-header {
      grid-template-columns: minmax(0, 1fr);
      align-items: start;
      gap: 0.55rem 0.75rem;
    }

    h1 {
      font-size: 1.5rem;
      overflow-wrap: break-word;
    }

    .page-header__context {
      grid-column: 1 / -1;
    }

    .page-header__actions {
      width: 100%;
      max-width: 100%;
      justify-content: flex-end;
    }

    .page-header--has-actions:not(.page-header--stacked) {
      grid-template-columns: minmax(0, 1fr) minmax(0, 45%);
    }

    .page-header--stacked {
      grid-template-columns: minmax(0, 1fr);
    }

    .page-header--stacked .page-header__actions {
      grid-column: 1;
      grid-row: 3;
      justify-content: flex-start;
    }
  }
</style>
