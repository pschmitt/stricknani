<script lang="ts">
  import { page } from "$app/state";
  import DiffPanel from "#lib/features/admin/DiffPanel.svelte";

  const eventId = $derived(Number(page.params.event_id));
</script>

<DiffPanel {eventId} />
