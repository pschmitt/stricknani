<script lang="ts">
  import { page } from "$app/state";
  import YarnDetail from "#lib/features/yarns/YarnDetail.svelte";

  const id = $derived(Number(page.params.id));
</script>

<YarnDetail {id} />
