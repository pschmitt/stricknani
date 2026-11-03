<script lang="ts">
  import Mascot from "#lib/components/Mascot.svelte";
</script>

<!-- Always used inside the brand link, which stays the only focus target. -->
<span class="brand-mark"><Mascot size={32} interactive="decorative" /></span>

<style>
  .brand-mark {
    display: block;
    width: 2rem;
    height: 2rem;
    flex: 0 0 auto;
  }

  .brand-mark :global(.mascot-art) {
    filter: none;
  }
</style>
