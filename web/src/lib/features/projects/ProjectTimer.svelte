<script lang="ts">
  import { onDestroy } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { session } from "#lib/session.svelte.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import {
    elapsedSeconds,
    currentRunSeconds,
    getProjectTimer,
    isUnsupportedTimerError,
    secondsFromParts,
    timerParts,
    updateProjectTimer,
    type ProjectTimer as TimerValue,
  } from "./project-timer";

  interface Props {
    projectId: number;
    locale: "en" | "de";
    canEdit: boolean;
    enabled: boolean;
  }

  let { projectId, locale, canEdit, enabled }: Props = $props();
  let timer = $state<TimerValue | null>(null);
  let loading = $state(true);
  let busy = $state(false);
  let error = $state("");
  let unsupported = $state(false);
  let now = $state(Date.now());
  let sampleTime = $state(0);
  let hours = $state("0");
  let minutes = $state("0");
  let seconds = $state("0");
  let editMode = $state(false);
  let generation = 0;
  let controller: AbortController | null = null;
  let refreshController: AbortController | null = null;
  let refreshGeneration = 0;
  let reminderMinutes = $state(60);
  let reminderAfter = $state<number | null>(null);
  let reminderRun = $state<string | null>(null);
  let reminderKey = $state("");
  let tick: ReturnType<typeof setInterval> | undefined;
  let refreshTick: ReturnType<typeof setInterval> | undefined;

  const total = $derived(timer ? elapsedSeconds(timer, now, sampleTime) : 0);
  const runSeconds = $derived(
    timer ? currentRunSeconds(timer, now, sampleTime) : 0,
  );
  const reminderDue = $derived(
    canEdit &&
      timer?.started_at !== null &&
      timer?.started_at !== undefined &&
      reminderMinutes > 0 &&
      runSeconds >= (reminderAfter ?? reminderMinutes * 60),
  );
  const parts = $derived(timerParts(total));
  const editing = $derived(timer !== null && editMode);
  const proposedTotal = $derived(secondsFromParts(hours, minutes, seconds));

  $effect(() => {
    const id = projectId;
    const timersEnabled = enabled;
    const userId = session.data?.user?.id;
    const currentSession = session.data;
    const requestGeneration = ++generation;
    controller?.abort();
    refreshController?.abort();
    const requestController = new AbortController();
    controller = requestController;
    timer = null;
    busy = false;
    unsupported = false;
    reminderMinutes = 60;
    reminderAfter = null;
    reminderRun = null;
    const currentReminderKey = currentSession?.user
      ? `stricknani:timer-reminder:${currentSession.user.id}:${id}`
      : "";
    reminderKey = currentReminderKey;
    if (currentReminderKey) {
      try {
        const saved = localStorage.getItem(currentReminderKey);
        if (saved) {
          const value: unknown = JSON.parse(saved);
          if (
            typeof value === "object" &&
            value !== null &&
            !Array.isArray(value)
          ) {
            const settings = value as Record<string, unknown>;
            if ([0, 30, 60, 120].includes(settings.minutes as number))
              reminderMinutes = settings.minutes as number;
            if (
              Number.isSafeInteger(settings.after) &&
              (settings.after as number) >= 0
            )
              reminderAfter = settings.after as number;
            if (
              typeof settings.startedAt === "string" ||
              settings.startedAt === null
            )
              reminderRun = settings.startedAt;
          }
        }
      } catch {
        // Browser storage can be unavailable; reminder settings then stay local to this view.
      }
    }
    error = "";
    loading = true;
    if (!timersEnabled) {
      loading = false;
      return () => requestController.abort();
    }
    if (!currentSession?.user || userId === undefined) {
      loading = false;
      error = t("Sign in to use the project timer.", locale);
      return () => requestController.abort();
    }
    void getProjectTimer(id, requestController.signal)
      .then((loaded) => {
        if (
          requestGeneration !== generation ||
          requestController.signal.aborted
        )
          return;
        const draft = secondsFromParts(hours, minutes, seconds);
        const hasDraft = timer !== null && draft !== timer.total_seconds;
        timer = loaded;
        editMode = false;
        sampleTime = performance.now();
        now = sampleTime;
        syncReminderRun(loaded);
        if (!hasDraft) {
          [hours, minutes, seconds] = timerParts(loaded.total_seconds);
        }
      })
      .catch((cause: unknown) => {
        if (
          requestGeneration !== generation ||
          requestController.signal.aborted
        )
          return;
        if (isUnsupportedTimerError(cause)) {
          unsupported = true;
          error = t("Timers are not available on this server yet.", locale);
        } else {
          error =
            cause instanceof ApiError
              ? cause.message
              : t("The project timer could not be loaded.", locale);
        }
      })
      .finally(() => {
        if (requestGeneration === generation) loading = false;
      });
    return () => requestController.abort();
  });

  async function refreshTimer() {
    const current = session.data;
    if (!enabled || unsupported || !current?.user || busy || loading) return;
    const requestGeneration = generation;
    const requestId = ++refreshGeneration;
    refreshController?.abort();
    const requestController = new AbortController();
    refreshController = requestController;
    try {
      const updated = await getProjectTimer(
        projectId,
        requestController.signal,
      );
      if (
        requestGeneration !== generation ||
        requestId !== refreshGeneration ||
        requestController.signal.aborted
      )
        return;
      const draft = secondsFromParts(hours, minutes, seconds);
      const hasDraft = timer !== null && draft !== timer.total_seconds;
      timer = updated;
      sampleTime = performance.now();
      now = sampleTime;
      syncReminderRun(updated);
      if (!hasDraft) {
        [hours, minutes, seconds] = timerParts(updated.total_seconds);
      }
    } catch (cause) {
      // Keep the last known timer visible; the next focus or poll will retry.
      if (isUnsupportedTimerError(cause) && requestGeneration === generation) {
        unsupported = true;
        timer = null;
        error = t("Timers are not available on this server yet.", locale);
      }
    }
  }

  function saveReminderSettings() {
    if (!reminderKey) return;
    try {
      localStorage.setItem(
        reminderKey,
        JSON.stringify({
          minutes: reminderMinutes,
          after: reminderAfter,
          startedAt: reminderRun,
        }),
      );
    } catch {
      // The reminder still works until this view is closed.
    }
  }

  function setReminderMinutes(value: string) {
    const minutesValue = Number(value);
    if (![0, 30, 60, 120].includes(minutesValue)) return;
    reminderMinutes = minutesValue;
    reminderAfter = minutesValue ? runSeconds + minutesValue * 60 : null;
    saveReminderSettings();
  }

  function snoozeReminder() {
    reminderAfter = runSeconds + reminderMinutes * 60;
    saveReminderSettings();
  }

  function refreshWhenVisible() {
    if (document.visibilityState === "visible") void refreshTimer();
  }

  function syncReminderRun(updated: TimerValue, force = false) {
    if (!updated.started_at) {
      if (reminderRun !== null || reminderAfter !== null) {
        reminderRun = null;
        reminderAfter = null;
        saveReminderSettings();
      }
      return;
    }
    if (!force && reminderRun === updated.started_at) return;
    reminderRun = updated.started_at;
    reminderAfter = reminderMinutes ? reminderMinutes * 60 : null;
    saveReminderSettings();
  }

  function beginEdit() {
    [hours, minutes, seconds] = timerParts(total);
    editMode = true;
  }

  function cancelEdit() {
    [hours, minutes, seconds] = timerParts(total);
    editMode = false;
  }

  $effect(() => {
    if (!enabled || unsupported || !timer || loading) return;
    refreshTick = setInterval(() => void refreshTimer(), 15_000);
    window.addEventListener("focus", refreshWhenVisible);
    document.addEventListener("visibilitychange", refreshWhenVisible);
    return () => {
      if (refreshTick) clearInterval(refreshTick);
      refreshTick = undefined;
      window.removeEventListener("focus", refreshWhenVisible);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
      refreshController?.abort();
    };
  });

  $effect(() => {
    if (enabled && !unsupported && timer?.started_at) {
      tick = setInterval(() => (now = performance.now()), 1000);
    }
    return () => {
      if (tick) clearInterval(tick);
      tick = undefined;
    };
  });

  onDestroy(() => {
    generation += 1;
    controller?.abort();
    refreshController?.abort();
    if (tick) clearInterval(tick);
    if (refreshTick) clearInterval(refreshTick);
  });

  async function changeTimer(action: "start" | "stop" | "set_total") {
    const currentSession = session.data;
    const requestGeneration = generation;
    if (
      !enabled ||
      unsupported ||
      !canEdit ||
      !currentSession?.user ||
      busy ||
      !timer
    )
      return;
    const totalSeconds = action === "set_total" ? proposedTotal : undefined;
    if (action === "set_total" && totalSeconds === null) return;
    busy = true;
    refreshController?.abort();
    error = "";
    const requestController = new AbortController();
    controller = requestController;
    try {
      const updated = await updateProjectTimer(
        projectId,
        currentSession,
        action,
        totalSeconds ?? undefined,
        requestController.signal,
      );
      if (requestGeneration !== generation || requestController.signal.aborted)
        return;
      timer = updated;
      sampleTime = performance.now();
      now = sampleTime;
      syncReminderRun(updated, action === "set_total");
      [hours, minutes, seconds] = timerParts(updated.total_seconds);
      if (action === "set_total") editMode = false;
    } catch (cause) {
      if (requestGeneration !== generation || requestController.signal.aborted)
        return;
      if (isUnsupportedTimerError(cause)) {
        unsupported = true;
        timer = null;
        error = t("Timers are not available on this server yet.", locale);
      } else {
        error =
          cause instanceof ApiError
            ? cause.message
            : t("The project timer could not be updated.", locale);
      }
    } finally {
      if (requestGeneration === generation) busy = false;
    }
  }
