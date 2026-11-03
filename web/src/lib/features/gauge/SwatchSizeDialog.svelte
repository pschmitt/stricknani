<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import {
    DEFAULT_SWATCH_CM,
    formatCm,
    parseCm,
    type SwatchSize,
  } from "./swatch-size";

  let {
    id,
    locale,
    onsave,
  }: {
    id: string;
    locale: Locale;
    onsave: (size: SwatchSize) => void;
  } = $props();

  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let width = $state("");
  let height = $state("");
  let showErrors = $state(false);
  const parsedWidth = $derived(parseCm(width));
  const parsedHeight = $derived(parseCm(height));
  const error = $derived(t("Enter a size above 0 and up to 100 cm.", locale));

  export function open(size: SwatchSize): void {
    width = formatCm(size.width);
    height = formatCm(size.height);
    showErrors = false;
    dialog?.showModal();
  }

  function reset() {
    width = String(DEFAULT_SWATCH_CM);
    height = String(DEFAULT_SWATCH_CM);
    showErrors = false;
  }

  function done() {
    if (parsedWidth === null || parsedHeight === null) {
      showErrors = true;
      return;
    }
    onsave({ width: parsedWidth, height: parsedHeight });
    dialog?.close();
  }
</script>

<ConfirmDialog
  bind:this={dialog}
  titleId={`${id}-title`}
  title={t("Swatch size", locale)}
>
  {#snippet children()}
    <form
      class="size-form"
      onsubmit={(event) => {
        event.preventDefault();
        done();
      }}
    >
      <p class="hint">
        {t(
          "How big was the area you counted? Patterns usually use 10 × 10 cm.",
          locale,
        )}
      </p>
      <label for={`${id}-width`}>
        <span>{t("Width", locale)} (cm)</span>
        <input
          id={`${id}-width`}
          type="text"
          inputmode="decimal"
          bind:value={width}
          aria-invalid={showErrors && parsedWidth === null ? "true" : undefined}
          aria-describedby={showErrors && parsedWidth === null
            ? `${id}-width-error`
            : undefined}
        />
        {#if showErrors && parsedWidth === null}
          <small id={`${id}-width-error`} class="field-error">{error}</small>
        {/if}
      </label>
      <label for={`${id}-height`}>
        <span>{t("Height", locale)} (cm)</span>
        <input
          id={`${id}-height`}
          type="text"
          inputmode="decimal"
          bind:value={height}
          aria-invalid={showErrors && parsedHeight === null
            ? "true"
            : undefined}
          aria-describedby={showErrors && parsedHeight === null
            ? `${id}-height-error`
            : undefined}
        />
        {#if showErrors && parsedHeight === null}
          <small id={`${id}-height-error`} class="field-error">{error}</small>
        {/if}
      </label>
      <button type="submit" hidden aria-hidden="true" tabindex="-1">OK</button>
    </form>
  {/snippet}
  {#snippet actions()}
    <Button variant="ghost" size="sm" icon="reset" onclick={reset}>
      {t("Back to 10 × 10 cm", locale)}
    </Button>
    <Button variant="secondary" icon="cancel" onclick={() => dialog?.close()}>
      {t("Cancel", locale)}
    </Button>
    <Button variant="primary" icon="check" onclick={done}>
      {t("Done", locale)}
    </Button>
  {/snippet}
</ConfirmDialog>

<style>
  .size-form {
    display: grid;
    gap: 0.8rem;
  }
  .hint {
    margin: 0;
    color: var(--ink-muted);
  }
  label {
    display: grid;
    gap: 0.3rem;
    font-weight: 620;
  }
  .field-error {
    color: var(--danger);
    font-weight: 500;
  }
</style>
