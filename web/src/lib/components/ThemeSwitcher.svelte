<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { theme, setTheme } from "#lib/theme.svelte.ts";
  import { themeChoices, type ThemeChoice } from "#lib/theme.ts";

  let { locale, showLabels = false }: { locale: Locale; showLabels?: boolean } =
    $props();

  const icons = { system: "monitor", light: "sun", dark: "moon" } as const;
  const labels = $derived<Record<ThemeChoice, string>>({
    system: t("Follow system", locale),
    light: t("Light", locale),
    dark: t("Dark", locale),
  });
</script>

<div
  class="theme-switcher"
  class:with-labels={showLabels}
  role="group"
  aria-label={t("Theme", locale)}
>
  {#each themeChoices as choice (choice)}
    <button
      type="button"
      class="theme-option"
      aria-pressed={theme.choice === choice}
      aria-label={showLabels ? undefined : labels[choice]}
      title={labels[choice]}
      onclick={() => setTheme(choice)}
    >
      <NavIcon name={icons[choice]} />
      {#if showLabels}<span>{labels[choice]}</span>{/if}
    </button>
  {/each}
</div>

<style>
  .theme-switcher {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 0.15rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0.15rem;
    background: var(--surface);
  }

  .theme-option {
    display: inline-flex;
    min-width: 2.75rem;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    border: 0;
    border-radius: 0.5rem;
    padding-inline: 0.5rem;
    color: var(--ink-muted);
    background: transparent;
    font: inherit;
    font-size: 0.85rem;
    font-weight: 700;
    cursor: pointer;
    transition: background-color 120ms ease;
    -webkit-tap-highlight-color: transparent;
  }

  .theme-option :global(svg) {
    width: var(--ui-action-icon-size-md);
    height: var(--ui-action-icon-size-md);
  }

  .theme-option:hover {
    background: var(--accent-wash);
  }

  .theme-option[aria-pressed="true"] {
    color: var(--accent-strong);
    background: var(--accent-wash);
    box-shadow: inset 0 0 0 1px var(--accent-strong);
  }

  .theme-option:focus-visible {
    outline: 2px solid var(--focus);
    outline-offset: -2px;
  }

  @media (prefers-reduced-motion: reduce) {
    .theme-option {
      transition: none;
    }
  }
</style>
