<script lang="ts">
  import { onDestroy } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import AdminTabs from "./AdminTabs.svelte";
  import BackupKindBadge from "./BackupKindBadge.svelte";
  import RestoreBackupDialog from "./RestoreBackupDialog.svelte";
  import {
    listBackups,
    type BackupEntry,
    type BackupList,
  } from "./backups-api.ts";
  import {
    compareBackups,
    formatBytes,
    formatCount,
  } from "./backups-format.ts";
  import {
    groupByDay,
    relativeAge,
    undoCandidate,
    type DayLabel,
  } from "./backups-present.ts";

  type LoadState = "loading" | "ready" | "error";

  let list = $state<BackupList | null>(null);
  let loadState = $state<LoadState>("loading");
  let loadError = $state("");
  let retry = $state(0);
  let loadGeneration = 0;
  let restoreDialog = $state<ReturnType<typeof RestoreBackupDialog>>();

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const backups = $derived(
    list ? [...list.backups].sort(compareBackups) : ([] as BackupEntry[]),
  );
  const undoTarget = $derived(list ? undoCandidate(list, backups) : null);
  let now = $state(new Date());
  const groups = $derived(groupByDay(backups, now));

  $effect(() => {
    const ownerId = userId;
    retry;
    const request = ++loadGeneration;
    const controller = new AbortController();
    if (ownerId === null || session.data?.user?.is_admin !== true) {
      loadState = "error";
      loadError = t("Administrator access is required.", locale);
      return () => controller.abort();
    }
    loadState = "loading";
    void listBackups(controller.signal)
      .then((result) => {
        if (controller.signal.aborted || request !== loadGeneration) return;
        now = new Date();
        list = result;
        loadState = "ready";
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || request !== loadGeneration) return;
        loadState = "error";
        loadError =
          error instanceof ApiError && error.status === 403
            ? t("Administrator access is required.", locale)
            : t("The backups could not be loaded.", locale);
      });
    return () => controller.abort();
  });

  onDestroy(() => {
    loadGeneration += 1;
  });

  function formatTime(value: string): string {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.valueOf())) return value;
    return new Intl.DateTimeFormat(locale, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(parsed);
  }

  /** The time of day only: the day header above the row carries the date. */
  function formatClock(value: string): string {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.valueOf())) return value;
    return new Intl.DateTimeFormat(locale, { timeStyle: "short" }).format(
      parsed,
    );
  }

  function dayTitle(label: DayLabel): string {
    if (label.kind === "today") return t("Today", locale);
    if (label.kind === "yesterday") return t("Yesterday", locale);
    return new Intl.DateTimeFormat(locale, {
      weekday: "long",
      day: "numeric",
      month: "long",
      year: "numeric",
    }).format(label.date);
  }

  function problem(entry: BackupEntry): string | null {
    if (entry.newer_schema)
      return t("Written by a newer version of Stricknani", locale);
    if (!entry.readable) return t("Cannot be read", locale);
    return null;
  }
</script>

<svelte:head
  ><title>Stricknani · {t("Admin", locale)} · {t("Backups", locale)}</title
  ></svelte:head
>

<section
  class="backups-page page-container page-container--wide"
  aria-labelledby="backups-title"
