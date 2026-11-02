<script lang="ts">
  import BrandMark from "#lib/components/BrandMark.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { appVersion } from "#lib/app-version.ts";

  let {
    href = "/app/",
    parentHref = null,
    parentLabel = "Back",
    reserveParentSlot = false,
  }: {
    href?: string;
    parentHref?: string | null;
    parentLabel?: string;
    reserveParentSlot?: boolean;
  } = $props();
</script>

<div class="brand-header">
  {#if reserveParentSlot}
    <span class="parent-slot">
      {#if parentHref}
        <a
          class="shell-back-button parent-link"
          href={parentHref}
          aria-label={parentLabel}
        >
          <NavIcon name="previous" />
        </a>
      {/if}
    </span>
  {/if}
  <a class="brand-lockup" {href} aria-label="Stricknani">
    <BrandMark />
    <span class="brand-copy">
      <span class="brand-title">Stricknani</span>
      <span class="brand-version">{appVersion}</span>
    </span>
  </a>
</div>

<style>
  .brand-header {
    display: flex;
    min-width: 0;
    min-height: 44px;
    align-items: center;
    gap: 0.125rem;
  }

  .parent-slot {
    display: flex;
    width: 44px;
    height: 44px;
    flex: 0 0 44px;
    align-items: center;
    justify-content: flex-end;
  }

  .parent-link {
    display: flex;
    justify-content: flex-end;
    padding-right: 4px;
  }

  .brand-lockup {
    display: inline-flex;
    min-width: 0;
    min-height: 44px;
    align-items: center;
    gap: 0.6rem;
    color: var(--ink);
    text-decoration: none;
  }

  .brand-copy {
    display: flex;
    min-width: 0;
    flex-direction: column;
    justify-content: center;
    line-height: 1.15;
  }

  .brand-title {
    font-size: 1rem;
    font-weight: 680;
    letter-spacing: -0.025em;
  }

  .brand-version {
    margin-top: 0.12rem;
    color: var(--ink-muted);
    font-size: 0.7rem;
    font-weight: 450;
    letter-spacing: 0.01em;
  }
</style>
