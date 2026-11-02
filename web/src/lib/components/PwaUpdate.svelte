<script lang="ts">
  import { onMount } from "svelte";
  import { version as appVersion } from "$app/env";
  import { resolve } from "$app/paths";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import SharedButton from "#lib/components/Button.svelte";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    askWorker,
    cancelLocalUpdate,
    confirmReloadedApp,
    handlePwaWorkerMessage,
    retryPreparedReload,
    updateCoordinatorChannel,
  } from "#lib/pwa-update-coordinator.ts";

  // Keep offline-page copy in the generated public catalogue for build-time substitution.
  const offlinePageMessages = [
    msg("No connection"),
    msg("Your workspace is waiting online."),
    msg(
      "We couldn’t reach Stricknani. Your projects, yarn, photos, and edits are not stored offline.",
    ),
  ];
  void offlinePageMessages;

  type NoticeState =
    | "available"
    | "preparing"
    | "committing"
    | "activated"
    | "blocked"
    | "reload-cancelled"
    | "cancelled";

  const locale = $derived(session.data?.locale ?? "en");
  let registration = $state<ServiceWorkerRegistration | null>(null);
  let waitingWorker = $state<ServiceWorker | null>(null);
  let noticeState = $state<NoticeState | null>(null);
  let reason = $state("");
  let noticeUpdateId = $state("");
  let dismissed = $state(false);
  let requestBusy = $state(false);
  let commitAccepted = $state(false);
  let requestGeneration = 0;

  const actionLabel = $derived(
    noticeState === "reload-cancelled" || noticeState === "activated"
      ? t("Reload to finish", locale)
      : noticeState === "preparing" || noticeState === "committing"
        ? t("Preparing update…", locale)
        : t("Reload to update", locale),
  );

  function setState(next: NoticeState, updateId = "", nextReason = "") {
    noticeState = next;
    if (updateId) noticeUpdateId = updateId;
    reason = nextReason;
    dismissed = false;
  }

  function reasonCopy(value: string): string {
    if (value === "dirty" || value === "changed") {
      return t(
        "An open form has unsaved changes. Save or discard them, then try again.",
        locale,
      );
    }
    if (value === "busy") {
      return t(
        "An operation is still in progress. Wait for it to finish, then try again.",
        locale,
      );
    }
    if (value === "unresponsive") {
      return t(
        "Another app tab did not respond. Save any edits there, then reload or close it and try again.",
        locale,
      );
    }
    if (value === "timeout" || value === "expired") {
      return t("The update timed out. You can safely try again.", locale);
    }
    if (value === "unloaded") {
      return t(
        "The browser kept this tab open. Reload when it is safe.",
        locale,
      );
    }
    return t("The update could not start. You can safely try again.", locale);
  }

  function onCoordinatorEvent(event: Event) {
    const detail = (
      event as CustomEvent<{
        state?: NoticeState;
        updateId?: string;
        reason?: string;
      }>
    ).detail;
    if (!detail?.state) return;
    if (detail.state === "preparing") {
      commitAccepted = false;
      setState("preparing", detail.updateId ?? "");
    } else if (detail.state === "committing") {
      commitAccepted = true;
      setState("committing", detail.updateId ?? "");
    } else if (detail.state === "blocked") {
      setState("blocked", detail.updateId ?? "", detail.reason ?? "");
    } else if (detail.state === "activated") {
      commitAccepted = true;
      setState("activated", detail.updateId ?? "");
    } else if (detail.state === "reload-cancelled") {
      commitAccepted = true;
      setState("reload-cancelled", detail.updateId ?? "", detail.reason ?? "");
    } else if (detail.state === "cancelled") {
      commitAccepted = false;
      setState(
        waitingWorker ? "available" : "cancelled",
        detail.updateId ?? "",
      );
    }
  }

  async function requestReloadUpdate() {
    if (requestBusy) return;
    if (noticeState === "activated" || noticeState === "reload-cancelled") {
      const generation = ++requestGeneration;
      requestBusy = true;
      const result = await retryPreparedReload(registration?.active ?? null);
      if (generation !== requestGeneration) return;
      requestBusy = false;
      if (result === "dirty" || result === "busy") {
        setState("reload-cancelled", noticeUpdateId, result);
      } else if (result === "stale") {
        waitingWorker = registration?.waiting ?? waitingWorker;
        setState(
          waitingWorker ? "available" : "blocked",
          noticeUpdateId,
          waitingWorker ? "" : "unavailable",
        );
      } else if (result !== "ready") {
        setState("blocked", noticeUpdateId, result);
      }
      return;
    }

    const worker = waitingWorker;
    if (!worker) return;
    const generation = ++requestGeneration;
    requestBusy = true;
    commitAccepted = false;
    reason = "";
    setState("preparing");
    const version = await askWorker(worker, { type: "GET_VERSION" }, 2_000);
    if (generation !== requestGeneration) return;
    if (
      !version ||
      typeof version.workerVersion !== "string" ||
      !/^[a-f0-9]{64}$/.test(version.workerVersion) ||
      typeof version.appVersion !== "string" ||
      version.appVersion.length === 0 ||
      version.appVersion.length > 128
    ) {
      requestBusy = false;
      setState("blocked", "", "unavailable");
      return;
    }

    const updateId = crypto.randomUUID().toLowerCase();
    noticeUpdateId = updateId;
    const tuple = {
      updateId,
      appVersion: version.appVersion,
      workerVersion: version.workerVersion,
    };
    const prepared = await askWorker(
      worker,
      {
        type: "PREPARE_UPDATE",
        ...tuple,
        sourcePageVersion: appVersion,
      },
      20_000,
    );
    if (generation !== requestGeneration) return;
    if (prepared?.type !== "UPDATE_PREPARED") {
      requestBusy = false;
      cancelLocalUpdate(updateId);
      setState(
        "blocked",
        updateId,
        typeof prepared?.reason === "string" ? prepared.reason : "unresponsive",
      );
      return;
    }

    setState("committing", updateId);
    const committed = await askWorker(
      worker,
      { type: "COMMIT_UPDATE", ...tuple },
      20_000,
    );
    if (generation !== requestGeneration) return;
    requestBusy = false;
    if (committed?.type !== "UPDATE_COMMIT_ACCEPTED") {
      cancelLocalUpdate(updateId);
      void askWorker(worker, { type: "CANCEL_UPDATE", updateId }, 2_000);
      setState(
        "blocked",
        updateId,
        typeof committed?.reason === "string" ? committed.reason : "expired",
      );
      return;
    }
    commitAccepted = true;
    setState("committing", updateId);
  }

  async function cancelUpdate() {
    const updateId = noticeUpdateId;
    if (!updateId || commitAccepted) return;
    requestGeneration += 1;
    requestBusy = false;
    cancelLocalUpdate(updateId);
    const worker = waitingWorker;
    if (worker) {
      await askWorker(worker, { type: "CANCEL_UPDATE", updateId }, 7_000);
    }
    setState(waitingWorker ? "available" : "cancelled", updateId);
  }

  function showWaitingUpdate() {
    waitingWorker = registration?.waiting ?? null;
    if (waitingWorker && navigator.serviceWorker.controller) {
      setState("available");
    }
  }

  function requestSafePrune(worker: ServiceWorker | null) {
    if (worker) {
      void askWorker(worker, { type: "PRUNE_APP_CACHES" }, 2_000);
    }
  }

  onMount(() => {
    if (!("serviceWorker" in navigator)) return;
    let disposed = false;
    let installingListener: ServiceWorker | null = null;
    let installingStateChange: (() => void) | null = null;

    const onWorkerMessage = (event: MessageEvent) =>
      handlePwaWorkerMessage(
        event as MessageEvent<{
          type?: unknown;
          updateId?: unknown;
          appVersion?: unknown;
          workerVersion?: unknown;
          reason?: unknown;
        }>,
      );
    const onControllerChange = () => {
      const controller = navigator.serviceWorker.controller;
      void confirmReloadedApp(controller);
      requestSafePrune(controller);
    };
    const onUpdateFound = () => {
      if (installingListener && installingStateChange) {
        installingListener.removeEventListener(
          "statechange",
          installingStateChange,
        );
      }
      const installing = registration?.installing;
      if (!installing) return;
      installingListener = installing;
      const onStateChange = () => {
        if (installing.state === "installed") showWaitingUpdate();
      };
      installingStateChange = onStateChange;
      installing.addEventListener("statechange", onStateChange);
    };

    const appRoot = resolve("/");
    const workerUrl = new URL(`${appRoot}sw.js`, window.location.href);
    if (workerUrl.origin !== window.location.origin) return;
    window.addEventListener("stricknani:pwa-update", onCoordinatorEvent);
    updateCoordinatorChannel();
    navigator.serviceWorker.addEventListener("message", onWorkerMessage);
    navigator.serviceWorker.addEventListener(
      "controllerchange",
      onControllerChange,
    );
    void navigator.serviceWorker
      .register(workerUrl.href, { scope: appRoot })
      .then((result) => {
        if (disposed) return;
        registration = result;
        registration.addEventListener("updatefound", onUpdateFound);
        showWaitingUpdate();
        const controller = navigator.serviceWorker.controller;
        requestSafePrune(controller);
        return navigator.serviceWorker.ready.then((ready) => {
          if (disposed) return;
          const active = ready.active;
          void confirmReloadedApp(navigator.serviceWorker.controller ?? active);
          requestSafePrune(navigator.serviceWorker.controller ?? active);
        });
      })
      .catch(() => {
        // Offline mode and unsupported contexts should not block the app.
      });

    return () => {
      disposed = true;
      window.removeEventListener("stricknani:pwa-update", onCoordinatorEvent);
      navigator.serviceWorker.removeEventListener("message", onWorkerMessage);
      navigator.serviceWorker.removeEventListener(
        "controllerchange",
        onControllerChange,
      );
      registration?.removeEventListener("updatefound", onUpdateFound);
      if (installingListener && installingStateChange) {
        installingListener.removeEventListener(
          "statechange",
          installingStateChange,
        );
      }
    };
  });
