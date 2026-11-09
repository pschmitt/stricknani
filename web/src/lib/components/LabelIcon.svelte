<script lang="ts">
  // A small, muted, decorative glyph in front of a form label. It is aria-hidden, so the label's
  // accessible name stays its text. Android draws the same Phosphor icon for the same field.
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";

  let { name }: { name: NavIconName } = $props();
</script>

<span class="label-icon" aria-hidden="true"><NavIcon {name} /></span>

<style>
  .label-icon {
    display: inline-block;
    width: 1.15em;
    height: 1.15em;
    margin-inline-end: 0.4em;
    vertical-align: -0.22em;
    color: var(--ink-muted);
  }

  .label-icon :global(svg) {
    width: 100%;
    height: 100%;
  }
</style>
