<script lang="ts">
  import { t } from "#lib/i18n.svelte.ts";
  import { importImageSrc } from "./import-api";
  import type { ImportAssetChoice } from "./import-draft";

  let {
    assets,
    disabled,
    external,
    locale,
  }: {
    assets: ImportAssetChoice[];
    /** Locks the choices (for example while saving). */
    disabled: boolean;
    /** Whether this server may fetch external images; if not there are no previews. */
    external: boolean;
    locale: "en" | "de";
  } = $props();

  function host(url: string): string {
    try {
      return new URL(url).hostname;
    } catch {
      return "";
    }
  }
</script>

<fieldset class="asset-choices">
  <legend>{t("Diagrams and documents", locale)}</legend>
  <ul>
    {#each assets as asset, index (asset.url + index)}
      <li class:selected={asset.selected}>
        <label>
          <input type="checkbox" bind:checked={asset.selected} {disabled} />
          {#if asset.kind === "diagram" && external}
            <img
              src={importImageSrc(asset.url)}
              alt=""
              loading="lazy"
              decoding="async"
            />
          {/if}
          <span class="asset-text">
            <span class="asset-kind"
              >{asset.kind === "pdf"
                ? t("PDF", locale)
                : t("Diagram", locale)}</span
            >
            <strong>{asset.label || host(asset.url)}</strong>
            <small>{host(asset.url)}</small>
          </span>
        </label>
      </li>
    {/each}
  </ul>
  <p class="supporting">
    {t("Selected files are saved as attachments of the project.", locale)}
  </p>
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
  .asset-choices {
    min-width: 0;
    margin: 0.75rem 0;
    padding: 0.8rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
  }
  .asset-choices legend {
    padding: 0 0.3rem;
    font-weight: 650;
  }
  ul {
    display: grid;
    gap: 0.5rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  li {
    border: 2px solid var(--line);
    border-radius: var(--radius-xs);
  }
  li.selected {
    border-color: var(--accent, currentColor);
  }
  label {
    display: flex;
    min-height: 2.75rem;
    align-items: center;
    gap: 0.6rem;
    padding: 0.4rem;
  }
  input {
    width: 1.4rem;
    height: 1.4rem;
    flex: none;
  }
  img {
    width: 4.5rem;
    height: 4.5rem;
    flex: none;
    border-radius: calc(var(--radius-xs) - 2px);
    background: var(--surface-muted, rgb(128 128 128 / 0.12));
    object-fit: contain;
  }
  .asset-text {
    display: flex;
    min-width: 0;
    flex-direction: column;
  }
  .asset-text strong {
    overflow-wrap: anywhere;
  }
  .asset-kind {
    color: var(--accent, currentColor);
    font-size: 0.72rem;
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }
  small {
    overflow: hidden;
    color: var(--ink-muted);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .supporting {
    color: var(--ink-muted);
  }
</style>
