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
    --action-feedback-header-height: calc(4.25rem + env(safe-area-inset-top));
    --action-feedback-inline-start: max(1rem, env(safe-area-inset-left));
    --action-feedback-inline-end: max(1rem, env(safe-area-inset-right));
    --action-feedback-max-height: min(
      9rem,
      calc(100dvh - var(--action-feedback-header-height) - 1.5rem)
    );
    position: fixed;
    z-index: 20;
    top: calc(var(--action-feedback-header-height) + 0.75rem);
    right: var(--action-feedback-inline-end);
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    width: min(
      32rem,
      calc(
        100% - var(--action-feedback-inline-start) -
          var(--action-feedback-inline-end)
      )
    );
    max-height: var(--action-feedback-max-height);
    min-height: 44px;
    align-items: start;
    gap: 0.75rem;
    box-sizing: border-box;
    border: 1px solid var(--control-border);
    border-radius: 0.85rem;
    padding: 0.55rem 0.65rem 0.55rem 0.9rem;
    color: var(--ink);
    background: var(--surface-raised);
    box-shadow: var(--shadow);
    overflow: hidden;
  }

  .action-feedback--error {
    border-color: var(--danger);
  }

  .action-feedback > span {
    min-width: 0;
    max-height: calc(var(--action-feedback-max-height) - 1.2rem);
    overflow-y: auto;
    overflow-wrap: anywhere;
  }

  @media (max-width: 760px) {
    .action-feedback {
      --action-feedback-header-height: max(
        3.5rem,
        calc(45px + env(safe-area-inset-top))
      );
      --action-feedback-inline-start: max(0.75rem, env(safe-area-inset-left));
      --action-feedback-inline-end: max(0.75rem, env(safe-area-inset-right));
      --action-feedback-max-height: min(
        9rem,
        calc(
          100dvh - var(--action-feedback-header-height) -
            var(--app-mobile-nav-height, 57px) - env(safe-area-inset-bottom) -
            1.5rem
        )
      );
    }
  }
</style>
