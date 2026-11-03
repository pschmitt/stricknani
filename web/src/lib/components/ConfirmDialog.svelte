<script lang="ts">
  import type { Snippet } from "svelte";

  let {
    titleId,
    title,
    children,
    actions,
    busy = false,
  }: {
    titleId: string;
    title: string;
    children: Snippet;
    actions: Snippet;
    busy?: boolean;
  } = $props();

  let dialog = $state<HTMLDialogElement>();

  export function showModal(): void {
    if (!dialog?.open) dialog?.showModal();
  }

  export function close(): void {
    if (dialog?.open) dialog.close();
  }

  function preventCancelWhileBusy(event: Event): void {
    if (busy) event.preventDefault();
  }
</script>

<dialog
  bind:this={dialog}
  class="confirm-dialog"
  aria-labelledby={titleId}
  oncancel={preventCancelWhileBusy}
>
  <h2 id={titleId}>{title}</h2>
  <div class="confirm-dialog__content">{@render children()}</div>
  <div class="confirm-dialog__actions">{@render actions()}</div>
</dialog>

<style>
  .confirm-dialog {
    box-sizing: border-box;
    width: min(32rem, calc(100vw - 2rem));
    max-width: calc(100vw - 2rem);
    max-height: calc(100vh - 2rem);
    max-height: calc(100dvh - 2rem);
    overflow: auto;
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: 1.2rem;
    color: var(--ink);
    background: var(--surface);
  }

  .confirm-dialog::backdrop {
    background: rgb(15 28 44 / 72%);
  }

  .confirm-dialog h2 {
    margin-top: 0;
  }

  .confirm-dialog__actions {
    display: flex;
    min-width: 0;
    align-items: center;
    justify-content: flex-end;
    flex-wrap: wrap;
    gap: 0.6rem;
    margin-top: 1.2rem;
  }

  .confirm-dialog__actions :global(*) {
    max-width: 100%;
  }
</style>
