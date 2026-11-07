<script lang="ts">
  import type { ChangeSetV1 } from "#lib/features/admin/admin-api.ts";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import {
    changeKind,
    delta,
    fieldLabel,
    isEmptyValue,
    kindLabel,
    segments,
  } from "./change-format";
  import MediaStrip from "./MediaStrip.svelte";
  import {
    hasMediaThumbnails,
    markMediaLists,
    mediaLabels,
    type ChangeMedia,
  } from "./media-change";

  // The field-by-field "before / after" diff of one recorded change, shared by the admin audit
  // detail page and the inline history on project and yarn pages.
  let {
    changes,
    locale,
    label,
    headingTag = "h2",
    media = null,
  }: {
    changes: ChangeSetV1;
    locale: Locale;
    label?: string;
    headingTag?: "h2" | "h3" | "h4";
    /** Thumbnails of the pictures named by picture changes (`media_thumbnails` of the event). */
    media?: ChangeMedia | null;
  } = $props();
</script>

<ol class="change-list" aria-label={label ?? t("Recorded changes", locale)}>
  {#each changes.items as item, index (`${index}:${item.field}`)}
    {@const kind = changeKind(item)}
    {@const parts = segments(item, locale)}
    {@const change = delta(item)}
    {@const thumbs = media !== null && hasMediaThumbnails(item, media)}
    {@const marks = thumbs
      ? markMediaLists(
          mediaLabels(item, item.before) ?? [],
          mediaLabels(item, item.after) ?? [],
        )
      : null}
    <li class="change" data-kind={kind}>
      <header class="change-head">
        <svelte:element this={headingTag} class="change-title"
          >{fieldLabel(item.field, locale)}</svelte:element
        >
        <span class="change-kind">{kindLabel(kind, locale)}</span>
        {#if change}
          <span class="change-delta">{change}</span>
        {/if}
      </header>
      <div class="change-values">
        <section class="side side--before">
          <span class="side-title">
            <span class="sign" aria-hidden="true">&minus;</span>
            {t("Before", locale)}
          </span>
          {#if marks && media}
            <MediaStrip marks={marks.before} {media} {locale} tone="before" />
          {:else}
            <!-- prettier-ignore -->
            <p class="value" class:value--empty={isEmptyValue(item.before)}>{#each parts.before as part, partIndex (partIndex)}{#if part.changed}<del>{part.text}</del>{:else}{part.text}{/if}{/each}</p>
          {/if}
        </section>
        <span class="change-arrow" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="18" height="18">
            <path
              d="M5 12h12m-5-5 5 5-5 5"
              fill="none"
              stroke="currentColor"
              stroke-width="2.2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </span>
        <section class="side side--after">
          <span class="side-title">
            <span class="sign" aria-hidden="true">+</span>
            {t("After", locale)}
          </span>
          {#if marks && media}
            <MediaStrip marks={marks.after} {media} {locale} tone="after" />
          {:else}
            <!-- prettier-ignore -->
            <p class="value" class:value--empty={isEmptyValue(item.after)}>{#each parts.after as part, partIndex (partIndex)}{#if part.changed}<ins>{part.text}</ins>{:else}{part.text}{/if}{/each}</p>
          {/if}
        </section>
      </div>
    </li>
  {/each}
</ol>

<style>
  .change-list .change {
    padding: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
  }
  .change-list {
    display: grid;
    gap: 0.9rem;
    padding: 0;
    margin: 0;
    list-style: none;
    counter-reset: change;
  }
  .change-list .change {
    --change-tone: var(--info);
    position: relative;
    padding: 1rem 1.1rem 1.1rem;
    border-radius: var(--radius-lg, 1.4rem);
    border-color: color-mix(in srgb, var(--change-tone) 22%, var(--line));
    background: linear-gradient(
      180deg,
      color-mix(in srgb, var(--change-tone) 7%, var(--surface)),
      var(--surface) 70%
    );
  }
  .change[data-kind="added"] {
    --change-tone: var(--success);
  }
  .change[data-kind="removed"] {
    --change-tone: var(--danger);
  }
  .change-head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin-bottom: 0.85rem;
  }
  .change-head .change-title {
    margin: 0;
    font-size: 1.08rem;
    overflow-wrap: anywhere;
  }
  .change-kind,
  .change-delta {
    border-radius: 999px;
    padding: 0.12rem 0.6rem;
    font-size: 0.8rem;
    font-weight: 700;
    color: var(--change-tone);
    background: color-mix(in srgb, var(--change-tone) 14%, var(--surface));
  }
  .change-delta {
    font-variant-numeric: tabular-nums;
  }
  .change-values {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
    align-items: stretch;
    gap: 0.6rem;
  }
  .side {
    --side-tone: var(--danger);
    min-width: 0;
    display: flex;
    flex-direction: column;
    border-radius: var(--radius-md, 1rem);
    border: 1px dashed color-mix(in srgb, var(--side-tone) 40%, transparent);
    background: color-mix(in srgb, var(--side-tone) 7%, var(--surface));
    padding: 0.55rem 0.8rem 0.75rem;
  }
  .side--after {
    --side-tone: var(--success);
  }
  .side-title {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    margin: 0 0 0.35rem;
    font-size: 0.82rem;
    color: var(--side-tone);
  }
  .sign {
    display: inline-grid;
    place-items: center;
    width: 1.25rem;
    height: 1.25rem;
    border-radius: 50%;
    font-weight: 800;
    line-height: 1;
    color: var(--surface);
    background: var(--side-tone);
  }
  .value {
    margin: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    line-height: 1.55;
  }
  .value--empty {
    color: var(--muted);
    font-style: italic;
  }
  .value del,
  .value ins {
    border-radius: 0.35rem;
    padding: 0.05rem 0.2rem;
    box-decoration-break: clone;
    -webkit-box-decoration-break: clone;
  }
  .value del {
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 18%, transparent);
    text-decoration: line-through;
    text-decoration-thickness: 2px;
    text-decoration-color: color-mix(in srgb, var(--danger) 70%, transparent);
  }
  .value ins {
    color: var(--success);
    background: color-mix(in srgb, var(--success) 20%, transparent);
    text-decoration: none;
    font-weight: 700;
  }
  .change-arrow {
    align-self: center;
    display: grid;
    place-items: center;
    width: 2rem;
    height: 2rem;
    border-radius: 50%;
    color: var(--change-tone);
    background: color-mix(in srgb, var(--change-tone) 14%, var(--surface));
  }
  @media (max-width: 38rem) {
    .change-values {
      grid-template-columns: 1fr;
    }
    .change-arrow {
      justify-self: center;
      transform: rotate(90deg);
      width: 1.6rem;
      height: 1.6rem;
    }
  }
</style>
