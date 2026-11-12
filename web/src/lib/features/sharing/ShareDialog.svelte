<script lang="ts">
  import { ApiError } from "#lib/api.ts";
  import { isAccessLost } from "#lib/access.ts";
  import Button from "#lib/components/Button.svelte";
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";
  import UserLabel from "#lib/components/UserLabel.svelte";
  import UserSentence from "#lib/components/UserSentence.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import type { ShareOutcome } from "#lib/share-link.ts";
  import { session } from "#lib/session.svelte.ts";
  import { personUser, type UserRef } from "#lib/user-label.ts";
  import PeoplePicker from "./PeoplePicker.svelte";
  import {
    addShare,
    changeShareRole,
    revokeShare,
    type Person,
    type Share,
    type ShareKind,
    type ShareList,
    type ShareRole,
  } from "./sharing-api.ts";
  import { linkedYarnsLabel } from "./share-copy.ts";

  let {
    kind,
    resourceId,
    name,
    locale,
    linkedYarnCount = 0,
    list = $bindable(null),
    loading,
    loadError,
    onretry,
    ontransfer,
    onsharelink,
    onaccesslost,
    onclose,
  }: {
    kind: ShareKind;
    resourceId: number;
    name: string;
    locale: Locale;
    /** Projects: how many yarns the project links (recipients read them). */
    linkedYarnCount?: number;
    list: ShareList | null;
    loading: boolean;
    loadError: string;
    onretry: () => void;
    ontransfer: () => void;
    /** Shares the page address (share sheet, else clipboard). */
    onsharelink: () => Promise<ShareOutcome>;
    /** A 404 means the resource is gone for this user. */
    onaccesslost: () => void;
    onclose?: () => void;
  } = $props();

  const dialogId = $props.id();

  let dialog = $state<HTMLDialogElement>();
  // A status line is a translated sentence; the people in it render as `UserLabel`s.
  type Status = { template: string; users: Record<string, UserRef> };
  let status = $state<Status | null>(null);
  const plain = (text: string): Status => ({ template: text, users: {} });
  let error = $state("");
  let busyIds = $state<number[]>([]);
  let adding = $state(false);
  let picked = $state<Person | null>(null);
  let newRole = $state<ShareRole>("viewer");

  export function showModal(): void {
    status = null;
    error = "";
    if (!dialog?.open) dialog?.showModal();
  }

  export function close(): void {
    if (dialog?.open) dialog.close();
  }

  const excludeIds = $derived([
    ...(list ? [list.owner.id] : []),
    ...(list?.shares.map((share) => share.user.id) ?? []),
  ]);

  function roleLabel(role: ShareRole): string {
    return role === "editor" ? t("Can edit", locale) : t("Can view", locale);
  }

  function fail(failure: unknown, fallback: string) {
    status = null;
    if (isAccessLost(failure)) {
      onaccesslost();
      return;
    }
    error = failure instanceof ApiError ? failure.message : fallback;
  }

  function setBusy(userId: number, busy: boolean) {
    busyIds = busy
      ? [...busyIds, userId]
      : busyIds.filter((value) => value !== userId);
  }

  async function add() {
    const currentSession = session.data;
    const person = picked;
    if (!currentSession?.user || !person || adding || !list) return;
    adding = true;
    error = "";
    status = null;
    try {
      const share = await addShare(
        kind,
        resourceId,
        person.id,
        newRole,
        currentSession,
      );
      if (list) list = { ...list, shares: [...list.shares, share] };
      picked = null;
      newRole = "viewer";
      status = {
        template: t("Shared with {name}.", locale),
        users: { name: personUser(person) },
      };
    } catch (failure) {
      fail(failure, t("The share could not be added.", locale));
    } finally {
      adding = false;
    }
  }

  async function changeRole(
    share: Share,
    role: ShareRole,
    select: HTMLSelectElement,
  ) {
    const currentSession = session.data;
    if (!currentSession?.user || !list || role === share.role) return;
    setBusy(share.user.id, true);
    error = "";
    status = null;
    try {
      const updated = await changeShareRole(
        kind,
        resourceId,
        share.user.id,
        role,
        currentSession,
      );
      if (list)
        list = {
          ...list,
          shares: list.shares.map((item) =>
            item.user.id === updated.user.id ? updated : item,
          ),
        };
      status = {
        template: t("Role updated for {name}.", locale),
        users: { name: personUser(share.user) },
      };
    } catch (failure) {
      select.value = share.role;
      fail(failure, t("The role could not be changed.", locale));
    } finally {
      setBusy(share.user.id, false);
    }
  }

  async function remove(share: Share) {
    const currentSession = session.data;
    if (!currentSession?.user || !list) return;
    setBusy(share.user.id, true);
    error = "";
    status = null;
    try {
      await revokeShare(kind, resourceId, share.user.id, currentSession);
      if (list)
        list = {
          ...list,
          shares: list.shares.filter((item) => item.user.id !== share.user.id),
        };
      status = {
        template: t("{name} no longer has access.", locale),
        users: { name: personUser(share.user) },
      };
    } catch (failure) {
      fail(failure, t("The share could not be removed.", locale));
    } finally {
      setBusy(share.user.id, false);
    }
  }

  async function shareLinkNow() {
    error = "";
    const outcome = await onsharelink();
    if (outcome === "copied") status = plain(t("Link copied.", locale));
    else if (outcome === "failed")
      error = t("The link could not be shared.", locale);
    else status = null;
  }
