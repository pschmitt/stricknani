<script lang="ts">
  import { onMount } from "svelte";
  import NavIcon from "./NavIcon.svelte";
  import type { NavIconName } from "./NavIcon.svelte";

  type Option = { value: string; label: string; icon?: NavIconName };
  let {
    label,
    value,
    options,
    disabled = false,
    onselect,
  }: {
    label: string;
    value: string;
    options: Option[];
    disabled?: boolean;
    onselect: (value: string) => void;
  } = $props();

  let root = $state<HTMLDivElement | null>(null);
  let open = $state(false);
  let active = $state(0);
  let upward = $state(false);
  const selected = $derived(
    Math.max(
      0,
      options.findIndex((item) => item.value === value),
    ),
  );

  function close(focusTrigger = false) {
    open = false;
    if (focusTrigger) root?.querySelector("button")?.focus();
  }
  function showMenu() {
    active = selected;
    const rect = root?.getBoundingClientRect();
    const estimatedHeight = Math.min(options.length * 45 + 12, 280);
    upward = Boolean(
      rect &&
      window.innerHeight - rect.bottom < estimatedHeight + 12 &&
      rect.top > estimatedHeight + 12,
    );
    open = true;
    queueMicrotask(() =>
      root?.querySelectorAll<HTMLElement>("[role=option]")[active]?.focus(),
    );
  }
  function choose(index: number) {
    const option = options[index];
    if (!option) return;
    onselect(option.value);
    close(true);
  }
  function keydown(event: KeyboardEvent) {
    if (event.key === "Escape" && open) {
      event.preventDefault();
      close(true);
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!open) {
        showMenu();
      } else {
        active =
          (active + (event.key === "ArrowDown" ? 1 : options.length - 1)) %
          options.length;
        root?.querySelectorAll<HTMLElement>("[role=option]")[active]?.focus();
      }
    } else if (event.key === "Enter" || event.key === " ") {
      if (!open) {
        event.preventDefault();
        showMenu();
      } else {
        event.preventDefault();
        choose(active);
      }
    } else if (event.key === "Home" && open) {
      event.preventDefault();
      active = 0;
      root?.querySelectorAll<HTMLElement>("[role=option]")[active]?.focus();
    } else if (event.key === "End" && open) {
      event.preventDefault();
      active = options.length - 1;
      root?.querySelectorAll<HTMLElement>("[role=option]")[active]?.focus();
    }
  }

  onMount(() => {
    const outside = (event: PointerEvent) => {
      if (root && !root.contains(event.target as Node)) close();
    };
    const focusOutside = (event: FocusEvent) => {
      if (root && !root.contains(event.target as Node)) close();
    };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("focusin", focusOutside);
    return () => {
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("focusin", focusOutside);
    };
  });
</script>

<div class="fancy-select" bind:this={root}>
  <button
    type="button"
    class="trigger"
    aria-label={label}
    aria-haspopup="listbox"
    aria-expanded={open}
    {disabled}
    onkeydown={keydown}
    onclick={() => {
      if (open) close();
      else showMenu();
    }}
  >
    {#if options[selected]?.icon}<NavIcon name={options[selected].icon!} />{/if}
    <span>{options[selected]?.label ?? value}</span>
    <NavIcon name="chevron-down" />
  </button>
  {#if open}
    <div class="menu" class:upward role="listbox" aria-label={label}>
      {#each options as option, index (option.value)}
        <button
          type="button"
          role="option"
          aria-selected={option.value === value}
          class:active={index === active}
          onkeydown={keydown}
          onclick={() => choose(index)}
          onfocus={() => (active = index)}
        >
          {#if option.icon}<NavIcon name={option.icon} />{:else}<span
              class="icon-space"
            ></span>{/if}
          <span>{option.label}</span>
          {#if option.value === value}<NavIcon name="check" />{/if}
        </button>
      {/each}
    </div>
  {/if}
</div>

<style>
  .fancy-select {
    position: relative;
    display: inline-flex;
    min-width: 10.5rem;
    color: var(--ink);
  }
  .trigger,
  .menu button {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    width: 100%;
    color: inherit;
    font: inherit;
    font-weight: 650;
    text-align: left;
  }
  .trigger {
    min-height: 2.75rem;
    border: 1px solid var(--line);
    border-radius: 0.85rem;
    padding: 0.55rem 0.75rem;
    background: var(--surface-raised);
    box-shadow: 0 1px 2px color-mix(in srgb, var(--ink) 7%, transparent);
    cursor: pointer;
    transition:
      border-color 150ms ease,
      box-shadow 150ms ease;
  }
  .trigger:hover:not(:disabled),
  .trigger[aria-expanded="true"] {
    border-color: var(--accent-strong);
  }
  .trigger:focus-visible,
  .menu button:focus-visible {
    outline: 2px solid var(--accent-strong);
    outline-offset: 2px;
  }
  .trigger:disabled {
    cursor: not-allowed;
    opacity: 0.65;
  }
  .trigger span {
    flex: 1;
  }
  .trigger :global(svg),
  .menu button :global(svg) {
    width: 1.05rem;
    height: 1.05rem;
    flex: none;
  }
  .trigger :global(svg:last-child) {
    color: var(--ink-muted);
    transition: transform 150ms ease;
  }
  .trigger[aria-expanded="true"] :global(svg:last-child) {
    transform: rotate(180deg);
  }
  .menu {
    position: absolute;
    z-index: 30;
    inset: calc(100% + 0.35rem) 0 auto;
    padding: 0.35rem;
    border: 1px solid var(--line);
    border-radius: 0.95rem;
    background: var(--surface-raised);
    box-shadow: 0 12px 30px color-mix(in srgb, var(--ink) 18%, transparent);
    animation: pop-in 130ms ease-out;
  }
  .menu.upward {
    inset: auto 0 calc(100% + 0.35rem);
  }
  .menu button {
    min-height: 2.6rem;
    padding: 0.5rem 0.65rem;
    border: 0;
    border-radius: 0.65rem;
    background: transparent;
    cursor: pointer;
  }
  .menu button:hover,
  .menu button.active {
    background: color-mix(in srgb, var(--accent) 11%, var(--surface-raised));
  }
  .menu button[aria-selected="true"] {
    color: var(--accent-strong);
  }
  .menu button span:nth-child(2) {
    flex: 1;
  }
  .menu button :global(svg:last-child) {
    width: 0.95rem;
    height: 0.95rem;
  }
  .icon-space {
    width: 1.05rem;
    flex: none;
  }
  @keyframes pop-in {
    from {
      opacity: 0;
      transform: translateY(-0.2rem) scale(0.98);
    }
    to {
      opacity: 1;
      transform: none;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    *,
    *::before,
    *::after {
      animation-duration: 0.01ms !important;
      transition-duration: 0.01ms !important;
    }
  }
</style>
