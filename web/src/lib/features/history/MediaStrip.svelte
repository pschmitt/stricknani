<script lang="ts">
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import type { ChangeMedia, MediaMark } from "./media-change";

  // The pictures of one side of a picture-order or cover change as small thumbnails. A ring
  // and badge carry what happened to each picture; labels without a current picture stay text.
  let {
    marks,
    media,
    locale,
    tone,
    onopen,
  }: {
    marks: MediaMark[];
    media: ChangeMedia;
    locale: Locale;
    tone: "before" | "after";
    /** Opens the picture of a label in the viewer; thumbnails without a url stay static. */
    onopen?: (label: string) => void;
  } = $props();

  function statusText(status: MediaMark["status"]): string {
    switch (status) {
      case "added":
        return t("Added", locale);
      case "removed":
        return t("Removed", locale);
      case "moved":
        return t("Moved", locale);
      default:
        return "";
    }
  }

  function badge(status: MediaMark["status"]): string {
    return status === "added" ? "+" : status === "removed" ? "−" : "⇅";
  }
</script>

{#if marks.length === 0}
  <p class="strip-empty">{t("Empty list", locale)}</p>
{:else}
  <ul class="strip" data-tone={tone}>
    {#each marks as mark, index (`${index}:${mark.label}`)}
      {@const entry = Object.hasOwn(media, mark.label)
        ? media[mark.label]
        : undefined}
      {@const status = statusText(mark.status)}
      <li class="tile" data-status={mark.status} title={mark.label}>
        <span class="thumb">
          {#if entry && onopen && entry.url}
            <button
              type="button"
              class="thumb-button"
              aria-label={`${t("Show picture", locale)}: ${mark.label}`}
              data-media-strip-open
              onclick={() => onopen(mark.label)}
            >
              <img
                src={entry.thumbnail_url}
                alt=""
                loading="lazy"
                decoding="async"
                width="52"
                height="52"
              />
            </button>
          {:else if entry}
            <img
              src={entry.thumbnail_url}
              alt={mark.label}
              loading="lazy"
              decoding="async"
              width="52"
              height="52"
            />
          {:else}
            <span class="thumb-text" aria-hidden="true">{mark.label}</span>
          {/if}
          {#if mark.status !== "same"}
            <span class="badge" aria-hidden="true">{badge(mark.status)}</span>
          {/if}
        </span>
        <span class="caption">{mark.label}</span>
        {#if status}<span class="status">{status}</span>{/if}
      </li>
    {/each}
  </ul>
{/if}

<style>
  .strip {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(3.4rem, 1fr));
    gap: 0.5rem 0.4rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .strip-empty {
    margin: 0;
    color: var(--muted);
    font-style: italic;
  }
  .tile {
    --ring: transparent;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.2rem;
    min-width: 0;
  }
  .tile[data-status="added"] {
    --ring: var(--success);
  }
  .tile[data-status="removed"] {
    --ring: var(--danger);
  }
  .tile[data-status="moved"] {
    --ring: var(--info);
  }
  .thumb {
    position: relative;
    display: block;
    width: 3.25rem;
    height: 3.25rem;
    border-radius: 0.7rem;
    background: color-mix(in srgb, var(--ring) 10%, var(--surface));
    box-shadow: 0 0 0 2px var(--ring);
  }
  .tile[data-status="same"] .thumb {
    box-shadow: 0 0 0 1px var(--line);
  }
  .tile[data-status="removed"] img {
    opacity: 0.7;
  }
  .thumb-button {
    display: block;
    width: 100%;
    height: 100%;
    border: 0;
    border-radius: inherit;
    padding: 0;
    background: transparent;
    cursor: zoom-in;
  }
  .thumb-button:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }
  img,
  .thumb-text {
    display: block;
    width: 100%;
    height: 100%;
    border-radius: inherit;
    object-fit: cover;
  }
  .thumb-text {
    display: grid;
    place-items: center;
    padding: 0.2rem;
    overflow: hidden;
    font-size: 0.65rem;
    line-height: 1.15;
    text-align: center;
    overflow-wrap: anywhere;
    color: var(--ink-muted);
  }
  .badge {
    position: absolute;
    top: -0.35rem;
    right: -0.35rem;
    display: grid;
    place-items: center;
    width: 1.1rem;
    height: 1.1rem;
    border-radius: 50%;
    font-size: 0.75rem;
    font-weight: 800;
    line-height: 1;
    color: var(--surface);
    background: var(--ring);
  }
  .caption {
    width: 100%;
    overflow: hidden;
    font-size: 0.7rem;
    line-height: 1.2;
    text-align: center;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--ink-muted);
  }
  .status {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .tile {
    position: relative;
  }
</style>
