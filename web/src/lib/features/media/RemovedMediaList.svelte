<script lang="ts" module>
  export interface RemovedEntry {
    key: string;
    /** Thumbnail of a photo; omitted for a file. */
    thumbnailUrl?: string | null;
    label: string;
    restoreLabel: string;
  }
</script>

<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { Locale } from "#lib/i18n.svelte.ts";

  let {
    entries,
    locale,
    disabled = false,
    onRestore,
  }: {
    entries: readonly RemovedEntry[];
    locale: Locale;
    disabled?: boolean;
    onRestore: (key: string) => void;
  } = $props();
</script>

{#if entries.length}
  <div class="removed" data-testid="removed-media">
    <p class="removed__title" role="status">
      {t("Will be removed when you save", locale)}
    </p>
    <ul class="removed__list">
      {#each entries as entry (entry.key)}
        <li data-testid="removed-media-item">
          {#if entry.thumbnailUrl}
            <img src={entry.thumbnailUrl} alt="" />
          {/if}
          <span class="removed__label">{entry.label}</span>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            icon="reset"
            {disabled}
            aria-label={entry.restoreLabel}
            data-testid="removed-media-restore"
            onclick={() => onRestore(entry.key)}
          >
            {t("Undo", locale)}
          </Button>
        </li>
      {/each}
    </ul>
    <p class="removed__hint">
      {t("What you remove stays in the trash for 90 days.", locale)}
    </p>
  </div>
{/if}

<style>
  .removed {
    display: grid;
    gap: 0.5rem;
    margin-top: 0.8rem;
    border: 1px dashed var(--line);
    border-radius: var(--radius-sm);
    padding: 0.6rem;
    background: var(--surface-raised);
  }

  .removed__title,
  .removed__hint {
    margin: 0;
    color: var(--ink-muted);
  }

  .removed__title {
    font-weight: 650;
  }

  .removed__hint {
    font-size: 0.9em;
  }

  .removed__list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 14rem), 1fr));
    gap: 0.5rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .removed__list li {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    min-width: 0;
  }

  .removed__list img {
    width: 3rem;
    height: 3rem;
    flex: none;
    border-radius: var(--radius-sm);
    object-fit: cover;
    opacity: 0.5;
    filter: grayscale(1);
  }

  .removed__label {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-decoration: line-through;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--ink-muted);
  }
</style>
