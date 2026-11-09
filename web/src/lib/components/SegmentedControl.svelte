<script lang="ts" generics="V extends string | number">
  import type { Snippet } from "svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";
  import {
    nextIndex,
    selectedIndex,
    tabStopIndex,
  } from "#lib/components/segmented.ts";

  type Option = {
    value: V;
    label: string;
    icon?: NavIconName;
    ariaLabel?: string;
    title?: string;
    disabled?: boolean;
  };

  /**
   * One single-choice toggle: a soft track with a filled thumb that slides to
   * the chosen option. A radio group: Tab reaches the chosen option, the arrow
   * keys, Home and End move the choice.
   */
  let {
    options,
    value,
    label,
    disabled = false,
    size = "regular",
    fullWidth = false,
    iconOnly = false,
    content,
    onchange,
  }: {
    options: Option[];
    value: V;
    label: string;
    disabled?: boolean;
    size?: "regular" | "compact";
    fullWidth?: boolean;
    /** Show only the icons; the labels stay as accessible names. */
    iconOnly?: boolean;
    /** Replaces the icon and label of an option. */
    content?: Snippet<[Option]>;
    onchange: (value: V) => void;
  } = $props();

  const selected = $derived(selectedIndex(options, value));
  const stop = $derived(tabStopIndex(options, selected));
  let buttons: HTMLButtonElement[] = [];

  function choose(index: number): void {
    const option = options[index];
    if (!option || disabled || option.disabled) return;
    if (option.value !== value) onchange(option.value);
  }

  function onkeydown(event: KeyboardEvent, index: number): void {
    if (disabled || event.altKey || event.ctrlKey || event.metaKey) return;
    const rtl =
      getComputedStyle(event.currentTarget as Element).direction === "rtl";
    const next = nextIndex(options, index, event.key, rtl);
    if (next === null) return;
    event.preventDefault();
    buttons[next]?.focus();
    choose(next);
  }
</script>

<div
  class="segmented"
  class:segmented--compact={size === "compact"}
  class:segmented--full={fullWidth}
  role="radiogroup"
  aria-label={label}
  style:--segments={options.length}
  style:--segment={Math.max(selected, 0)}
>
  {#if selected >= 0}<span class="segmented__thumb" aria-hidden="true"
    ></span>{/if}
  {#each options as option, index (option.value)}
    <button
      bind:this={buttons[index]}
      type="button"
      role="radio"
      class="segmented__option"
      class:segmented__option--on={index === selected}
      aria-checked={index === selected}
      aria-label={option.ariaLabel ?? (iconOnly ? option.label : undefined)}
      title={option.title ?? (iconOnly ? option.label : undefined)}
      tabindex={index === stop ? 0 : -1}
      disabled={disabled || option.disabled}
      onclick={() => choose(index)}
      onkeydown={(event) => onkeydown(event, index)}
    >
      {#if content}
        {@render content(option)}
      {:else}
        {#if option.icon}<NavIcon name={option.icon} />{/if}
        {#if !iconOnly}<span>{option.label}</span>{/if}
      {/if}
    </button>
  {/each}
</div>

<style>
  .segmented {
    --segmented-pad: 0.25rem;
    --segmented-height: 2.75rem;

    position: relative;
    display: inline-grid;
    max-width: 100%;
    grid-auto-columns: minmax(2.75rem, 1fr);
    grid-auto-flow: column;
    padding: var(--segmented-pad);
    border-radius: 999px;
    background: var(--surface-raised);
    box-shadow:
      inset 0 1px 3px color-mix(in srgb, var(--ink) 14%, transparent),
      inset 0 0 0 1px color-mix(in srgb, var(--line) 70%, transparent);
    isolation: isolate;
  }

  .segmented--compact {
    --segmented-height: 2.25rem;
    --segmented-pad: 0.2rem;
  }

  .segmented--full {
    display: grid;
    width: 100%;
  }

  .segmented__thumb {
    position: absolute;
    z-index: 0;
    top: var(--segmented-pad);
    bottom: var(--segmented-pad);
    left: var(--segmented-pad);
    width: calc((100% - 2 * var(--segmented-pad)) / var(--segments));
    border-radius: 999px;
    background: var(--accent-strong);
    box-shadow:
      0 1px 2px color-mix(in srgb, var(--accent-strong) 45%, transparent),
      0 3px 8px color-mix(in srgb, var(--accent-strong) 28%, transparent);
    transform: translateX(calc(var(--segment) * 100%));
    transition: transform 210ms cubic-bezier(0.34, 1.4, 0.64, 1);
    pointer-events: none;
  }

  .segmented__option {
    position: relative;
    z-index: 1;
    display: inline-flex;
    min-width: 0;
    min-height: var(--segmented-height);
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    border: 0;
    border-radius: 999px;
    padding-inline: 0.9rem;
    color: var(--ink-muted);
    background: transparent;
    font: inherit;
    font-size: 0.9rem;
    font-weight: 700;
    white-space: nowrap;
    cursor: pointer;
    transition: color 160ms ease;
    -webkit-tap-highlight-color: transparent;
  }

  .segmented__option :global(svg) {
    flex: none;
    width: var(--ui-action-icon-size-sm);
    height: var(--ui-action-icon-size-sm);
  }

  .segmented__option:hover:not(:disabled):not(.segmented__option--on) {
    color: var(--ink);
  }

  /*
   * The chosen option paints the thumb colour itself once the thumb has
   * arrived, so its label always sits on its own background.
   */
  .segmented__option--on {
    color: var(--on-accent);
    background: var(--accent-strong);
    transition:
      color 120ms ease 90ms,
      background-color 0s 210ms;
  }

  .segmented__option:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 1px;
  }

  .segmented__option:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }

  /* A compact option stays 44px tall to the finger: its hit area reaches past the track. */
  .segmented--compact .segmented__option::after {
    position: absolute;
    inset: -0.3rem 0;
    content: "";
  }

  @media (prefers-reduced-motion: reduce) {
    .segmented__thumb,
    .segmented__option {
      transition: none;
    }
  }
</style>
