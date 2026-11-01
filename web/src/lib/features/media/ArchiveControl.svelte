<script lang="ts">
  import { ApiError } from "#lib/api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    canManuallyCheckArchive,
    getArchiveStatus,
    pollArchiveStatus,
    requestArchive,
    type ArchiveEntity,
    type ArchiveState,
    type ArchiveStatus,
  } from "./media-tools-api";

  let {
    entity,
    id,
    sourceLink,
    detailHasArchive,
    ownerId,
    locale,
    enabled,
    onArchived,
  }: {
    entity: ArchiveEntity;
    id: number;
    sourceLink: string;
    detailHasArchive: boolean;
    ownerId: number | null;
    locale: "en" | "de";
    enabled: boolean;
    onArchived: (marker: string) => void | Promise<void>;
  } = $props();

  let status = $state<ArchiveStatus | null>(null);
  let loaded = $state(false);
  let busy = $state(false);
  let pollingExhausted = $state(false);
  let errorMessage = $state("");
  let requestGeneration = 0;
  let controller: AbortController | null = null;
  let refreshedArchiveMarker: string | null = null;

  const initialRequestTimeoutMs = 15_000;

  const archiveActive = $derived(
    status?.state === "pending" || status?.state === "running",
  );

  $effect(() => {
    const currentEntity = entity;
    const currentId = id;
    const currentSource = sourceLink;
    const currentOwner = ownerId;
    const canArchive = enabled;
    const generation = ++requestGeneration;
    controller?.abort();
    controller = null;
    status = null;
    loaded = false;
    busy = false;
    pollingExhausted = false;
    errorMessage = "";
    refreshedArchiveMarker = null;
    if (
      !canArchive ||
      !Number.isSafeInteger(currentId) ||
      currentId <= 0 ||
      currentSource.length === 0 ||
      currentOwner === null
    )
      return;
    void refreshStatus(false);
    return () => {
      if (generation === requestGeneration) requestGeneration += 1;
      controller?.abort();
      controller = null;
    };
  });

  async function enqueue() {
    const currentSession = session.data;
    if (
      !canRun(currentSession?.user?.id ?? null) ||
      !loaded ||
      busy ||
      (status?.state !== null &&
        status?.state !== "failed" &&
        status?.state !== "superseded")
    )
      return;
    await refreshStatus(true);
  }

  async function checkAgain() {
    if (!canRun(session.data?.user?.id ?? null) || busy) return;
    await refreshStatus(false);
  }

  async function retryAfterError() {
    if (
      loaded &&
      (status?.state === null ||
        status?.state === "failed" ||
        status?.state === "superseded")
    ) {
      await enqueue();
    } else {
      await checkAgain();
    }
  }

  async function refreshStatus(enqueueRequest: boolean) {
    const currentSession = session.data;
    if (!currentSession?.user || !canRun(currentSession.user.id)) return;
    const generation = ++requestGeneration;
    controller?.abort();
    const nextController = new AbortController();
    controller = nextController;
    let timedOut = false;
    const requestTimeout = window.setTimeout(() => {
      timedOut = true;
      nextController.abort();
    }, initialRequestTimeoutMs);
    busy = true;
    pollingExhausted = false;
    errorMessage = "";
    try {
      const nextStatus = enqueueRequest
        ? await requestArchive(
            entity,
            id,
            currentSession,
            nextController.signal,
          )
        : await getVisibleStatus(nextController.signal);
      window.clearTimeout(requestTimeout);
      if (!isCurrent(generation, nextController)) return;
      const previousState = status?.state ?? null;
      status = nextStatus;
      loaded = true;
      await refreshIfArchived(
        nextStatus,
        previousState,
        generation,
        nextController,
      );
      if (!isCurrent(generation, nextController)) return;
      if (isActive(nextStatus))
        await pollUntilSettled(generation, nextController);
    } catch (error) {
      if (
        timedOut &&
        generation === requestGeneration &&
        canRun(session.data?.user?.id ?? null)
      ) {
        errorMessage = t(
          "Archive status could not be reached. Check your connection and try again.",
          locale,
        );
      } else if (isCurrent(generation, nextController)) {
        errorMessage = errorText(error);
      }
    } finally {
      window.clearTimeout(requestTimeout);
      if (generation === requestGeneration) {
        busy = false;
        controller = null;
      }
    }
  }

  async function pollUntilSettled(
    generation: number,
    requestController: AbortController,
  ) {
    await pollArchiveStatus({
      controller: requestController,
      readStatus: getVisibleStatus,
      onStatus: async (nextStatus) => {
        if (!isCurrent(generation, requestController)) return;
        const previousState = status?.state ?? null;
        status = nextStatus;
        await refreshIfArchived(
          nextStatus,
          previousState,
          generation,
          requestController,
        );
      },
      onDeadline: () => {
        if (isCurrent(generation, requestController)) {
          pollingExhausted = true;
        }
      },
      wait: delayWhileVisible,
    });
  }

  async function getVisibleStatus(signal: AbortSignal): Promise<ArchiveStatus> {
    while (!signal.aborted) {
      await waitUntilVisible(signal);
      const visibleRequest = new AbortController();
      const abortWithParent = () => visibleRequest.abort();
      const abortWhenHidden = () => {
        if (document.hidden) visibleRequest.abort();
      };
      signal.addEventListener("abort", abortWithParent, { once: true });
      document.addEventListener("visibilitychange", abortWhenHidden);
      if (signal.aborted) visibleRequest.abort();
      try {
        return await getArchiveStatus(entity, id, visibleRequest.signal);
      } catch (error) {
        if (signal.aborted) throw error;
        if (!document.hidden || !visibleRequest.signal.aborted) throw error;
      } finally {
        signal.removeEventListener("abort", abortWithParent);
        document.removeEventListener("visibilitychange", abortWhenHidden);
      }
    }
    throw new DOMException("Request aborted", "AbortError");
  }

  async function waitUntilVisible(signal: AbortSignal): Promise<void> {
    if (!document.hidden) return;
    await new Promise<void>((resolve, reject) => {
      const finish = () => {
        document.removeEventListener("visibilitychange", checkVisibility);
        signal.removeEventListener("abort", abort);
        resolve();
      };
      const checkVisibility = () => {
        if (!document.hidden) finish();
      };
      const abort = () => {
        document.removeEventListener("visibilitychange", checkVisibility);
        signal.removeEventListener("abort", abort);
        reject(new DOMException("Request aborted", "AbortError"));
      };
      document.addEventListener("visibilitychange", checkVisibility);
      signal.addEventListener("abort", abort, { once: true });
      if (signal.aborted) abort();
      else if (!document.hidden) finish();
    });
  }

  function delayWhileVisible(
    duration: number,
    signal: AbortSignal,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      let remaining = duration;
      let startedAt = 0;
      let timer: number | null = null;
      const cleanup = () => {
        if (timer !== null) window.clearTimeout(timer);
        document.removeEventListener("visibilitychange", visibilityChanged);
        signal.removeEventListener("abort", aborted);
      };
      const complete = () => {
        cleanup();
        resolve();
      };
      const aborted = () => {
        cleanup();
        reject(new DOMException("Request aborted", "AbortError"));
      };
      const schedule = () => {
        if (signal.aborted) return aborted();
        if (document.hidden) return;
        if (remaining <= 0) return complete();
        startedAt = performance.now();
        timer = window.setTimeout(complete, remaining);
      };
      const visibilityChanged = () => {
        if (document.hidden && timer !== null) {
          window.clearTimeout(timer);
          timer = null;
          remaining = Math.max(0, remaining - (performance.now() - startedAt));
        } else if (!document.hidden && timer === null) {
          schedule();
        }
      };
      document.addEventListener("visibilitychange", visibilityChanged);
      signal.addEventListener("abort", aborted, { once: true });
      schedule();
    });
  }

  async function refreshIfArchived(
    nextStatus: ArchiveStatus,
    previousState: ArchiveState,
    generation: number,
    requestController: AbortController,
  ) {
    if (
      nextStatus.state !== "archived" ||
      (previousState === "archived" && detailHasArchive) ||
      (previousState === null && detailHasArchive) ||
      !isCurrent(generation, requestController)
    )
      return;
    const marker = `${entity}:${id}:${nextStatus.updated_at ?? "unknown"}`;
    if (marker === refreshedArchiveMarker) return;
    refreshedArchiveMarker = marker;
    try {
      await onArchived(marker);
    } catch {
      if (isCurrent(generation, requestController)) {
        refreshedArchiveMarker = null;
        errorMessage = t(
          "The archive completed, but the updated details could not be loaded.",
          locale,
        );
      }
    }
  }

  function isCurrent(
    generation: number,
    requestController: AbortController,
  ): boolean {
    return (
      generation === requestGeneration &&
      !requestController.signal.aborted &&
      canRun(session.data?.user?.id ?? null)
    );
  }

  function canRun(currentUserId: number | null): boolean {
    return (
      enabled &&
      ownerId !== null &&
      currentUserId === ownerId &&
      Number.isSafeInteger(id) &&
      id > 0 &&
      sourceLink.length > 0
    );
  }

  function isActive(value: ArchiveStatus): boolean {
    return value.state === "pending" || value.state === "running";
  }

  function errorText(error: unknown): string {
    if (error instanceof ApiError) {
      if (error.code === "network_error")
        return t(
          "Archive status could not be reached. Check your connection and try again.",
          locale,
        );
      if (error.code === "rate_limited")
        return t("Too many archive requests. Try again later.", locale);
      return error.message;
    }
    if (error instanceof DOMException && error.name === "AbortError") return "";
    return t(
      "Archive status could not be reached. Check your connection and try again.",
      locale,
    );
  }

  function statusText(value: ArchiveStatus | null): string {
    switch (value?.state) {
      case "pending":
        return t("The archive request is waiting to start.", locale);
      case "running":
        return t("The archive service is working on this source.", locale);
      case "archived":
        return t("An archived copy is ready.", locale);
      case "failed":
        return t("The archive could not be completed.", locale);
      case "superseded":
        return t("The source link changed; request a new archive.", locale);
      default:
        return t("Save a copy of the source in the web archive.", locale);
    }
  }
