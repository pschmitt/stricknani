<script lang="ts">
  import { onDestroy } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import AdminTabs from "./AdminTabs.svelte";
  import RestoreBackupDialog from "./RestoreBackupDialog.svelte";
  import {
    listBackups,
    type BackupEntry,
    type BackupKind,
    type BackupList,
  } from "./backups-api.ts";
  import {
    compareBackups,
    formatBytes,
    formatCount,
  } from "./backups-format.ts";

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
  const undoTarget = $derived(
    list?.latest_pre_restore
      ? (backups.find((entry) => entry.name === list?.latest_pre_restore) ??
          null)
      : null,
  );

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

  function kindLabel(kind: BackupKind): string {
    switch (kind) {
      case "nightly":
        return t("Nightly", locale);
      case "predeploy":
        return t("Before update", locale);
      case "pre_migrate":
        return t("Before migration", locale);
      case "pre_restore":
        return t("Before restore", locale);
      default:
        return t("Manual", locale);
    }
  }

  function formatTime(value: string): string {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.valueOf())) return value;
    return new Intl.DateTimeFormat(locale, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(parsed);
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
    {#if list.last_restore}
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

    {#if undoTarget}
      <section class="undo" aria-labelledby="undo-title">
        <div>
          <h2 id="undo-title">{t("Undo the last restore", locale)}</h2>
          <p>
            {t(
              "Before every restore the current data is saved as {name} ({time}). Restoring it brings that state back.",
              locale,
            )
              .replace("{name}", undoTarget.name)
              .replace("{time}", formatTime(undoTarget.created_at))}
          </p>
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
      <ul class="backup-list" aria-label={t("Backups", locale)}>
        {#each backups as entry (entry.name)}
          {@const blocked = problem(entry)}
          <li class="backup-row" data-backup={entry.name}>
            <div class="backup-main">
              <p class="backup-title">
                <span class="chip chip--{entry.kind}"
                  >{kindLabel(entry.kind)}</span
                >
                <time datetime={entry.created_at}
                  >{formatTime(entry.created_at)}</time
                >
              </p>
              <p class="backup-file"><code>{entry.name}</code></p>
              <p class="backup-meta">
                <span>{formatBytes(entry.size_bytes)}</span>
                {#if entry.schema_version !== null}
                  <span
                    >{t("Schema {version}", locale).replace(
                      "{version}",
                      String(entry.schema_version),
                    )}</span
                  >
                {/if}
                {#if entry.has_media}<span>{t("Includes media", locale)}</span
                  >{/if}
              </p>
              {#if entry.counts}
                <p class="backup-counts">
                  {t("{users} accounts", locale).replace(
                    "{users}",
                    formatCount(entry.counts.users),
                  )}
                  ·
                  {t("{projects} projects", locale).replace(
                    "{projects}",
                    formatCount(entry.counts.projects),
                  )}
                  ·
                  {t("{yarns} yarns", locale).replace(
                    "{yarns}",
                    formatCount(entry.counts.yarns),
                  )}
                  ·
                  {t("{trashed} in the trash", locale).replace(
                    "{trashed}",
                    formatCount(entry.counts.trashed),
                  )}
                </p>
              {/if}
              {#if blocked}<p class="backup-problem">{blocked}</p>{/if}
            </div>
            <SharedButton
              variant="secondary"
              icon="reset"
              disabled={!entry.restorable}
              onclick={() => void restoreDialog?.open(entry)}
            >
              {t("Restore…", locale)}
            </SharedButton>
          </li>
        {/each}
      </ul>
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
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.8rem 1.2rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.9rem 1rem;
    background: var(--surface);
  }

  .undo h2 {
    margin: 0 0 0.25rem;
    font-size: 1.05rem;
  }

  .undo p {
    margin: 0;
    color: var(--ink-muted);
    overflow-wrap: anywhere;
  }

  .empty {
    color: var(--ink-muted);
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
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem 1rem;
    padding: 0.8rem 1rem;
  }

  .backup-row + .backup-row {
    border-top: 1px solid var(--line);
  }

  .backup-main {
    min-width: 0;
    flex: 1 1 16rem;
  }

  .backup-main p {
    margin: 0;
  }

  .backup-title {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    font-weight: 650;
  }

  .backup-file {
    margin-top: 0.15rem;
    color: var(--ink-muted);
    font-size: 0.85rem;
    overflow-wrap: anywhere;
  }

  .backup-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.2rem 0.9rem;
    margin-top: 0.25rem;
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .backup-counts {
    margin-top: 0.2rem;
    font-size: 0.9rem;
    font-variant-numeric: tabular-nums;
  }

  .backup-problem {
    margin-top: 0.25rem;
    color: var(--danger, #b3261e);
    font-weight: 650;
    font-size: 0.9rem;
  }

  .chip {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    padding: 0.1rem 0.6rem;
    background: var(--surface-raised);
    color: var(--ink);
    font-size: 0.78rem;
    font-weight: 700;
    letter-spacing: 0.01em;
  }

  .chip--pre_restore {
    background: var(--accent-soft, var(--surface-raised));
    color: var(--accent-strong);
  }
</style>
