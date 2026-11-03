<script lang="ts">
  import { onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import SharedButton from "#lib/components/Button.svelte";
  import BrandHeader from "#lib/components/BrandHeader.svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import LocaleSwitcher from "#lib/components/LocaleSwitcher.svelte";
  import { androidAction } from "#lib/app-links.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session, signIn } from "#lib/session.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";

  let email = $state("");
  let password = $state("");
  let submitting = $state(false);
  let failure = $state<ApiError | Error | null>(null);

  const locale = $derived(session.data?.locale ?? "en");
  const accountCreated = $derived(page.url.searchParams.get("created") === "1");

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: email.length > 0 || password.length > 0,
      busy: submitting,
    })),
  );

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (submitting) return;

    failure = null;
    submitting = true;
    try {
      await signIn(email.trim(), password);
      password = "";
      await goto(resolve("/projects"));
    } catch (error) {
      failure =
        error instanceof Error
          ? error
          : new Error(t("Sign in failed.", locale));
    } finally {
      submitting = false;
    }
  }
</script>

<svelte:head>
  <title>{t("Sign in", locale)} · Stricknani</title>
  <meta name="theme-color" content="#f7f3fc" />
</svelte:head>

<main id="main-content" class="auth-page">
  <section class="auth-panel" aria-labelledby="auth-title">
    <BrandHeader href="/app/" />

    <div class="auth-copy">
      <div class="auth-hello">
        <Mascot size={88} bob={true} />
        <p class="welcome-line">{t("Hello, knitter!", locale)}</p>
      </div>
      <h1 id="auth-title">{t("Sign in", locale)}</h1>
      <p class="auth-description">{t("Pick up where you left off.", locale)}</p>
    </div>
    {#if session.expired}
      <div class="form-error" role="status">
        {t("Your session expired. Sign in again to continue.", locale)}
      </div>
    {/if}

    {#if accountCreated}
      <div class="form-success" role="status">
        {t(
          "Your account is ready. Sign in with your email and password.",
          locale,
        )}
      </div>
    {/if}

    {#if failure}
      <div class="form-error" role="alert">
        <strong>{failure.message}</strong>
        {#if failure instanceof ApiError && failure.fields}
          <ul>
            {#each Object.entries(failure.fields) as [field, message]}
              <li>{field}: {message}</li>
            {/each}
          </ul>
        {/if}
      </div>
    {/if}

    <form class="auth-form" onsubmit={submit}>
      <label for="email">{t("Email", locale)}</label>
      <input
        bind:value={email}
        autocomplete="username"
        id="email"
        name="email"
        type="email"
        inputmode="email"
        required
        autocapitalize="none"
        spellcheck="false"
        disabled={submitting}
      />

      <label for="password">{t("Password", locale)}</label>
      <input
        bind:value={password}
        autocomplete="current-password"
        id="password"
        name="password"
        type="password"
        required
        disabled={submitting}
      />

      <SharedButton
        variant="primary"
        class="auth-submit"
        type="submit"
        disabled={submitting || !session.data?.csrf_token}
      >
        {#if submitting}
          <span class="spinner spinner-light" aria-hidden="true"></span>
          <ActionLabel icon="sign-in">{t("Signing in…", locale)}</ActionLabel>
        {:else}
          <ActionLabel icon="sign-in">{t("Sign in", locale)}</ActionLabel>
        {/if}
      </SharedButton>
    </form>

    <p class="auth-footnote">
      {t("Your projects stay on your own Stricknani server.", locale)}
    </p>
    {#if session.data?.capabilities.signup}
      <p class="auth-signup-link">
        {t("New to Stricknani?", locale)}
        <a href={resolve("/signup")}>{t("Create an account", locale)}</a>
      </p>
    {/if}
    <p class="auth-privacy">
      {t("Learn how Stricknani handles your data in the", locale)}
      <a href={resolve("/privacy")}>{t("Privacy Policy", locale)}</a>.
    </p>
    <p class="auth-app-link">
      <a href={androidAction().url} target="_blank" rel="noopener noreferrer"
        >{t("Get the Android app", locale)}</a
      >
    </p>
    <footer class="auth-footer">
      <LocaleSwitcher />
      <a
        class="ui-button ui-button--secondary ui-button--md"
        href={resolve("/gauge")}
        ><ActionLabel icon="gauge">{t("Gauge calculator", locale)}</ActionLabel
        ></a
      >
    </footer>
  </section>
  <div class="auth-side" aria-hidden="true">
    <div class="thread-art">
      <span class="thread-loop thread-loop-one"></span>
      <span class="thread-loop thread-loop-two"></span>
      <span class="thread-loop thread-loop-three"></span>
    </div>
    <p>{t("One row at a time.", locale)}</p>
  </div>
</main>

<style>
  .auth-page {
    display: grid;
    width: 100%;
    min-width: 0;
    min-height: 100svh;
    grid-template-columns: minmax(0, 1fr) minmax(16rem, 0.8fr);
    padding: clamp(1rem, 3vw, 2.5rem);
  }

  .auth-panel {
    position: relative;
    align-self: center;
    width: min(100%, 28rem);
    margin-inline: auto;
    padding: clamp(1.5rem, 5vw, 3rem);
    border-radius: var(--radius-lg);
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  /* The running stitch, sewn just inside the card's edge. */
  .auth-panel::after {
    position: absolute;
    inset: 7px;
    border: 1.6px dashed color-mix(in srgb, var(--accent) 45%, transparent);
    border-radius: 21px;
    content: "";
    pointer-events: none;
  }

  .auth-copy {
    margin-top: 1.25rem;
  }

  .auth-hello {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin-bottom: 0.9rem;
  }

  .welcome-line {
    min-width: 0;
    margin: 0;
    color: var(--ink);
    font-family: var(--font-display);
    font-size: clamp(1.45rem, 5.4vw, 2.2rem);
    overflow-wrap: anywhere;
    font-weight: 700;
    line-height: 1.1;
    font-variation-settings:
      "SOFT" 100,
      "WONK" 1;
  }

  h1 {
    margin: 0;
    color: var(--accent-strong);
    font-size: 1.35rem;
    line-height: 1.2;
  }

  .auth-description {
    margin: 0.8rem 0 0;
    color: var(--ink-muted);
  }

  .auth-form {
    display: grid;
    gap: 0.6rem;
    margin-top: 2rem;
  }

  label {
    margin-top: 0.7rem;
    font-size: 0.92rem;
    font-weight: 800;
  }

  input {
    width: 100%;
  }

  input:focus {
    border-color: var(--focus);
  }

  :global(.auth-submit) {
    width: 100%;
    margin-top: 1rem;
  }

  :global(.spinner-light) {
    width: 1.25rem;
    border: 2px solid transparent;
    border-radius: 50%;
    background: none;
    border-color: color-mix(in srgb, var(--on-accent) 35%, transparent);
    border-top-color: var(--on-accent);
  }

  .form-error {
    margin-top: 1.4rem;
    border: 1px solid color-mix(in srgb, var(--danger) 38%, transparent);
    border-radius: var(--radius-xs);
    padding: 0.8rem 0.9rem;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  .form-success {
    margin-top: 1.4rem;
    border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
    border-radius: var(--radius-xs);
    padding: 0.8rem 0.9rem;
    color: var(--success);
    background: var(--success-surface);
  }

  .form-error ul {
    margin: 0.5rem 0 0;
    padding-left: 1.2rem;
  }

  .auth-footnote {
    margin: 1.25rem 0 0;
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .auth-signup-link {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0 0.35rem;
    margin: 0.65rem 0 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .auth-signup-link a {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 650;
  }

  .auth-app-link {
    margin: 0;
    font-size: 0.9rem;
  }

  .auth-app-link a {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 650;
  }

  .auth-privacy {
    margin: 0.5rem 0 0;
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .auth-privacy a {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 650;
  }

  .auth-footer {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    margin-top: 0.85rem;
  }

  .auth-side {
    position: relative;
    display: flex;
    min-height: 30rem;
    align-items: flex-end;
    justify-content: flex-end;
    margin: 1rem;
    border-radius: var(--radius-xl);
    padding: clamp(1.5rem, 4vw, 3.5rem);
    overflow: hidden;
    color: var(--on-primary-container);
    background: var(--primary-container);
  }

  .auth-side p {
    position: relative;
    z-index: 1;
    margin: 0;
    font-family: var(--font-display);
    font-size: clamp(1.3rem, 2vw, 1.9rem);
    font-weight: 700;
    font-variation-settings:
      "SOFT" 100,
      "WONK" 1;
  }

  .thread-art {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    opacity: 0.6;
    transform: rotate(-20deg) scale(1.35);
  }

  .thread-loop {
    position: absolute;
    width: 9rem;
    height: 16rem;
    border: 1.7rem solid var(--accent);
    border-radius: 48% 52% 45% 55%;
  }

  .thread-loop-two {
    border-color: var(--tone-yarns);
  }

  .thread-loop-three {
    border-color: var(--tone-projects);
  }

  .thread-loop-one {
    transform: translate(-3.3rem, -1rem) rotate(-33deg);
  }

  .thread-loop-two {
    transform: translate(2.7rem, -0.4rem) rotate(32deg);
  }

  .thread-loop-three {
    width: 5.5rem;
    height: 11rem;
    border-width: 1rem;
    transform: translate(0, 3.7rem) rotate(2deg);
  }

  @media (max-width: 390px) {
    .thread-art {
      display: none;
    }
  }

  @media (max-width: 720px) {
    .auth-page {
      grid-template-columns: minmax(0, 1fr);
      align-content: center;
      padding: 1rem;
    }

    .auth-panel {
      padding: clamp(1.35rem, 7vw, 2rem);
    }

    .auth-side {
      min-height: 7.5rem;
      margin: 1rem 0 0;
      padding: 1rem 1.25rem;
    }

    .thread-art {
      place-items: center end;
      transform: rotate(-20deg) scale(0.55);
    }
  }
</style>
