<script lang="ts">
  import { onMount, untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import SharedButton from "#lib/components/Button.svelte";
  import AuthFooter from "#lib/components/AuthFooter.svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import {
    registrationOpen,
    selfHostCardVisible,
  } from "#lib/features/auth/registration-gate.ts";
  import SelfHostCard from "#lib/components/SelfHostCard.svelte";
  import OidcSignIn from "#lib/features/auth/OidcSignIn.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import {
    renewLoginCsrf,
    session,
    signIn,
    signInWithPasskey,
  } from "#lib/session.svelte.ts";
  import {
    conditionalMediationSupported,
    passkeysSupported,
  } from "#lib/features/auth/passkey-api.ts";
  import {
    classifyPasskeyLoginFailure,
    runConditionalSignIn,
    type PasskeyLoginFailure,
  } from "#lib/features/auth/passkey-login.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";

  let email = $state("");
  let password = $state("");
  let submitting = $state(false);
  let failure = $state<ApiError | Error | null>(null);
  let passkeyBrowser = $state(false);
  let passkeyAutofill = $state(false);
  let passkeyBusy = $state(false);
  let passkeyFailure = $state<PasskeyLoginFailure | null>(null);
  // Bumped to restart the autofill request after the button flow ended.
  let autofillRound = $state(0);
  let autofillController: AbortController | null = null;

  const locale = $derived(session.data?.locale ?? "en");
  const accountCreated = $derived(page.url.searchParams.get("created") === "1");
  const accountDeleted = $derived(page.url.searchParams.get("goodbye") === "1");
  // Passkeys need the server to have them configured and a browser that can use them.
  const passkeysAvailable = $derived(
    passkeyBrowser && session.data?.capabilities.passkeys === true,
  );
  const autofillReady = $derived(
    passkeysAvailable && passkeyAutofill && !!session.data?.csrf_token,
  );

  onMount(() => {
    passkeyBrowser = passkeysSupported();
    void conditionalMediationSupported().then(
      (supported) => (passkeyAutofill = supported),
    );
    return registerPwaUpdateGuard(() => ({
      dirty: email.length > 0 || password.length > 0,
      busy: submitting || passkeyBusy,
    }));
  });

  // A passkey request that stays pending behind the email field's autofill, so
  // choosing a passkey there signs in without pressing anything.
  $effect(() => {
    void autofillRound;
    if (!autofillReady) return;
    const controller = new AbortController();
    autofillController = controller;
    untrack(() => {
      void runConditionalSignIn({
        signIn: signInWithPasskey,
        renew: () => renewLoginCsrf(controller.signal),
        onSignedIn: () => goto(resolve("/projects")),
        onFailure: (reason) => (passkeyFailure = reason),
        signal: controller.signal,
      });
    });
    return () => {
      controller.abort();
      if (autofillController === controller) autofillController = null;
    };
  });

  async function submitPasskey() {
    if (passkeyBusy || submitting) return;
    passkeyFailure = null;
    passkeyBusy = true;
    autofillController?.abort();
    try {
      await signInWithPasskey();
      await goto(resolve("/projects"));
    } catch (error) {
      passkeyFailure = classifyPasskeyLoginFailure(error);
    } finally {
      passkeyBusy = false;
      autofillRound += 1;
    }
  }

  function passkeyFailureMessage(reason: PasskeyLoginFailure): string {
    switch (reason) {
      case "cancelled":
        return t(
          "Passkey sign-in was cancelled. Try again or use your password.",
          locale,
        );
      case "not_recognized":
        return t(
          "That passkey is not registered for any account here. Use another passkey or your password.",
          locale,
        );
      case "rate_limited":
        return t("Too many attempts. Wait a moment and try again.", locale);
      case "unavailable":
        return t(
          "Passkeys do not work on this page right now. Use your password.",
          locale,
        );
      default:
        return t("Passkey sign-in did not work. Try again.", locale);
    }
  }

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
</svelte:head>

<main id="main-content" class="auth-page">
  <div class="auth-column">
    <section class="auth-panel" aria-labelledby="auth-title">
      <div class="auth-copy">
        <div class="auth-hello">
          <Mascot size={88} bob={true} />
          <p class="welcome-line">{t("Hello, knitter!", locale)}</p>
        </div>
        <h1 id="auth-title">{t("Sign in", locale)}</h1>
        <p class="auth-description">
          {t("Pick up where you left off.", locale)}
        </p>
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

      {#if accountDeleted}
        <div class="form-success" role="status">
          {t(
            "Your account and everything in it have been deleted. Goodbye, and thank you for knitting with us.",
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

      {#if passkeyFailure}
        <div class="form-error" role="alert">
          {passkeyFailureMessage(passkeyFailure)}
        </div>
      {/if}

      <div class="auth-body">
        <form class="auth-form" onsubmit={submit}>
          <label for="email">{t("Email", locale)}</label>
          <input
            bind:value={email}
            autocomplete={passkeysAvailable ? "username webauthn" : "username"}
            id="email"
            name="email"
            type="email"
            inputmode="email"
            enterkeyhint="next"
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
            enterkeyhint="go"
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
              <ActionLabel icon="sign-in"
                >{t("Signing in…", locale)}</ActionLabel
              >
            {:else}
              <ActionLabel icon="sign-in">{t("Sign in", locale)}</ActionLabel>
            {/if}
          </SharedButton>
        </form>

        {#if passkeysAvailable}
          <div class="passkey-divider" aria-hidden="true">
            <span>{t("or", locale)}</span>
          </div>
          <SharedButton
            variant="secondary"
            class="passkey-button"
            type="button"
            disabled={submitting || passkeyBusy || !session.data?.csrf_token}
            onclick={() => void submitPasskey()}
            icon="key"
          >
            {passkeyBusy
              ? t("Waiting for your passkey…", locale)
              : t("Sign in with a passkey", locale)}
          </SharedButton>
        {/if}

        <OidcSignIn {locale} />
      </div>

      {#if registrationOpen(session)}
        <p class="auth-signup-link">
          {t("New to Stricknani?", locale)}
          <a href={resolve("/signup")}>{t("Create an account", locale)}</a>
        </p>
      {/if}
    </section>
    {#if selfHostCardVisible(session)}
      <SelfHostCard {locale} />
    {/if}
    <AuthFooter />
  </div>
</main>

<style>
  .auth-page {
    display: grid;
    width: 100%;
    min-width: 0;
    min-height: 100svh;
    place-items: center;
    padding: clamp(1rem, 3vw, 2.5rem);
  }

  .auth-column {
    width: min(100%, 29rem);
    min-width: 0;
  }

  .auth-panel {
    position: relative;
    padding: clamp(1.5rem, 5vw, 2.5rem);
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
    margin-top: 0;
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

  .auth-body {
    display: flex;
    flex-direction: column;
  }

  .auth-form {
    display: grid;
    gap: 0.6rem;
    margin-top: 1.5rem;
  }

  label {
    margin-top: 0.7rem;
    font-size: var(--ui-label-font-size);
    font-weight: var(--ui-label-font-weight);
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

  .passkey-divider {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin: 1rem 0 0.75rem;
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .passkey-divider::before,
  .passkey-divider::after {
    flex: 1;
    height: 1px;
    background: var(--line);
    content: "";
  }

  :global(.passkey-button) {
    width: 100%;
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

  .auth-signup-link {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 0 0.35rem;
    margin: 1.25rem 0 0;
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

  @media (max-width: 720px) {
    .auth-page {
      align-items: start;
      padding: 1rem;
    }

    .auth-panel {
      padding: clamp(1.35rem, 7vw, 2rem);
    }
  }
</style>
