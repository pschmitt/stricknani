<script lang="ts">
  import Mascot from "#lib/components/Mascot.svelte";

  // The welcome band of the New project and New yarn pages: the mascot doing
  // what the page is about (knitting, or playing with yarn), a warm line and a
  // hint. The Android app draws the same composition (NewBanner.kt).
  let {
    kind,
    title,
    subtitle,
  }: { kind: "project" | "yarn"; title: string; subtitle: string } = $props();
</script>

<div class="new-banner new-banner--{kind}">
  <div class="new-banner__art" aria-hidden="true">
    <svg viewBox="0 0 128 104" width="128" height="104" focusable="false">
      <ellipse class="new-banner__shadow" cx="64" cy="100" rx="40" ry="3" />
    </svg>
    <div class="new-banner__mascot">
      <Mascot size={76} reactive={false} interactive="decorative" bob={true} />
    </div>
    {#if kind === "project"}
      <svg
        class="new-banner__props"
        viewBox="0 0 128 104"
        width="128"
        height="104"
        focusable="false"
      >
        <rect
          class="new-banner__knit"
          x="42"
          y="80"
          width="44"
          height="20"
          rx="4"
        />
        <path
          class="new-banner__stitches"
          d="M48 86l3 3 3-3m4 0l3 3 3-3m4 0l3 3 3-3m4 0l3 3 3-3m-31 6l3 3 3-3m4 0l3 3 3-3m4 0l3 3 3-3m4 0l3 3 3-3"
        />
        <g class="new-banner__needle new-banner__needle--a">
          <line x1="26" y1="100" x2="92" y2="62" />
          <circle cx="92" cy="62" r="3.6" />
        </g>
        <g class="new-banner__needle new-banner__needle--b">
          <line x1="102" y1="100" x2="36" y2="62" />
          <circle cx="36" cy="62" r="3.6" />
        </g>
      </svg>
    {:else}
      <svg
        class="new-banner__props"
        viewBox="0 0 128 104"
        width="128"
        height="104"
        focusable="false"
      >
        <path
          class="new-banner__thread"
          d="M96 90C112 96 100 102 112 102S126 94 124 90"
        />
        <g class="new-banner__ball">
          <circle cx="88" cy="78" r="16" />
          <path d="M74 72Q88 64 102 74M73 80Q88 74 103 82M78 90Q88 84 98 90" />
        </g>
      </svg>
    {/if}
  </div>
  <div class="new-banner__text">
    <p class="new-banner__title">{title}</p>
    <p class="new-banner__subtitle">{subtitle}</p>
  </div>
</div>

<style>
  .new-banner {
    --tone: var(--tone-projects);
    --wash: var(--tone-projects-wash);
    display: flex;
    align-items: center;
    gap: 0.5rem;
    min-height: 0;
    max-height: 120px;
    padding: 0 1rem 0 0.25rem;
    border-radius: var(--radius-md);
    background: var(--wash);
    color: var(--ink);
    overflow: hidden;
  }

  .new-banner--yarn {
    --tone: var(--tone-yarns);
    --wash: var(--tone-yarns-wash);
  }

  .new-banner__art {
    position: relative;
    flex: none;
    width: 128px;
    height: 104px;
  }

  .new-banner__art svg {
    position: absolute;
    inset: 0;
  }

  .new-banner__mascot {
    position: absolute;
    left: 26px;
    top: 4px;
  }

  .new-banner__shadow {
    fill: var(--tone);
    opacity: 0.18;
  }

  .new-banner__text {
    min-width: 0;
  }

  .new-banner__title {
    margin: 0;
    font-size: 1.15rem;
    font-weight: 700;
    line-height: 1.25;
    color: var(--tone);
  }

  .new-banner__subtitle {
    margin: 0.2rem 0 0;
    font-size: 0.9rem;
    line-height: 1.35;
    color: var(--ink);
  }

  .new-banner__needle line {
    stroke: var(--tone);
    stroke-width: 4;
    stroke-linecap: round;
  }

  .new-banner__needle circle {
    fill: var(--tone);
  }

  .new-banner__knit {
    fill: var(--surface);
    stroke: var(--tone);
    stroke-width: 1.5;
  }

  .new-banner__stitches,
  .new-banner__ball path,
  .new-banner__thread {
    fill: none;
    stroke: var(--tone);
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .new-banner__thread {
    stroke-width: 2.4;
  }

  .new-banner__ball circle {
    fill: var(--surface);
    stroke: var(--tone);
    stroke-width: 2;
  }

  @media (prefers-reduced-motion: no-preference) {
    .new-banner__needle--a {
      transform-origin: 64px 81px;
      animation: new-banner-click-a 2.6s ease-in-out infinite;
    }

    .new-banner__needle--b {
      transform-origin: 64px 81px;
      animation: new-banner-click-b 2.6s ease-in-out infinite;
    }

    .new-banner__thread {
      animation: new-banner-thread 3.2s ease-in-out infinite alternate;
    }
  }

  @keyframes new-banner-click-a {
    0%,
    100% {
      transform: rotate(0);
    }
    50% {
      transform: rotate(-4deg);
    }
  }

  @keyframes new-banner-click-b {
    0%,
    100% {
      transform: rotate(0);
    }
    50% {
      transform: rotate(4deg);
    }
  }

  @keyframes new-banner-thread {
    from {
      transform: translateY(0);
    }
    to {
      transform: translateY(-1.5px);
    }
  }

  @media (max-width: 380px) {
    .new-banner__art {
      width: 104px;
      margin-left: -12px;
    }
  }
</style>
