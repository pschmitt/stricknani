<script lang="ts">
  import { onMount } from "svelte";
  import type { Snippet } from "svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";

  let {
    label,
    children,
  }: {
    label: string;
    children: Snippet<[close: () => void]>;
  } = $props();

  let details = $state<HTMLDetailsElement>();
  let trigger = $state<HTMLElement>();
  let panel = $state<HTMLDivElement>();
  let panelLeft = $state(0);
  let panelTop = $state(0);
  let positioned = $state(false);

  const viewportGutter = 8;
  const anchorGap = 6;

  function close(restoreFocus = true) {
    if (!details?.open) return;
    details.open = false;
    positioned = false;
    if (restoreFocus) trigger?.focus({ preventScroll: true });
  }

  function positionPanel() {
    if (!details?.open || !trigger || !panel) return;

    const anchor = trigger.getBoundingClientRect();
    const bounds = panel.getBoundingClientRect();
    const maxLeft = Math.max(
      viewportGutter,
      window.innerWidth - bounds.width - viewportGutter,
    );
    const left = Math.min(
      Math.max(anchor.right - bounds.width, viewportGutter),
      maxLeft,
    );
    const maxTop = Math.max(
      viewportGutter,
      window.innerHeight - bounds.height - viewportGutter,
    );
    let top = anchor.bottom + anchorGap;

    if (top + bounds.height > window.innerHeight - viewportGutter) {
      top = anchor.top - bounds.height - anchorGap;
    }

    panelLeft = left;
    panelTop = Math.min(Math.max(top, viewportGutter), maxTop);
    positioned = true;
  }

  function handleToggle() {
    if (details?.open) {
      positionPanel();
    } else {
      positioned = false;
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (!details?.open || event.key !== "Escape") return;

    event.preventDefault();
    event.stopPropagation();
    close();
  }

  function handleOutsidePointer(event: PointerEvent) {
    if (!details?.open || !(event.target instanceof Node)) return;
    if (!details.contains(event.target)) close(false);
  }

  function handleViewportChange() {
    if (details?.open) positionPanel();
  }

  onMount(() => {
    window.addEventListener("keydown", handleKeydown, true);
    window.addEventListener("pointerdown", handleOutsidePointer, true);
    window.addEventListener("resize", handleViewportChange);
    window.addEventListener("scroll", handleViewportChange, true);
    window.visualViewport?.addEventListener("resize", handleViewportChange);
    window.visualViewport?.addEventListener("scroll", handleViewportChange);

    return () => {
      window.removeEventListener("keydown", handleKeydown, true);
      window.removeEventListener("pointerdown", handleOutsidePointer, true);
      window.removeEventListener("resize", handleViewportChange);
      window.removeEventListener("scroll", handleViewportChange, true);
      window.visualViewport?.removeEventListener(
        "resize",
        handleViewportChange,
      );
      window.visualViewport?.removeEventListener(
        "scroll",
        handleViewportChange,
      );
    };
  });
</script>

<details class="action-overflow" bind:this={details} ontoggle={handleToggle}>
  <summary
    class="action-overflow__trigger"
    bind:this={trigger}
    aria-label={label}
  >
    <NavIcon name="more" />
    <span class="action-overflow__visually-hidden">{label}</span>
  </summary>
  <div
    class="action-overflow__panel"
    class:action-overflow__panel--positioned={positioned}
    bind:this={panel}
    style:left={`${panelLeft}px`}
    style:top={`${panelTop}px`}
  >
    {@render children(close)}
  </div>
</details>

<style>
  .action-overflow {
    position: relative;
    display: inline-flex;
    flex: 0 0 auto;
  }

  .action-overflow__trigger {
    display: inline-flex;
    width: 44px;
    min-width: 44px;
    height: 44px;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    color: var(--accent-strong);
    background: var(--surface);
    cursor: pointer;
    list-style: none;
    -webkit-tap-highlight-color: transparent;
  }

  .action-overflow__trigger::-webkit-details-marker {
    display: none;
  }

  .action-overflow__trigger::marker {
    content: "";
  }

  .action-overflow__trigger:hover {
    border-color: var(--control-border);
    background: var(--accent-wash);
  }

  .action-overflow__trigger:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 3px;
  }

  .action-overflow__trigger :global(svg) {
    width: var(--ui-action-icon-size-md);
    height: var(--ui-action-icon-size-md);
    stroke-width: 2;
  }

  .action-overflow__panel {
    position: fixed;
    z-index: 30;
    box-sizing: border-box;
    width: max-content;
    min-width: min(13rem, calc(100vw - 1rem));
    max-width: min(22rem, calc(100vw - 1rem));
    max-height: min(24rem, calc(100vh - 1rem));
    max-height: min(24rem, calc(100dvh - 1rem));
    overflow: auto;
    overscroll-behavior: contain;
    padding: 0.4rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    background: var(--surface-raised);
    box-shadow: var(--shadow);
    visibility: hidden;
  }

  .action-overflow[open] .action-overflow__panel--positioned {
    visibility: visible;
  }

  .action-overflow__panel :global(.ui-button) {
    width: 100%;
    justify-content: flex-start;
  }

  .action-overflow__visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    clip-path: inset(50%);
  }

  @media (prefers-reduced-motion: reduce) {
    .action-overflow__trigger {
      transition: none;
    }
  }
</style>
