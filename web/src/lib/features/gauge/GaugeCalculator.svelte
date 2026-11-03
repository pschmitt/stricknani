<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import KnittedSwatch from "./KnittedSwatch.svelte";
  import Disclosure from "#lib/components/Disclosure.svelte";
  import SwatchSizeDialog from "./SwatchSizeDialog.svelte";
  import {
    defaultSwatchSize,
    isDefaultSize,
    type SwatchSize,
  } from "./swatch-size";
  import { t } from "#lib/i18n.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    calculateGauge,
    type GaugeInput,
    type GaugeResult,
  } from "./gauge-api";
  import {
    GAUGE_FIELDS,
    parseGaugeValues,
    type GaugeField,
    type GaugeValue,
  } from "./gauge-input";

  const DEBOUNCE_MS = 300;
  const emptyValues = (): Record<GaugeField, GaugeValue> => ({
    pattern_gauge_stitches: undefined,
    pattern_gauge_rows: undefined,
    user_gauge_stitches: undefined,
    user_gauge_rows: undefined,
    pattern_cast_on_stitches: undefined,
    pattern_row_count: undefined,
  });

  const locale = $derived(session.data?.locale ?? "en");
  let values = $state<Record<GaugeField, GaugeValue>>(emptyValues());
  const hasInputs = $derived(
    Object.values(values).some(
      (value) =>
        value !== undefined && value !== null && String(value).length > 0,
    ),
  );
  const parsed = $derived(parseGaugeValues(values));
  let sizes = $state<{ pattern: SwatchSize; user: SwatchSize }>({
    pattern: defaultSwatchSize(),
    user: defaultSwatchSize(),
  });
  let patternSizeDialog = $state<ReturnType<typeof SwatchSizeDialog>>();
  let userSizeDialog = $state<ReturnType<typeof SwatchSizeDialog>>();

  /** Swatch sizes are only sent when they differ from the server's 10 x 10 cm default. */
  function withSizes(input: GaugeInput): GaugeInput {
    const next = { ...input };
    if (!isDefaultSize(sizes.pattern)) {
      next.pattern_swatch_width_cm = sizes.pattern.width;
      next.pattern_swatch_height_cm = sizes.pattern.height;
    }
    if (!isDefaultSize(sizes.user)) {
      next.user_swatch_width_cm = sizes.user.width;
      next.user_swatch_height_cm = sizes.user.height;
    }
    return next;
  }
  let touched = $state<Partial<Record<GaugeField, boolean>>>({});
  let submitAttempted = $state(false);
  let serverFields = $state<Partial<Record<GaugeField, boolean>>>({});
  // Empty fields only complain after blur or an explicit submit; clearly invalid
  // values complain at once.
  const fieldErrors = $derived.by(() => {
    const errors: Partial<Record<GaugeField, string>> = {};
    for (const field of GAUGE_FIELDS) {
      const state = parsed.states[field];
      if (
        serverFields[field] ||
        state === "invalid" ||
        (state === "empty" && (touched[field] || submitAttempted))
      ) {
        errors[field] = t("Enter a positive whole number.", locale);
      }
    }
    return errors;
  });
  let result = $state<GaugeResult | null>(null);
  let resultInput = $state<GaugeInput | null>(null);
  let errorMessage = $state("");
  let pending = $state(false);
  let submitting = $state(false);
  const calculating = $derived(pending || submitting);
  let controller: AbortController | null = null;
  let debounceTimer: ReturnType<typeof setTimeout> | undefined;
  let requestGeneration = 0;

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: hasInputs,
      busy: calculating,
    })),
  );

  onDestroy(() => {
    requestGeneration += 1;
    clearTimeout(debounceTimer);
    controller?.abort();
  });

  function cancelInFlight() {
    requestGeneration += 1;
    clearTimeout(debounceTimer);
    controller?.abort();
    controller = null;
    pending = false;
    submitting = false;
  }

  function sizeChanged(which: "pattern" | "user", size: SwatchSize) {
    sizes[which] = size;
    valueChanged();
  }

  function touch(field: GaugeField) {
    touched[field] = true;
  }

  function valueChanged() {
    cancelInFlight();
    errorMessage = "";
    serverFields = {};
    submitAttempted = false;
    const { input } = parseGaugeValues(values);
    if (!input) {
      // Incomplete or invalid: drop the result rather than show a stale one.
      result = null;
      resultInput = null;
      return;
    }
    // Keep the previous result (dimmed) until the new one arrives.
    pending = true;
    debounceTimer = setTimeout(() => void run(withSizes(input)), DEBOUNCE_MS);
  }

  function clearInputs() {
    cancelInFlight();
    values = emptyValues();
    sizes = { pattern: defaultSwatchSize(), user: defaultSwatchSize() };
    touched = {};
    submitAttempted = false;
    serverFields = {};
    result = null;
    resultInput = null;
    errorMessage = "";
  }

  async function run(input: GaugeInput) {
    cancelInFlight();
    const generation = requestGeneration;
    const nextController = new AbortController();
    controller = nextController;
    submitting = true;
    errorMessage = "";
    try {
      const calculated = await calculateGauge(input, nextController.signal);
      if (generation !== requestGeneration) return;
      result = calculated;
      resultInput = input;
    } catch (error) {
      if (nextController.signal.aborted || generation !== requestGeneration)
        return;
      result = null;
      resultInput = null;
      if (error instanceof ApiError && error.status === 422) {
        const recognized = Object.keys(error.fields ?? {}).filter(
          (field): field is GaugeField => field in values,
        );
        serverFields = Object.fromEntries(
          recognized.map((field) => [field, true]),
        );
        errorMessage = t("Check the highlighted fields and try again.", locale);
      } else if (error instanceof ApiError && error.status === 413) {
        errorMessage = t(
          "The calculation request is too large. Reduce the values and try again.",
          locale,
        );
      } else {
        errorMessage =
          error instanceof Error
            ? error.message
            : t("The calculation could not be completed.", locale);
      }
    } finally {
      if (generation === requestGeneration) {
        submitting = false;
        controller = null;
      }
    }
  }

  // Enter in a field calculates right away instead of waiting for the debounce.
  function submit(event: SubmitEvent) {
    event.preventDefault();
    const { input } = parseGaugeValues(values);
    if (!input) {
      cancelInFlight();
      result = null;
      resultInput = null;
      submitAttempted = true;
      errorMessage = t("Check the highlighted fields and try again.", locale);
      return;
    }
    void run(withSizes(input));
  }
