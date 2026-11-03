<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { onDestroy } from "svelte";
  import { onMount } from "svelte";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import SharedButton from "#lib/components/Button.svelte";
  import { ApiError } from "#lib/api.ts";
  import LocaleSwitcher from "#lib/components/LocaleSwitcher.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { refreshSession, session } from "#lib/session.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { createAccount } from "./signup-api";

  let email = $state("");
  let password = $state("");
  let confirmation = $state("");
  let submitting = $state(false);
  let failure = $state<string | null>(null);
  let fieldErrors = $state<Record<string, string>>({});
  let generation = 0;
  let controller: AbortController | undefined;

  const locale = $derived(session.data?.locale ?? "en");
  const enabled = $derived(
    session.status === "ready" &&
      session.data?.user === null &&
      session.data.capabilities.signup,
  );

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: email.length > 0 || password.length > 0 || confirmation.length > 0,
      busy: submitting,
    })),
  );

  onDestroy(() => {
    generation += 1;
    controller?.abort();
  });

  function translatedFieldError(field: string, message: string): string {
    const known: Record<string, string> = {
      "Enter a valid email address.": t("Enter a valid email address.", locale),
      "Password is too long.": t(
        "Passwords must be 1024 bytes or fewer.",
        locale,
      ),
      "Use at least 12 characters.": t("Use at least 12 characters.", locale),
      "Choose a less common password.": t(
        "Choose a password that is not commonly used.",
        locale,
      ),
      "Password does not meet the account policy.": t(
        "Choose a password that is not commonly used.",
        locale,
      ),
    };
    if (field !== "email" && field !== "password")
      return t("Check the highlighted fields and try again.", locale);
    return (
      known[message] ?? t("Check the highlighted fields and try again.", locale)
    );
  }

  function errorCopy(error: unknown): string {
    if (!(error instanceof ApiError))
      return t("The account could not be created. Try again.", locale);
    if (error.code === "account_exists")
      return t("An account already exists for this email.", locale);
    if (error.code === "signup_disabled")
      return t("Account registration is unavailable.", locale);
    if (error.code === "rate_limited")
      return t("Too many sign-up attempts. Try again later.", locale);
    if (error.code === "csrf_failed")
      return t("Your sign-up session expired. Refresh and try again.", locale);
    return t("The account could not be created. Try again.", locale);
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (submitting || !enabled || !session.data) return;
    failure = null;
    fieldErrors = {};
    if (password !== confirmation) {
      fieldErrors = { confirmation: t("The passwords do not match.", locale) };
      return;
    }
    if (new TextEncoder().encode(password).length > 1024) {
      fieldErrors = {
        password: t("Passwords must be 1024 bytes or fewer.", locale),
      };
      return;
    }

    const csrfToken = session.data.csrf_token;
    const requestGeneration = ++generation;
    controller?.abort();
    controller = new AbortController();
    const requestController = controller;
    submitting = true;
    try {
      await createAccount(
        email.trim(),
        password,
        csrfToken,
        requestController.signal,
      );
      if (!isCurrent(requestGeneration, csrfToken)) return;

      password = "";
      confirmation = "";
      submitting = false;
      const bootstrap = refreshSession();
      await goto(`${resolve("/login")}?created=1`, { replaceState: true });
      await bootstrap;
    } catch (error) {
      if (!isCurrent(requestGeneration, csrfToken)) return;
      if (error instanceof ApiError && error.fields) {
        const translated: Record<string, string> = {};
        for (const [field, message] of Object.entries(error.fields))
          translated[field] = translatedFieldError(field, message);
        fieldErrors = translated;
        failure = t("Check the highlighted fields and try again.", locale);
      } else {
        failure = errorCopy(error);
      }
    } finally {
      if (generation === requestGeneration) submitting = false;
    }
  }

  function isCurrent(requestGeneration: number, csrfToken: string): boolean {
    return (
      generation === requestGeneration &&
      !controller?.signal.aborted &&
      session.status === "ready" &&
      session.data?.user === null &&
      session.data.csrf_token === csrfToken &&
      session.data.capabilities.signup
    );
  }
</script>

<svelte:head>
  <title>{t("Create an account", locale)} · Stricknani</title>
</svelte:head>

