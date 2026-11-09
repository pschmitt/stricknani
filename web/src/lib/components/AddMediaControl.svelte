<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import TakePhotoInput from "#lib/components/TakePhotoInput.svelte";
  import type { Locale } from "#lib/i18n.svelte.ts";

  /**
   * One obvious way to add photos or files: a single button that opens the picker (several files
   * at once), plus the camera on phones. A drop target around it (`use:fileDrop`) hands files to
   * the same `onFiles`. With `empty` it grows into a dashed drop area.
   */
  let {
    id,
    locale,
    label,
    inputLabel,
    accept = "image/*",
    disabled = false,
    hint = "",
    empty = false,
    dropHint = "",
    camera = false,
    variant = "secondary",
    onFiles,
  }: {
    id: string;
    locale: Locale;
    label: string;
    /** Accessible name of the hidden file input. */
    inputLabel: string;
    accept?: string;
    disabled?: boolean;
    /** One line shown while the control is disabled, such as why. */
    hint?: string;
    empty?: boolean;
    /** Shown next to the button in the empty state, such as "or drop images here". */
    dropHint?: string;
    camera?: boolean;
    variant?: "primary" | "secondary";
    onFiles: (files: File[]) => void;
  } = $props();

  let input = $state<HTMLInputElement | null>(null);

  function onChange(event: Event): void {
    const target = event.currentTarget;
    if (!(target instanceof HTMLInputElement)) return;
    const files = Array.from(target.files ?? []);
    target.value = "";
    if (disabled || files.length === 0) return;
    onFiles(files);
  }
</script>

<div class="add-media" class:add-media--empty={empty}>
  <div class="add-media__row">
    <Button
      type="button"
      {variant}
      icon="upload"
      {disabled}
      onclick={() => input?.click()}
    >
      {label}
    </Button>
    {#if camera}
      <TakePhotoInput {locale} {disabled} onchange={onChange} />
    {/if}
    {#if empty && dropHint}<span class="add-media__drop">{dropHint}</span>{/if}
  </div>
  <input
    bind:this={input}
    {id}
    type="file"
    {accept}
    multiple
    hidden
    {disabled}
    aria-label={inputLabel}
    onchange={onChange}
  />
  {#if disabled && hint}<p class="add-media__hint">{hint}</p>{/if}
</div>

<style>
  .add-media {
    display: grid;
    gap: 0.4rem;
    min-width: 0;
  }

  .add-media--empty {
    justify-items: center;
    border: 2px dashed var(--control-border);
    border-radius: var(--radius-sm);
    padding: 1.25rem 1rem;
    background: var(--surface-raised);
    text-align: center;
  }

  .add-media__row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.6rem;
  }

  .add-media--empty .add-media__row {
    justify-content: center;
  }

  .add-media__drop,
  .add-media__hint {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }
</style>
