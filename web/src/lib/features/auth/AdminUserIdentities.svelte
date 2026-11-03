<script lang="ts">
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    adminUnlinkIdentity,
    formatLinkedDate,
    isReauthRequired,
    listAdminUserIdentities,
    type LinkedIdentity,
    type OidcProvider,
    type ReauthRequest,
  } from "./oidc-api";
  import ReauthDialog from "./ReauthDialog.svelte";

  /**
   * Per-user linked logins for administrators: a row action that opens a
   * dialog listing identities, with unlink behind step-up authentication.
   */
  let {
    user,
    providers,
    disabled = false,
  }: {
    user: { id: number; email: string };
    providers: OidcProvider[];
    disabled?: boolean;
  } = $props();

  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let reauthDialog = $state<ReturnType<typeof ReauthDialog>>();
  let identities = $state<LinkedIdentity[]>([]);
  let status = $state<"loading" | "ready" | "error">("loading");
  let error = $state("");
  let busy = $state(false);
  let opener: HTMLButtonElement | null = null;
  let generation = 0;

  const locale = $derived(session.data?.locale ?? "en");
  const providerName = (id: string): string =>
    providers.find((provider) => provider.id === id)?.name ?? id;

  function message(failure: unknown): string {
    return failure instanceof ApiError
      ? failure.message
      : t("The request could not be completed.", locale);
  }

  async function refresh(): Promise<void> {
    const request = ++generation;
    status = "loading";
    try {
      const result = await listAdminUserIdentities(user.id);
      if (request !== generation) return;
      identities = result.identities;
      status = "ready";
      error = "";
    } catch (failure) {
      if (request !== generation) return;
      status = "error";
      error = message(failure);
    }
  }

  function openDialog(
    event: MouseEvent & { currentTarget: HTMLButtonElement },
  ) {
    opener = event.currentTarget;
    identities = [];
    error = "";
    dialog?.showModal();
    void refresh();
  }

  async function unlink(identity: LinkedIdentity): Promise<void> {
    const request: ReauthRequest = {
      action: "admin_unlink_identity",
      target: { user_id: user.id, identity_id: identity.id },
    };
    error = "";
    busy = true;
    try {
      await adminUnlinkIdentity(
        user.id,
        identity.id,
        session.data?.csrf_token ?? "",
      );
      if (session.data?.user)
        publishFeedback({
          ownerId: session.data.user.id,
          kind: "success",
          message: t("The login was unlinked.", locale),
        });
      await refresh();
    } catch (failure) {
      if (isReauthRequired(failure)) reauthDialog?.open(request);
      else error = message(failure);
    } finally {
      busy = false;
    }
  }

  async function afterReauth(request: ReauthRequest): Promise<void> {
    const identity = identities.find(
      (item) => item.id === request.target?.identity_id,
    );
    if (identity) await unlink(identity);
  }
</script>

<SharedButton
  variant="ghost"
  size="sm"
  type="button"
  {disabled}
  onclick={openDialog}
  icon="link"
  aria-label={t("Linked logins", locale)}
  title={t("Linked logins", locale)}
/>

<ConfirmDialog
  bind:this={dialog}
  titleId={`admin-identities-title-${user.id}`}
  title={t("Linked logins", locale)}
  {busy}
  onclose={() => {
    generation += 1;
    opener?.focus();
  }}
>
  <p class="target">{user.email}</p>
  {#if error}<p class="failure" role="alert">{error}</p>{/if}
  {#if status === "loading"}
    <p class="muted">{t("Loading linked logins…", locale)}</p>
  {:else if status === "error"}
    <SharedButton variant="ghost" onclick={() => void refresh()} icon="refresh">
      {t("Try again", locale)}
    </SharedButton>
  {:else if identities.length === 0}
    <p class="muted">{t("No logins are linked yet.", locale)}</p>
  {:else}
    <ul class="identity-list">
      {#each identities as identity (identity.id)}
        <li class="identity-row">
          <div class="identity-copy">
            <strong>{providerName(identity.provider)}</strong>
            <span class="identity-hint">{identity.subject_hint}</span>
            {#if identity.email}<span>{identity.email}</span>{/if}
            <span class="muted">
              {t("Linked on {date}", locale).replaceAll(
                "{date}",
                formatLinkedDate(identity.linked_at, locale),
              )}
            </span>
            {#if !identity.can_unlink}
              <span class="muted" id={`admin-unlink-hint-${identity.id}`}>
                {t(
                  "This is the only way this account can sign in, so it cannot be unlinked.",
                  locale,
                )}
              </span>
            {/if}
          </div>
          <SharedButton
            variant="danger"
            size="sm"
            disabled={busy || !identity.can_unlink}
            aria-describedby={identity.can_unlink
              ? undefined
              : `admin-unlink-hint-${identity.id}`}
            onclick={() => void unlink(identity)}
            icon="action-remove"
          >
            {t("Unlink", locale)}
          </SharedButton>
        </li>
      {/each}
    </ul>
  {/if}
  {#snippet actions()}
    <SharedButton
      variant="secondary"
      disabled={busy}
      onclick={() => dialog?.close()}
      icon="close"
    >
      {t("Close", locale)}
    </SharedButton>
  {/snippet}
</ConfirmDialog>

<ReauthDialog bind:this={reauthDialog} {locale} onconfirmed={afterReauth} />

<style>
  .target {
    margin: 0 0 0.8rem;
    font-weight: 650;
    overflow-wrap: anywhere;
  }

  .muted {
    margin: 0;
    color: var(--ink-muted);
  }

  .failure {
    color: var(--danger);
    font-weight: 650;
  }

  .identity-list {
    display: grid;
    gap: 0.6rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .identity-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.7rem 0.9rem;
  }

  .identity-copy {
    display: grid;
    min-width: 0;
    gap: 0.15rem;
  }

  .identity-hint {
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: 0.9rem;
    overflow-wrap: anywhere;
  }
</style>