</script>

{#if enabled && ownerId !== null && sourceLink.length > 0}
  <section class="archive-control" aria-busy={busy}>
    <p class="archive-status" aria-live="polite">{statusText(status)}</p>
    {#if errorMessage}<p class="archive-error" role="alert">
        {errorMessage}
      </p>{/if}
    {#if errorMessage && !busy}
      <button type="button" onclick={() => void retryAfterError()}>
        {loaded
          ? t("Try again", locale)
          : t("Check archive status again", locale)}
      </button>
    {/if}
    {#if busy}
      <p class="archive-progress">{t("Checking archive status…", locale)}</p>
    {:else if !loaded}
      <p class="archive-progress">{t("Loading archive status…", locale)}</p>
    {:else if canManuallyCheckArchive(status, pollingExhausted)}
      <button type="button" onclick={() => void checkAgain()}>
        {t("Check archive status again", locale)}
      </button>
    {:else if archiveActive}
      <p class="archive-progress">
        {t("Status checks pause in the background.", locale)}
      </p>
    {:else if status?.state !== "archived"}
      <button type="button" onclick={() => void enqueue()}>
        {status?.state === "failed" || status?.state === "superseded"
          ? t("Request a new archive", locale)
          : t("Archive this source", locale)}
      </button>
    {/if}
  </section>
{/if}

<style>
  .archive-control {
    display: grid;
    gap: 0.45rem;
    margin-top: 0.75rem;
    border-top: 1px solid var(--line);
    padding-top: 0.7rem;
  }

  .archive-status,
  .archive-progress,
  .archive-error {
    margin: 0;
    font-size: 0.84rem;
  }

  .archive-status {
    color: var(--ink);
    font-weight: 600;
  }

  .archive-progress {
    color: var(--ink-muted);
  }

  .archive-error {
    color: var(--danger-strong);
  }

  button {
    width: fit-content;
    max-width: 100%;
    min-height: 44px;
    border: 1px solid var(--line);
    border-radius: 0.55rem;
    padding: 0.55rem 0.7rem;
    color: var(--accent-strong);
    background: var(--surface-raised);
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }

  @media print {
    .archive-control {
      display: none !important;
    }
  }
</style>
