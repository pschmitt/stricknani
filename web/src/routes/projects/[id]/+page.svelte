<script lang="ts">
  import { page } from "$app/state";
  import ProjectReader from "#lib/features/projects/ProjectReader.svelte";

  const id = $derived(Number(page.params.id));
</script>

<ProjectReader {id} />
