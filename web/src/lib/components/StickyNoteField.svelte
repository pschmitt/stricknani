<script lang="ts">
  // The editable twin of StickyNote: a paper sticky note (same paper, fold, tilt, tape and shadow
  // tokens, and the same look as Android's StickyNoteCard) around a real, labelled textarea.
  import { focusOnHash } from "./focus-on-hash.ts";
  import LabelIcon from "./LabelIcon.svelte";
  import type { NavIconName } from "./NavIcon.svelte";

  let {
    id,
    label,
    icon,
    value = $bindable(""),
    rows = 5,
    disabled = false,
    hint = "",
    maxlength,
    onInput,
    onfocus,
  }: {
    id: string;
    label: string;
    /** Optional decorative glyph before the label text. */
    icon?: NavIconName;
    value?: string;
    rows?: number;
    disabled?: boolean;
    hint?: string;
    maxlength?: number;
    /** Called with the new text on every edit, for parents that do not bind `value`. */
    onInput?: (value: string) => void;
    onfocus?: () => void;
  } = $props();
</script>

<div class="sticky-field" style:--rows={rows}>
  <span class="sticky-field__paper" aria-hidden="true"></span>
  <div class="sticky-field__body">
    <label for={id}
      >{#if icon}<LabelIcon name={icon} />{/if}{label}</label
    >
    <textarea
      {id}
      class="reading-input"
      use:focusOnHash
      bind:value
      {rows}
      {maxlength}
      {disabled}
      {onfocus}
      oninput={(event) => onInput?.(event.currentTarget.value)}
      aria-describedby={hint ? `${id}-hint` : undefined}></textarea>
    {#if hint}<small id={`${id}-hint`}>{hint}</small>{/if}
  </div>
</div>

<style>
  .sticky-field {
    --fold: 1.4rem;
    --tilt: -1.5deg;
    position: relative;
    display: block;
    margin: 1.4rem 0.35rem 1rem;
    color: var(--sticky-ink);
  }

  .sticky-field__paper {
    position: absolute;
    inset: 0;
    border-radius: 14px 14px 4px 14px;
    background: linear-gradient(
      315deg,
      transparent var(--fold),
      var(--sticky-paper) var(--fold)
    );
    transform: rotate(var(--tilt));
    filter: drop-shadow(0 1px 1px rgb(var(--sticky-shadow) / 22%))
      drop-shadow(0 6px 8px rgb(var(--sticky-shadow) / 16%))
      drop-shadow(0 14px 18px rgb(var(--sticky-shadow) / 10%));
    pointer-events: none;
  }

  .sticky-field__paper::before {
    content: "";
    position: absolute;
    top: -0.55rem;
    left: 50%;
    width: 1.35rem;
    height: 1.35rem;
    border-radius: 50%;
    background:
      radial-gradient(
        circle at 34% 30%,
        rgb(255 255 255 / 75%) 0 14%,
        rgb(255 255 255 / 0%) 32%
      ),
      radial-gradient(
        circle at 50% 50%,
        var(--sticky-tape) 0 55%,
        color-mix(in srgb, var(--sticky-tape) 65%, black) 100%
      );
    box-shadow:
      0 1px 1px rgb(0 0 0 / 35%),
      3px 5px 6px rgb(var(--sticky-shadow) / 30%);
    transform: translateX(-50%);
  }

  .sticky-field__paper::after {
    content: "";
    position: absolute;
    right: 0;
    bottom: 0;
    width: calc(var(--fold) * 1.4142);
    height: calc(var(--fold) * 1.4142);
    border-top-left-radius: 3px;
    background: linear-gradient(
      315deg,
      transparent 50%,
      var(--sticky-fold) 50%
    );
  }

  .sticky-field__body {
    position: relative;
    display: grid;
    gap: 0.4rem;
    /* Room on the right and at the bottom for the folded corner. */
    padding: 1.1rem 1.6rem 1.8rem 1.1rem;
  }

  label {
    font-weight: var(--ui-label-font-weight);
  }

  /* Writing on the note itself: ruled lines, no box, and the note's own ink for focus. */
  textarea {
    --line-height: calc(var(--reading-line-height) * 1em);
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    min-height: calc(var(--rows, 4) * var(--line-height) + 0.5rem);
    field-sizing: content;
    resize: none;
    border: 0;
    border-radius: 8px;
    padding: 0.25rem 0.4rem;
    background: repeating-linear-gradient(
        to bottom,
        transparent 0 calc(var(--line-height) - 1px),
        color-mix(in srgb, var(--sticky-ink) 16%, transparent)
          calc(var(--line-height) - 1px) var(--line-height)
      )
      0 0.25rem / 100% var(--line-height) local;
    color: var(--sticky-ink);
    font: inherit;
    font-size: var(--reading-font-size);
    line-height: var(--line-height);
    box-shadow: none;
    transition: box-shadow 120ms ease;
  }

  textarea:focus,
  textarea:focus-visible {
    outline: none;
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--sticky-ink) 45%, transparent);
  }

  @media (prefers-reduced-motion: reduce) {
    textarea {
      transition: none;
    }
  }

  small {
    color: color-mix(in srgb, var(--sticky-ink) 75%, transparent);
  }

  @media print {
    .sticky-field__paper {
      filter: none;
    }
  }
</style>
