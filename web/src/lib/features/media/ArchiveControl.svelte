<script lang="ts">
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import SourceLink from "#lib/components/SourceLink.svelte";
  import type { NavIconName } from "#lib/components/NavIcon.svelte";
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
    readStateEnabled,
    onArchived,
    archiveHref = null,
    archiveLabel = "",
  }: {
    entity: ArchiveEntity;
    id: number;
    sourceLink: string;
    detailHasArchive: boolean;
    ownerId: number | null;
    locale: "en" | "de";
    enabled: boolean;
    readStateEnabled: boolean;
    onArchived: (marker: string) => void | Promise<void>;
    /** The archived copy: shown as the row's link, with the status below and the action beside. */
    archiveHref?: string | null;
    archiveLabel?: string;
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
    const canReadState = readStateEnabled;
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
      (!canArchive && !canReadState) ||
      !Number.isSafeInteger(currentId) ||
      currentId <= 0 ||
      currentSource.length === 0 ||
      currentOwner === null
    )
      return;
    if (canReadState) void refreshStatus(false);
    else loaded = true;
    return () => {
      if (generation === requestGeneration) requestGeneration += 1;
      controller?.abort();
      controller = null;
    };
  });

  async function enqueue() {
    const currentSession = session.data;
    if (
      !canEnqueue(currentSession?.user?.id ?? null) ||
      (!loaded && readStateEnabled) ||
      busy ||
      (status !== null &&
        status.state !== "failed" &&
        status.state !== "superseded")
    )
      return;
    await refreshStatus(true);
  }

  async function checkAgain() {
    if (!canReadArchiveState(session.data?.user?.id ?? null) || busy) return;
    await refreshStatus(false);
  }

  async function retryAfterError() {
    if (
      canEnqueue(session.data?.user?.id ?? null) &&
      loaded &&
      (status === null ||
        status.state === "failed" ||
        status.state === "superseded")
    ) {
      await enqueue();
    } else {
      await checkAgain();
    }
  }

  async function refreshStatus(enqueueRequest: boolean) {
    const currentSession = session.data;
    if (
      !currentSession?.user ||
      (enqueueRequest
        ? !canEnqueue(currentSession.user.id)
        : !canReadArchiveState(currentSession.user.id))
    )
      return;
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
      const requestKind = enqueueRequest ? "enqueue" : "read";
      if (!isCurrent(generation, nextController, requestKind)) return;
      const previousState = status?.state ?? null;
      status = nextStatus;
      loaded = true;
      await refreshIfArchived(
        nextStatus,
        previousState,
        generation,
        nextController,
        requestKind,
      );
      if (!isCurrent(generation, nextController, requestKind)) return;
      if (
        isActive(nextStatus) &&
        canReadArchiveState(session.data?.user?.id ?? null)
      )
        await pollUntilSettled(generation, nextController);
    } catch (error) {
      if (
        timedOut &&
        generation === requestGeneration &&
        (enqueueRequest
          ? canEnqueue(session.data?.user?.id ?? null)
          : canReadArchiveState(session.data?.user?.id ?? null))
      ) {
        errorMessage = t(
          "Archive status could not be reached. Check your connection and try again.",
          locale,
        );
      } else if (
        isCurrent(
          generation,
          nextController,
          enqueueRequest ? "enqueue" : "read",
        )
      ) {
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
        if (!isCurrent(generation, requestController, "read")) return;
        const previousState = status?.state ?? null;
        status = nextStatus;
        await refreshIfArchived(
          nextStatus,
          previousState,
          generation,
          requestController,
          "read",
        );
      },
      onDeadline: () => {
        if (isCurrent(generation, requestController, "read")) {
          pollingExhausted = true;
        }
      },
      wait: delayWhileVisible,
    });
  }

  async function getVisibleStatus(signal: AbortSignal): Promise<ArchiveStatus> {
    while (!signal.aborted) {
      if (!canReadArchiveState(session.data?.user?.id ?? null)) {
        throw new DOMException("Request aborted", "AbortError");
      }
      await waitUntilVisible(signal);
      if (!canReadArchiveState(session.data?.user?.id ?? null)) {
        throw new DOMException("Request aborted", "AbortError");
      }
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
    requestKind: "enqueue" | "read" | "either" = "either",
  ) {
    if (
      nextStatus.state !== "archived" ||
      (previousState === "archived" && detailHasArchive) ||
      (previousState === null && detailHasArchive) ||
      !isCurrent(generation, requestController, requestKind)
    )
      return;
    const marker = `${entity}:${id}:${nextStatus.updated_at ?? "unknown"}`;
    if (marker === refreshedArchiveMarker) return;
    refreshedArchiveMarker = marker;
    try {
      await onArchived(marker);
    } catch {
      if (isCurrent(generation, requestController, requestKind)) {
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
    requestKind: "enqueue" | "read" | "either" = "either",
  ): boolean {
    return (
      generation === requestGeneration &&
      !requestController.signal.aborted &&
      (requestKind === "read"
        ? canReadArchiveState(session.data?.user?.id ?? null)
        : requestKind === "enqueue"
          ? canEnqueue(session.data?.user?.id ?? null)
          : canEnqueue(session.data?.user?.id ?? null) ||
            canReadArchiveState(session.data?.user?.id ?? null))
    );
  }

  function canEnqueue(currentUserId: number | null): boolean {
    return (
      enabled &&
      ownerId !== null &&
      currentUserId === ownerId &&
      Number.isSafeInteger(id) &&
      id > 0 &&
      sourceLink.length > 0
    );
  }

  function canReadArchiveState(currentUserId: number | null): boolean {
    return (
      readStateEnabled &&
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

<!-- With an archived copy on the line the action is a small icon button; else a labelled one. -->
{#snippet action(label: string, icon: NavIconName, run: () => void)}
  {#if archiveHref}
    <Button
      variant="ghost"
      size="sm"
      {icon}
      type="button"
      aria-label={label}
      title={label}
      onclick={run}
    />
  {:else}
    <Button variant="secondary" size="sm" {icon} type="button" onclick={run}>
      {label}
    </Button>
  {/if}
{/snippet}

{#if (enabled || readStateEnabled) && ownerId !== null && sourceLink.length > 0}
  <section
    class="archive-control"
    class:archive-control--linked={archiveHref !== null}
    aria-busy={busy}
  >
    <!-- One line: the archived copy (or the status) and what can be done about it. -->
    <div class="archive-row">
      {#if archiveHref}
        <SourceLink href={archiveHref} icon="history" label={archiveLabel} />
      {:else}
        <p class="archive-status" aria-live="polite">{statusText(status)}</p>
      {/if}
      {#if errorMessage && !busy}
        {@render action(
          loaded
            ? t("Try again", locale)
            : t("Check archive status again", locale),
          "refresh",
          () => void retryAfterError(),
        )}
      {:else if busy || (!loaded && readStateEnabled)}
        <!-- progress is shown below -->
      {:else if readStateEnabled && canManuallyCheckArchive(status, pollingExhausted)}
        {@render action(
          t("Check archive status again", locale),
          "refresh",
          () => void checkAgain(),
        )}
      {:else if !archiveActive && enabled && status?.state !== "archived"}
        {@render action(
          status?.state === "failed" || status?.state === "superseded"
            ? t("Request a new archive", locale)
            : t("Archive this source", locale),
          archiveHref ? "refresh" : "link",
          () => void enqueue(),
        )}
      {/if}
    </div>
    {#if archiveHref && status !== null && status.state !== "archived"}
      <p class="archive-status archive-caption" aria-live="polite">
        {statusText(status)}
      </p>
    {/if}
    {#if busy}
      <p class="archive-progress">{t("Checking archive status…", locale)}</p>
    {:else if !loaded && readStateEnabled}
      <p class="archive-progress">{t("Loading archive status…", locale)}</p>
    {:else if archiveActive && !canManuallyCheckArchive(status, pollingExhausted)}
      <p class="archive-progress">
        {t("Status checks pause in the background.", locale)}
      </p>
    {/if}
    {#if errorMessage}<p class="archive-error" role="alert">
        {errorMessage}
      </p>{/if}
  </section>
{:else if archiveHref}
  <SourceLink href={archiveHref} icon="history" label={archiveLabel} />
{/if}

<style>
  .archive-control {
    display: grid;
    gap: 0.2rem;
    margin-top: 0.25rem;
  }

  .archive-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.4rem 0.75rem;
  }

  .archive-control--linked .archive-row {
    flex-wrap: nowrap;
  }

  /* Under the link text, past its icon. */
  .archive-caption {
    margin-top: -0.4rem;
    padding-inline-start: 2.35rem;
    font-size: 0.78rem;
  }

  .archive-status,
  .archive-progress,
  .archive-error {
    margin: 0;
    font-size: 0.84rem;
  }

  .archive-status {
    color: var(--ink-muted);
  }

  .archive-progress {
    color: var(--ink-muted);
  }

  .archive-error {
    color: var(--danger-strong);
  }

  .archive-row :global(.ui-button) {
    flex: none;
    max-width: 100%;
  }

  @media print {
    .archive-control {
      display: none !important;
    }
  }
</style>
