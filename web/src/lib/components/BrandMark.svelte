<script lang="ts">
  import { asset } from "$app/paths";
</script>

<img
  class="brand-mark"
  src={asset("favicon.svg")}
  alt=""
  aria-hidden="true"
  width="32"
  height="32"
  draggable="false"
/>

<style>
  .brand-mark {
    display: block;
    width: 2rem;
    height: 2rem;
    flex: 0 0 auto;
    object-fit: contain;
  }
</style>
