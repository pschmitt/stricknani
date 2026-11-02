<script lang="ts">
  import { ApiError } from "#lib/api.ts";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import BrandMark from "#lib/components/BrandMark.svelte";
  import LocaleSwitcher from "#lib/components/LocaleSwitcher.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session, signIn } from "#lib/session.svelte.ts";

  let email = $state("");
  let password = $state("");
  let submitting = $state(false);
  let failure = $state<ApiError | Error | null>(null);

  const locale = $derived(session.data?.locale ?? "en");
  const accountCreated = $derived(page.url.searchParams.get("created") === "1");

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
  <meta name="theme-color" content="#f3f6fb" />
</svelte:head>

<main id="main-content" class="auth-page">
  <section class="auth-panel" aria-labelledby="auth-title">
    <a class="wordmark" href="/app/">
      <BrandMark />
      <span>Stricknani</span>
    </a>

    <div class="auth-copy">
      <p class="welcome-line">
        {t("A calm place for the work in your hands.", locale)}
      </p>
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

      <button
        class="primary-button"
        type="submit"
        disabled={submitting || !session.data?.csrf_token}
      >
        {#if submitting}
          <span class="spinner spinner-light" aria-hidden="true"></span>
          {t("Signing in…", locale)}
        {:else}
          {t("Sign in", locale)}
        {/if}
      </button>
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
    <footer class="auth-footer">
      <LocaleSwitcher />
      <a class="public-tool-link" href={resolve("/gauge")}
        >{t("Gauge calculator", locale)}</a
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
    align-self: center;
    width: min(100%, 28rem);
    margin-inline: auto;
    padding: clamp(1.25rem, 5vw, 3rem);
    border: 1px solid var(--line);
    border-radius: 1.35rem;
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  .wordmark {
    display: inline-flex;
    min-height: 2.75rem;
    align-items: center;
    gap: 0.65rem;
    color: var(--ink);
    font-size: 1.05rem;
    font-weight: 680;
    letter-spacing: -0.035em;
    text-decoration: none;
  }

  .auth-copy {
    margin-top: clamp(2.75rem, 7vh, 4.25rem);
  }

  .welcome-line {
    margin: 0 0 0.75rem;
    color: var(--accent);
    font-size: 0.92rem;
    font-weight: 650;
  }

  h1 {
    margin: 0;
    font-size: clamp(2rem, 6vw, 2.7rem);
    font-weight: 680;
    letter-spacing: -0.055em;
    line-height: 1.12;
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

  .primary-button {
    display: inline-flex;
    min-height: 2.9rem;
    align-items: center;
    justify-content: center;
    gap: 0.65rem;
    margin-top: 1rem;
    border: 0;
    border-radius: 0.7rem;
    padding: 0.65rem 1.1rem;
    color: var(--on-accent);
    background: var(--accent-strong);
    font-weight: 650;
    cursor: pointer;
    transition:
      background-color 150ms ease,
      transform 150ms ease;
  }

  .primary-button:hover:not(:disabled) {
    background: var(--accent);
  }

  .primary-button:active:not(:disabled) {
    transform: translateY(1px);
  }

  :global(.spinner-light) {
    width: 1rem;
    border-color: rgb(255 255 255 / 40%);
    border-top-color: var(--on-accent);
  }

  .form-error {
    margin-top: 1.4rem;
    border: 1px solid color-mix(in srgb, var(--danger) 38%, transparent);
    border-radius: 0.7rem;
    padding: 0.8rem 0.9rem;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  .form-success {
    margin-top: 1.4rem;
    border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
    border-radius: 0.7rem;
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

  .auth-footer {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    margin-top: 0.85rem;
  }

  .public-tool-link {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    padding: 0.4rem 0.7rem;
    color: var(--accent-strong);
    background: var(--surface);
    font-size: 0.88rem;
    font-weight: 650;
    text-decoration: none;
  }

  .auth-side {
    position: relative;
    display: flex;
    min-height: 30rem;
    align-items: flex-end;
    justify-content: flex-end;
    margin: 1rem;
    border-radius: 1.35rem;
    padding: clamp(1.5rem, 4vw, 3.5rem);
    overflow: hidden;
    color: var(--accent-strong);
    background: var(--accent-wash);
  }

  .auth-side p {
    position: relative;
    z-index: 1;
    margin: 0;
    font-size: clamp(1.2rem, 2vw, 1.75rem);
    font-weight: 620;
    letter-spacing: -0.035em;
  }

  .thread-art {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    opacity: 0.38;
    transform: rotate(-20deg) scale(1.35);
  }

  .thread-loop {
    position: absolute;
    width: 9rem;
    height: 16rem;
    border: 1.7rem solid var(--accent);
    border-radius: 48% 52% 45% 55%;
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