</script>

{#if enabled}
  <section class="project-timer" aria-labelledby="project-timer-title">
    {#if !unsupported}
      <div class="timer-face" aria-live="off">
        <span class="timer-watch"><NavIcon name="audit" /></span>
        <div>
          <h2 id="project-timer-title">{t("Knitting time", locale)}</h2>
          <p
            class="timer-digits"
            aria-label={`${parts[0]} ${t("Hours", locale)}, ${parts[1]} ${t("Minutes", locale)}, ${parts[2]} ${t("Seconds", locale)}`}
          >
            <span>{parts[0].padStart(2, "0")}</span><b>:</b><span
              >{parts[1].padStart(2, "0")}</span
            ><b>:</b><span>{parts[2].padStart(2, "0")}</span>
          </p>
          <p class="timer-caption">
            {timer?.started_at
              ? t("Time spent on this project", locale)
              : t("Ready when you are", locale)}
          </p>
        </div>
      </div>
    {/if}

    {#if loading}
      <p class="timer-status" role="status">{t("Loading timer…", locale)}</p>
    {:else if error}
      <p class="timer-error" role="alert">{error}</p>
    {/if}

    {#if canEdit && !unsupported && timer?.started_at}
      <label class="reminder-setting"
        >{t("Running reminder", locale)}
        <select
          aria-label={t("Running reminder", locale)}
          value={reminderMinutes}
          onchange={(event) => setReminderMinutes(event.currentTarget.value)}
        >
          <option value="0">{t("Off", locale)}</option>
          <option value="30">{t("30 minutes", locale)}</option>
          <option value="60">{t("1 hour", locale)}</option>
          <option value="120">{t("2 hours", locale)}</option>
        </select>
      </label>
    {/if}

    {#if reminderDue}
      <div class="reminder-alert" role="alert">
        <p>{t("Your knitting timer has been running for a while.", locale)}</p>
        <div class="timer-actions">
          <Button
            class="stop-time"
            variant="primary"
            disabled={busy}
            onclick={() => void changeTimer("stop")}
            >{t("Stop timer", locale)}</Button
          >
          <Button class="save-time" variant="secondary" onclick={snoozeReminder}
            >{t("Keep timing", locale)}</Button
          >
        </div>
      </div>
    {/if}

    {#if timer && !unsupported && canEdit && editing}
      <form
        class="timer-controls"
        onsubmit={(event) => {
          event.preventDefault();
          void changeTimer("set_total");
        }}
      >
        <fieldset disabled={busy}>
          <legend>{t("Adjust total time", locale)}</legend>
          <label
            >{t("Hours", locale)}<input
              aria-label={t("Hours", locale)}
              inputmode="numeric"
              type="text"
              maxlength="9"
              bind:value={hours}
            /></label
          >
          <span aria-hidden="true">:</span>
          <label
            >{t("Minutes", locale)}<input
              aria-label={t("Minutes", locale)}
              inputmode="numeric"
              type="text"
              maxlength="2"
              bind:value={minutes}
            /></label
          >
          <span aria-hidden="true">:</span>
          <label
            >{t("Seconds", locale)}<input
              aria-label={t("Seconds", locale)}
              inputmode="numeric"
              type="text"
              maxlength="2"
              bind:value={seconds}
            /></label
          >
        </fieldset>
        <div class="timer-actions">
          <Button
            type="submit"
            class="save-time"
            variant="primary"
            disabled={busy ||
              proposedTotal === null ||
              proposedTotal === timer.total_seconds}
            >{t("Save time", locale)}</Button
          >
          <Button
            class="save-time"
            variant="secondary"
            disabled={busy}
            onclick={cancelEdit}>{t("Cancel", locale)}</Button
          >
        </div>
      </form>
    {:else if !unsupported && timer?.started_at && canEdit}
      <div class="timer-actions">
        <Button
          class="save-time"
          variant="secondary"
          disabled={busy}
          onclick={beginEdit}>{t("Edit time", locale)}</Button
        >
        <Button
          class="stop-time"
          variant="primary"
          disabled={busy}
          onclick={() => void changeTimer("stop")}>{t("Stop", locale)}</Button
        >
      </div>
    {:else if !unsupported && timer && canEdit}
      <div class="timer-actions">
        <Button
          class="save-time"
          variant="secondary"
          disabled={busy}
          onclick={beginEdit}>{t("Edit time", locale)}</Button
        >
        <Button
          class="start-time"
          variant="primary"
          disabled={busy}
          onclick={() => void changeTimer("start")}>{t("Start", locale)}</Button
        >
      </div>
    {/if}
  </section>
{/if}

<style>
  .project-timer {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 1rem;
    margin: 1rem 0;
    padding: 1rem 1.15rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
  }
  .timer-face {
    display: flex;
    align-items: center;
    gap: 0.85rem;
    min-width: 0;
  }
  .timer-watch {
    display: grid;
    place-items: center;
    width: 3rem;
    height: 3rem;
    border-radius: var(--radius-xs);
    background: var(--accent-wash);
    font-size: 1.55rem;
  }
  .timer-watch :global(svg) {
    width: 1.5rem;
    height: 1.5rem;
    color: var(--accent-strong);
  }
  h2 {
    margin: 0;
    font-size: 0.95rem;
    font-weight: 650;
  }
  .timer-digits {
    display: flex;
    align-items: baseline;
    gap: 0.12rem;
    margin: 0.1rem 0;
    color: var(--ink);
    font-size: clamp(1.65rem, 5vw, 2.2rem);
    font-variant-numeric: tabular-nums;
    font-weight: 700;
    letter-spacing: 0.015em;
    line-height: 1.05;
  }
  .timer-digits b {
    color: var(--ink-muted);
    font-size: 0.75em;
    font-weight: 500;
  }
  .timer-caption,
  .timer-status {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.78rem;
  }
  .timer-controls {
    display: grid;
    gap: 0.6rem;
  }
  fieldset {
    display: flex;
    align-items: end;
    gap: 0.35rem;
    margin: 0;
    padding: 0;
    border: 0;
  }
  legend {
    margin-bottom: 0.3rem;
    color: var(--ink-muted);
    font-size: 0.72rem;
  }
  label {
    display: grid;
    gap: 0.15rem;
    color: var(--ink-muted);
    font-size: 0.68rem;
  }
  input {
    box-sizing: border-box;
    width: 4rem;
    min-height: 44px;
    padding: 0.38rem 0.3rem;
    border: 1px solid var(--control-border);
    border-radius: var(--radius-xs);
    background: var(--surface);
    color: var(--ink);
    font: inherit;
    text-align: center;
  }
  input:focus-visible,
  select:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }
  .timer-actions {
    display: flex;
    justify-content: end;
    gap: 0.45rem;
  }
  .reminder-setting {
    grid-column: 1 / -1;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    color: var(--ink-muted);
    font-size: 0.78rem;
  }
  select {
    max-width: 10rem;
    min-height: 44px;
    padding: 0.3rem 0.5rem;
    border: 1px solid var(--control-border);
    border-radius: var(--radius-xs);
    background: var(--surface);
    color: var(--ink);
    font: inherit;
  }
  .reminder-alert {
    grid-column: 1 / -1;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.8rem;
    padding: 0.75rem;
    border-radius: var(--radius-sm);
    background: var(--accent-wash);
    color: var(--ink);
  }
  .reminder-alert p {
    margin: 0;
    font-size: 0.88rem;
  }
  .timer-error {
    grid-column: 1 / -1;
    margin: 0;
    color: var(--danger);
    font-size: 0.84rem;
  }
  @media (max-width: 640px) {
    .project-timer {
      grid-template-columns: 1fr;
    }
    .timer-actions {
      justify-content: stretch;
    }
    .timer-actions :global(.ui-button) {
      flex: 1;
    }
    fieldset {
      justify-content: center;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    *,
    *::before,
    *::after {
      scroll-behavior: auto !important;
    }
  }
</style>
