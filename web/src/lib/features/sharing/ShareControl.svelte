<script lang="ts">
  import { untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { shareCurrentPage } from "#lib/share-link-action.ts";
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

  let list = $state<ShareList | null>(null);
  let loading = $state(false);
  let loadError = $state("");
  let shareDialog = $state<ShareDialog | undefined>(undefined);
  let transferDialog = $state<TransferDialog | undefined>(undefined);
  let generation = 0;

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

  /** Opens the Share dialog; the page's overflow menu item calls this. */
  export function open(): void {
    untrack(() => shareDialog?.showModal());
  }

  function openTransfer() {
    untrack(() => transferDialog?.showModal());
  }

  function transferred(result: TransferResult) {
    transferDialog?.close();
    shareDialog?.close();
    ontransferred(result);
  }
</script>

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
  onsharelink={() => shareCurrentPage(name)}
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
