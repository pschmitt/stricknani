<script lang="ts">
  import BrandMark from "#lib/components/BrandMark.svelte";
  import { appVersion } from "#lib/app-version.ts";

  let { href = "/app/" }: { href?: string } = $props();
</script>

<a class="brand-lockup" {href} aria-label="Stricknani">
  <BrandMark />
  <span class="brand-copy">
    <span class="brand-title">Stricknani</span>
    <span class="brand-version">{appVersion}</span>
  </span>
</a>

<style>
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
