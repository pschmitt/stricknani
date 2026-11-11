<script lang="ts">
  import { backOut } from "svelte/easing";
  import { fade, fly } from "svelte/transition";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { dismissFeedback, feedback } from "#lib/feedback.ts";

  let {
    ownerId,
    locale,
  }: {
    ownerId: number | null;
    locale: "en" | "de";
  } = $props();

  // Reduced-motion users get the toast without the slide or fade.
  const motionMs = (ms: number): number =>
    typeof window !== "undefined" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
      ? 0
      : ms;

  let actionBusy = $state(false);

  async function runAction(
    id: number,
    onAction: () => void | Promise<void>,
  ): Promise<void> {
    if (actionBusy) return;
    actionBusy = true;
    dismissFeedback(id);
    try {
      await onAction();
    } finally {
      actionBusy = false;
    }
  }

  const currentEvent = $derived(
    ownerId !== null && $feedback?.ownerId === ownerId ? $feedback : null,
  );
</script>

{#if currentEvent}
  {#key currentEvent.id}
    <div
      class="action-feedback"
      class:action-feedback--error={currentEvent.kind === "error"}
      class:action-feedback--has-action={Boolean(currentEvent.action)}
      role={currentEvent.kind === "error" ? "alert" : "status"}
      aria-live={currentEvent.kind === "error" ? "assertive" : "polite"}
      aria-atomic="true"
      in:fly|global={{ y: 14, duration: motionMs(320), easing: backOut }}
      out:fade|global={{ duration: motionMs(160) }}
    >
      <span class="action-feedback__badge" aria-hidden="true">
        {#if currentEvent.kind === "error"}
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            focusable="false"
          >
            <path d="M12 7v6.5M12 17.2v.1" />
          </svg>
        {:else}
          <NavIcon name="check" />
        {/if}
      </span>
      <span class="action-feedback__text">{currentEvent.message}</span>
      {#if currentEvent.action}
        {@const action = currentEvent.action}
        <button
          type="button"
          class="action-feedback__action"
          data-testid="feedback-action"
          disabled={actionBusy}
          onclick={() => void runAction(currentEvent.id, action.onAction)}
        >
          {action.label}
        </button>
      {/if}
      <button
        type="button"
        class="action-feedback__close"
        aria-label={t("Dismiss message", locale)}
        onclick={() => dismissFeedback(currentEvent.id)}
      >
        <NavIcon name="close" />
      </button>
    </div>
  {/key}
{/if}

<style>
  /* Pill-shaped toast: tinted status badge, message, then a round close
     button. Every column is centre-aligned so the close icon sits on the
     text's vertical middle even when the message wraps to several lines. */
  .action-feedback {
    --toast-tone: var(--tone-yarns);
    --toast-tone-wash: var(--tone-yarns-wash);
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
    grid-template-columns: auto minmax(0, 1fr) auto;
    width: fit-content;
    min-width: min(16rem, 100%);
    max-width: min(
      30rem,
      calc(
        100% - var(--action-feedback-inline-start) -
          var(--action-feedback-inline-end)
      )
    );
    max-height: var(--action-feedback-max-height);
    align-items: center;
    gap: 0.6rem;
    box-sizing: border-box;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.4rem 0.4rem 0.4rem 0.5rem;
    color: var(--ink);
    background: var(--surface);
    box-shadow:
      0 14px 38px rgb(63 20 80 / 16%),
      0 2px 6px rgb(63 20 80 / 10%);
    font-size: 0.95rem;
    font-weight: 650;
    line-height: 1.3;
    overflow: hidden;
  }

  .action-feedback--has-action {
    grid-template-columns: auto minmax(0, 1fr) auto auto;
  }

  .action-feedback--error {
    --toast-tone: var(--on-danger);
    --toast-tone-wash: var(--danger);
    border-color: var(--danger);
    border-radius: var(--radius-md);
  }

  .action-feedback__badge {
    display: grid;
    width: 2rem;
    height: 2rem;
    place-items: center;
    border-radius: 50%;
    color: var(--toast-tone);
    background: var(--toast-tone-wash);
  }

  .action-feedback__badge :global(svg) {
    width: 1.2rem;
    height: 1.2rem;
  }

  .action-feedback__text {
    min-width: 0;
    max-height: calc(var(--action-feedback-max-height) - 1.2rem);
    padding-block: 0.2rem;
    overflow-y: auto;
    overflow-wrap: anywhere;
  }

  .action-feedback__action {
    min-height: 44px;
    border: 0;
    border-radius: 999px;
    padding: 0 0.9rem;
    color: var(--accent-strong);
    background: transparent;
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }

  .action-feedback__action:hover {
    background: var(--surface-raised);
  }

  .action-feedback__action:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: -3px;
  }

  .action-feedback__close {
    display: grid;
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    place-items: center;
    border: 0;
    border-radius: 50%;
    padding: 0;
    color: var(--ink-muted);
    background: transparent;
    cursor: pointer;
    transition:
      background-color 120ms ease,
      color 120ms ease;
  }

  .action-feedback__close :global(svg) {
    width: 1.25rem;
    height: 1.25rem;
  }

  .action-feedback__close:hover {
    color: var(--ink);
    background: var(--surface-raised);
  }

  .action-feedback__close:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: -3px;
  }

  @media (max-width: 760px) {
    .action-feedback {
      --toast-clearance: calc(
        var(--app-mobile-nav-height, 57px) +
          var(--app-mobile-nav-bottom-inset, env(safe-area-inset-bottom, 0px)) +
          0.75rem
      );
      top: auto;
      bottom: var(--toast-clearance);
      right: var(--action-feedback-inline-end);
      left: var(--action-feedback-inline-start);
      margin-inline: auto;
      --action-feedback-inline-start: max(0.75rem, env(safe-area-inset-left));
      --action-feedback-inline-end: max(0.75rem, env(safe-area-inset-right));
      --action-feedback-max-height: min(
        9rem,
        calc(100dvh - var(--toast-clearance) - 4.5rem)
      );
    }

    /* Edit pages keep a sticky Save bar above the phone navigation. */
    :global(body:has(.sticky-action-bar)) .action-feedback {
      bottom: calc(var(--toast-clearance) + 4.25rem);
    }
  }
</style>
