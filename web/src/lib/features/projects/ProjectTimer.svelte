<script lang="ts">
  import { onDestroy, untrack } from "svelte";
  import { resolve } from "$app/paths";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { session } from "#lib/session.svelte.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { webNotifications } from "#lib/web-notifications.svelte.ts";
  import { saveTimerReminder, timerReminderSchedule } from "#lib/web-push.ts";
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
  // The hands keep cumulative angles so a minute boundary sweeps on instead of
  // spinning back through zero. Only a one-second tick sweeps linearly; any other
  // change (back from a hidden tab, a server refresh, an edit) moves each hand
  // once, the short way round, to where it belongs.
  let secondAngle = $state(0);
  let minuteAngle = $state(0);
  let tickStep = $state(false);
  let lastTotal: number | null = null;
  function towards(current: number, target: number): number {
    const delta = ((((target - current) % 360) + 540) % 360) - 180;
    return current + delta;
  }
  $effect.pre(() => {
    const next = total;
    const previous = lastTotal;
    lastTotal = next;
    untrack(() => {
      tickStep = previous !== null && next - previous === 1;
      if (previous === null) {
        secondAngle = (next % 60) * 6;
        minuteAngle = ((next % 3600) / 60) * 6;
        return;
      }
      secondAngle = tickStep
        ? secondAngle + 6
        : towards(secondAngle, (next % 60) * 6);
      minuteAngle = towards(minuteAngle, ((next % 3600) / 60) * 6);
    });
  });
  const running = $derived(Boolean(timer?.started_at));
  const ticks = Array.from({ length: 60 }, (_, index) => index);
  // The chip in the action row shows a short "12:34" / "1:02:03"; nothing for an
  // untouched timer, so a fresh project only shows the little stopwatch.
  const shortTime = $derived(
    !timer || (total === 0 && !running)
      ? ""
      : Number(parts[0]) > 0
        ? `${parts[0]}:${parts[1].padStart(2, "0")}:${parts[2].padStart(2, "0")}`
        : `${parts[1]}:${parts[2].padStart(2, "0")}`,
  );
  let dialog = $state<HTMLDialogElement>();

  function openDialog() {
    if (!dialog?.open) dialog?.showModal();
  }

  function closeDialog() {
    if (dialog?.open) dialog.close();
  }

  // A click on the backdrop (the dialog element itself, outside its padded
  // body) closes the dialog, like tapping outside a sheet.
  $effect(() => {
    const element = dialog;
    if (!element) return;
    const onBackdrop = (event: MouseEvent) => {
      if (event.target === element) closeDialog();
    };
    element.addEventListener("click", onBackdrop);
    return () => element.removeEventListener("click", onBackdrop);
  });

  // A due reminder opens the dialog, so its question is not missed in the chip.
  $effect(() => {
    if (reminderDue) untrack(openDialog);
  });
  const editing = $derived(timer !== null && editMode);
  const proposedTotal = $derived(secondsFromParts(hours, minutes, seconds));

  $effect(() => {
    const id = projectId;
    const timersEnabled = enabled;
    // Only the signed-in user matters here: depending on the whole session would
    // reset the timer on every session refresh.
    const userId = session.data?.user?.id;
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
    const currentReminderKey =
      userId !== undefined ? `stricknani:timer-reminder:${userId}:${id}` : "";
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
    if (userId === undefined) {
      loading = false;
      error = untrack(() => t("Sign in to use the project timer.", locale));
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

  // One browser notification per due reminder, when this tab is not in view;
  // "Keep timing" arms the next one. The in-page alert covers a visible tab.
  let notifiedReminder: string | null = null;
  $effect(() => {
    if (!reminderDue || !timer?.started_at) return;
    const key = `${projectId}:${timer.started_at}:${reminderAfter ?? reminderMinutes * 60}`;
    if (notifiedReminder === key) return;
    notifiedReminder = key;
    if (document.visibilityState === "visible" && document.hasFocus()) return;
    void webNotifications.show({
      title: t("Project timer is still running", locale),
      body: t("Open the project to stop the timer or keep timing.", locale),
      tag: `project-timer-${projectId}`,
      path: resolve("/projects/[id]", { id: String(projectId) }),
    });
  });

  // The server sends this reminder as a Web Push, so it also arrives with no
  // tab open. It learns the schedule whenever it changes (start, interval,
  // "Keep timing") while notifications are on; the run ending drops it.
  let savedSchedule = "";
  $effect(() => {
    const schedule =
      enabled && canEdit && webNotifications.state === "on"
        ? timerReminderSchedule(
            timer?.started_at ?? null,
            reminderMinutes,
            reminderAfter,
            locale,
          )
        : null;
    const key = schedule ? JSON.stringify(schedule) : "";
    if (key === savedSchedule) return;
    const previous = savedSchedule;
    savedSchedule = key;
    const csrf = untrack(() => session.data?.csrf_token);
    if (!csrf) return;
    void saveTimerReminder(projectId, csrf, schedule).catch(() => {
      // Retried on the next change; reminders still show in an open tab.
      if (savedSchedule === key) savedSchedule = previous;
    });
  });

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

{#snippet stopwatch(mini: boolean)}
  <svg
    class={mini ? "stopwatch stopwatch--mini" : "stopwatch"}
    viewBox="0 0 100 112"
    aria-hidden="true"
    focusable="false"
  >
    {#if !mini}<circle class="halo" cx="50" cy="62" r="46" />{/if}
    <g class="crown">
      <rect x="46.5" y="8" width="7" height="8" rx="1.5" />
      <rect x="41" y="2" width="18" height="8" rx="3" />
    </g>
    <rect
      class="pusher"
      x="46"
      y="10"
      width="8"
      height="7"
      rx="2"
      transform="rotate(42 50 62)"
    />
    <circle class="case" cx="50" cy="62" r={mini ? 44 : 41} />
    <circle class="dial" cx="50" cy="62" r={mini ? 34 : 35} />
    {#if mini}
      {#each [0, 15, 30, 45] as tick (tick)}
        <line
          class="tick tick--hour"
          x1="50"
          y1="31"
          x2="50"
          y2="38"
          transform={`rotate(${tick * 6} 50 62)`}
        />
      {/each}
    {:else}
      {#each ticks as tick (tick)}
        <line
          class={tick % 5 === 0 ? "tick tick--hour" : "tick"}
          x1="50"
          y1="28.5"
          x2="50"
          y2={tick % 5 === 0 ? 34 : 31.5}
          transform={`rotate(${tick * 6} 50 62)`}
        />
      {/each}
      <circle class="subdial" cx="50" cy="76" r="8.5" />
      <line
        class="minute-hand"
        x1="50"
        y1="76"
        x2="50"
        y2="69.5"
        style={`transform: rotate(${minuteAngle}deg)`}
      />
    {/if}
    <g class="second-hand" style={`transform: rotate(${secondAngle}deg)`}>
      <line x1="50" y1={mini ? 66 : 70} x2="50" y2={mini ? 34 : 31} />
      {#if !mini}<circle class="needle-knob" cx="50" cy="71.5" r="3.4" />{/if}
    </g>
    <circle class="hub" cx="50" cy="62" r={mini ? 5 : 2.6} />
  </svg>
{/snippet}

{#if enabled && !unsupported}
  <div class="project-timer" class:running class:tick-step={tickStep}>
    <button
      type="button"
      class="timer-chip"
      class:timer-chip--due={reminderDue}
      aria-haspopup="dialog"
      aria-label={shortTime
        ? `${t("Knitting time", locale)}: ${shortTime}`
        : t("Knitting time", locale)}
      onclick={openDialog}
    >
      {@render stopwatch(true)}
      {#if shortTime}<span class="timer-chip__time">{shortTime}</span>{/if}
    </button>

    <dialog
      bind:this={dialog}
      class="timer-dialog"
      aria-labelledby="project-timer-title"
    >
      <div class="timer-dialog__body">
        <button
          type="button"
          class="timer-dialog__close"
          aria-label={t("Close", locale)}
          onclick={closeDialog}><NavIcon name="close" /></button
        >
        <div class="timer-face" aria-live="off">
          {@render stopwatch(false)}
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
            {running
              ? t("Time spent on this project", locale)
              : t("Ready when you are", locale)}
          </p>
        </div>

        {#if loading}
          <p class="timer-status" role="status">
            {t("Loading timer…", locale)}
          </p>
        {:else if error}
          <p class="timer-error" role="alert">{error}</p>
        {/if}

        {#if reminderDue}
          <div class="reminder-alert" role="alert">
            <p>
              {t("Your knitting timer has been running for a while.", locale)}
            </p>
            <div class="timer-actions">
              <Button
                class="stop-time"
                variant="primary"
                disabled={busy}
                onclick={() => void changeTimer("stop")}
                >{t("Stop timer", locale)}</Button
              >
              <Button
                class="save-time"
                variant="secondary"
                onclick={snoozeReminder}>{t("Keep timing", locale)}</Button
              >
            </div>
          </div>
        {/if}

        {#if timer && canEdit && editing}
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
                class="save-time"
                variant="secondary"
                disabled={busy}
                onclick={cancelEdit}>{t("Cancel", locale)}</Button
              >
              <Button
                type="submit"
                class="save-time"
                variant="primary"
                disabled={busy ||
                  proposedTotal === null ||
                  proposedTotal === timer.total_seconds}
                >{t("Save time", locale)}</Button
              >
            </div>
          </form>
        {:else if timer && canEdit}
          <div class="timer-actions">
            <Button
              class="save-time"
              variant="secondary"
              disabled={busy}
              onclick={beginEdit}>{t("Edit time", locale)}</Button
            >
            {#if running}
              <Button
                class="stop-time"
                variant="primary"
                disabled={busy}
                onclick={() => void changeTimer("stop")}
                >{t("Stop", locale)}</Button
              >
            {:else}
              <Button
                class="start-time"
                variant="primary"
                disabled={busy}
                onclick={() => void changeTimer("start")}
                >{t("Start", locale)}</Button
              >
            {/if}
          </div>
        {/if}

        {#if canEdit && running}
          <label class="reminder-setting"
            >{t("Running reminder", locale)}
            <select
              aria-label={t("Running reminder", locale)}
              value={reminderMinutes}
              onchange={(event) =>
                setReminderMinutes(event.currentTarget.value)}
            >
              <option value="0">{t("Off", locale)}</option>
              <option value="30">{t("30 minutes", locale)}</option>
              <option value="60">{t("1 hour", locale)}</option>
              <option value="120">{t("2 hours", locale)}</option>
            </select>
          </label>
        {/if}
      </div>
    </dialog>
  </div>
{/if}

<style>
  .project-timer {
    display: inline-flex;
    flex: 0 0 auto;
  }
  /* The chip: a 44px round button like its Favorite neighbour, growing a time
     label once the timer has run. */
  .timer-chip {
    display: inline-flex;
    box-sizing: border-box;
    min-width: 44px;
    height: 44px;
    align-items: center;
    justify-content: center;
    gap: 0.35rem;
    padding: 0 0.6rem;
    border: 1px solid var(--control-border);
    border-radius: 999px;
    color: var(--ink);
    background: var(--surface);
    font: inherit;
    font-size: 0.9rem;
    font-variant-numeric: tabular-nums;
    font-weight: 700;
    cursor: pointer;
    transition:
      background-color 120ms ease,
      border-color 120ms ease;
    -webkit-tap-highlight-color: transparent;
  }
  .timer-chip:hover {
    background: var(--accent-wash);
  }
  .timer-chip:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 3px;
  }
  .running .timer-chip {
    border-color: var(--accent);
    background: var(--accent-wash);
    color: var(--accent-strong);
  }
  .timer-chip--due {
    animation: chip-nudge 2.4s ease-in-out infinite;
  }
  .timer-chip__time {
    line-height: 1;
  }

  .timer-dialog {
    box-sizing: border-box;
    width: min(22rem, calc(100vw - 2rem));
    max-height: calc(100dvh - 2rem);
    overflow: auto;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: calc(var(--radius-sm) * 2);
    color: var(--ink);
    background: var(--surface);
    box-shadow: var(--shadow);
  }
  .timer-dialog[open] {
    animation: dialog-pop 320ms cubic-bezier(0.34, 1.56, 0.64, 1);
  }
  .timer-dialog::backdrop {
    background: rgb(15 28 44 / 55%);
  }
  .timer-dialog__body {
    position: relative;
    display: grid;
    gap: 1rem;
    padding: 1.4rem 1.2rem 1.2rem;
  }
  .timer-dialog__close {
    position: absolute;
    top: 0.4rem;
    right: 0.4rem;
    display: grid;
    width: 44px;
    height: 44px;
    place-items: center;
    border: 0;
    border-radius: 999px;
    color: var(--ink-muted);
    background: transparent;
    cursor: pointer;
  }
  .timer-dialog__close:hover {
    background: var(--accent-wash);
  }
  .timer-dialog__close:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }
  .timer-dialog__close :global(svg) {
    width: 20px;
    height: 20px;
  }
  .timer-face {
    display: grid;
    justify-items: center;
    gap: 0.2rem;
    text-align: center;
  }
  h2 {
    margin: 0.4rem 0 0;
    color: var(--ink-muted);
    font-size: 0.8rem;
    font-weight: 800;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .timer-digits {
    display: flex;
    align-items: baseline;
    gap: 0.12rem;
    margin: 0;
    color: var(--ink);
    font-size: 2.4rem;
    font-variant-numeric: tabular-nums;
    font-weight: 750;
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
    font-size: 0.82rem;
  }
  .timer-status {
    text-align: center;
  }

  .stopwatch {
    flex: none;
    width: 7.5rem;
    height: auto;
    overflow: visible;
    transform-origin: 50% 60%;
  }
  .stopwatch--mini {
    width: 22px;
  }
  .stopwatch .halo {
    fill: var(--accent-wash);
    opacity: 0;
    transform-box: view-box;
    transform-origin: 50px 62px;
  }
  .stopwatch .crown,
  .stopwatch .pusher,
  .stopwatch .case,
  .stopwatch .needle-knob,
  .stopwatch .hub {
    fill: var(--accent-strong);
  }
  .stopwatch .crown {
    transition: transform 160ms ease-out;
  }
  .stopwatch .dial {
    fill: var(--surface);
  }
  .stopwatch .tick {
    stroke: var(--ink-muted);
    stroke-linecap: round;
    stroke-width: 0.8;
    opacity: 0.55;
  }
  .stopwatch .tick--hour {
    stroke: var(--ink);
    stroke-width: 1.6;
    opacity: 0.8;
  }
  .stopwatch--mini .tick--hour {
    stroke-width: 7;
    opacity: 0.6;
  }
  .stopwatch .subdial {
    fill: var(--accent-wash);
  }
  .stopwatch .minute-hand {
    stroke: var(--ink);
    stroke-linecap: round;
    stroke-width: 1.6;
  }
  .stopwatch .minute-hand,
  .stopwatch .second-hand {
    transform-box: view-box;
    transition: transform 450ms cubic-bezier(0.2, 0.8, 0.2, 1);
  }
  .stopwatch .minute-hand {
    transform-origin: 50px 76px;
  }
  .stopwatch .second-hand {
    transform-origin: 50px 62px;
  }
  .stopwatch .second-hand line {
    stroke: var(--accent-strong);
    stroke-linecap: round;
    stroke-width: 1.8;
  }
  .stopwatch--mini .second-hand line {
    stroke-width: 9;
  }
  .stopwatch .hub {
    stroke: var(--surface);
    stroke-width: 1;
  }
  /* Running: the crown stays pressed, the second hand sweeps smoothly between
     ticks, the halo breathes and the watch gives one happy wiggle on start. */
  .running .stopwatch {
    animation: stopwatch-wiggle 520ms ease-out;
  }
  .running .stopwatch .crown {
    transform: translateY(3px);
  }
  .running.tick-step .stopwatch .second-hand,
  .running.tick-step .stopwatch .minute-hand {
    transition-duration: 1s;
    transition-timing-function: linear;
  }
  .running .stopwatch .halo {
    animation: stopwatch-breathe 2.4s ease-in-out infinite;
  }
  .running .timer-digits b {
    animation: stopwatch-blink 1s steps(1, end) infinite;
  }
  @keyframes stopwatch-wiggle {
    0%,
    100% {
      transform: rotate(0);
    }
    25% {
      transform: rotate(-7deg);
    }
    55% {
      transform: rotate(5deg);
    }
    80% {
      transform: rotate(-2deg);
    }
  }
  @keyframes stopwatch-breathe {
    0%,
    100% {
      opacity: 0.25;
      transform: scale(0.94);
    }
    50% {
      opacity: 0.9;
      transform: scale(1.04);
    }
  }
  @keyframes stopwatch-blink {
    50% {
      opacity: 0.25;
    }
  }
  @keyframes dialog-pop {
    from {
      opacity: 0;
      transform: scale(0.85) translateY(12px);
    }
  }
  @keyframes chip-nudge {
    0%,
    80%,
    100% {
      transform: rotate(0);
    }
    85% {
      transform: rotate(-6deg);
    }
    90% {
      transform: rotate(6deg);
    }
    95% {
      transform: rotate(-3deg);
    }
  }

  .timer-controls {
    display: grid;
    gap: 0.8rem;
  }
  fieldset {
    display: flex;
    align-items: end;
    justify-content: center;
    gap: 0.35rem;
    margin: 0;
    padding: 0;
    border: 0;
  }
  legend {
    margin: 0 auto 0.4rem;
    color: var(--ink-muted);
    font-size: 0.78rem;
  }
  label {
    display: grid;
    gap: 0.15rem;
    color: var(--ink-muted);
    font-size: 0.7rem;
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
    gap: 0.5rem;
  }
  .timer-actions :global(.ui-button) {
    flex: 1;
  }
  .reminder-setting {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    font-size: 0.8rem;
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
    display: grid;
    gap: 0.7rem;
    padding: 0.8rem;
    border-radius: var(--radius-sm);
    background: var(--accent-wash);
    color: var(--ink);
  }
  .reminder-alert p {
    margin: 0;
    font-size: 0.9rem;
    text-align: center;
  }
  .timer-error {
    margin: 0;
    color: var(--danger);
    font-size: 0.84rem;
    text-align: center;
  }
  @media (prefers-reduced-motion: reduce) {
    .stopwatch,
    .stopwatch *,
    .timer-digits b,
    .timer-chip,
    .timer-dialog {
      animation: none !important;
      transition: none !important;
    }
  }
</style>