</script>

<svelte:head>
  <title>Stricknani · {t("Gauge calculator", locale)}</title>
  <meta
    name="description"
    content={t(
      "Calculate how many stitches to cast on and rows to knit based on your gauge and the pattern's gauge.",
      locale,
    )}
  />
</svelte:head>

<section
  class="gauge-page page-container page-container--compact"
  aria-labelledby="gauge-title"
>
  <PageHeader title={t("Gauge calculator", locale)} titleId="gauge-title" />

  <Disclosure
    class="help-panel"
    icon="help"
    label={t("How to use this calculator", locale)}
  >
    <p class="help-intro">
      {t(
        "Calculate how many stitches to cast on and rows to knit based on your gauge and the pattern's gauge.",
        locale,
      )}
    </p>
    <ol>
      <li>
        {t(
          "Enter the gauge from your pattern (stitches and rows per 10cm)",
          locale,
        )}
      </li>
      <li>
        {t(
          "Enter your actual gauge from your swatch (stitches and rows per 10cm)",
          locale,
        )}
      </li>
      <li>
        {t(
          "Enter the stitch count from your pattern (cast-on stitches)",
          locale,
        )}
      </li>
      <li>
        {t(
          "The result appears automatically as soon as every field is filled in",
          locale,
        )}
      </li>
    </ol>
  </Disclosure>

  <form class="gauge-form" onsubmit={submit}>
    <fieldset class="swatch-fieldset swatch-fieldset--pattern">
      <legend>{t("Pattern Gauge (per 10cm)", locale)}</legend>
      <div class="swatch-row">
        <KnittedSwatch
          stitches={values.pattern_gauge_stitches ?? undefined}
          rows={values.pattern_gauge_rows ?? undefined}
          size={sizes.pattern}
          changeLabel={t("Change swatch size", locale)}
          onchange={() => patternSizeDialog?.open(sizes.pattern)}
        />
        <div class="field-grid">
          <label for="pattern-gauge-stitches">
            <span class="field-caption">{t("Stitches", locale)}</span>
            <span class="field-control">
              <input
                bind:value={values.pattern_gauge_stitches}
                id="pattern-gauge-stitches"
                name="pattern_gauge_stitches"
                type="number"
                min="1"
                max="4294967295"
                step="1"
                inputmode="numeric"
                required
                aria-invalid={fieldErrors.pattern_gauge_stitches
                  ? "true"
                  : undefined}
                aria-describedby={fieldErrors.pattern_gauge_stitches
                  ? "pattern-gauge-stitches-error"
                  : undefined}
                oninput={valueChanged}
                onblur={() => touch("pattern_gauge_stitches")}
              />
              {#if fieldErrors.pattern_gauge_stitches}
                <small id="pattern-gauge-stitches-error" class="field-error">
                  {fieldErrors.pattern_gauge_stitches}
                </small>
              {/if}
            </span>
          </label>

          <label for="pattern-gauge-rows">
            <span class="field-caption">{t("Rows", locale)}</span>
            <span class="field-control">
              <input
                bind:value={values.pattern_gauge_rows}
                id="pattern-gauge-rows"
                name="pattern_gauge_rows"
                type="number"
                min="1"
                max="4294967295"
                step="1"
                inputmode="numeric"
                required
                aria-invalid={fieldErrors.pattern_gauge_rows
                  ? "true"
                  : undefined}
                aria-describedby={fieldErrors.pattern_gauge_rows
                  ? "pattern-gauge-rows-error"
                  : undefined}
                oninput={valueChanged}
                onblur={() => touch("pattern_gauge_rows")}
              />
              {#if fieldErrors.pattern_gauge_rows}
                <small id="pattern-gauge-rows-error" class="field-error">
                  {fieldErrors.pattern_gauge_rows}
                </small>
              {/if}
            </span>
          </label>
        </div>
      </div>
    </fieldset>

    <fieldset class="swatch-fieldset swatch-fieldset--pattern counts-fieldset">
      <legend>{t("Pattern Counts", locale)}</legend>
      <div class="field-grid">
        <label for="pattern-cast-on-stitches">
          <span class="field-caption">{t("Stitches to cast on", locale)}</span>
          <span class="field-control">
            <input
              bind:value={values.pattern_cast_on_stitches}
              id="pattern-cast-on-stitches"
              name="pattern_cast_on_stitches"
              type="number"
              min="1"
              max="4294967295"
              step="1"
              inputmode="numeric"
              required
              aria-invalid={fieldErrors.pattern_cast_on_stitches
                ? "true"
                : undefined}
              aria-describedby={fieldErrors.pattern_cast_on_stitches
                ? "pattern-cast-on-stitches-error"
                : undefined}
              oninput={valueChanged}
              onblur={() => touch("pattern_cast_on_stitches")}
            />
            {#if fieldErrors.pattern_cast_on_stitches}
              <small id="pattern-cast-on-stitches-error" class="field-error">
                {fieldErrors.pattern_cast_on_stitches}
              </small>
            {/if}
          </span>
        </label>

        <label for="pattern-row-count">
          <span class="field-caption">
            {t("Rows to knit", locale)}
            <span class="optional">({t("optional", locale)})</span>
          </span>
          <span class="field-control">
            <input
              bind:value={values.pattern_row_count}
              id="pattern-row-count"
              name="pattern_row_count"
              type="number"
              min="1"
              max="4294967295"
              step="1"
              inputmode="numeric"
              aria-invalid={fieldErrors.pattern_row_count ? "true" : undefined}
              aria-describedby={fieldErrors.pattern_row_count
                ? "pattern-row-count-error"
                : undefined}
              oninput={valueChanged}
              onblur={() => touch("pattern_row_count")}
            />
            {#if fieldErrors.pattern_row_count}
              <small id="pattern-row-count-error" class="field-error">
                {fieldErrors.pattern_row_count}
              </small>
            {/if}
          </span>
        </label>
      </div>
    </fieldset>

    <fieldset class="swatch-fieldset swatch-fieldset--yours">
      <legend>{t("Your Gauge (per 10cm)", locale)}</legend>
      <div class="swatch-row">
        <KnittedSwatch
          stitches={values.user_gauge_stitches ?? undefined}
          rows={values.user_gauge_rows ?? undefined}
          size={sizes.user}
          changeLabel={t("Change swatch size", locale)}
          onchange={() => userSizeDialog?.open(sizes.user)}
        />
        <div class="field-grid">
          <label for="user-gauge-stitches">
            <span class="field-caption">{t("Stitches", locale)}</span>
            <span class="field-control">
              <input
                bind:value={values.user_gauge_stitches}
                id="user-gauge-stitches"
                name="user_gauge_stitches"
                type="number"
                min="1"
                max="4294967295"
                step="1"
                inputmode="numeric"
                required
                aria-invalid={fieldErrors.user_gauge_stitches
                  ? "true"
                  : undefined}
                aria-describedby={fieldErrors.user_gauge_stitches
                  ? "user-gauge-stitches-error"
                  : undefined}
                oninput={valueChanged}
                onblur={() => touch("user_gauge_stitches")}
              />
              {#if fieldErrors.user_gauge_stitches}
                <small id="user-gauge-stitches-error" class="field-error">
                  {fieldErrors.user_gauge_stitches}
                </small>
              {/if}
            </span>
          </label>

          <label for="user-gauge-rows">
            <span class="field-caption">{t("Rows", locale)}</span>
            <span class="field-control">
              <input
                bind:value={values.user_gauge_rows}
                id="user-gauge-rows"
                name="user_gauge_rows"
                type="number"
                min="1"
                max="4294967295"
                step="1"
                inputmode="numeric"
                required
                aria-invalid={fieldErrors.user_gauge_rows ? "true" : undefined}
                aria-describedby={fieldErrors.user_gauge_rows
                  ? "user-gauge-rows-error"
                  : undefined}
                oninput={valueChanged}
                onblur={() => touch("user_gauge_rows")}
              />
              {#if fieldErrors.user_gauge_rows}
                <small id="user-gauge-rows-error" class="field-error">
                  {fieldErrors.user_gauge_rows}
                </small>
              {/if}
            </span>
          </label>
        </div>
      </div>
    </fieldset>

    {#if errorMessage}
      <p class="form-error" role="alert">{errorMessage}</p>
    {/if}

    <div class="form-actions">
      <Button
        class="clear-button"
        variant="secondary"
        icon="reset"
        size="sm"
        type="button"
        onclick={clearInputs}
        disabled={!hasInputs}
      >
        {t("Clear", locale)}
      </Button>
      <p class="calc-status" class:visible={calculating} role="status">
        {calculating ? t("Calculating…", locale) : ""}
      </p>
    </div>
  </form>

  <SwatchSizeDialog
    bind:this={patternSizeDialog}
    id="pattern-swatch-size"
    {locale}
    onsave={(size) => sizeChanged("pattern", size)}
  />
  <SwatchSizeDialog
    bind:this={userSizeDialog}
    id="user-swatch-size"
    {locale}
    onsave={(size) => sizeChanged("user", size)}
  />

  {#if result}
    <section
      class="results"
      class:stale={calculating}
      aria-labelledby="results-title"
      aria-live="polite"
      aria-busy={calculating}
    >
      <h2 id="results-title">{t("Calculation Results", locale)}</h2>
      <div class="result-grid">
        <div>
          <p>{t("Adjusted Stitches", locale)}</p>
          <strong>{result.adjusted_stitches}</strong>
        </div>
        <div>
          <p>{t("Adjusted Rows", locale)}</p>
          <strong>{result.adjusted_rows ?? t("N/A", locale)}</strong>
        </div>
      </div>
      {#if resultInput}
        <p class="result-context">
          {t("Based on a pattern cast-on of", locale)}
          {resultInput.pattern_cast_on_stitches}
          {t("stitches", locale)}
          {#if resultInput.pattern_row_count}
            {t("and", locale)}
            {resultInput.pattern_row_count}
            {t("rows", locale)}
          {/if}.
        </p>
      {/if}
    </section>
  {/if}
</section>

<style>
  .gauge-page {
    display: grid;
    gap: 1rem;
  }

  .gauge-form,
  .results {
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: clamp(1rem, 3vw, 1.5rem);
    background: var(--surface);
  }

  .help-intro,
  :global(.help-panel) ol {
    margin-inline: clamp(1rem, 3vw, 1.5rem);
  }

  .help-intro {
    max-width: 42rem;
    margin-block: 0.25rem 0;
    color: var(--ink-muted);
    line-height: 1.55;
  }

  :global(.help-panel) ol {
    display: grid;
    gap: 0.55rem;
    margin-block: 0.75rem 1.25rem;
    padding-left: 1.35rem;
    color: var(--ink-muted);
  }

  .gauge-form {
    display: grid;
    gap: 1.1rem;
  }

  fieldset {
    min-width: 0;
    margin: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0.9rem;
  }

  legend {
    padding-inline: 0.35rem;
    color: var(--ink);
    font-weight: 680;
  }

  /* The two gauge fieldsets are swatch cards like on Android: the pattern's in marigold,
     yours in lagoon, each with a little knitted square that follows the numbers. */
  .swatch-fieldset {
    --swatch-tone: var(--tone-projects);
    border: 1.5px dashed color-mix(in srgb, var(--swatch-tone) 45%, transparent);
    border-radius: var(--radius-lg, 28px);
    padding: 1rem 1.1rem 1.15rem;
    background: color-mix(in srgb, var(--swatch-tone) 12%, var(--surface));
  }
  .swatch-fieldset--yours {
    --swatch-tone: var(--tone-yarns);
  }
  .swatch-fieldset legend {
    color: var(--swatch-tone);
    font-family: var(--font-display);
    font-size: 1.08rem;
  }
  .swatch-row {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    align-items: center;
    gap: 1rem;
  }
  .swatch-row :global(.knitted-swatch) {
    color: var(--swatch-tone);
  }
  @media (max-width: 26rem) {
    .swatch-row {
      grid-template-columns: 1fr;
    }
  }

  .field-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: auto auto;
    column-gap: 0.8rem;
    row-gap: 0.35rem;
  }

  label {
    display: grid;
    grid-row: span 2;
    grid-template-rows: subgrid;
    min-width: 0;
    color: var(--ink);
    font-weight: 620;
  }

  .field-caption {
    display: flex;
    min-width: 0;
    align-items: baseline;
    flex-wrap: wrap;
    column-gap: 0.3rem;
  }

  .field-control {
    display: grid;
    align-content: start;
    gap: 0.35rem;
    min-width: 0;
  }

  .optional {
    color: var(--ink-muted);
    font-size: 0.82rem;
    font-weight: 450;
  }

  input {
    width: 100%;
    min-width: 0;
  }

  .field-error,
  .form-error {
    color: var(--danger);
  }

  .field-error {
    font-size: 0.85rem;
    font-weight: 500;
  }

  .form-error {
    margin: 0;
    border-inline-start: 3px solid var(--danger);
    padding: 0.65rem 0.8rem;
    background: var(--surface-raised);
  }

  .form-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.65rem;
  }

  .calc-status {
    margin: 0;
    min-height: 1.4em;
    color: var(--ink-muted);
    font-size: 0.9rem;
    opacity: 0;
  }

  .calc-status.visible {
    opacity: 1;
  }

  .results.stale {
    opacity: 0.55;
  }

  @media (prefers-reduced-motion: no-preference) {
    .calc-status,
    .results {
      transition: opacity 0.2s ease;
    }

    .results {
      animation: results-in 0.25s ease both;
    }
  }

  @keyframes results-in {
    from {
      opacity: 0;
    }
  }

  .results h2 {
    margin: 0 0 0.85rem;
    font-size: 1.2rem;
  }

  .result-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.75rem;
  }

  .result-grid > div {
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0.8rem;
    background: var(--surface-raised);
  }

  .result-grid p {
    margin: 0;
    color: var(--ink-muted);
  }

  .result-grid strong {
    display: block;
    margin-top: 0.3rem;
    font-size: clamp(1.5rem, 6vw, 2.2rem);
    line-height: 1.1;
    overflow-wrap: anywhere;
  }

  .result-context {
    margin: 0.8rem 0 0;
    color: var(--ink-muted);
  }

  @media (max-width: 36rem) {
    .field-grid {
      grid-template-columns: 1fr;
      grid-template-rows: none;
      gap: 0.8rem;
    }

    label {
      grid-row: auto;
      grid-template-rows: auto auto;
      row-gap: 0.35rem;
    }

    .result-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
