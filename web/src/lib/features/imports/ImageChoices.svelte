<script lang="ts">
  import { t } from "#lib/i18n.svelte.ts";
  import { importImageSrc } from "./import-api";
  import type { ImportImageChoice } from "./import-draft";

  let {
    title,
    images,
    disabled,
    external,
    locale,
  }: {
    title: string;
    images: ImportImageChoice[];
    /** Locks the choices (for example while saving). */
    disabled: boolean;
    /** Whether this server may fetch external images; if not there are no previews. */
    external: boolean;
    locale: "en" | "de";
  } = $props();
</script>

<fieldset class="image-choices">
  <legend>{title}</legend>
  <div class="image-grid">
    {#each images as image, index (image.url + index)}
      <label class="image-choice" class:selected={image.selected}>
        <input type="checkbox" bind:checked={image.selected} {disabled} />
        <!-- With external images off the proxy answers 503: show no preview. -->
        {#if external}<img
            src={importImageSrc(image.url)}
            alt=""
            loading="lazy"
            decoding="async"
          />{/if}
        <span class="image-source">{new URL(image.url).hostname}</span>
      </label>
    {/each}
  </div>
  {#if !external}
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
    border-radius: var(--radius-xs);
  }
  .image-choices legend {
    padding: 0 0.3rem;
    font-weight: 650;
  }
  .image-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr));
    gap: 0.6rem;
  }
  .image-choice {
    position: relative;
    display: flex;
    min-height: 2.75rem;
    flex-direction: column;
    gap: 0.3rem;
    padding: 0.3rem;
    border: 2px solid var(--line);
    border-radius: var(--radius-xs);
  }
  .image-choice.selected {
    border-color: var(--accent, currentColor);
  }
  .image-choice input {
    position: absolute;
    top: 0.5rem;
    left: 0.5rem;
    z-index: 1;
    width: 1.4rem;
    height: 1.4rem;
  }
  .image-choice img {
    width: 100%;
    aspect-ratio: 1;
    border-radius: calc(var(--radius-xs) - 2px);
    background: var(--surface-muted, rgb(128 128 128 / 0.12));
    object-fit: cover;
  }
  .image-source {
    overflow: hidden;
    color: var(--ink-muted);
    font-size: 0.72rem;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .supporting {
    color: var(--ink-muted);
  }
</style>
