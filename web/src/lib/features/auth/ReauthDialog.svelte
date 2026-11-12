<script lang="ts">
  import SharedButton from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    navigateToProvider,
    reauthWithPassword,
    startOidcReauth,
    type OidcProvider,
    type ReauthRequest,
  } from "./oidc-api";
  import { reauthWithPasskey } from "./passkey-api";
  import {
    CLOSED,
    classifyReauthFailure,
    isBusy,
    reauthReducer,
    savePendingReauth,
    type ReauthEvent,
    type ReauthFailure,
    type ReauthState,
  } from "./reauth-flow";

  /**
   * Shared step-up dialog. It asks for the password (or a provider
   * confirmation when allowed), lets the server stamp the session, then tells
   * the caller to retry the original action. The password lives only in this
   * component and is cleared as soon as the request is sent.
   */
  let {
    locale,
    oidcProvider = null,
    passwordEnabled = true,
    passkeyAvailable = false,
    message,
    returnTo,
    oncancel,
    onconfirmed,
  }: {
    locale: Locale;
    /** Offered as "Confirm with ..." when the user already has a linked login. */
    oidcProvider?: OidcProvider | null;
    passwordEnabled?: boolean;
    /** Offered as "Confirm with a passkey" when the account has one and the browser can use it. */
    passkeyAvailable?: boolean;
    /** Replaces the default explanation above the password field. */
    message?: string;
    /** In-app path a provider confirmation returns to (default: the Account page). */
    returnTo?: string;
    /** The person closed the dialog without confirming. */
    oncancel?: () => void;
    onconfirmed: (request: ReauthRequest) => void | Promise<void>;
  } = $props();

  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let reauth = $state<ReauthState>(CLOSED);
  let password = $state("");
  let passwordInput = $state<HTMLInputElement | null>(null);

  const busy = $derived(isBusy(reauth));
  const failure = $derived<ReauthFailure | null>(
    reauth.phase === "prompt" ? reauth.failure : null,
  );

  function dispatch(event: ReauthEvent): void {
    const before = reauth.phase;
    reauth = reauthReducer(reauth, event);
    if (event.type === "cancel" && before === "prompt") oncancel?.();
    if (reauth.phase === "closed") dialog?.close();
  }

  /** Ask for fresh proof for the given action. */
  export function open(request: ReauthRequest): void {
    password = "";
    reauth = CLOSED;
    dispatch({ type: "open", request });
    dialog?.showModal();
    queueMicrotask(() => passwordInput?.focus());
  }

  function reset(): void {
    password = "";
    if (reauth.phase === "prompt") oncancel?.();
    if (reauth.phase === "prompt" || reauth.phase === "confirmed")
      reauth = CLOSED;
  }

  async function confirmWithPassword(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (reauth.phase !== "prompt" || !password) return;
    const request = reauth.request;
    const csrf = session.data?.csrf_token ?? "";
    const proof = password;
    password = "";
    dispatch({ type: "submit", method: "password" });
    try {
      await reauthWithPassword(proof, request, csrf);
      dispatch({ type: "password_confirmed" });
    } catch (error) {
      dispatch({ type: "failed", failure: classifyReauthFailure(error) });
      queueMicrotask(() => passwordInput?.focus());
      return;
    }
    dialog?.close();
    await onconfirmed(request);
  }

  async function confirmWithPasskey(): Promise<void> {
    if (reauth.phase !== "prompt" || !passkeyAvailable) return;
    const request = reauth.request;
    dispatch({ type: "submit", method: "passkey" });
    try {
      await reauthWithPasskey(request, session.data?.csrf_token ?? "");
      dispatch({ type: "passkey_confirmed" });
    } catch (error) {
      dispatch({ type: "failed", failure: classifyReauthFailure(error) });
      return;
    }
    dialog?.close();
    await onconfirmed(request);
  }

  async function confirmWithProvider(): Promise<void> {
    if (reauth.phase !== "prompt" || !oidcProvider) return;
    const request = reauth.request;
    dispatch({ type: "submit", method: "oidc" });
    try {
      const start = await startOidcReauth(
        oidcProvider.id,
        request,
        session.data?.csrf_token ?? "",
        returnTo,
      );
      savePendingReauth(
        typeof sessionStorage === "undefined" ? undefined : sessionStorage,
        request,
      );
      dispatch({ type: "provider_started" });
      navigateToProvider(start);
    } catch (error) {
      dispatch({ type: "failed", failure: classifyReauthFailure(error) });
    }
  }

  function failureMessage(value: ReauthFailure): string {
    switch (value) {
      case "invalid_credentials":
        return t("That password is not correct.", locale);
      case "invalid_passkey":
        return t("That passkey could not be verified.", locale);
      case "passkey_cancelled":
        return t("Confirming with a passkey was cancelled.", locale);
      case "rate_limited":
        return t("Too many attempts. Wait a moment and try again.", locale);
      case "unavailable":
        return t(
          "The login provider is unavailable right now. Try again later or use your password.",
          locale,
        );
      default:
        return t("The request could not be completed.", locale);
    }
  }
