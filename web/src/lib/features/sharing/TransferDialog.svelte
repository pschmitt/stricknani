<script lang="ts">
  import { untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import { isAccessLost } from "#lib/access.ts";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import UserSentence from "#lib/components/UserSentence.svelte";
  import { personUser } from "#lib/user-label.ts";
  import PeoplePicker from "./PeoplePicker.svelte";
  import { moveYarnsLabel } from "./share-copy.ts";
  import {
    transferOwnership,
    type Person,
    type ShareKind,
    type TransferResult,
  } from "./sharing-api.ts";

  let {
    kind,
    resourceId,
    locale,
    ownerId,
    movableYarnCount = 0,
    ontransferred,
    onaccesslost,
  }: {
    kind: ShareKind;
    resourceId: number;
    locale: Locale;
    /** The current owner cannot be the recipient. */
    ownerId: number;
    /** Projects: linked yarns the owner owns and could move along. */
    movableYarnCount?: number;
    ontransferred: (result: TransferResult) => void;
    onaccesslost: () => void;
  } = $props();

  const dialogId = $props.id();

  let dialog = $state<ConfirmDialog | undefined>(undefined);
  let picked = $state<Person | null>(null);
  let moveYarns = $state(false);
  let busy = $state(false);
  let error = $state("");

  export function showModal(): void {
    picked = null;
    moveYarns = false;
    error = "";
    dialog?.showModal();
  }

  export function close(): void {
    untrack(() => dialog?.close());
  }

  async function confirm() {
    const currentSession = session.data;
    const person = picked;
    if (!currentSession?.user || !person || busy) return;
    busy = true;
    error = "";
    try {
      const result = await transferOwnership(
        kind,
        resourceId,
        person.id,
        kind === "projects" && movableYarnCount > 0 && moveYarns,
        currentSession,
      );
      ontransferred(result);
    } catch (failure) {
      if (isAccessLost(failure)) {
        onaccesslost();
        return;
      }
      error =
        failure instanceof ApiError
          ? failure.message
          : t("Ownership could not be transferred.", locale);
    } finally {
      busy = false;
    }
  }
</script>

<ConfirmDialog
  bind:this={dialog}
  titleId={`${dialogId}-title`}
  title={t("Transfer ownership", locale)}
  {busy}
>
  {#snippet children()}
    <div class="transfer">
      <p>
        {t(
          "Ownership moves to the person you pick, right away. You stay on as an editor and can leave later.",
          locale,
        )}
      </p>
      <PeoplePicker
        {locale}
        inputId={`${dialogId}-search`}
        excludeIds={[ownerId]}
        selected={picked}
        onselect={(person) => (picked = person)}
      />
      {#if kind === "projects" && movableYarnCount > 0}
        <div class="transfer__yarns">
          <label class="transfer__check">
            <input type="checkbox" bind:checked={moveYarns} />
            <span>{moveYarnsLabel(movableYarnCount, locale)}</span>
          </label>
          <p class="transfer__hint">
            {t(
              "Off, the yarns stay yours and the new owner can only read them. On, they move too, and you keep an editor share on each.",
              locale,
            )}
          </p>
        </div>
      {/if}
      {#if error}<p class="transfer__error" role="alert">{error}</p>{/if}
    </div>
  {/snippet}
  {#snippet actions()}
    <Button
      type="button"
      variant="secondary"
      icon="cancel"
      disabled={busy}
      onclick={close}
    >
      {t("Cancel", locale)}
    </Button>
    <Button
      type="button"
      variant="primary"
      icon="check"
      disabled={!picked || busy}
      onclick={() => void confirm()}
    >
      {#if picked}<UserSentence
          template={t("Transfer to {name}", locale)}
          users={{ name: personUser(picked) }}
          {locale}
        />{:else}{t("Transfer ownership", locale)}{/if}
    </Button>
  {/snippet}
</ConfirmDialog>

<style>
  .transfer {
    display: grid;
    gap: 0.8rem;
  }

  .transfer p {
    margin: 0;
  }

  .transfer__yarns {
    display: grid;
    gap: 0.3rem;
  }

  .transfer__check {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    font-weight: 600;
  }

  .transfer__check input {
    width: 1.25rem;
    height: 1.25rem;
    accent-color: var(--accent-strong);
  }

  .transfer__hint {
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .transfer__error {
    color: var(--danger);
  }
</style>
