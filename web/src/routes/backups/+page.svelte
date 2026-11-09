<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";

  // Backups moved to the Account page; old links, bookmarks and
  // notifications still arrive here.
  onMount(() => {
    void goto(`${resolve("/account")}#backups`, { replaceState: true });
  });
</script>