<section class="signup-panel" aria-label={t("Account registration", locale)}>
  {#if session.data?.user}
    <h1>{t("You are already signed in.", locale)}</h1>
    <p>{t("Open your project library to continue.", locale)}</p>
    <a
      class="ui-button ui-button--primary ui-button--md signup-link"
      href={resolve("/projects")}
      ><ActionLabel icon="projects">{t("Open projects", locale)}</ActionLabel
      ></a
    >
  {:else if !enabled}
    <h1>{t("Account registration is unavailable.", locale)}</h1>
    <p>{t("Ask your server administrator if you need an account.", locale)}</p>
    <a
      class="ui-button ui-button--primary ui-button--md signup-link"
      href={resolve("/login")}
      ><ActionLabel icon="sign-in">{t("Back to sign in", locale)}</ActionLabel
      ></a
    >
  {:else}
    <p class="signup-intro">
      {t("A place for your projects and yarn.", locale)}
    </p>
    <h1>{t("Create an account", locale)}</h1>
    <p class="signup-description">
      {t("Choose an email and a strong password to get started.", locale)}
    </p>

    {#if failure}
      <div class="signup-error" role="alert">{failure}</div>
    {/if}

    <form class="signup-form" onsubmit={submit}>
      <label for="signup-email">{t("Email", locale)}</label>
      <input
        id="signup-email"
        name="email"
        type="email"
        inputmode="email"
        autocomplete="email"
        autocapitalize="none"
        spellcheck="false"
        bind:value={email}
        aria-invalid={fieldErrors.email ? "true" : undefined}
        aria-describedby={fieldErrors.email ? "signup-email-error" : undefined}
        required
        disabled={submitting}
      />
      {#if fieldErrors.email}
        <p class="field-error" id="signup-email-error">{fieldErrors.email}</p>
      {/if}

      <label for="signup-password">{t("Password", locale)}</label>
      <input
        id="signup-password"
        name="password"
        type="password"
        autocomplete="new-password"
        minlength="12"
        bind:value={password}
        aria-invalid={fieldErrors.password ? "true" : undefined}
        aria-describedby={fieldErrors.password
          ? "signup-password-hint signup-password-error"
          : "signup-password-hint"}
        required
        disabled={submitting}
      />
      <p class="field-hint" id="signup-password-hint">
        {t("Use at least 12 characters. Avoid common passwords.", locale)}
      </p>
      {#if fieldErrors.password}
        <p class="field-error" id="signup-password-error">
          {fieldErrors.password}
        </p>
      {/if}

      <label for="signup-confirmation">{t("Confirm password", locale)}</label>
      <input
        id="signup-confirmation"
        name="confirmation"
        type="password"
        autocomplete="new-password"
        bind:value={confirmation}
        aria-invalid={fieldErrors.confirmation ? "true" : undefined}
        aria-describedby={fieldErrors.confirmation
          ? "signup-confirmation-error"
          : undefined}
        required
        disabled={submitting}
      />
      {#if fieldErrors.confirmation}
        <p class="field-error" id="signup-confirmation-error">
          {fieldErrors.confirmation}
        </p>
      {/if}

      <SharedButton
        variant="primary"
        class="signup-submit"
        type="submit"
        disabled={submitting || !session.data?.csrf_token}
      >
        {#if submitting}
          <span class="signup-spinner" aria-hidden="true"></span>
          <ActionLabel icon="user-add"
            >{t("Creating account…", locale)}</ActionLabel
          >
        {:else}
          <ActionLabel icon="user-add"
            >{t("Create account", locale)}</ActionLabel
          >
        {/if}
      </SharedButton>
    </form>

    <p class="signin-prompt">
      {t("Already have an account?", locale)}
      <a href={resolve("/login")}>{t("Sign in", locale)}</a>
    </p>
  {/if}
  <footer class="signup-footer">
    <LocaleSwitcher />
    <a
      class="ui-button ui-button--secondary ui-button--md"
      href={resolve("/gauge")}
      ><ActionLabel icon="gauge">{t("Gauge calculator", locale)}</ActionLabel
      ></a
    >
  </footer>
</section>

<style>
  .signup-panel {
    width: min(100%, 31rem);
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: 1.25rem;
    padding: clamp(1.25rem, 5vw, 2.5rem);
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  h1 {
    margin: 0;
    font-size: clamp(1.8rem, 6vw, 2.35rem);
    letter-spacing: -0.05em;
    line-height: 1.15;
  }

  .signup-intro {
    margin: 0 0 0.65rem;
    color: var(--accent-strong);
    font-weight: 650;
  }

  .signup-description,
  .field-hint,
  .signin-prompt {
    color: var(--ink-muted);
  }

  .signup-form {
    display: grid;
    gap: 0.6rem;
    margin-top: 1.6rem;
  }

  label {
    margin-top: 0.55rem;
    font-size: 0.92rem;
    font-weight: 620;
  }

  input {
    width: 100%;
    min-height: 3rem;
    border: 1px solid var(--control-border);
    border-radius: 0.7rem;
    padding: 0.65rem 0.8rem;
    color: var(--ink);
    background: var(--surface-raised);
  }

  input:focus {
    border-color: var(--focus);
  }

  .field-hint,
  .field-error {
    margin: 0;
    font-size: 0.86rem;
  }

  .field-error,
  .signup-error {
    color: var(--danger);
  }

  .signup-error {
    margin-top: 1.25rem;
    border: 1px solid color-mix(in srgb, var(--danger) 38%, transparent);
    border-radius: 0.7rem;
    padding: 0.75rem 0.85rem;
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  :global(.signup-submit) {
    margin-top: 0.8rem;
  }

  .signup-link {
    margin-top: 0.8rem;
  }

  .signin-prompt {
    margin: 1.3rem 0 0;
    text-align: center;
  }

  .signin-prompt a {
    min-height: 44px;
    display: inline-flex;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 650;
  }

  .signup-footer {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    margin-top: 0.9rem;
  }

  .signup-spinner {
    width: 1rem;
    aspect-ratio: 1;
    border: 2px solid rgb(255 255 255 / 45%);
    border-top-color: var(--on-accent);
    border-radius: 50%;
    animation: turn 900ms linear infinite;
  }

  @keyframes turn {
    to {
      transform: rotate(1turn);
    }
  }
</style>
