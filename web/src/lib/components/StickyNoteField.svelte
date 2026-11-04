<script lang="ts">
  // The editable twin of StickyNote: a paper sticky note (same paper, fold, tilt, tape and shadow
  // tokens, and the same look as Android's StickyNoteCard) around a real, labelled textarea.
  import { focusOnHash } from "./focus-on-hash.ts";

  let {
    id,
    label,
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

<div class="sticky-field">
  <span class="sticky-field__paper" aria-hidden="true"></span>
  <div class="sticky-field__body">
    <label for={id}>{label}</label>
    <textarea
      {id}
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
    padding: 1.1rem 1.1rem 1.3rem;
  }

  label {
    font-weight: 700;
  }

  textarea {
    width: 100%;
    min-width: 0;
    min-height: 44px;
    resize: vertical;
    border: 0;
    border-bottom: 1px dashed
      color-mix(in srgb, var(--sticky-ink) 45%, transparent);
    border-radius: 0;
    background: transparent;
    color: var(--sticky-ink);
    font: inherit;
    line-height: 1.5;
  }

  textarea:focus-visible {
    outline: 2px solid var(--sticky-ink);
    outline-offset: 3px;
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
