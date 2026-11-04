<script lang="ts">
  import { onMount, tick, untrack } from "svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import {
    nextMenuIndex,
    POINTER_MEDIA_QUERY,
    SHEET_MEDIA_QUERY,
    shouldDismissSheet,
    trapTabIndex,
  } from "#lib/account-menu.ts";
  import LocaleSwitcher from "#lib/components/LocaleSwitcher.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import QrCode from "#lib/components/QrCode.svelte";
  import ThemeSwitcher from "#lib/components/ThemeSwitcher.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { androidAction } from "#lib/app-links.ts";
  import { publishFeedback } from "#lib/feedback.ts";
  import { session, signOut } from "#lib/session.svelte.ts";

  let {
    email,
    avatarUrl,
    isAdmin,
    canManageAdmin,
    locale,
  }: {
    email: string;
    avatarUrl: string | null;
    isAdmin: boolean;
    canManageAdmin: boolean;
    locale: Locale;
  } = $props();

  const androidApp = androidAction();
  const exitMs = 180;
  const focusableSelector =
    'button:not(:disabled), a[href], [role="menuitem"]:not([aria-disabled="true"])';

  let trigger = $state<HTMLButtonElement>();
  let panel = $state<HTMLDivElement>();
  let mounted = $state(false);
  let open = $state(false);
  let sheet = $state(false);
  let showQr = $state(false);
  let anchorTop = $state(0);
  let anchorRight = $state(0);
  let dragY = $state(0);
  let dragging = $state(false);
  let dragStart: { y: number; at: number } | null = null;
  let signOutBusy = $state(false);
  let closeTimer: ReturnType<typeof setTimeout> | undefined;

  const reducedMotion = () =>
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function portal(node: HTMLElement) {
    document.body.append(node);
    return { destroy: () => node.remove() };
  }

  function menuItems(): HTMLElement[] {
    return panel
      ? Array.from(panel.querySelectorAll<HTMLElement>('[role="menuitem"]'))
      : [];
  }

  function focusables(): HTMLElement[] {
    return panel
      ? Array.from(panel.querySelectorAll<HTMLElement>(focusableSelector))
      : [];
  }

  async function openMenu(focusLast = false) {
    if (mounted || !trigger) return;
    clearTimeout(closeTimer);
    sheet = window.matchMedia(SHEET_MEDIA_QUERY).matches;
    // Phones keep the plain link; a mouse-driven wide screen also shows a code to scan.
    showQr = !sheet && window.matchMedia(POINTER_MEDIA_QUERY).matches;
    const anchor = trigger.getBoundingClientRect();
    anchorTop = anchor.bottom + 6;
    anchorRight = Math.max(8, window.innerWidth - anchor.right);
    dragY = 0;
    mounted = true;
    open = true;
    await tick();
    const items = menuItems();
    (focusLast ? items.at(-1) : items[0])?.focus({ preventScroll: true });
  }

  function closeMenu(restoreFocus = true) {
    if (!mounted || !open) return;
    open = false;
    clearTimeout(closeTimer);
    closeTimer = setTimeout(
      () => {
        mounted = false;
        dragY = 0;
      },
      reducedMotion() ? 0 : exitMs,
    );
    if (restoreFocus) trigger?.focus({ preventScroll: true });
  }

  function toggle() {
    if (open) closeMenu();
    else void openMenu();
  }

  function handleTriggerKeydown(event: KeyboardEvent) {
    if (event.key === "ArrowDown" && !mounted) {
      event.preventDefault();
      void openMenu();
    } else if (event.key === "ArrowUp" && !mounted) {
      event.preventDefault();
      void openMenu(true);
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (!mounted || !open) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      closeMenu();
      return;
    }
    if (event.key === "Tab") {
      const items = focusables();
      const index = items.indexOf(document.activeElement as HTMLElement);
      const target = trapTabIndex(index, items.length, event.shiftKey);
      if (target !== null) {
        event.preventDefault();
        items[target]?.focus();
      }
      return;
    }
    const items = menuItems();
    const index = items.indexOf(document.activeElement as HTMLElement);
    const target = nextMenuIndex(index, items.length, event.key);
    if (target === null) return;
    event.preventDefault();
    items[target]?.focus({ preventScroll: true });
  }

  function handleResize() {
    if (mounted) closeMenu(false);
  }

  function gripDown(event: PointerEvent) {
    if (!sheet || event.button > 0) return;
    dragStart = { y: event.clientY, at: event.timeStamp };
    dragging = true;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }

  function gripMove(event: PointerEvent) {
    if (!dragStart) return;
    dragY = Math.max(0, event.clientY - dragStart.y);
  }

  function gripUp(event: PointerEvent) {
    if (!dragStart) return;
    const start = dragStart;
    dragStart = null;
    dragging = false;
    const delta = event.clientY - start.y;
    if (shouldDismissSheet(delta, event.timeStamp - start.at)) closeMenu();
    else dragY = 0;
  }

  async function handleSignOut() {
    if (signOutBusy) return;
    signOutBusy = true;
    const ownerId = session.data?.user?.id;
    const origin = page.url.pathname + page.url.search + page.url.hash;
    try {
      // Leave the current page first so its own unsaved-changes confirm runs.
      // A cancelled navigation keeps the page, the draft and the session.
      await goto(resolve("/login"));
      if (page.url.pathname !== resolve("/login")) return;
      try {
        await signOut();
      } catch {
        const message = t("Could not sign out.", locale);
        if (ownerId !== undefined)
          publishFeedback({ ownerId, kind: "error", message });
        await goto(origin, { replaceState: true });
      }
    } finally {
      signOutBusy = false;
    }
  }

  $effect(() => {
    void page.url.pathname;
    untrack(() => {
      if (mounted) closeMenu(false);
    });
  });

  onMount(() => {
    window.addEventListener("keydown", handleKeydown, true);
    window.addEventListener("resize", handleResize);
    return () => {
      clearTimeout(closeTimer);
      window.removeEventListener("keydown", handleKeydown, true);
      window.removeEventListener("resize", handleResize);
    };
  });
