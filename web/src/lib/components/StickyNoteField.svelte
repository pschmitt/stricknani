<script lang="ts">
  // The editable twin of StickyNote: a paper sticky note (same paper, fold, tilt, tape and shadow
  // tokens, and the same look as Android's StickyNoteCard) around a real, labelled textarea.
  import LabelIcon from "./LabelIcon.svelte";
  import MarkdownEditor from "./MarkdownEditor.svelte";
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
    <label for={id} id={`${id}-label`}
      >{#if icon}<LabelIcon name={icon} />{/if}{label}</label
    >
    <MarkdownEditor
      {id}
      labelledBy={`${id}-label`}
      describedBy={hint ? `${id}-hint` : undefined}
      sticky
      bind:value
      {rows}
      {maxlength}
      {disabled}
      {onfocus}
      {onInput}
    />
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
    /* Room on the right and at the bottom for the folded corner (its square is the fold
       times the square root of 2): the ruled lines stop before it. */
    padding: 1.1rem calc(var(--fold) * 1.4142 + 0.6rem)
      calc(var(--fold) * 1.4142 + 0.5rem) 1.1rem;
  }

  label {
    font-weight: var(--ui-label-font-weight);
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
