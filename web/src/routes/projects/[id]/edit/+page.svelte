<script lang="ts">
  import ProjectEditor from "#lib/features/projects/ProjectEditor.svelte";

  let { data }: { data: { projectId: number } } = $props();
</script>

<ProjectEditor projectId={data.projectId} />
