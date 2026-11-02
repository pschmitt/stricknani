<script lang="ts">
  import { onMount } from "svelte";
  import { resolve } from "$app/paths";
  import { msg } from "#lib/i18n.svelte.ts";

  // Keep offline-page copy in the generated public catalogue for build-time substitution.
  const offlinePageMessages = [
    msg("No connection"),
    msg("Your workspace is waiting online."),
    msg(
      "We couldn’t reach Stricknani. Your projects, yarn, photos, and edits are not stored offline.",
    ),
  ];
  void offlinePageMessages;
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  let updateAvailable = $state(false);
  let dismissed = $state(false);

  onMount(() => {
    if (!("serviceWorker" in navigator)) return;

    let disposed = false;
    let registration: ServiceWorkerRegistration | null = null;
    let waitingWorker: ServiceWorker | null = null;

    function showWaitingUpdate() {
      const waiting = registration?.waiting;
      if (!waiting || !navigator.serviceWorker.controller) return;
      if (waiting !== waitingWorker) {
        waitingWorker = waiting;
        dismissed = false;
      }
      updateAvailable = true;
    }

    function onUpdateFound() {
      const installing = registration?.installing;
      if (!installing) return;

      const onStateChange = () => {
        if (installing.state === "installed") showWaitingUpdate();
      };
      installing.addEventListener("statechange", onStateChange);
    }

    const appRoot = resolve("/");
    const workerUrl = new URL(`${appRoot}sw.js`, window.location.href);
    if (workerUrl.origin !== window.location.origin) return;
    const scope = appRoot;

    void navigator.serviceWorker
      .register(workerUrl.href, { scope })
      .then((result) => {
        if (disposed) return;
        registration = result;
        registration.addEventListener("updatefound", onUpdateFound);
        showWaitingUpdate();
      })
      .catch(() => {
        // Offline mode or browsers without worker support should not block the app.
      });

    return () => {
      disposed = true;
      registration?.removeEventListener("updatefound", onUpdateFound);
    };
  });
</script>

{#if updateAvailable && !dismissed}
  <aside class="update-notice" aria-labelledby="update-title" role="status">
    <div>
      <h2 id="update-title">{t("Update available", locale)}</h2>
      <p>
        {t(
          "Close all Stricknani tabs and reopen the app to finish installing the update.",
          locale,
        )}
      </p>
    </div>
    <button type="button" onclick={() => (dismissed = true)}>
      {t("Dismiss", locale)}
    </button>
  </aside>
{/if}

<style>
  .update-notice {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    max-width: 76rem;
    margin: 0.75rem auto;
    border: 1px solid var(--line);
    border-radius: 0.8rem;
    padding: 0.75rem 1rem;
    color: var(--ink);
    background: var(--accent-wash);
  }

  h2 {
    margin: 0;
    font-size: 1rem;
  }

  p {
    margin: 0.2rem 0 0;
    color: var(--ink-muted);
  }

  button {
    min-height: 44px;
    flex: 0 0 auto;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.55rem 0.9rem;
    color: var(--accent-strong);
    background: var(--surface);
    font-weight: 650;
    cursor: pointer;
  }

  @media (max-width: 38rem) {
    .update-notice {
      align-items: stretch;
      flex-direction: column;
      margin-inline: 0.75rem;
    }
  }
</style>
