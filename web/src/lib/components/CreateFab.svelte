<script lang="ts">
  import { onMount } from "svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";

  /**
   * The phone layout's create button on the project and yarn lists, as on
   * Android: an extended floating button above the bottom bar that shrinks to
   * its icon once the list is scrolled. Wider layouts use the header button.
   */
  let {
    href,
    label,
    testId,
  }: {
    href: string;
    label: string;
    testId?: string;
  } = $props();

  let collapsed = $state(false);

  onMount(() => {
    const update = () => {
      collapsed = window.scrollY > 8;
    };
    update();
    window.addEventListener("scroll", update, { passive: true });
    return () => window.removeEventListener("scroll", update);
  });
</script>

<a
  class="create-fab"
  class:create-fab--collapsed={collapsed}
  {href}
  aria-label={label}
  data-testid={testId}
>
  <NavIcon name="add" />
  <span class="create-fab__label" aria-hidden="true">{label}</span>
</a>

<style>
  .create-fab {
    display: none;
  }

  @media (max-width: 640px) {
    .create-fab {
      position: fixed;
      z-index: 20;
      right: max(1rem, env(safe-area-inset-right));
      bottom: calc(
        var(--app-mobile-nav-height) + var(--app-mobile-nav-bottom-inset) + 1rem
      );
      display: inline-flex;
      min-width: 56px;
      min-height: 56px;
      align-items: center;
      justify-content: center;
      gap: 0.6rem;
      padding: 0 1.15rem;
      border-radius: 16px;
      background: var(--primary-container);
      box-shadow: var(--shadow);
      color: var(--on-primary-container);
      font-weight: 600;
      text-decoration: none;
      transition:
        padding 160ms ease,
        gap 160ms ease;
    }

    .create-fab:focus-visible {
      outline: 3px solid var(--focus);
      outline-offset: 3px;
    }

    .create-fab__label {
      overflow: hidden;
      max-width: 12rem;
      white-space: nowrap;
      transition: max-width 160ms ease;
    }

    .create-fab--collapsed {
      gap: 0;
      padding: 0;
    }

    .create-fab--collapsed .create-fab__label {
      max-width: 0;
    }
  }

  @media (max-width: 640px) and (prefers-reduced-motion: reduce) {
    .create-fab,
    .create-fab__label {
      transition: none;
    }
  }
</style>
