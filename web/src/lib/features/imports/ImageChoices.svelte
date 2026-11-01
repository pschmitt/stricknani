<script lang="ts">
  import { t } from "#lib/i18n.svelte.ts";
  import type { ImportImageChoice } from "./import-draft";

  let {
    title,
    images,
    disabled,
    locale,
  }: {
    title: string;
    images: ImportImageChoice[];
    disabled: boolean;
    locale: "en" | "de";
  } = $props();
</script>

<fieldset class="image-choices">
  <legend>{title}</legend>
  {#each images as image, index (image.url + index)}
    <label class="image-choice">
      <input type="checkbox" bind:checked={image.selected} {disabled} />
      <code>{image.url}</code>
    </label>
  {/each}
  {#if disabled}
    <p class="supporting">
      {t(
        "External images are disabled. Links will be kept in notes and never attached.",
        locale,
      )}
    </p>
  {/if}
</fieldset>

<style>
  .image-choices {
    min-width: 0;
    margin: 0.75rem 0;
    padding: 0.8rem;
    border: 1px solid var(--line);
    border-radius: 0.75rem;
  }
  .image-choices legend {
    padding: 0 0.3rem;
    font-weight: 650;
  }
  .image-choice {
    display: flex;
    min-height: 2.75rem;
    align-items: flex-start;
    gap: 0.6rem;
    padding: 0.35rem 0;
  }
  code {
    overflow-wrap: anywhere;
    font-size: 0.78rem;
  }
  .supporting {
    color: var(--ink-muted);
  }
</style>