>
  <PageHeader title={t("Admin", locale)} titleId="backups-title"></PageHeader>
  <AdminTabs active="backups" {locale} />

  {#if loadState === "loading"}
    <p class="notice-panel" aria-live="polite">
      {t("Loading backups…", locale)}
    </p>
  {:else if loadState === "error"}
    <section class="notice-panel" role="alert" aria-labelledby="backups-error">
      <h2 id="backups-error">{loadError}</h2>
      <SharedButton
        variant="secondary"
        onclick={() => (retry += 1)}
        icon="refresh"
      >
        {t("Try again", locale)}
      </SharedButton>
    </section>
  {:else if list && !list.available}
    <p class="notice-panel">
      {t("Backups are not available on this server.", locale)}
    </p>
  {:else if list}
    {#if list.pending_restore}
      <p class="banner" role="status" data-testid="restore-pending">
        {t(
          "A restore of {name} is waiting for the server to restart.",
          locale,
        ).replace("{name}", list.pending_restore.backup)}
      </p>
    {/if}
    {#if list.last_restore && !(undoTarget && list.last_restore.status === "succeeded")}
      <section
        class="banner"
        class:banner--problem={list.last_restore.status === "failed"}
        role={list.last_restore.status === "failed" ? "alert" : "status"}
        data-testid="last-restore"
      >
        <p>
          {#if list.last_restore.status === "failed"}
            {t("The last restore did not work: {name}.", locale).replace(
              "{name}",
              list.last_restore.backup,
            )}
            {#if list.last_restore.error}<code>{list.last_restore.error}</code
              >{/if}
          {:else}
            {t("{name} was restored on {time} by {email}.", locale)
              .replace("{name}", list.last_restore.backup)
              .replace("{time}", formatTime(list.last_restore.finished_at))
              .replace("{email}", list.last_restore.requested_by_email)}
          {/if}
        </p>
        {#if list.last_restore.media_error}
          <p>
            {t("Some media files could not be added: {error}", locale).replace(
              "{error}",
              list.last_restore.media_error,
            )}
          </p>
        {/if}
      </section>
    {/if}

    {#if undoTarget && list.last_restore}
      <section
        class="undo"
        aria-labelledby="undo-title"
        data-testid="undo-card"
      >
        <span class="undo-icon" aria-hidden="true"
          ><NavIcon name="reset" /></span
        >
        <div class="undo-text">
          <h2 id="undo-title">{t("Undo the last restore", locale)}</h2>
          <p>
            {t(
              "{email} restored {name} on {time}. The data from before is saved as {saved}; restoring it brings that state back.",
              locale,
            )
              .replace("{email}", list.last_restore.requested_by_email)
              .replace("{name}", list.last_restore.backup)
              .replace("{time}", formatTime(list.last_restore.finished_at))
              .replace("{saved}", undoTarget.name)}
          </p>
          {#if list.last_restore.media_error}
            <p>
              {t(
                "Some media files could not be added: {error}",
                locale,
              ).replace("{error}", list.last_restore.media_error)}
            </p>
          {/if}
        </div>
        <SharedButton
          variant="secondary"
          icon="reset"
          disabled={!undoTarget.restorable}
          onclick={() => void restoreDialog?.open(undoTarget)}
        >
          {t("Undo last restore…", locale)}
        </SharedButton>
      </section>
    {/if}

    {#if backups.length === 0}
      <p class="empty">{t("No backups found.", locale)}</p>
    {:else}
      <div class="backup-days" aria-label={t("Backups", locale)} role="group">
        {#each groups as group (group.key)}
          <section class="backup-day">
            <h2 class="day-title">{dayTitle(group.label)}</h2>
            <ul class="backup-list">
              {#each group.entries as entry (entry.name)}
                {@const blocked = problem(entry)}
                {@const age = relativeAge(entry.created_at, now, locale)}
                <li class="backup-row" data-backup={entry.name}>
                  <div class="backup-head">
                    <BackupKindBadge kind={entry.kind} {locale} />
                    <time
                      class="backup-time"
                      datetime={entry.created_at}
                      title={formatTime(entry.created_at)}
                      >{formatClock(entry.created_at)}</time
                    >
                    {#if age}<span class="backup-age">{age}</span>{/if}
                  </div>
                  <p class="backup-file" title={entry.name}>
                    <code>{entry.name}</code>
                  </p>
                  <ul class="facts">
                    <li class="fact">
                      <NavIcon name="package" />{formatBytes(entry.size_bytes)}
                    </li>
                    {#if entry.schema_version !== null}
                      <li class="fact">
                        <NavIcon name="hash" />{t(
                          "Schema {version}",
                          locale,
                        ).replace("{version}", String(entry.schema_version))}
                      </li>
                    {/if}
                    {#if entry.counts}
                      <li class="fact">
                        <NavIcon name="account" />{t(
                          "{users} accounts",
                          locale,
                        ).replace("{users}", formatCount(entry.counts.users))}
                      </li>
                      <li class="fact">
                        <NavIcon name="projects" />{t(
                          "{projects} projects",
                          locale,
                        ).replace(
                          "{projects}",
                          formatCount(entry.counts.projects),
                        )}
                      </li>
                      <li class="fact">
                        <NavIcon name="yarns" />{t(
                          "{yarns} yarns",
                          locale,
                        ).replace("{yarns}", formatCount(entry.counts.yarns))}
                      </li>
                      <li class="fact">
                        <NavIcon name="trash" />{t(
                          "{trashed} in the trash",
                          locale,
                        ).replace(
                          "{trashed}",
                          formatCount(entry.counts.trashed),
                        )}
                      </li>
                    {/if}
                    {#if entry.has_media}
                      <li class="fact">
                        <NavIcon name="image" />{t("Includes photos", locale)}
                      </li>
                    {/if}
                  </ul>
                  {#if blocked}
                    <p class="backup-problem">
                      <NavIcon name="info" />{blocked}
                    </p>
                  {/if}
                  <div class="backup-action">
                    <SharedButton
                      variant="secondary"
                      size="sm"
                      icon="reset"
                      disabled={!entry.restorable}
                      onclick={() => void restoreDialog?.open(entry)}
                    >
                      {t("Restore…", locale)}
                    </SharedButton>
                  </div>
                </li>
              {/each}
            </ul>
          </section>
        {/each}
      </div>
    {/if}
  {/if}

  <RestoreBackupDialog
    bind:this={restoreDialog}
    {locale}
    dataEpoch={list?.data_epoch ?? null}
    onfinished={() => (retry += 1)}
  />
</section>

<style>
  .backups-page {
    display: grid;
    gap: 1rem;
    align-content: start;
  }

  .banner {
    margin: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.7rem 0.9rem;
    background: var(--surface);
  }

  .banner p {
    margin: 0;
    overflow-wrap: anywhere;
  }

  .banner--problem {
    border-color: var(--danger, #b3261e);
  }

  .undo {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 0.75rem 1rem;
    border: 1px solid var(--warning);
    border-radius: var(--radius-sm);
    padding: 1rem;
    background: var(--warning-surface);
    color: var(--ink);
  }

  .undo-icon {
    display: grid;
    place-items: center;
    width: 2.5rem;
    height: 2.5rem;
    border-radius: 999px;
    background: var(--surface);
    color: var(--warning);
    font-size: 1.4rem;
  }

  .undo h2 {
    margin: 0 0 0.25rem;
    font-size: 1.05rem;
  }

  .undo p {
    margin: 0;
    overflow-wrap: anywhere;
  }

  .undo p + p {
    margin-top: 0.25rem;
  }

  .empty {
    color: var(--ink-muted);
  }

  .backup-days {
    display: grid;
    gap: 1.25rem;
  }

  .backup-day {
    display: grid;
    gap: 0.5rem;
  }

  .day-title {
    margin: 0 0 0 0.25rem;
    color: var(--ink-muted);
    font-family: var(--font-body);
    font-size: 0.82rem;
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }

  .backup-list {
    display: grid;
    margin: 0;
    padding: 0;
    list-style: none;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
    overflow: hidden;
  }

  .backup-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      "head action"
      "file action"
      "facts action"
      "problem action";
    align-items: start;
    column-gap: 1rem;
    row-gap: 0.5rem;
    padding: 1rem;
    content-visibility: auto;
    contain-intrinsic-size: auto 7.5rem;
  }

  .backup-row + .backup-row {
    border-top: 1px solid var(--line);
  }

  .backup-head {
    grid-area: head;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.25rem 0.6rem;
    min-width: 0;
  }

  .backup-time {
    font-weight: 650;
    font-variant-numeric: tabular-nums;
  }

  .backup-age {
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .backup-file {
    grid-area: file;
    margin: 0;
    min-width: 0;
    color: var(--ink-muted);
    font-size: 0.82rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .backup-file code {
    font-family: var(--font-mono, ui-monospace, monospace);
  }

  .facts {
    grid-area: facts;
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .fact {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
    border-radius: 999px;
    padding: 0.1rem 0.6rem 0.1rem 0.45rem;
    background: var(--surface-raised);
    color: var(--ink-muted);
    font-size: 0.82rem;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }

  .fact :global(svg) {
    width: 0.95rem;
    height: 0.95rem;
  }

  .backup-problem {
    grid-area: problem;
    display: flex;
    align-items: center;
    gap: 0.35rem;
    margin: 0;
    color: var(--danger, #b3261e);
    font-weight: 650;
    font-size: 0.9rem;
  }

  .backup-problem :global(svg) {
    width: 1rem;
    height: 1rem;
    flex: none;
  }

  .backup-action {
    grid-area: action;
    align-self: start;
  }

  @media (max-width: 40rem) {
    .undo {
      grid-template-columns: auto minmax(0, 1fr);
    }

    .undo :global(.ui-button) {
      grid-column: 1 / -1;
      justify-self: stretch;
    }

    .backup-row {
      grid-template-columns: minmax(0, 1fr);
      grid-template-areas:
        "head"
        "file"
        "facts"
        "problem"
        "action";
    }

    .backup-action :global(.ui-button) {
      width: 100%;
      justify-content: center;
    }
  }
</style>
