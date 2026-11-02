<script lang="ts">
  import { page } from "$app/state";
  import AdminResource from "#lib/features/admin/AdminResource.svelte";

  const kind = $derived(page.params.kind ?? "");
  const id = $derived(Number(page.params.id));
</script>

<AdminResource {kind} {id} />
