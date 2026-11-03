<script lang="ts" generics="V extends string | number">
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";

  type Option = { value: V; label: string; icon?: NavIconName };

  let {
    options,
    value,
    label,
    disabled = false,
    onchange,
  }: {
    options: Option[];
    value: V;
    label: string;
    disabled?: boolean;
    onchange: (value: V) => void;
  } = $props();
</script>

<div class="segmented" role="group" aria-label={label}>
  {#each options as option (option.value)}
    <button
      type="button"
      class="segmented__option"
      aria-pressed={value === option.value}
      {disabled}
      onclick={() => onchange(option.value)}
    >
      {#if option.icon}<NavIcon name={option.icon} />{/if}
      <span>{option.label}</span>
    </button>
  {/each}
</div>

<style>
  .segmented {
    display: inline-flex;
    max-width: 100%;
    align-items: stretch;
    gap: 0.15rem;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.2rem;
    background: var(--surface-raised);
  }

  .segmented__option {
    display: inline-flex;
    min-width: 2.75rem;
    min-height: 40px;
    flex: 1 1 auto;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    border: 0;
    border-radius: 999px;
    padding-inline: 0.85rem;
    color: var(--ink-muted);
    background: transparent;
    font: inherit;
    font-size: 0.9rem;
    font-weight: 700;
    white-space: nowrap;
    cursor: pointer;
    transition:
      background-color 120ms ease,
      color 120ms ease;
    -webkit-tap-highlight-color: transparent;
  }

  .segmented__option :global(svg) {
    flex: none;
    width: var(--ui-action-icon-size-sm);
    height: var(--ui-action-icon-size-sm);
  }

  .segmented__option:hover:not(:disabled) {
    color: var(--ink);
  }

  .segmented__option[aria-pressed="true"] {
    color: var(--accent-strong);
    background: var(--surface);
    box-shadow:
      0 0 0 1px var(--accent-strong),
      0 1px 3px rgb(63 20 80 / 14%);
  }

  .segmented__option:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 1px;
  }

  .segmented__option:disabled {
    cursor: not-allowed;
    opacity: 0.62;
  }

  @media (prefers-reduced-motion: reduce) {
    .segmented__option {
      transition: none;
    }
  }
</style>
