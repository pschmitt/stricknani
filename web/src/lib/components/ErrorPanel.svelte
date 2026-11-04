<script lang="ts">
  import { resolve } from "$app/paths";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import SharedButton from "#lib/components/Button.svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import { ERROR_COPY, pickQuip, type ErrorKind } from "#lib/error-copy.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  // A playful full-panel error: the mascot reacts, a headline and a joke explain it,
  // and there is always a way back (plus "Try again" when retrying can help).
  let {
    kind,
    seed = "",
    detail = "",
    onretry,
    backHref,
    backLabel,
  }: {
    kind: ErrorKind;
    /** Picks the footnote joke; the same seed keeps the same joke. */
    seed?: string;
    /** Technical detail for the curious, shown small. */
    detail?: string;
    onretry?: () => void;
    /** An extra, closer way back than Home. */
    backHref?: string;
    backLabel?: string;
  } = $props();

  const locale = $derived(session.data?.locale ?? "en");
  const copy = $derived(ERROR_COPY[kind]);
  const quip = $derived(pickQuip(seed || kind, copy.quips));
  // Exactly one button is the primary action: retry, else the close way back, else Home.
  const backVariant = $derived(onretry ? "secondary" : "primary");
  const homeVariant = $derived(onretry || backHref ? "secondary" : "primary");
</script>

<section class="error-panel" aria-labelledby="error-panel-title">
  <div class="error-panel__art">
    <Mascot size={132} mood={copy.mood} />
  </div>
  <p class="error-panel__kicker">{t(copy.kicker, locale)}</p>
  <h1 id="error-panel-title">{t(copy.title, locale)}</h1>
  <p class="error-panel__body">{t(copy.body, locale)}</p>
  {#if quip}<p class="error-panel__quip">{t(quip, locale)}</p>{/if}
  <div class="error-panel__actions">
    {#if onretry}
      <SharedButton
        variant="primary"
        type="button"
        icon="refresh"
        onclick={onretry}
      >
        {t("Try again", locale)}
      </SharedButton>
    {/if}
    {#if backHref && backLabel}
      <a
        class="ui-button ui-button--{backVariant} ui-button--md"
        href={backHref}
        ><ActionLabel icon="previous">{t(backLabel, locale)}</ActionLabel></a
      >
    {/if}
    <a
      class="ui-button ui-button--{homeVariant} ui-button--md"
      href={resolve("/")}
      ><ActionLabel icon="home">{t("Back to Home", locale)}</ActionLabel></a
    >
  </div>
  {#if detail}<p class="error-panel__detail">{detail}</p>{/if}
</section>

<style>
  .error-panel {
    display: grid;
    max-width: 34rem;
    margin-inline: auto;
    justify-items: center;
    gap: 0.6rem;
    padding: clamp(1.5rem, 6vw, 3.5rem) 0;
    text-align: center;
  }

  .error-panel__art {
    margin-bottom: 0.5rem;
  }

  .error-panel__kicker {
    margin: 0;
    border-radius: 999px;
    padding: 0.25rem 0.8rem;
    color: var(--on-primary-container);
    background: var(--primary-container);
    font-size: 0.85rem;
    font-weight: 700;
  }

  h1 {
    margin: 0.2rem 0 0;
    font-size: clamp(1.8rem, 7vw, 2.6rem);
    line-height: 1.1;
    letter-spacing: -0.04em;
    text-wrap: balance;
  }

  .error-panel__body {
    margin: 0;
    color: var(--ink-muted);
    line-height: 1.6;
    text-wrap: pretty;
  }

  .error-panel__quip {
    margin: 0.2rem 0 0;
    color: var(--accent-strong);
    font-style: italic;
    font-weight: 600;
    text-wrap: balance;
  }

  .error-panel__actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0.6rem;
    margin-top: 1rem;
  }

  .error-panel__detail {
    margin: 1rem 0 0;
    color: var(--ink-muted);
    font-size: 0.8rem;
    overflow-wrap: anywhere;
  }
</style>
