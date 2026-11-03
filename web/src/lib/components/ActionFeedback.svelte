<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { dismissFeedback, feedback } from "#lib/feedback.ts";

  let {
    ownerId,
    locale,
  }: {
    ownerId: number | null;
    locale: "en" | "de";
  } = $props();

  const currentEvent = $derived(
    ownerId !== null && $feedback?.ownerId === ownerId ? $feedback : null,
  );
</script>

{#if currentEvent}
  {#key currentEvent.id}
    <div
      class="action-feedback"
      class:action-feedback--error={currentEvent.kind === "error"}
      role={currentEvent.kind === "error" ? "alert" : "status"}
      aria-live={currentEvent.kind === "error" ? "assertive" : "polite"}
      aria-atomic="true"
    >
      <span>{currentEvent.message}</span>
      <Button
        variant="ghost"
        icon="close"
        type="button"
        aria-label={t("Dismiss message", locale)}
        onclick={() => dismissFeedback(currentEvent.id)}
      />
    </div>
  {/key}
{/if}

<style>
  .action-feedback {
    position: fixed;
    z-index: 20;
    right: max(1rem, env(safe-area-inset-right));
    bottom: 6rem;
    display: flex;
    width: min(32rem, calc(100vw - 2rem));
    min-height: 44px;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    box-sizing: border-box;
    border: 1px solid var(--control-border);
    border-radius: 0.85rem;
    padding: 0.55rem 0.65rem 0.55rem 0.9rem;
    color: var(--ink);
    background: var(--surface-raised);
    box-shadow: var(--shadow);
  }

  .action-feedback--error {
    border-color: var(--danger);
  }

  .action-feedback > span {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  @media (max-width: 760px) {
    .action-feedback {
      right: max(0.75rem, env(safe-area-inset-right));
      bottom: calc(56px + env(safe-area-inset-bottom) + 6rem);
      width: min(32rem, calc(100vw - 1.5rem));
    }
  }
</style>
