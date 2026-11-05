<script lang="ts">
  import { untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import { isAccessLost, mayLeave, type ResourceAccess } from "#lib/access.ts";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import type { ResourceOwner } from "#lib/owner.ts";
  import { session } from "#lib/session.svelte.ts";
  import { leaveShare, type ShareKind } from "./sharing-api.ts";

  let {
    kind,
    resourceId,
    owner,
    access,
    locale,
    onleft,
  }: {
    kind: ShareKind;
    resourceId: number;
    owner: ResourceOwner;
    access: ResourceAccess;
    locale: Locale;
    /** The share is gone (left, or already revoked): go back to the list. */
    onleft: () => void;
  } = $props();

  const dialogId = $props.id();

  let dialog = $state<ConfirmDialog | undefined>(undefined);
  let busy = $state(false);
  let error = $state("");

  const roleText = $derived(
    access.role === "editor" ? t("Can edit", locale) : t("Can view", locale),
  );
  const line = $derived(
    t("Shared by {name}", locale).replace("{name}", owner.name),
  );
  const leaveTitle = $derived(
    kind === "projects"
      ? t("Leave this project?", locale)
      : t("Leave this yarn?", locale),
  );

  function openLeave() {
    error = "";
    dialog?.showModal();
  }

  async function leave() {
    const currentSession = session.data;
    if (!currentSession?.user || busy) return;
    busy = true;
    error = "";
    try {
      await leaveShare(kind, resourceId, currentSession);
      untrack(() => dialog?.close());
      onleft();
    } catch (failure) {
      if (isAccessLost(failure)) {
        untrack(() => dialog?.close());
        onleft();
        return;
      }
      error =
        failure instanceof ApiError
          ? failure.message
          : t("You could not leave. Try again.", locale);
    } finally {
      busy = false;
    }
  }
</script>

<div class="access-notice" data-testid="access-notice">
  <UserAvatar avatarUrl={owner.avatar_url} name={owner.name} />
  <p class="access-notice__text">
    {#if access.derived}
      <span class="access-notice__main">{line}</span>
      <span class="access-notice__sub"
        >{t("Visible through a shared project", locale)}</span
      >
    {:else}
      <span class="access-notice__main">{line} · {roleText}</span>
    {/if}
  </p>
  {#if mayLeave(access)}
    <Button type="button" variant="ghost" size="sm" onclick={openLeave}>
      {t("Leave", locale)}
    </Button>
  {/if}
</div>

{#if mayLeave(access)}
  <ConfirmDialog
    bind:this={dialog}
    titleId={`${dialogId}-leave`}
    title={leaveTitle}
    {busy}
  >
    {#snippet children()}
      <p>
        {t(
          "It disappears from your library. Only the owner can share it with you again.",
          locale,
        )}
      </p>
      {#if error}<p class="access-notice__error" role="alert">{error}</p>{/if}
    {/snippet}
    {#snippet actions()}
      <Button
        type="button"
        variant="secondary"
        icon="cancel"
        disabled={busy}
        onclick={() => untrack(() => dialog?.close())}
      >
        {t("Cancel", locale)}
      </Button>
      <Button
        type="button"
        variant="danger"
        icon="sign-out"
        disabled={busy}
        onclick={() => void leave()}
      >
        {t("Leave", locale)}
      </Button>
    {/snippet}
  </ConfirmDialog>
{/if}

<style>
  .access-notice {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.65rem;
    border: 1px solid color-mix(in srgb, var(--accent-strong) 35%, var(--line));
    border-radius: var(--radius-sm, 12px);
    padding: 0.4rem 0.6rem;
    background: var(--accent-wash);
  }

  .access-notice :global(.user-avatar) {
    flex-basis: 2rem;
    width: 2rem;
    height: 2rem;
    font-size: 0.85rem;
  }

  .access-notice__text {
    display: grid;
    min-width: 0;
    flex: 1;
    margin: 0;
    line-height: 1.3;
  }

  .access-notice__main {
    overflow-wrap: anywhere;
    font-weight: 600;
  }

  .access-notice__sub {
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .access-notice__error {
    color: var(--danger);
  }
</style>
