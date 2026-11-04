<script lang="ts">
  import { onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    getAuthOptions,
    isReauthRequired,
    listIdentities,
    NO_AUTH_OPTIONS,
    type AuthOptions,
    type LinkedIdentity,
    type OidcProvider,
    type ReauthRequest,
  } from "#lib/features/auth/oidc-api.ts";
  import ReauthDialog from "#lib/features/auth/ReauthDialog.svelte";
  import { takePendingReauth } from "#lib/features/auth/reauth-flow.ts";
  import { deleteAccount } from "./account-api.ts";
  import { confirmationMatches } from "./delete-account.ts";

  /**
   * Permanent account deletion: a confirmation step (type DELETE or the
   * account email) followed, when the server asks for it, by the shared
   * re-authentication dialog (password, or the linked login provider). The
   * parent decides what happens after the account is gone.
   */
  let {
    locale,
    email,
    ondeleted,
  }: {
    locale: Locale;
    email: string;
    ondeleted: () => void | Promise<void>;
  } = $props();

  const REAUTH_REQUEST: ReauthRequest = { action: "delete_account" };

  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let reauthDialog = $state<ReturnType<typeof ReauthDialog>>();
  let confirmation = $state("");
  let busy = $state(false);
  let error = $state("");
  let reauthed = $state(false);
  let options = $state<AuthOptions>(NO_AUTH_OPTIONS);
  let identities = $state<LinkedIdentity[]>([]);
  let passwordEnabled = $state(true);

  const ready = $derived(confirmationMatches(confirmation, email));
  const reauthProvider = $derived<OidcProvider | null>(
    identities
      .map((identity) => options.oidc.find((p) => p.id === identity.provider))
      .find((provider) => provider !== undefined) ?? null,
  );

  export function open(): void {
    confirmation = "";
    error = "";
    reauthed = false;
    dialog?.showModal();
  }

  onMount(() => {
    const controller = new AbortController();
    void getAuthOptions(controller.signal)
      .then(async (result) => {
        options = result;
        if (result.oidc.length === 0) return;
        const list = await listIdentities();
        identities = list.identities;
        passwordEnabled = list.passwordEnabled;
      })
      .catch(() => undefined);
    // Back from the login provider: the session is freshly confirmed, so
    // only the typed confirmation is left.
    const resumed = takePendingReauth(
      typeof sessionStorage === "undefined" ? undefined : sessionStorage,
      Date.now(),
      (action) => action === "delete_account",
    );
    if (resumed) {
      open();
      reauthed = true;
    }
    return () => controller.abort();
  });

  function failureMessage(failure: unknown): string {
    if (failure instanceof ApiError) {
      if (failure.code === "last_admin")
        return t(
          "You are the last administrator. Make someone else an administrator before deleting your account.",
          locale,
        );
      if (failure.fields?.confirmation)
        return t("Type DELETE or your email address to confirm.", locale);
      if (failure.code === "rate_limited")
        return t("Too many attempts. Wait a moment and try again.", locale);
      return failure.message;
    }
    return t("The request could not be completed.", locale);
  }

  async function run(): Promise<void> {
    if (busy || !ready || !session.data) return;
    busy = true;
    error = "";
    try {
      await deleteAccount(confirmation.trim(), session.data);
    } catch (failure) {
      busy = false;
      if (isReauthRequired(failure)) {
        reauthDialog?.open(REAUTH_REQUEST);
        return;
      }
      error = failureMessage(failure);
      return;
    }
    dialog?.close();
    try {
      await ondeleted();
    } finally {
      busy = false;
    }
  }

  async function onReauthConfirmed(): Promise<void> {
    reauthed = true;
    await run();
  }
</script>

<ConfirmDialog
  bind:this={dialog}
  titleId="delete-account-title"
  title={t("Delete your account?", locale)}
  {busy}
>
  <p>
    {t(
      "This permanently deletes your account and everything in it: projects, yarns, photos, attachments, favourites, your picture, and every app and device you connected. It cannot be undone.",
      locale,
    )}
  </p>
  {#if reauthed}
    <p class="note" role="status">
      {t("You confirmed it is you. One last step below.", locale)}
    </p>
  {/if}
  {#if error}
    <p class="problem" role="alert">{error}</p>
  {/if}
  <form
    id="delete-account-form"
    class="confirm-form"
    onsubmit={(event) => {
      event.preventDefault();
      void run();
    }}
  >
    <label for="delete-account-confirmation">
      {t("Type DELETE or your email address to confirm", locale)}
    </label>
    <input
      id="delete-account-confirmation"
      bind:value={confirmation}
      type="text"
      autocomplete="off"
      autocapitalize="off"
      spellcheck="false"
      disabled={busy}
      placeholder="DELETE"
    />
  </form>
  {#snippet actions()}
    <SharedButton
      variant="ghost"
      disabled={busy}
      onclick={() => dialog?.close()}
      icon="cancel"
    >
      {t("Keep my account", locale)}
    </SharedButton>
    <SharedButton
      variant="danger"
      type="submit"
      form="delete-account-form"
      disabled={busy || !ready}
      icon="action-delete"
    >
      {busy ? t("Deleting…", locale) : t("Delete my account", locale)}
    </SharedButton>
  {/snippet}
</ConfirmDialog>

<ReauthDialog
  bind:this={reauthDialog}
  {locale}
  {passwordEnabled}
  oidcProvider={reauthProvider}
  message={t(
    "For your security, confirm it is you before your account is deleted.",
    locale,
  )}
  onconfirmed={onReauthConfirmed}
/>

<style>
  .confirm-form {
    display: grid;
    gap: 0.4rem;
    margin-top: 0.8rem;
  }

  .confirm-form label {
    font-weight: 650;
  }

  .problem {
    margin: 0.8rem 0 0;
    color: var(--danger, #b3261e);
    font-weight: 650;
  }

  .note {
    margin: 0.8rem 0 0;
    color: var(--ink-muted);
  }
</style>