</script>

{#if noticeState && !dismissed}
  <aside class="update-notice" aria-labelledby="update-title" role="status">
    <div>
      <h2 id="update-title">{t("Update available", locale)}</h2>
      {#if noticeState === "blocked"}
        <p>{reasonCopy(reason)}</p>
      {:else if noticeState === "preparing"}
        <p>{t("Checking open tabs before updating…", locale)}</p>
      {:else if noticeState === "committing"}
        <p>{t("Installing the update…", locale)}</p>
      {:else if noticeState === "activated" || noticeState === "reload-cancelled"}
        {#if noticeState === "reload-cancelled" && reason}
          <p>{reasonCopy(reason)}</p>
        {:else}
          <p>
            {noticeState === "reload-cancelled"
              ? t(
                  "The browser kept this tab open. Reload when it is safe.",
                  locale,
                )
              : t("Reloading this tab with the update…", locale)}
          </p>
        {/if}
      {:else if noticeState === "cancelled"}
        <p>{t("The update was cancelled. You can try again later.", locale)}</p>
      {:else}
        <p>{t("A new version is ready to install.", locale)}</p>
      {/if}
    </div>
    <div class="notice-actions">
      {#if noticeState === "blocked" || noticeState === "available" || noticeState === "reload-cancelled"}
        <SharedButton
          variant="primary"
          type="button"
          onclick={requestReloadUpdate}
          disabled={requestBusy}
          ><ActionLabel icon="refresh"
            >{requestBusy ? t("Working…", locale) : actionLabel}</ActionLabel
          ></SharedButton
        >
      {/if}
      {#if (noticeState === "preparing" || noticeState === "committing") && !commitAccepted}
        <SharedButton
          variant="secondary"
          type="button"
          onclick={cancelUpdate}
          disabled={commitAccepted}
        >
          <ActionLabel icon="cancel">{t("Cancel update", locale)}</ActionLabel>
        </SharedButton>
      {:else if noticeState === "available" || noticeState === "blocked" || noticeState === "cancelled"}
        <SharedButton
          variant="secondary"
          type="button"
          onclick={() => (dismissed = true)}
        >
          <ActionLabel icon="close">{t("Later", locale)}</ActionLabel>
        </SharedButton>
      {/if}
    </div>
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
    background: var(--surface);
    box-shadow: 0 0.4rem 1rem rgb(15 23 42 / 12%);
  }

  h2 {
    margin: 0;
    font-size: 1rem;
  }

  p {
    margin: 0.2rem 0 0;
    color: var(--ink-muted);
  }

  .notice-actions {
    display: flex;
    flex: 0 0 auto;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  @media (max-width: 38rem) {
    .update-notice {
      align-items: stretch;
      flex-direction: column;
      margin-inline: 0.75rem;
    }

    .notice-actions :global(.ui-button) {
      flex: 1 1 auto;
    }
  }
</style>
