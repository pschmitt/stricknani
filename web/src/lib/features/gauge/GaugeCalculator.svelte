<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    calculateGauge,
    type GaugeInput,
    type GaugeResult,
  } from "./gauge-api";

  type GaugeField = keyof GaugeInput;
  type GaugeValue = number | string | undefined;

  const locale = $derived(session.data?.locale ?? "en");
  let values = $state<Record<GaugeField, GaugeValue>>({
    pattern_gauge_stitches: undefined,
    pattern_gauge_rows: undefined,
    user_gauge_stitches: undefined,
    user_gauge_rows: undefined,
    pattern_cast_on_stitches: undefined,
    pattern_row_count: undefined,
  });
  const hasInputs = $derived(
    Object.values(values).some(
      (value) => value !== undefined && String(value).length > 0,
    ),
  );
  let fieldErrors = $state<Partial<Record<GaugeField, string>>>({});
  let result = $state<GaugeResult | null>(null);
  let resultInput = $state<GaugeInput | null>(null);
  let errorMessage = $state("");
  let submitting = $state(false);
  let controller: AbortController | null = null;
  let requestGeneration = 0;

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: hasInputs,
      busy: submitting,
    })),
  );

  onDestroy(() => {
    requestGeneration += 1;
    controller?.abort();
  });

  function parsePositiveU32(value: GaugeValue): number | null {
    if (value === undefined || (typeof value === "string" && !value.trim()))
      return null;
    const parsed = typeof value === "number" ? value : Number(value);
    return Number.isSafeInteger(parsed) && parsed > 0 && parsed <= 0xffff_ffff
      ? parsed
      : null;
  }

  function inputValues(): GaugeInput | null {
    const requiredFields = [
      "pattern_gauge_stitches",
      "pattern_gauge_rows",
      "user_gauge_stitches",
      "user_gauge_rows",
      "pattern_cast_on_stitches",
    ] as const;
    const parsed: Partial<Record<GaugeField, number>> = {};
    const nextErrors: Partial<Record<GaugeField, string>> = {};

    for (const field of requiredFields) {
      const value = parsePositiveU32(values[field]);
      if (value === null) {
        nextErrors[field] = t("Enter a positive whole number.", locale);
      } else {
        parsed[field] = value;
      }
    }

    const optionalValue = values.pattern_row_count;
    const hasOptionalRows = optionalValue !== undefined && optionalValue !== "";
    const optionalRows = hasOptionalRows
      ? parsePositiveU32(optionalValue)
      : null;
    if (hasOptionalRows && optionalRows === null) {
      nextErrors.pattern_row_count = t(
        "Enter a positive whole number.",
        locale,
      );
    }

    fieldErrors = nextErrors;
    if (Object.keys(nextErrors).length > 0) return null;

    const input: GaugeInput = {
      pattern_gauge_stitches: parsed.pattern_gauge_stitches!,
      pattern_gauge_rows: parsed.pattern_gauge_rows!,
      user_gauge_stitches: parsed.user_gauge_stitches!,
      user_gauge_rows: parsed.user_gauge_rows!,
      pattern_cast_on_stitches: parsed.pattern_cast_on_stitches!,
    };
    if (optionalRows !== null) input.pattern_row_count = optionalRows;
    return input;
  }

  function valueChanged() {
    requestGeneration += 1;
    controller?.abort();
    controller = null;
    submitting = false;
    result = null;
    resultInput = null;
    errorMessage = "";
    fieldErrors = {};
  }

  function clearInputs() {
    if (submitting) return;
    values = {
      pattern_gauge_stitches: undefined,
      pattern_gauge_rows: undefined,
      user_gauge_stitches: undefined,
      user_gauge_rows: undefined,
      pattern_cast_on_stitches: undefined,
      pattern_row_count: undefined,
    };
    valueChanged();
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (submitting) return;
    result = null;
    resultInput = null;
    errorMessage = "";
    const input = inputValues();
    if (!input) {
      errorMessage = t("Check the highlighted fields and try again.", locale);
      return;
    }

    const generation = ++requestGeneration;
    controller?.abort();
    const nextController = new AbortController();
    controller = nextController;
    submitting = true;
    try {
      const calculated = await calculateGauge(input, nextController.signal);
      if (generation !== requestGeneration) return;
      result = calculated;
      resultInput = input;
    } catch (error) {
      if (nextController.signal.aborted || generation !== requestGeneration)
        return;
      if (error instanceof ApiError && error.status === 422) {
        const serverFields = error.fields ?? {};
        const recognizedFields = Object.keys(serverFields).filter(
          (field): field is GaugeField => field in values,
        );
        fieldErrors = Object.fromEntries(
          recognizedFields.map((field) => [
            field,
            t("Enter a positive whole number.", locale),
          ]),
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

  <details class="help-panel">
    <summary>{t("How to use this calculator", locale)}</summary>
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
          "Click Calculate to see how many stitches and rows you need",
          locale,
        )}
      </li>
    </ol>
  </details>

  <form class="gauge-form" onsubmit={submit}>
    <fieldset>
      <legend>{t("Pattern Gauge (per 10cm)", locale)}</legend>
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
              aria-invalid={fieldErrors.pattern_gauge_rows ? "true" : undefined}
              aria-describedby={fieldErrors.pattern_gauge_rows
                ? "pattern-gauge-rows-error"
                : undefined}
              oninput={valueChanged}
            />
            {#if fieldErrors.pattern_gauge_rows}
              <small id="pattern-gauge-rows-error" class="field-error">
                {fieldErrors.pattern_gauge_rows}
              </small>
            {/if}
          </span>
        </label>
      </div>
    </fieldset>

    <fieldset>
      <legend>{t("Your Gauge (per 10cm)", locale)}</legend>
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
            />
            {#if fieldErrors.user_gauge_rows}
              <small id="user-gauge-rows-error" class="field-error">
                {fieldErrors.user_gauge_rows}
              </small>
            {/if}
          </span>
        </label>
      </div>
    </fieldset>

    <fieldset>
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

    {#if errorMessage}
      <p class="form-error" role="alert">{errorMessage}</p>
    {/if}

    <div class="form-actions">
      <Button
        class="calculate-button"
        variant="primary"
        icon="gauge"
        type="submit"
        disabled={submitting}
      >
        {submitting ? t("Calculating…", locale) : t("Calculate", locale)}
      </Button>
      <Button
        class="clear-button"
        variant="secondary"
        icon="reset"
        size="sm"
        type="button"
        onclick={clearInputs}
        disabled={submitting || !hasInputs}
      >
        {t("Clear", locale)}
      </Button>
    </div>
  </form>

  {#if result}
    <section class="results" aria-labelledby="results-title" aria-live="polite">
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

  .help-panel,
  .gauge-form,
  .results {
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: clamp(1rem, 3vw, 1.5rem);
    background: var(--surface);
  }

  .help-intro {
    max-width: 42rem;
    margin: 0.5rem 0 0;
    color: var(--ink-muted);
    line-height: 1.55;
  }

  .help-panel summary {
    min-height: 44px;
    display: flex;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 650;
    cursor: pointer;
  }

  .help-panel ol {
    display: grid;
    gap: 0.55rem;
    margin: 0.75rem 0 0;
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
    border-radius: 0.75rem;
    padding: 0.9rem;
  }

  legend {
    padding-inline: 0.35rem;
    color: var(--ink);
    font-weight: 680;
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
    min-height: 48px;
    border: 1px solid var(--control-border);
    border-radius: 0.65rem;
    padding: 0.55rem 0.7rem;
    color: var(--ink);
    background: var(--surface-raised);
  }

  input[aria-invalid="true"] {
    border-color: var(--danger);
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
    gap: 0.65rem;
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
    border-radius: 0.75rem;
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