</script>

<ConfirmDialog
  bind:this={dialog}
  titleId="reauth-title"
  title={t("Confirm it is you", locale)}
  {busy}
  onclose={reset}
>
  <p>
    {message ??
      t(
        "For your security, confirm your identity before changing how you sign in.",
        locale,
      )}
  </p>
  {#if failure}
    <p class="reauth-error" role="alert">{failureMessage(failure)}</p>
  {/if}
  {#if passwordEnabled}
    <form id="reauth-form" class="reauth-form" onsubmit={confirmWithPassword}>
      <label for="reauth-password">{t("Password", locale)}</label>
      <input
        id="reauth-password"
        bind:this={passwordInput}
        bind:value={password}
        type="password"
        autocomplete="current-password"
        required
        disabled={busy}
        aria-invalid={failure === "invalid_credentials" ? "true" : undefined}
      />
    </form>
  {:else if !oidcProvider && !passkeyAvailable}
    <p class="reauth-muted">
      {t("There is no way to confirm your identity right now.", locale)}
    </p>
  {/if}
  {#snippet actions()}
    <SharedButton
      variant="ghost"
      disabled={busy}
      onclick={() => dispatch({ type: "cancel" })}
      icon="cancel"
    >
      {t("Cancel", locale)}
    </SharedButton>
    {#if passkeyAvailable}
      <SharedButton
        variant="secondary"
        disabled={busy}
        onclick={() => void confirmWithPasskey()}
        icon="key"
      >
        {reauth.phase === "verifying" && reauth.method === "passkey"
          ? t("Waiting for your passkey…", locale)
          : t("Confirm with a passkey", locale)}
      </SharedButton>
    {/if}
    {#if oidcProvider}
      <SharedButton
        variant="secondary"
        disabled={busy}
        onclick={() => void confirmWithProvider()}
        icon="key"
      >
        {t("Confirm with {name}", locale).replaceAll(
          "{name}",
          oidcProvider.name,
        )}
      </SharedButton>
    {/if}
    {#if passwordEnabled}
      <SharedButton
        variant="primary"
        type="submit"
        form="reauth-form"
        disabled={busy}
        icon="check"
      >
        {reauth.phase === "verifying" && reauth.method === "password"
          ? t("Confirming…", locale)
          : t("Confirm", locale)}
      </SharedButton>
    {/if}
  {/snippet}
</ConfirmDialog>

<style>
  .reauth-form {
    display: grid;
    gap: 0.4rem;
    margin-top: 0.8rem;
  }

  .reauth-form label {
    font-weight: 650;
  }

  .reauth-error {
    margin: 0.8rem 0 0;
    color: var(--danger, #b3261e);
    font-weight: 650;
  }

  .reauth-muted {
    color: var(--ink-muted);
  }
</style>
