<script lang="ts">
  import { onMount } from "svelte";
  import { fly } from "svelte/transition";
  import { beforeNavigate } from "$app/navigation";
  import { version as appVersion } from "$app/env";
  import { resolve } from "$app/paths";
  import { updated } from "$app/state";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import SharedButton from "#lib/components/Button.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { updateReadiness } from "#lib/pwa-update-coordinator.ts";
  import { session } from "#lib/session.svelte.ts";

  // Keep offline-page copy in the generated public catalogue for build-time substitution.
  const offlinePageMessages = [
    msg("No connection"),
    msg("Your workspace is waiting online."),
    msg(
      "We couldn’t reach Stricknani. Your projects, yarn, photos, and edits are not stored offline.",
    ),
  ];
  void offlinePageMessages;

  // Each tab updates itself; no other tab is asked. HTML is always fetched from
  // the network and assets are content-hashed, so a full load is the update.
  // SvelteKit sets updated.current when it sees a new deployment (poll, focus,
  // response headers); the next clean in-app navigation then loads it.
  const SEEN_VERSION_KEY = "stricknani.seen-app-version";

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  let dismissed = $state(false);
  let blocked = $state<"dirty" | "busy" | null>(null);
  let announced = false;

  beforeNavigate(({ to, type, willUnload, cancel }) => {
    if (!updated.current || willUnload || !to?.url) return;
    if (type !== "link" && type !== "goto") return;
    if (to.url.origin !== window.location.origin) return;
    // Same page, other fragment: an in-page jump, not a page change.
    if (
      to.url.pathname === window.location.pathname &&
      to.url.search === window.location.search
    ) {
      return;
    }
    // A dirty or busy tab keeps the old version; its own leave guard runs.
    if (updateReadiness() !== "ready") return;
    cancel();
    window.location.assign(to.url.href);
  });

  function reloadNow() {
    const readiness = updateReadiness();
    if (readiness !== "ready") {
      blocked = readiness;
      return;
    }
    window.location.reload();
  }

  // One quiet confirmation after a tab has loaded a newer version.
  $effect(() => {
    if (announced || userId === null) return;
    announced = true;
    try {
      const seen = window.localStorage.getItem(SEEN_VERSION_KEY);
      window.localStorage.setItem(SEEN_VERSION_KEY, appVersion);
      if (seen && seen !== appVersion) {
        publishFeedback({
          kind: "success",
          ownerId: userId,
          message: t("Stricknani was updated.", locale),
        });
      }
    } catch {
      // Storage can be unavailable (private mode); the toast is optional.
    }
  });

  onMount(() => {
    if (!("serviceWorker" in navigator)) return;
    const appRoot = resolve("/");
    const workerUrl = new URL(`${appRoot}sw.js`, window.location.href);
    if (workerUrl.origin !== window.location.origin) return;
    // A new worker takes over by itself; use that as a cue to look for the
    // matching app version instead of waiting for the next poll.
    const onControllerChange = () => void updated.check().catch(() => false);
    navigator.serviceWorker.addEventListener(
      "controllerchange",
      onControllerChange,
    );
    void navigator.serviceWorker
      .register(workerUrl.href, { scope: appRoot })
      .catch(() => {
        // Offline mode and unsupported contexts should not block the app.
      });
    return () =>
      navigator.serviceWorker.removeEventListener(
        "controllerchange",
        onControllerChange,
      );
  });
</script>

{#if updated.current && !dismissed}
  <aside
    class="update-chip"
    aria-labelledby="update-title"
    role="status"
    transition:fly={{ y: -12, duration: 220 }}
  >
    <p id="update-title">
      {#if blocked === "dirty"}
        {t("Save your changes first, then reload.", locale)}
      {:else if blocked === "busy"}
        {t("Wait until the current action finishes, then reload.", locale)}
      {:else}
        {t("New version ready", locale)}
      {/if}
    </p>
    <SharedButton variant="primary" size="sm" type="button" onclick={reloadNow}>
      <ActionLabel icon="refresh">{t("Reload", locale)}</ActionLabel>
    </SharedButton>
    <SharedButton
      variant="ghost"
      size="sm"
      type="button"
      icon="close"
      aria-label={t("Later", locale)}
      onclick={() => (dismissed = true)}
    />
  </aside>
{/if}

<style>
  /* Just below the header: the bottom edge belongs to sticky Save bars,
     the action toast and the phone navigation. Header heights from app.css. */
  .update-chip {
    position: fixed;
    z-index: 19;
    top: calc(4.25rem + env(safe-area-inset-top) + 0.75rem);
    right: max(1rem, env(safe-area-inset-right));
    display: flex;
    max-width: min(28rem, calc(100vw - 2rem));
    align-items: center;
    gap: 0.5rem;
    border: 1px solid var(--control-border);
    border-radius: 999px;
    padding: 0.3rem 0.3rem 0.3rem 1rem;
    color: var(--ink);
    background: var(--surface-raised);
    box-shadow: var(--shadow);
  }

  p {
    margin: 0;
    min-width: 0;
    font-size: 0.9rem;
    font-weight: 560;
  }

  @media (max-width: 760px) {
    .update-chip {
      top: calc(max(3.5rem, calc(45px + env(safe-area-inset-top))) + 0.5rem);
      right: max(0.75rem, env(safe-area-inset-right));
      left: max(0.75rem, env(safe-area-inset-left));
      max-width: none;
      border-radius: 0.85rem;
    }

    p {
      flex: 1 1 auto;
    }
  }
</style>
