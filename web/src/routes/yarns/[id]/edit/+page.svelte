<script lang="ts">
  import { page } from "$app/state";
  import YarnForm from "#lib/features/yarns/YarnForm.svelte";

  const id = $derived(Number(page.params.id));
</script>

<YarnForm yarnId={id} />
