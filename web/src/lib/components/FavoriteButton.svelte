<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import { burstParticles } from "#lib/components/favorite-burst.ts";
  import { t } from "#lib/i18n.svelte.ts";

  let {
    favorite,
    busy,
    locale,
    onclick,
  }: {
    favorite: boolean;
    busy: boolean;
    locale: "en" | "de";
    onclick: () => void;
  } = $props();

  const label = $derived(
    favorite
      ? t("Remove from favorites", locale)
      : t("Add to favorites", locale),
  );

  const particles = burstParticles();
  const HEART_PATH =
    "M20.8 8.8c0 5.7-8.8 10.3-8.8 10.3S3.2 14.5 3.2 8.8a4.6 4.6 0 0 1 8.8-2.1 4.6 4.6 0 0 1 8.8 2.1Z";

  // The effect plays only for a change this user asked for with a click, never
  // for the first render or a list refresh that happens to flip the state.
  let phase = $state<"on" | "off" | null>(null);
  let playKey = $state(0);
  let requested: boolean | null = null;
  let requestTimer: ReturnType<typeof setTimeout> | undefined;
  let phaseTimer: ReturnType<typeof setTimeout> | undefined;

  function handleClick() {
    requested = !favorite;
    clearTimeout(requestTimer);
    requestTimer = setTimeout(() => (requested = null), 4000);
    onclick();
  }

  $effect(() => {
    const current = favorite;
    if (requested === null || requested !== current) return;
    requested = null;
    clearTimeout(phaseTimer);
    phase = current ? "on" : "off";
    playKey += 1;
    phaseTimer = setTimeout(() => (phase = null), 800);
  });

  $effect(() => () => {
    clearTimeout(requestTimer);
    clearTimeout(phaseTimer);
  });
</script>

<span class="favorite-wrap">
  <Button
    class={`favorite-button${phase ? ` favorite-button--${phase}` : ""}`}
    icon="favorite"
    size="md"
    variant="secondary"
    aria-label={label}
    aria-pressed={favorite}
    aria-busy={busy}
    disabled={busy}
    onclick={handleClick}
  />
  {#if phase}
    {#key playKey}
      <span class="favorite-fx" aria-hidden="true">
        {#if phase === "on"}
          {#each particles as particle, index (index)}
            <svg
              class="favorite-particle"
              viewBox="0 0 24 24"
              style:--dx="{particle.dx}px"
              style:--dy="{particle.dy}px"
              style:--size="{particle.size}px"
              style:--delay="{particle.delay}ms"
            >
              <path d={HEART_PATH} />
            </svg>
          {/each}
        {:else}
          <svg class="favorite-drift" viewBox="0 0 24 24">
            <path d={HEART_PATH} />
          </svg>
        {/if}
      </span>
    {/key}
  {/if}
</span>

<style>
  .favorite-wrap {
    position: relative;
    display: inline-flex;
  }

  /* Overlay for the particles: no layout, no pointer events, no clipping. */
  .favorite-fx {
    position: absolute;
    inset: 0;
    pointer-events: none;
    overflow: visible;
  }

  .favorite-particle,
  .favorite-drift {
    position: absolute;
    top: 50%;
    left: 50%;
    width: var(--size, 8px);
    height: var(--size, 8px);
    margin: calc(var(--size, 8px) / -2) 0 0 calc(var(--size, 8px) / -2);
    fill: var(--accent);
    stroke: none;
    opacity: 0;
  }

  .favorite-drift {
    --size: 9px;
    fill: var(--ink-muted);
  }

  :global(.favorite-button.ui-button) {
    width: 44px;
    min-width: 44px;
    height: 44px;
    min-height: 44px;
    padding: 0;
  }

  :global(.favorite-button svg) {
    transition:
      fill 180ms ease,
      stroke 180ms ease;
  }

  :global(.favorite-button[aria-pressed="true"] svg) {
    fill: var(--accent);
    stroke: var(--accent);
  }

  @media (prefers-reduced-motion: no-preference) {
    :global(.favorite-button--on svg) {
      animation: favorite-pop 450ms cubic-bezier(0.3, 1.5, 0.5, 1);
    }

    :global(.favorite-button--off svg) {
      animation: favorite-deflate 420ms ease-out;
    }

    .favorite-particle {
      animation: favorite-burst 500ms ease-out var(--delay, 0ms) both;
    }

    .favorite-drift {
      animation: favorite-drift 650ms ease-in both;
    }
  }

  @keyframes favorite-pop {
    0% {
      transform: scale(0.7);
    }
    45% {
      transform: scale(1.35);
    }
    70% {
      transform: scale(0.94);
    }
    100% {
      transform: scale(1);
    }
  }

  @keyframes favorite-deflate {
    0% {
      transform: scale(1) rotate(0deg);
    }
    30% {
      transform: scale(0.8) rotate(-8deg);
    }
    55% {
      transform: scale(0.84) rotate(6deg);
    }
    80% {
      transform: scale(0.97) rotate(-2deg);
    }
    100% {
      transform: scale(1) rotate(0deg);
    }
  }

  @keyframes favorite-burst {
    0% {
      opacity: 1;
      transform: translate(0, 0) scale(0.4);
    }
    100% {
      opacity: 0;
      transform: translate(var(--dx), var(--dy)) scale(1);
    }
  }

  @keyframes favorite-drift {
    0% {
      opacity: 0.8;
      transform: translateY(0) scale(1);
    }
    100% {
      opacity: 0;
      transform: translateY(26px) scale(0.6);
    }
  }
</style>
