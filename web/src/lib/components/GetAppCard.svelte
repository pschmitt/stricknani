<script lang="ts">
  import { resolve } from "$app/paths";
  import { androidAction, connectAppHash } from "#lib/app-links.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import { t } from "#lib/i18n.svelte.ts";

  let {
    locale,
    variant = "compact",
    ondismiss,
  }: {
    locale: "en" | "de";
    variant?: "prominent" | "compact";
    ondismiss?: () => void;
  } = $props();

  const action = $derived(androidAction());
  const connectHref = $derived(`${resolve("/account")}${connectAppHash}`);
</script>

<section
  class="app-card"
  class:app-card--prominent={variant === "prominent"}
  aria-labelledby="app-card-title"
>
  <Mascot size={variant === "prominent" ? 72 : 48} />
  <div class="app-card__copy">
    <h2 id="app-card-title">
      {t("Knit offline with the Stricknani app for Android", locale)}
    </h2>
    {#if variant === "prominent"}
      <p>
        {t(
          "Your projects and yarn stay with you, even without a connection.",
          locale,
        )}
      </p>
    {/if}
    <div class="app-card__actions">
      <a
        class="ui-button ui-button--primary ui-button--md"
        href={action.url}
        target="_blank"
        rel="noopener noreferrer"
      >
        <ActionLabel icon="download">
          {action.kind === "play"
            ? t("Get it on Google Play", locale)
            : t("Download for Android", locale)}
        </ActionLabel>
      </a>
      <a
        class="ui-button ui-button--secondary ui-button--md"
        href={connectHref}
      >
        <ActionLabel icon="key">{t("Connect the app", locale)}</ActionLabel>
      </a>
    </div>
  </div>
  {#if ondismiss}
    <Button
      class="app-card__dismiss"
      variant="ghost"
      size="sm"
      icon="close"
      aria-label={t("Dismiss", locale)}
      onclick={ondismiss}
    />
  {/if}
</section>

<style>
  .app-card {
    position: relative;
    display: flex;
    align-items: center;
    gap: 1rem;
    border-radius: var(--radius-lg);
    padding: 1rem 3rem 1rem 1.1rem;
    background: var(--primary-container);
    color: var(--on-primary-container);
  }
  .app-card--prominent {
    flex-direction: column;
    align-items: flex-start;
    padding: 1.25rem 1.25rem 1.4rem;
  }
  .app-card__copy {
    min-width: 0;
  }
  h2 {
    margin: 0;
    font-family: var(--font-display);
    font-size: 1.05rem;
    line-height: 1.25;
  }
  .app-card--prominent h2 {
    font-size: 1.3rem;
  }
  p {
    margin: 0.35rem 0 0;
  }
  .app-card__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin-top: 0.75rem;
  }
  .app-card :global(.app-card__dismiss) {
    position: absolute;
    top: 0.4rem;
    right: 0.4rem;
  }
</style>
