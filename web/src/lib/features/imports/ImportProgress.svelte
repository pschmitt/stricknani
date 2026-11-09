<script lang="ts">
  import { onMount } from "svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import {
    currentStep,
    formatElapsed,
    importSteps,
    progressFraction,
    type ImportSourceKind,
    type ImportStepId,
  } from "./import-progress";

  /**
   * What the import page shows while the server reads the source: the mascot
   * at work, the steps of an import, a bar and the time so far. The Android
   * import dialogs show the same panel.
   */
  let {
    kind,
    ai,
    locale,
    since = null,
  }: {
    kind: ImportSourceKind;
    ai: boolean;
    locale: "en" | "de";
    /** When the server started the import (epoch ms), for one that was already running. */
    since?: number | null;
  } = $props();

  let elapsed = $state(0);
  const steps = $derived(importSteps(kind, ai));
  const current = $derived(currentStep(steps, elapsed));
  const fraction = $derived(progressFraction(elapsed, ai));

  onMount(() => {
    const head = since === null ? 0 : Math.max(0, Date.now() - since);
    const started = performance.now() - head;
    const timer = window.setInterval(() => {
      elapsed = performance.now() - started;
    }, 250);
    return () => window.clearInterval(timer);
  });

  // Branches instead of a computed key: the i18n extractor needs literal ids.
  function label(id: ImportStepId): string {
    switch (id) {
      case "fetch":
        return t("Fetching the page", locale);
      case "file":
        return t("Reading the file", locale);
      case "text":
        return t("Reading the text", locale);
      case "pictures":
        return t("Looking at the pictures", locale);
      case "read":
        return ai
          ? t("The AI reads the pattern", locale)
          : t("Reading the pattern", locale);
      case "review":
        return t("Preparing your review", locale);
    }
  }
</script>

<div class="import-progress" role="status" aria-live="polite">
  <div class="import-progress__mascot">
    <Mascot size={60} roll interactive="decorative" />
  </div>
  <div class="import-progress__body">
    <ol class="import-progress__steps">
      {#each steps as step, index (step.id)}
        <li
          class:done={index < current}
          class:current={index === current}
          aria-current={index === current ? "step" : undefined}
        >
          <span class="import-progress__marker" aria-hidden="true">
            {#if index < current}<NavIcon name="check" />{/if}
          </span>
          <span>{label(step.id)}</span>
        </li>
      {/each}
    </ol>
    <div
      class="import-progress__bar"
      role="progressbar"
      aria-label={t("Import progress", locale)}
      aria-valuemin="0"
      aria-valuemax="100"
      aria-valuenow={Math.round(fraction * 100)}
    >
      <span style:width={`${fraction * 100}%`}></span>
    </div>
    <p class="import-progress__meta">
      <span>{formatElapsed(elapsed)}</span>
      {#if ai}<span
          >{t(
            "Some pages take a minute when the AI opens them in a browser.",
            locale,
          )}</span
        >{/if}
    </p>
  </div>
</div>

<style>
  .import-progress {
    display: flex;
    gap: 1rem;
    align-items: center;
    padding: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm, 16px);
    background: var(--surface-raised);
  }

  .import-progress__mascot {
    flex: none;
  }

  .import-progress__body {
    display: grid;
    flex: 1;
    min-width: 0;
    gap: 0.6rem;
  }

  .import-progress__steps {
    display: grid;
    gap: 0.35rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .import-progress__steps li {
    display: flex;
    gap: 0.55rem;
    align-items: center;
    color: var(--ink-muted);
  }

  .import-progress__steps li.done,
  .import-progress__steps li.current {
    color: var(--ink);
  }

  .import-progress__steps li.current {
    font-weight: 650;
  }

  .import-progress__marker {
    display: inline-grid;
    flex: none;
    width: 1.25rem;
    height: 1.25rem;
    place-items: center;
    border: 2px solid var(--line);
    border-radius: 50%;
    font-size: 0.8rem;
  }

  .done .import-progress__marker {
    border-color: var(--accent);
    background: var(--accent);
    color: var(--on-accent);
  }

  .done .import-progress__marker :global(svg) {
    width: 0.8rem;
    height: 0.8rem;
  }

  .current .import-progress__marker {
    border-color: var(--accent);
    animation: import-pulse 1.4s ease-in-out infinite;
  }

  .import-progress__bar {
    overflow: hidden;
    height: 6px;
    border-radius: 999px;
    background: var(--primary-container);
  }

  .import-progress__bar span {
    display: block;
    height: 100%;
    border-radius: inherit;
    background: var(--accent);
    transition: width 250ms linear;
  }

  .import-progress__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem 0.75rem;
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .import-progress__meta span:first-child {
    font-variant-numeric: tabular-nums;
  }

  @keyframes import-pulse {
    50% {
      box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 30%, transparent);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .current .import-progress__marker {
      animation: none;
    }

    .import-progress__bar span {
      transition: none;
    }
  }
</style>
