<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { androidAction, connectAppHash } from "#lib/app-links.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import ConnectAppDialog from "#lib/features/account/ConnectAppDialog.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

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
  let connectDialog = $state<ReturnType<typeof ConnectAppDialog>>();

  /** One click: signed-in users get a setup QR right here, others sign in via the account page. */
  function connect(): void {
    if (session.data?.user) {
      void connectDialog?.start();
    } else {
      void goto(`${resolve("/account")}${connectAppHash}`);
    }
  }
</script>

<section
  class="app-card"
  class:app-card--prominent={variant === "prominent"}
  aria-labelledby="app-card-title"
>
  <!-- Android robot head, Phosphor Icons (fill, 2.1.1), MIT. -->
  <span class="app-card__logo" aria-hidden="true">
    <svg viewBox="0 0 256 256" focusable="false">
      <path
        fill="currentColor"
        d="M207.06,80.67c-.74-.74-1.49-1.46-2.24-2.17l24.84-24.84a8,8,0,0,0-11.32-11.32l-26,26a111.43,111.43,0,0,0-128.55.19L37.66,42.34A8,8,0,0,0,26.34,53.66L51.4,78.72A113.38,113.38,0,0,0,16,161.13V184a16,16,0,0,0,16,16H224a16,16,0,0,0,16-16V160A111.25,111.25,0,0,0,207.06,80.67ZM92,160a12,12,0,1,1,12-12A12,12,0,0,1,92,160Zm72,0a12,12,0,1,1,12-12A12,12,0,0,1,164,160Z"
      />
    </svg>
  </span>
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
      <Button variant="secondary" icon="key" onclick={connect}>
        {t("Connect the app", locale)}
      </Button>
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

<ConnectAppDialog bind:this={connectDialog} {locale} />

<style>
  /* Android green with the brand's dark navy for the robot. */
  .app-card__logo {
    --logo-size: 48px;
    display: grid;
    flex: none;
    width: var(--logo-size);
    height: var(--logo-size);
    place-items: center;
    border-radius: 50%;
    color: #073042;
    background: #3ddc84;
  }

  .app-card--prominent .app-card__logo {
    --logo-size: 72px;
  }

  .app-card__logo svg {
    width: 62%;
    height: 62%;
  }

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
