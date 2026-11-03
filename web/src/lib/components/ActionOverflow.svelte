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

  const focusableSelector =
    'button:not(:disabled), a[href], [role="menuitem"]:not([aria-disabled="true"])';

  function focusableItems(): HTMLElement[] {
    return panel
      ? Array.from(panel.querySelectorAll<HTMLElement>(focusableSelector))
      : [];
  }

  function handleToggle() {
    if (details?.open) {
      positionPanel();
      // Move focus into the menu once it is visible.
      requestAnimationFrame(() => {
        if (!details?.open) return;
        const active = document.activeElement;
        if (!(active instanceof Node) || !panel?.contains(active)) {
          (
            panel?.querySelector<HTMLElement>('[role="menuitem"]') ??
            focusableItems()[0]
          )?.focus({ preventScroll: true });
        }
      });
    } else {
      positioned = false;
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (!details?.open) return;

    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close();
      return;
    }

    if (event.key === "Tab") {
      // The menu is transient: tabbing out closes it.
      close(false);
      return;
    }

    const keys = ["ArrowDown", "ArrowUp", "Home", "End"];
    if (!keys.includes(event.key)) return;
    const items = focusableItems();
    if (items.length === 0) return;
    event.preventDefault();
    const index = items.indexOf(document.activeElement as HTMLElement);
    let next = 0;
    if (event.key === "ArrowDown") next = (index + 1) % items.length;
    else if (event.key === "ArrowUp")
      next = (index - 1 + items.length) % items.length;
    else if (event.key === "End") next = items.length - 1;
    items[next]?.focus({ preventScroll: true });
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
    role="group"
    aria-label={label}
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
    padding: 0.5rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
    box-shadow:
      0 2px 6px rgb(63 20 80 / 8%),
      var(--shadow);
    visibility: hidden;
  }

  .action-overflow[open] .action-overflow__panel--positioned {
    visibility: visible;
  }

  .action-overflow__panel :global(.action-overflow__section) {
    display: grid;
    gap: 0.4rem;
    padding: 0.25rem 0.25rem 0.4rem;
  }

  .action-overflow__panel :global(.action-overflow__heading) {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.75rem;
    font-weight: 800;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }

  .action-overflow__panel :global(.action-overflow__divider) {
    height: 1px;
    margin: 0.2rem -0.5rem 0.4rem;
    border: 0;
    background: var(--line);
  }

  .action-overflow__panel :global(.action-overflow__menu) {
    display: grid;
    gap: 0.15rem;
  }

  .action-overflow__panel :global(.action-overflow__item) {
    display: flex;
    box-sizing: border-box;
    width: 100%;
    min-height: 44px;
    align-items: center;
    gap: 0.75rem;
    padding: 0.5rem 0.75rem;
    border: 0;
    border-radius: var(--radius-xs);
    color: var(--ink);
    background: transparent;
    font: inherit;
    font-weight: 700;
    text-align: left;
    text-decoration: none;
    cursor: pointer;
    transition: background-color 120ms ease;
    -webkit-tap-highlight-color: transparent;
  }

  .action-overflow__panel :global(.action-overflow__item svg) {
    flex: 0 0 auto;
    width: var(--ui-action-icon-size-md);
    height: var(--ui-action-icon-size-md);
    color: var(--accent-strong);
  }

  .action-overflow__panel :global(.action-overflow__item:hover),
  .action-overflow__panel :global(.action-overflow__item:focus-visible) {
    background: var(--accent-wash);
  }

  .action-overflow__panel :global(.action-overflow__item:focus-visible) {
    outline: 2px solid var(--focus);
    outline-offset: -2px;
  }

  .action-overflow__panel :global(.action-overflow__item:disabled),
  .action-overflow__panel
    :global(.action-overflow__item[aria-disabled="true"]) {
    color: var(--ink-muted);
    cursor: progress;
  }

  .action-overflow__panel :global(.action-overflow__item--danger),
  .action-overflow__panel :global(.action-overflow__item--danger svg) {
    color: var(--danger);
  }

  .action-overflow__panel :global(.action-overflow__segmented) {
    display: grid;
    grid-template-columns: 44px minmax(3.5rem, 1fr) 44px;
    align-items: stretch;
    overflow: hidden;
    border: 1px solid var(--control-border);
    border-radius: 999px;
    background: var(--surface);
  }

  .action-overflow__panel :global(.action-overflow__segment) {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    border: 0;
    color: var(--accent-strong);
    background: transparent;
    font: inherit;
    font-weight: 800;
    cursor: pointer;
    transition: background-color 120ms ease;
    -webkit-tap-highlight-color: transparent;
  }

  .action-overflow__panel :global(button.action-overflow__segment:hover),
  .action-overflow__panel
    :global(button.action-overflow__segment:focus-visible) {
    background: var(--accent-wash);
  }

  .action-overflow__panel
    :global(button.action-overflow__segment:focus-visible) {
    outline: 2px solid var(--focus);
    outline-offset: -3px;
  }

  .action-overflow__panel :global(.action-overflow__segment:disabled) {
    color: var(--ink-muted);
    opacity: 0.45;
    cursor: not-allowed;
  }

  .action-overflow__panel :global(.action-overflow__value) {
    border-inline: 1px solid var(--line);
    color: var(--ink);
    font-variant-numeric: tabular-nums;
    cursor: default;
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
    .action-overflow__trigger,
    .action-overflow__panel :global(.action-overflow__item),
    .action-overflow__panel :global(.action-overflow__segment) {
      transition: none;
    }
  }
</style>