</script>

<dialog
  bind:this={dialog}
  class="share-dialog"
  aria-labelledby={`${dialogId}-title`}
  {onclose}
>
  <header class="share-dialog__head">
    <h2 id={`${dialogId}-title`}>{t("Share", locale)}</h2>
    <p class="share-dialog__subject">{name}</p>
  </header>

  <section class="share-link" aria-labelledby={`${dialogId}-link`}>
    <Button
      class="share-link__button"
      icon="share"
      aria-describedby={`${dialogId}-link-hint`}
      onclick={() => void shareLinkNow()}
    >
      <span id={`${dialogId}-link`}>{t("Share link", locale)}</span>
    </Button>
    <p class="share-link__hint" id={`${dialogId}-link-hint`}>
      {t(
        "Opens for people who already have access. Pick someone below to give them access.",
        locale,
      )}
    </p>
  </section>

  {#if kind === "projects"}
    <p class="share-dialog__note">
      {t(
        "People you share this with can also see its linked yarns, read-only.",
        locale,
      )}
      {#if linkedYarnCount > 0}
        <span class="share-dialog__count"
          >{linkedYarnsLabel(linkedYarnCount, locale)}</span
        >
      {/if}
    </p>
  {/if}

  {#if loading && !list}
    <p class="share-dialog__muted" role="status">{t("Loading…", locale)}</p>
  {:else if loadError && !list}
    <div class="share-dialog__muted" role="alert">
      <p>{loadError}</p>
      <Button type="button" variant="secondary" icon="reset" onclick={onretry}>
        {t("Try again", locale)}
      </Button>
    </div>
  {:else if list}
    <ul
      class="share-dialog__people"
      aria-label={t("People with access", locale)}
    >
      <li class="share-person">
        <span class="share-person__name"
          ><UserLabel user={personUser(list.owner)} {locale} /></span
        >
        <span class="share-person__owner">{t("Owner", locale)}</span>
      </li>
      {#each list.shares as share (share.user.id)}
        {@const busy = busyIds.includes(share.user.id)}
        <li class="share-person">
          <span class="share-person__name"
            ><UserLabel user={personUser(share.user)} {locale} /></span
          >
          <div class="share-person__role">
            <label
              class="visually-hidden"
              for={`${dialogId}-role-${share.user.id}`}
              >{t("Role of {name}", locale).replace(
                "{name}",
                share.user.name,
              )}</label
            >
            <select
              id={`${dialogId}-role-${share.user.id}`}
              value={share.role}
              disabled={busy}
              onchange={(event) =>
                void changeRole(
                  share,
                  event.currentTarget.value === "editor" ? "editor" : "viewer",
                  event.currentTarget,
                )}
            >
              <option value="viewer">{t("Can view", locale)}</option>
              <option value="editor">{t("Can edit", locale)}</option>
            </select>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            icon="action-remove"
            disabled={busy}
            aria-label={`${t("Remove", locale)} ${share.user.name}`}
            onclick={() => void remove(share)}
          />
        </li>
      {/each}
    </ul>

    <section class="share-add" aria-labelledby={`${dialogId}-add`}>
      <h3 id={`${dialogId}-add`}>{t("Add people", locale)}</h3>
      <PeoplePicker
        {locale}
        inputId={`${dialogId}-search`}
        {excludeIds}
        selected={picked}
        onselect={(person) => (picked = person)}
      />
      <div class="share-add__row">
        <div class="share-add__role">
          <span class="share-add__role-label">{t("Role", locale)}</span>
          <SegmentedControl
            label={t("Role", locale)}
            value={newRole}
            options={[
              { value: "viewer", label: t("Can view", locale) },
              { value: "editor", label: t("Can edit", locale) },
            ]}
            onchange={(next) => (newRole = next)}
          />
        </div>
        <Button
          type="button"
          variant="primary"
          icon="user-add"
          disabled={!picked || adding}
          onclick={() => void add()}
        >
          {#if picked}<UserSentence
              template={t("Share with {name}", locale)}
              users={{ name: personUser(picked) }}
              {locale}
            />{:else}{t("Share", locale)}{/if}
        </Button>
      </div>
    </section>
  {/if}

  <div class="share-dialog__feedback" aria-live="polite">
    {#if status}<p class="share-dialog__status" role="status">
        <UserSentence
          template={status.template}
          users={status.users}
          {locale}
        />
      </p>{/if}
  </div>
  {#if error}<p class="share-dialog__error" role="alert">{error}</p>{/if}

  <footer class="share-dialog__foot">
    <div class="share-dialog__transfer">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        disabled={!list}
        onclick={ontransfer}
      >
        {t("Transfer ownership", locale)}
      </Button>
    </div>
    <Button type="button" variant="secondary" icon="check" onclick={close}>
      {t("Done", locale)}
    </Button>
  </footer>
</dialog>

<style>
  .share-dialog {
    box-sizing: border-box;
    width: min(32rem, calc(100vw - 1.5rem));
    max-width: calc(100vw - 1.5rem);
    max-height: calc(100dvh - 1.5rem);
    overflow: auto;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 1.1rem;
    color: var(--ink);
    background: var(--surface);
  }

  .share-dialog::backdrop {
    background: rgb(15 28 44 / 72%);
  }

  .share-dialog__head h2 {
    margin: 0;
  }

  .share-dialog__subject {
    margin: 0.1rem 0 0;
    color: var(--ink-muted);
    overflow-wrap: anywhere;
  }

  .share-link {
    display: grid;
    gap: 0.35rem;
    margin-top: 0.9rem;
    padding-bottom: 0.9rem;
    border-bottom: 1px solid var(--line);
  }

  .share-link :global(.share-link__button) {
    width: 100%;
    justify-content: flex-start;
  }

  .share-link__hint {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.85rem;
    line-height: 1.4;
  }

  .share-dialog__note {
    margin: 0.8rem 0 0;
    border-radius: var(--radius-xs);
    padding: 0.55rem 0.75rem;
    border: 1px solid var(--line);
    background: var(--surface-raised);
    font-size: 0.9rem;
    line-height: 1.4;
  }

  .share-dialog__count {
    display: block;
    margin-top: 0.15rem;
    color: var(--ink-muted);
  }

  .share-dialog__muted {
    margin: 1rem 0;
    color: var(--ink-muted);
  }

  .share-dialog__people {
    display: grid;
    margin: 0.9rem 0 0;
    padding: 0;
    list-style: none;
  }

  .share-person {
    display: grid;
    min-width: 0;
    grid-template-columns: auto minmax(0, 1fr) auto auto;
    align-items: center;
    gap: 0.35rem 0.65rem;
    border-bottom: 1px solid var(--line);
    padding: 0.5rem 0;
  }

  .share-person__name {
    min-width: 0;
    overflow-wrap: anywhere;
    font-weight: 600;
  }

  .share-person__owner {
    grid-column: 3 / -1;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .share-person__role select {
    max-width: 9.5rem;
  }

  .share-add {
    display: grid;
    gap: 0.6rem;
    margin-top: 1.1rem;
  }

  .share-add h3 {
    margin: 0;
    font-size: 1.05rem;
  }

  .share-add__row {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: 0.6rem;
  }

  .share-add__role {
    display: grid;
    gap: 0.2rem;
    font-weight: 600;
  }

  .share-add__row :global(.ui-button--primary) {
    min-width: 0;
    flex: 1 1 10rem;
  }

  .share-dialog__feedback {
    min-height: 1.4rem;
    margin-top: 0.6rem;
  }

  .share-dialog__status,
  .share-dialog__error {
    margin: 0;
    font-size: 0.92rem;
  }

  .share-dialog__status {
    color: var(--accent-strong);
  }

  .share-dialog__error {
    color: var(--danger);
  }

  .share-dialog__foot {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem;
    margin-top: 1.1rem;
    border-top: 1px solid var(--line);
    padding-top: 0.8rem;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }

  @media (max-width: 30rem) {
    .share-person {
      grid-template-columns: auto minmax(0, 1fr) auto;
    }

    .share-person__role {
      grid-column: 2 / 3;
      grid-row: 2;
    }

    .share-person :global(.ui-button) {
      grid-column: 3;
      grid-row: 1 / span 2;
    }

    .share-person__owner {
      grid-column: 3;
    }
  }
</style>
