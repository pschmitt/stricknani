<script lang="ts">
  import { untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import ShareDialog from "./ShareDialog.svelte";
  import TransferDialog from "./TransferDialog.svelte";
  import {
    listShares,
    type ShareKind,
    type ShareList,
    type TransferResult,
  } from "./sharing-api.ts";

  let {
    kind,
    resourceId,
    name,
    locale,
    ownerId,
    linkedYarnCount = 0,
    movableYarnCount = 0,
    ontransferred,
    onaccesslost,
  }: {
    kind: ShareKind;
    resourceId: number;
    name: string;
    locale: Locale;
    ownerId: number;
    linkedYarnCount?: number;
    movableYarnCount?: number;
    ontransferred: (result: TransferResult) => void;
    onaccesslost: () => void;
  } = $props();

  const maxAvatars = 3;

  let list = $state<ShareList | null>(null);
  let loading = $state(false);
  let loadError = $state("");
  let shareDialog = $state<ShareDialog | undefined>(undefined);
  let transferDialog = $state<TransferDialog | undefined>(undefined);
  let generation = 0;

  const people = $derived(list?.shares.map((share) => share.user) ?? []);

  async function load(id: number, signal?: AbortSignal) {
    const current = ++generation;
    loading = true;
    loadError = "";
    try {
      const result = await listShares(kind, id, signal);
      if (signal?.aborted || current !== generation) return;
      list = result;
    } catch (error) {
      if (signal?.aborted || current !== generation) return;
      if (error instanceof ApiError && error.status === 404) {
        onaccesslost();
        return;
      }
      loadError =
        error instanceof ApiError
          ? error.message
          : t("The people with access could not be loaded.", locale);
    } finally {
      if (current === generation) loading = false;
    }
  }

  $effect(() => {
    const id = resourceId;
    list = null;
    const controller = new AbortController();
    void untrack(() => load(id, controller.signal));
    return () => {
      controller.abort();
      generation += 1;
    };
  });

  function openTransfer() {
    untrack(() => transferDialog?.showModal());
  }

  function transferred(result: TransferResult) {
    transferDialog?.close();
    shareDialog?.close();
    ontransferred(result);
  }
</script>

<div class="share-control" data-testid="share-control">
  <button
    type="button"
    class="share-control__button"
    aria-haspopup="dialog"
    onclick={() => shareDialog?.showModal()}
  >
    {#if people.length}
      <span class="share-control__avatars" aria-hidden="true">
        {#each people.slice(0, maxAvatars) as person (person.id)}
          <UserAvatar avatarUrl={person.avatar_url} name={person.name} />
        {/each}
        {#if people.length > maxAvatars}
          <span class="share-control__more">+{people.length - maxAvatars}</span>
        {/if}
      </span>
    {/if}
    <ActionLabel icon="user-add">{t("Share", locale)}</ActionLabel>
  </button>
</div>

<ShareDialog
  bind:this={shareDialog}
  {kind}
  {resourceId}
  {name}
  {locale}
  {linkedYarnCount}
  bind:list
  {loading}
  {loadError}
  onretry={() => void load(resourceId)}
  ontransfer={openTransfer}
  {onaccesslost}
/>
<TransferDialog
  bind:this={transferDialog}
  {kind}
  {resourceId}
  {locale}
  {ownerId}
  {movableYarnCount}
  ontransferred={transferred}
  {onaccesslost}
/>

<style>
  .share-control {
    display: flex;
    min-width: 0;
  }

  .share-control__button {
    display: inline-flex;
    min-height: 2.75rem;
    max-width: 100%;
    align-items: center;
    gap: 0.6rem;
    border: 1px solid color-mix(in srgb, var(--accent-strong) 40%, var(--line));
    border-radius: 999px;
    padding: 0.2rem 0.95rem 0.2rem 0.45rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-weight: 600;
    cursor: pointer;
  }

  .share-control__button:not(:has(.share-control__avatars)) {
    padding-left: 0.95rem;
  }

  .share-control__button:focus-visible {
    outline: 2px solid var(--accent-strong);
    outline-offset: 2px;
  }

  .share-control__avatars {
    display: inline-flex;
    align-items: center;
  }

  .share-control__avatars :global(.user-avatar) {
    flex-basis: 1.9rem;
    width: 1.9rem;
    height: 1.9rem;
    border: 2px solid var(--surface);
    font-size: 0.8rem;
  }

  .share-control__avatars :global(.user-avatar + .user-avatar),
  .share-control__avatars :global(.user-avatar + .share-control__more) {
    margin-left: -0.55rem;
  }

  .share-control__more {
    display: inline-grid;
    height: 1.9rem;
    min-width: 1.9rem;
    place-items: center;
    border: 2px solid var(--surface);
    border-radius: 999px;
    padding: 0 0.3rem;
    color: var(--on-accent);
    background: var(--accent-strong);
    font-size: 0.78rem;
  }
</style>