</script>

<div class="account-menu">
  <button
    bind:this={trigger}
    class="header-account"
    type="button"
    aria-label={t("Account", locale)}
    aria-haspopup="dialog"
    aria-expanded={open}
    onclick={toggle}
    onkeydown={handleTriggerKeydown}
  >
    <UserAvatar {avatarUrl} name={email} />
  </button>
</div>

{#if mounted}
  <div use:portal class="account-menu-layer">
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
    <div
      class="account-menu-backdrop"
      class:sheet
      data-state={open ? "open" : "closed"}
      onclick={() => closeMenu()}
    ></div>
    <div
      bind:this={panel}
      class="account-menu-panel"
      class:sheet
      class:dragging
      data-state={open ? "open" : "closed"}
      role="dialog"
      aria-label={t("Account", locale)}
      style:top={sheet ? undefined : `${anchorTop}px`}
      style:right={sheet ? undefined : `${anchorRight}px`}
      style:transform={dragY > 0 ? `translateY(${dragY}px)` : undefined}
    >
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="account-menu-head"
        onpointerdown={gripDown}
        onpointermove={gripMove}
        onpointerup={gripUp}
        onpointercancel={gripUp}
      >
        {#if sheet}<span class="account-menu-grip" aria-hidden="true"
          ></span>{/if}
        <UserAvatar {avatarUrl} name={email} size="large" />
        <div class="account-menu-identity">
          <span class="account-menu-email">{email}</span>
          {#if isAdmin}
            <span class="account-menu-badge">{t("Admin", locale)}</span>
          {/if}
        </div>
      </div>

      <div class="account-menu-prefs">
        <div class="account-menu-pref">
          <span class="account-menu-label">{t("Language", locale)}</span>
          <LocaleSwitcher />
        </div>
        <div class="account-menu-pref">
          <span class="account-menu-label">{t("Theme", locale)}</span>
          <ThemeSwitcher {locale} />
        </div>
      </div>

      <div
        class="account-menu-items"
        role="menu"
        aria-label={t("Account", locale)}
      >
        <a
          class="account-menu-item"
          role="menuitem"
          href={resolve("/account")}
          onclick={() => closeMenu(false)}
        >
          <NavIcon name="account" />
          <span>{t("Account settings", locale)}</span>
        </a>
        {#if canManageAdmin}
          <a
            class="account-menu-item"
            role="menuitem"
            href={resolve("/admin/users")}
            onclick={() => closeMenu(false)}
          >
            <NavIcon name="admin" />
            <span>{t("Admin", locale)}</span>
          </a>
        {/if}
        <a
          class="account-menu-item"
          role="menuitem"
          href={androidApp.url}
          target="_blank"
          rel="noopener noreferrer"
          onclick={() => closeMenu(false)}
        >
          <NavIcon name="download" />
          <span>{t("Get the Android app", locale)}</span>
        </a>
        {#if showQr}
          <div class="account-menu-qr" role="none">
            <div class="account-menu-qr-code">
              <QrCode
                value={androidApp.url}
                label={t("QR code for the Android app download", locale)}
              />
            </div>
            <p class="account-menu-qr-hint">
              {t("Scan with your phone to get the app.", locale)}
            </p>
          </div>
        {/if}
        <a
          class="account-menu-item"
          role="menuitem"
          href={resolve("/about")}
          onclick={() => closeMenu(false)}
        >
          <NavIcon name="info" />
          <span>{t("About", locale)}</span>
        </a>
        <button
          class="account-menu-item account-menu-item--danger"
          type="button"
          role="menuitem"
          aria-disabled={signOutBusy}
          onclick={() => void handleSignOut()}
        >
          <NavIcon name="sign-out" />
          <span
            >{signOutBusy ? t("Working…", locale) : t("Sign out", locale)}</span
          >
        </button>
      </div>
    </div>
  </div>
{/if}

<style>
  .account-menu {
    display: inline-flex;
    flex: 0 0 44px;
    margin-left: auto;
  }

  .header-account {
    margin-left: 0;
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }

  .header-account:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .header-account[aria-expanded="true"] :global(.user-avatar) {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-wash);
  }

  .account-menu-backdrop {
    position: fixed;
    z-index: 40;
    inset: 0;
    background: transparent;
  }

  .account-menu-backdrop.sheet {
    background: rgb(42 32 51 / 45%);
    animation: backdrop-in 200ms ease both;
  }

  .account-menu-backdrop.sheet[data-state="closed"] {
    animation: backdrop-out 180ms ease both;
  }

  .account-menu-panel {
    position: fixed;
    z-index: 41;
    box-sizing: border-box;
    width: min(20rem, calc(100vw - 1rem));
    max-height: calc(100dvh - 5rem);
    overflow: auto;
    overscroll-behavior: contain;
    padding: 0.75rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    color: var(--ink);
    background: var(--surface);
    box-shadow:
      0 2px 6px rgb(63 20 80 / 8%),
      var(--shadow);
    transform-origin: top right;
    animation: popover-in 160ms cubic-bezier(0.2, 0.9, 0.3, 1) both;
  }

  .account-menu-panel[data-state="closed"] {
    animation: popover-out 140ms ease both;
  }

  .account-menu-panel.sheet {
    top: auto;
    right: 0;
    bottom: 0;
    left: 0;
    width: auto;
    max-height: 85dvh;
    padding: 0.5rem 1rem calc(1rem + env(safe-area-inset-bottom, 0px));
    border-width: 1px 0 0;
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    transform-origin: bottom center;
    animation: sheet-in 380ms cubic-bezier(0.34, 1.35, 0.64, 1) both;
  }

  .account-menu-panel.sheet[data-state="closed"] {
    animation: sheet-out 180ms ease-in both;
  }

  .account-menu-panel.dragging {
    transition: none;
    animation: none;
  }

  .account-menu-head {
    position: relative;
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.25rem 0.25rem 0.75rem;
  }

  .sheet .account-menu-head {
    flex-wrap: wrap;
    padding-top: 1.5rem;
    touch-action: none;
  }

  .account-menu-grip {
    position: absolute;
    top: 0.35rem;
    left: 50%;
    width: 2.5rem;
    height: 0.3rem;
    border-radius: 999px;
    background: var(--line);
    transform: translateX(-50%);
  }

  .account-menu-identity {
    display: grid;
    min-width: 0;
    gap: 0.2rem;
    justify-items: start;
  }

  .account-menu-email {
    max-width: 100%;
    overflow: hidden;
    font-family: var(--font-display);
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .account-menu-badge {
    border-radius: 999px;
    padding: 0.1rem 0.6rem;
    color: var(--tone-admin);
    background: var(--tone-admin-wash);
    font-size: 0.75rem;
    font-weight: 800;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }

  .account-menu-prefs {
    display: grid;
    gap: 0.25rem;
    padding: 0.5rem 0.25rem;
    border-block: 1px solid var(--line);
  }

  .account-menu-pref {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
  }

  /* Compact segmented controls in the menu: they are secondary settings, and
     the full 44px rows made the preferences block taller than the menu items. */
  .account-menu-pref :global(.theme-switcher .theme-option),
  .account-menu-pref :global(.locale-switcher .locale-option) {
    min-height: 32px;
    padding-block: 0;
  }

  .account-menu-pref :global(.theme-switcher),
  .account-menu-pref :global(.locale-switcher) {
    padding: 0.1rem;
  }

  .account-menu-label {
    color: var(--ink-muted);
    font-size: 0.8rem;
    font-weight: 800;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }

  .account-menu-items {
    display: grid;
    gap: 0.15rem;
    padding-top: 0.5rem;
  }

  .account-menu-item {
    display: flex;
    box-sizing: border-box;
    width: 100%;
    min-height: 44px;
    align-items: center;
    gap: 0.75rem;
    padding: 0.5rem 0.75rem;
    border: 0;
    border-radius: var(--radius-xs);
    color: var(--ink);
    background: transparent;
    font: inherit;
    font-weight: 700;
    text-align: left;
    text-decoration: none;
    cursor: pointer;
    transition: background-color 120ms ease;
    -webkit-tap-highlight-color: transparent;
  }

  .account-menu-item :global(svg) {
    flex: 0 0 auto;
    width: var(--ui-action-icon-size-md);
    height: var(--ui-action-icon-size-md);
    color: var(--accent-strong);
  }

  .account-menu-item:hover,
  .account-menu-item:focus-visible {
    background: var(--accent-wash);
  }

  .account-menu-item:focus-visible {
    outline: 2px solid var(--focus);
    outline-offset: -2px;
  }

  .account-menu-item[aria-disabled="true"] {
    color: var(--ink-muted);
    cursor: progress;
  }

  .account-menu-qr {
    display: grid;
    justify-items: center;
    gap: 0.4rem;
    padding: 0.25rem 0.75rem 0.5rem;
  }

  .account-menu-qr-code {
    box-sizing: border-box;
    width: 9rem;
    padding: 0.25rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    background: var(--surface);
  }

  .account-menu-qr-hint {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.8rem;
    text-align: center;
  }

  .account-menu-item--danger,
  .account-menu-item--danger :global(svg) {
    color: var(--danger);
  }

  @keyframes popover-in {
    from {
      opacity: 0;
      transform: scale(0.92);
    }
  }

  @keyframes popover-out {
    to {
      opacity: 0;
      transform: scale(0.95);
    }
  }

  @keyframes sheet-in {
    from {
      transform: translateY(100%);
    }
  }

  @keyframes sheet-out {
    to {
      transform: translateY(100%);
    }
  }

  @keyframes backdrop-in {
    from {
      opacity: 0;
    }
  }

  @keyframes backdrop-out {
    to {
      opacity: 0;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .account-menu-panel,
    .account-menu-panel.sheet,
    .account-menu-backdrop.sheet,
    .account-menu-item {
      transition: none;
      animation: none !important;
    }
  }
</style>
