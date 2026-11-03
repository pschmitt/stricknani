<script lang="ts">
  import { onMount } from "svelte";
  import { replaceState } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    NO_AUTH_OPTIONS,
    cleanOidcQuery,
    getAuthOptions,
    navigateToProvider,
    oidcLoginErrorMessage,
    readOidcError,
    startOidcLogin,
    type AuthOptions,
    type OidcLoginError,
  } from "./oidc-api";

  /**
   * Optional provider buttons for the sign-in page. Renders nothing at all
   * unless the server advertises a provider (or redirected back with an
   * oidc_error result), so the password-only page stays unchanged.
   */
  let { locale }: { locale: Locale } = $props();

  let options = $state<AuthOptions>(NO_AUTH_OPTIONS);
  let loginError = $state<OidcLoginError | null>(null);
  let startError = $state("");
  let starting = $state<string | null>(null);

  const providerName = $derived(options.oidc[0]?.name ?? "Authelia");

  onMount(() => {
    // Capture the result first, then drop the query so a reload stays clean.
    loginError = readOidcError(window.location.search);
    const cleaned = cleanOidcQuery(window.location.href);
    if (cleaned !== null) {
      try {
        replaceState(cleaned, {});
      } catch {
        window.history.replaceState(window.history.state, "", cleaned);
      }
    }
    const controller = new AbortController();
    getAuthOptions(controller.signal)
      .then((result) => (options = result))
      .catch(() => undefined);
    return () => controller.abort();
  });

  async function start(providerId: string): Promise<void> {
    if (starting) return;
    startError = "";
    loginError = null;
    starting = providerId;
    try {
      const result = await startOidcLogin(
        providerId,
        session.data?.csrf_token ?? "",
        resolve("/projects"),
      );
      navigateToProvider(result);
    } catch (error) {
      startError =
        error instanceof ApiError
          ? error.message
          : t(
              "Signing in with {name} did not work. Try again.",
              locale,
            ).replaceAll("{name}", providerName);
      starting = null;
    }
  }
</script>

{#if loginError}
  <div
    class={loginError === "unlinked" ? "oidc-notice" : "oidc-error"}
    role={loginError === "unlinked" ? "status" : "alert"}
  >
    {oidcLoginErrorMessage(loginError, locale, providerName)}
  </div>
{/if}

{#if options.oidc.length > 0}
  {#if startError}
    <div class="oidc-error" role="alert">{startError}</div>
  {/if}
  <div class="oidc-providers">
    {#each options.oidc as provider (provider.id)}
      <SharedButton
        variant="secondary"
        class="oidc-button"
        icon="key"
        disabled={starting !== null || !session.data?.csrf_token}
        onclick={() => void start(provider.id)}
      >
        {starting === provider.id
          ? t("Opening {name}…", locale).replaceAll("{name}", provider.name)
          : t("Sign in with {name}", locale).replaceAll(
              "{name}",
              provider.name,
            )}
      </SharedButton>
    {/each}
  </div>
  <div class="oidc-divider" role="separator" aria-label={t("or", locale)}>
    <span>{t("or", locale)}</span>
  </div>
{/if}

<style>
  .oidc-providers {
    display: grid;
    gap: 0.6rem;
    margin-top: 1.2rem;
  }

  .oidc-providers :global(.oidc-button) {
    width: 100%;
    min-height: 48px;
    font-weight: 650;
  }

  .oidc-divider {
    display: flex;
    align-items: center;
    gap: 0.8rem;
    margin: 1rem 0 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .oidc-divider::before,
  .oidc-divider::after {
    flex: 1;
    height: 1px;
    background: var(--line);
    content: "";
  }

  .oidc-notice,
  .oidc-error {
    margin: 1.4rem 0 1rem;
    border-radius: var(--radius-xs);
    padding: 0.8rem 0.9rem;
  }

  .oidc-notice {
    border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
    color: var(--ink);
    background: var(--surface-raised);
  }

  .oidc-error {
    border: 1px solid color-mix(in srgb, var(--danger) 38%, transparent);
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }
</style>
