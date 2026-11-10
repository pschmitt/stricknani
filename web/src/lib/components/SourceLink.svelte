<script lang="ts">
  import NavIcon, { type NavIconName } from "./NavIcon.svelte";

  let {
    href,
    icon,
    label,
  }: { href: string; icon: NavIconName; label: string } = $props();
</script>

<!-- One external source link (original page or archived copy): round icon, label and "↗".
     The icon colour follows --source-tone of the surrounding source rows. -->
<a class="source-link" {href} target="_blank" rel="noopener noreferrer">
  <span class="source-link__icon" aria-hidden="true"
    ><NavIcon name={icon} /></span
  >
  {label} ↗
</a>

<style>
  .source-link {
    display: inline-flex;
    min-height: 44px;
    width: fit-content;
    min-width: 0;
    align-items: center;
    gap: 0.55rem;
    color: var(--accent-strong);
    font-weight: 640;
    overflow-wrap: anywhere;
  }

  .source-link__icon {
    display: inline-grid;
    width: 1.8rem;
    height: 1.8rem;
    flex: none;
    place-items: center;
    border-radius: 999px;
    color: var(--source-tone, var(--accent-strong));
    background: color-mix(
      in srgb,
      var(--source-tone, var(--accent-strong)) 16%,
      transparent
    );
  }

  .source-link__icon :global(svg) {
    width: 1rem;
    height: 1rem;
  }
</style>
