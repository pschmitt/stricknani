<script lang="ts">
  import { onDestroy } from "svelte";
  import { SvelteMap } from "svelte/reactivity";
  import Button from "#lib/components/Button.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { Locale } from "#lib/i18n.svelte.ts";

  let {
    files,
    locale,
    disabled = false,
    onRemove,
  }: {
    files: readonly File[];
    locale: Locale;
    disabled?: boolean;
    onRemove: (file: File) => void;
  } = $props();

  const previews = new SvelteMap<File, string>();
  $effect(() => {
    const current = new Set(files);
    for (const [file, url] of previews) {
      if (!current.has(file)) {
        URL.revokeObjectURL(url);
        previews.delete(file);
      }
    }
    for (const file of files) {
      if (!previews.has(file)) previews.set(file, URL.createObjectURL(file));
    }
  });
  onDestroy(() => {
    for (const url of previews.values()) URL.revokeObjectURL(url);
    previews.clear();
  });
</script>

{#if files.length}
  <ul class="queued-photos">
    {#each files as file (file)}
      <li>
        <img src={previews.get(file) ?? ""} alt={file.name} />
        <span>{file.name}</span>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          icon="action-delete"
          {disabled}
          aria-label={`${t("Remove", locale)} ${file.name}`}
          onclick={() => onRemove(file)}
        >
          {t("Remove", locale)}
        </Button>
      </li>
    {/each}
  </ul>
{/if}

<style>
  .queued-photos {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 12rem), 1fr));
    gap: 0.65rem;
    margin: 0.8rem 0 0;
    padding: 0;
    list-style: none;
  }

  .queued-photos li {
    display: grid;
    grid-template-columns: 3.5rem minmax(0, 1fr) auto;
    align-items: center;
    gap: 0.55rem;
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.4rem;
    background: var(--surface-raised);
  }

  .queued-photos img {
    width: 3.5rem;
    height: 3.5rem;
    border-radius: var(--radius-sm);
    object-fit: cover;
  }

  .queued-photos span {
    overflow: hidden;
    text-overflow: ellipsis;
  }
</style>
