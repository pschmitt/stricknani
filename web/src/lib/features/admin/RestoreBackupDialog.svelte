<script lang="ts">
  import { onMount } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    getAuthOptions,
    isReauthRequired,
    listIdentities,
    NO_AUTH_OPTIONS,
    type AuthOptions,
    type LinkedIdentity,
    type OidcProvider,
    type ReauthRequest,
  } from "#lib/features/auth/oidc-api.ts";
  import ReauthDialog from "#lib/features/auth/ReauthDialog.svelte";
  import {
    listPasskeys,
    passkeysSupported,
  } from "#lib/features/auth/passkey-api.ts";
  import { takePendingReauth } from "#lib/features/auth/reauth-flow.ts";
  import {
    listBackups,
    previewRestore,
    restoreBackup,
    waitForRestart,
    type BackupEntry,
    type MediaOption,
  } from "./backups-api.ts";
  import { countsDiffer, formatBytes, formatCount } from "./backups-format.ts";
  import {
    CLOSED,
    canRestore,
    isLocked,
    restoreReducer,
    type RestoreErrorKind,
    type RestoreEvent,
    type RestoreState,
  } from "./restore-flow.ts";

  /**
   * The guarded restore, in steps: what changes (preview), a fresh
   * re-authentication, typing the backup's exact name, then the destructive
   * "Restore now". Afterwards the dialog follows the server restart through
   * /healthz and /api/v2/meta and reports success or failure. The state machine
   * lives in restore-flow.ts; this component performs the requests.
   */
  let {
    locale,
    dataEpoch,
    onfinished,
  }: {
    locale: Locale;
    /** The server's data epoch before the restore (from the listing). */
    dataEpoch: string | null;
    /** The dialog closed after the restore ended (success, failure or timeout). */
    onfinished: () => void;
  } = $props();

  const REAUTH_REQUEST: ReauthRequest = { action: "restore_backup" };
  const RETURN_TO = "/app/admin/backups";

  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let reauthDialog = $state<ReturnType<typeof ReauthDialog>>();
  let flow = $state<RestoreState>(CLOSED);
  let reauthedAt: number | null = null;
  let loadGeneration = 0;
  let pollController: AbortController | null = null;
  let options = $state<AuthOptions>(NO_AUTH_OPTIONS);
  let identities = $state<LinkedIdentity[]>([]);
  let passwordEnabled = $state(true);
  let passkeyAvailable = $state(false);
  let resumedNotice = $state(false);

  const reauthProvider = $derived<OidcProvider | null>(
    identities
      .map((identity) => options.oidc.find((p) => p.id === identity.provider))
      .find((provider) => provider !== undefined) ?? null,
  );
  const locked = $derived(isLocked(flow));
  const typed = $derived(flow.phase === "confirm" ? flow.typed : "");

  function dispatch(event: RestoreEvent): void {
    flow = restoreReducer(flow, event);
    if (flow.phase === "closed") dialog?.close();
  }

  onMount(() => {
    const controller = new AbortController();
    // A passkey can confirm the restore instead of the password.
    if (session.data?.capabilities.passkeys === true && passkeysSupported())
      void listPasskeys(controller.signal)
        .then((result) => (passkeyAvailable = result.passkeys.length > 0))
        .catch(() => undefined);
    void getAuthOptions(controller.signal)
      .then(async (result) => {
        options = result;
        if (result.oidc.length === 0) return;
        const list = await listIdentities();
        identities = list.identities;
        passwordEnabled = list.passwordEnabled;
      })
      .catch(() => undefined);
    // Back from the login provider: the session is freshly confirmed.
    const resumed = takePendingReauth(
      typeof sessionStorage === "undefined" ? undefined : sessionStorage,
      Date.now(),
      (action) => action === "restore_backup",
    );
    if (resumed) {
      reauthedAt = Date.now();
      resumedNotice = true;
    }
    return () => {
      controller.abort();
      pollController?.abort();
    };
  });

  /** Start the flow for one backup. */
  export async function open(backup: BackupEntry): Promise<void> {
    dispatch({ type: "open", backup, reauthedAt });
    if (flow.phase !== "loading") return;
    dialog?.showModal();
    const generation = ++loadGeneration;
    try {
      const preview = await previewRestore(backup.name);
      if (generation !== loadGeneration) return;
      dispatch({ type: "preview_loaded", preview });
    } catch (failure) {
      if (generation !== loadGeneration) return;
      dispatch({ type: "preview_failed", error: classify(failure) });
    }
  }

  function classify(failure: unknown): RestoreErrorKind {
    if (!(failure instanceof ApiError)) return "unknown";
    switch (failure.code) {
      case "backup_newer_schema":
        return "newer_schema";
      case "backup_unreadable":
        return "unreadable";
      case "restore_in_progress":
        return "busy";
      case "pre_restore_failed":
        return "pre_restore_failed";
      case "stage_failed":
        return "stage_failed";
      case "confirmation_mismatch":
        return "confirmation";
      case "media_unavailable":
        return "media";
      case "backups_unavailable":
        return "unavailable";
      default:
        return "unknown";
    }
  }

  function errorMessage(kind: RestoreErrorKind): string {
    switch (kind) {
      case "newer_schema":
        return t(
          "This backup was written by a newer version of Stricknani than this server runs.",
          locale,
        );
      case "unreadable":
        return t("This backup cannot be read or is damaged.", locale);
      case "busy":
        return t("A restore is already in progress.", locale);
      case "pre_restore_failed":
        return t(
          "The safety copy of the current data could not be written. Nothing was changed.",
          locale,
        );
      case "stage_failed":
        return t(
          "The backup could not be prepared. Nothing was changed.",
          locale,
        );
      case "confirmation":
        return t("Type the exact backup name to confirm.", locale);
      case "media":
        return t("This backup has no media files to add.", locale);
      case "unavailable":
        return t("Backups are not available on this server.", locale);
      default:
        return t("The request could not be completed.", locale);
    }
  }

  function proceed(): void {
    dispatch({ type: "continue", now: Date.now() });
    if (flow.phase === "reauth") reauthDialog?.open(REAUTH_REQUEST);
  }

  function onReauthConfirmed(): void {
    const now = Date.now();
    reauthedAt = now;
    dispatch({ type: "reauth_confirmed", now });
  }

  async function submit(): Promise<void> {
    if (!canRestore(flow) || flow.phase !== "confirm" || !session.data) return;
    const { backup, typed: confirmation, media } = flow;
    dispatch({ type: "submit" });
    try {
      const accepted = await restoreBackup(
        backup.name,
        confirmation,
        media,
        session.data,
      );
      const now = Date.now();
      dispatch({ type: "accepted", accepted, now });
      if (accepted.restarting) void follow(now);
    } catch (failure) {
      if (isReauthRequired(failure)) {
        reauthedAt = null;
        dispatch({
          type: "rejected",
          error: "unknown",
          reauthRequired: true,
        });
        reauthDialog?.open(REAUTH_REQUEST);
        return;
      }
      dispatch({ type: "rejected", error: classify(failure) });
    }
  }

  /** Poll the restarting server until it says what happened. */
  async function follow(requestedAtMs: number): Promise<void> {
    pollController?.abort();
    const controller = new AbortController();
    pollController = controller;
    const result = await waitForRestart({
      previousEpoch: dataEpoch,
      requestedAtMs,
      signal: controller.signal,
    });
    if (controller.signal.aborted) return;
    if (result.kind === "failed") {
      // The original data is back, so the session still works: ask why.
      let reason: string | null = null;
      try {
        reason = (await listBackups()).last_restore?.error ?? null;
      } catch {
        reason = null;
      }
      dispatch({ type: "server_back", result: "failed", error: reason });
    } else {
      dispatch({ type: "server_back", result: result.kind });
    }
  }

  function checkAgain(): void {
    if (flow.phase !== "timeout") return;
    flow = {
      phase: "waiting",
      backup: flow.backup,
      accepted: flow.accepted,
      requestedAt: Date.now(),
    };
    void follow(Date.now() - 60_000);
  }

  function finish(): void {
    const succeeded = flow.phase === "succeeded";
    dispatch({ type: "close" });
    onfinished();
    // The data is a different one now: start over from a clean page.
    if (succeeded) window.location.reload();
  }

  function formatTime(value: string): string {
    const parsed = new Date(value);
    if (!Number.isFinite(parsed.valueOf())) return value;
    return new Intl.DateTimeFormat(locale, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(parsed);
  }

  const countRows = $derived.by(() => {
    if (
      flow.phase !== "preview" &&
      flow.phase !== "confirm" &&
      flow.phase !== "reauth"
    )
      return [];
    const { current, backup_snapshot } = flow.preview;
    return [
      ["users", t("Accounts", locale)],
      ["projects", t("Projects", locale)],
      ["yarns", t("Yarns", locale)],
      ["trashed", t("In the trash", locale)],
    ].map(([key, label]) => ({
      key,
      label,
      now: formatCount(current.counts?.[key as "users"]),
      then: formatCount(backup_snapshot.counts?.[key as "users"]),
      differs: countsDiffer(
        current.counts,
        backup_snapshot.counts,
        key as "users",
      ),
    }));
  });
</script>

<ConfirmDialog
  bind:this={dialog}
  titleId="restore-backup-title"
  title={flow.phase === "succeeded"
    ? t("Backup restored", locale)
    : flow.phase === "failed"
      ? t("The restore did not work", locale)
      : flow.phase === "waiting" || flow.phase === "restoring"
        ? t("Restoring…", locale)
        : t("Restore a backup", locale)}
  busy={locked}
  onclose={() => {
    if (!locked && flow.phase !== "closed") {
      const ended =
        flow.phase === "succeeded" ||
        flow.phase === "failed" ||
        flow.phase === "timeout" ||
        flow.phase === "manual_restart";
      const succeeded = flow.phase === "succeeded";
      dispatch({ type: "close" });
      if (ended) onfinished();
      if (succeeded) window.location.reload();
    }
  }}
>
  {#if flow.phase !== "closed"}
    <p class="backup-name"><code>{flow.backup.name}</code></p>
  {/if}

  {#if flow.phase === "loading"}
    <p aria-live="polite">
      {t("Comparing the backup with the current data…", locale)}
    </p>
  {:else if flow.phase === "error"}
    <p class="problem" role="alert">{errorMessage(flow.error)}</p>
  {:else if flow.phase === "preview" || flow.phase === "reauth" || flow.phase === "confirm" || flow.phase === "restoring"}
    {#if resumedNotice && flow.phase === "preview"}
      <p class="note" role="status">
        {t("You confirmed it is you.", locale)}
      </p>
    {/if}
    <p>
      {t("This backup was taken on {time}.", locale).replace(
        "{time}",
        formatTime(flow.backup.created_at),
      )}
      {formatBytes(flow.backup.size_bytes)}
    </p>
    <table class="compare">
      <caption class="visually-hidden"
        >{t("Current data and this backup", locale)}</caption
      >
      <thead>
        <tr>
          <th scope="col"></th>
          <th scope="col">{t("Now", locale)}</th>
          <th scope="col">{t("This backup", locale)}</th>
        </tr>
      </thead>
      <tbody>
        {#each countRows as row (row.key)}
          <tr class:differs={row.differs}>
            <th scope="row">{row.label}</th>
            <td>{row.now}</td>
            <td>{row.then}</td>
          </tr>
        {/each}
      </tbody>
    </table>
    <p class="warning">
      {#if flow.preview.changes_since_backup !== null && flow.preview.changes_since_backup > 0}
        {t(
          "Changes made since {time} will be lost, including {count} recorded edits.",
          locale,
        )
          .replace("{time}", formatTime(flow.backup.created_at))
          .replace("{count}", String(flow.preview.changes_since_backup))}
      {:else}
        {t("Changes made since {time} will be lost.", locale).replace(
          "{time}",
          formatTime(flow.backup.created_at),
        )}
      {/if}
    </p>
    <ul class="effects">
      <li>
        {t(
          "Accounts, sign-ins and settings go back to that time too. Anyone who signed in later has to sign in again.",
          locale,
        )}
      </li>
      <li>
        {t(
          "Phones and tablets download everything again. Changes made offline on a device that were not synced yet are lost.",
          locale,
        )}
      </li>
      <li>
        {t(
          "A safety copy of the current data is saved first, so you can undo the restore.",
          locale,
        )}
      </li>
      <li>
        {flow.preview.restarts_server
          ? t(
              "The server restarts and is unavailable for a short while.",
              locale,
            )
          : t(
              "You have to restart the server yourself to finish the restore.",
              locale,
            )}
      </li>
    </ul>
    {#if flow.preview.media_options.includes("add_missing") && (flow.phase === "preview" || flow.phase === "confirm")}
      <fieldset class="media-choice">
        <legend>{t("Media files", locale)}</legend>
        <label>
          <input
            type="radio"
            name="restore-media"
            value="none"
            checked={flow.media === "none"}
            onchange={() => dispatch({ type: "set_media", media: "none" })}
          />
          {t("Keep the current media files", locale)}
        </label>
        <label>
          <input
            type="radio"
            name="restore-media"
            value="add_missing"
            checked={flow.media === "add_missing"}
            onchange={() =>
              dispatch({
                type: "set_media",
                media: "add_missing" as MediaOption,
              })}
          />
          {t(
            "Also add media files that are missing (nothing is overwritten or deleted)",
            locale,
          )}
        </label>
      </fieldset>
    {/if}
    {#if flow.phase === "confirm" || flow.phase === "restoring"}
      {#if flow.phase === "confirm" && flow.error}
        <p class="problem" role="alert">{errorMessage(flow.error)}</p>
      {/if}
      <form
        id="restore-backup-form"
        class="confirm-form"
        onsubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <label for="restore-backup-confirmation">
          {t("Type the backup name to confirm", locale)}
        </label>
        <input
          id="restore-backup-confirmation"
          value={typed}
          oninput={(event) =>
            dispatch({ type: "typed", value: event.currentTarget.value })}
          type="text"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          disabled={flow.phase === "restoring"}
          placeholder={flow.backup.name}
        />
      </form>
    {/if}
  {:else if flow.phase === "waiting"}
    <p role="status" aria-live="polite">
      {t("Restoring… the server is restarting.", locale)}
    </p>
    <p class="note">
      {t("Keep this page open. The result appears here.", locale)}
    </p>
  {:else if flow.phase === "succeeded"}
    <p role="status">
      {t("The backup was restored and the server is running again.", locale)}
    </p>
    <p class="note">
      {t("The data from before is saved as {name}.", locale).replace(
        "{name}",
        flow.accepted.pre_restore,
      )}
    </p>
  {:else if flow.phase === "failed"}
    <p class="problem" role="alert">
      {t(
        "The server restarted but could not use this backup. Your data from before is unchanged.",
        locale,
      )}
    </p>
    {#if flow.error}<p class="note"><code>{flow.error}</code></p>{/if}
  {:else if flow.phase === "timeout"}
    <p class="problem" role="alert">
      {t("The server is taking longer than expected to come back.", locale)}
    </p>
  {:else if flow.phase === "manual_restart"}
    <p role="status">
      {t(
        "The restore is ready. Restart the server to finish it; until then it only accepts reads.",
        locale,
      )}
    </p>
  {/if}

  {#snippet actions()}
    {#if flow.phase === "loading" || flow.phase === "error"}
      <SharedButton
        variant="ghost"
        disabled={flow.phase === "loading"}
        onclick={() => dispatch({ type: "close" })}
        icon="close"
      >
        {t("Close", locale)}
      </SharedButton>
    {:else if flow.phase === "preview" || flow.phase === "reauth"}
      <SharedButton
        variant="ghost"
        onclick={() => dispatch({ type: "close" })}
        icon="cancel"
      >
        {t("Cancel", locale)}
      </SharedButton>
      <SharedButton variant="primary" onclick={proceed} icon="next">
        {t("Continue", locale)}
      </SharedButton>
    {:else if flow.phase === "confirm" || flow.phase === "restoring"}
      <SharedButton
        variant="ghost"
        disabled={flow.phase === "restoring"}
        onclick={() => dispatch({ type: "back" })}
        icon="previous"
      >
        {t("Back", locale)}
      </SharedButton>
      <SharedButton
        variant="danger"
        type="submit"
        form="restore-backup-form"
        disabled={flow.phase === "restoring" || !canRestore(flow)}
        icon="reset"
      >
        {flow.phase === "restoring"
          ? t("Preparing…", locale)
          : t("Restore now", locale)}
      </SharedButton>
    {:else if flow.phase === "timeout"}
      <SharedButton variant="secondary" onclick={checkAgain} icon="refresh">
        {t("Check again", locale)}
      </SharedButton>
      <SharedButton variant="ghost" onclick={finish} icon="close">
        {t("Close", locale)}
      </SharedButton>
    {:else if flow.phase === "succeeded"}
      <SharedButton variant="primary" onclick={finish} icon="check">
        {t("Reload", locale)}
      </SharedButton>
    {:else if flow.phase === "failed" || flow.phase === "manual_restart"}
      <SharedButton variant="primary" onclick={finish} icon="check">
        {t("Close", locale)}
      </SharedButton>
    {/if}
  {/snippet}
</ConfirmDialog>

<ReauthDialog
  bind:this={reauthDialog}
  {locale}
  {passwordEnabled}
  {passkeyAvailable}
  oidcProvider={reauthProvider}
  returnTo={RETURN_TO}
  message={t(
    "For your security, confirm it is you before a backup is restored.",
    locale,
  )}
  oncancel={() => dispatch({ type: "reauth_cancelled" })}
  onconfirmed={onReauthConfirmed}
/>

<style>
  .backup-name {
    margin: 0 0 0.6rem;
    overflow-wrap: anywhere;
  }

  .compare {
    width: 100%;
    border-collapse: collapse;
    margin: 0.6rem 0;
    font-variant-numeric: tabular-nums;
  }

  .compare th,
  .compare td {
    padding: 0.3rem 0.5rem;
    border-bottom: 1px solid var(--line);
    text-align: right;
  }

  .compare th[scope="row"],
  .compare thead th:first-child {
    text-align: left;
    font-weight: 600;
  }

  .compare tr.differs td {
    font-weight: 700;
  }

  .warning {
    margin: 0.6rem 0;
    font-weight: 650;
  }

  .effects {
    margin: 0.4rem 0 0.8rem;
    padding-left: 1.2rem;
    color: var(--ink-muted);
  }

  .effects li + li {
    margin-top: 0.25rem;
  }

  .media-choice {
    display: grid;
    gap: 0.4rem;
    margin: 0.6rem 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.6rem 0.8rem;
  }

  .media-choice legend {
    padding-inline: 0.3rem;
    font-weight: 650;
  }

  .media-choice label {
    display: flex;
    align-items: start;
    gap: 0.5rem;
  }

  .confirm-form {
    display: grid;
    gap: 0.4rem;
    margin-top: 0.8rem;
  }

  .confirm-form label {
    font-weight: 650;
  }

  .problem {
    margin: 0.8rem 0 0;
    color: var(--danger, #b3261e);
    font-weight: 650;
  }

  .note {
    margin: 0.8rem 0 0;
    color: var(--ink-muted);
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
</style>
