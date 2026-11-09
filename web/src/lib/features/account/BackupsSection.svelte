<script lang="ts">
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { onDestroy, onMount } from "svelte";
  import { resolve } from "$app/paths";
  import Button from "#lib/components/Button.svelte";
  import { fileDrop } from "#lib/components/file-drop.ts";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";
  import { apiRequest, ApiError } from "#lib/api.ts";
  import {
    archiveTypes,
    encryptedExportBody,
    exportPath,
    exportUrl,
    parseExportTicket,
    passphraseProblem,
    readArchiveType,
    RestoreError,
    restoreAccept,
    uploadRestore,
    type RestoreErrorKind,
    type RestoreResult,
    type RestoreUpload,
    writeArchiveType,
    type ArchiveType,
  } from "#lib/backups.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  let archive = $state<ArchiveType>("tar.gz");
  const href = $derived(exportUrl(archive));
  const archiveOptions = archiveTypes.map((type) => ({
    value: type,
    label: `.${type}`,
  }));

  let fileInput = $state<HTMLInputElement>();
  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let pending = $state<File | null>(null);
  let busy = $state(false);
  let percent = $state(0);
  let processing = $state(false);
  let result = $state<RestoreResult | null>(null);
  let failure = $state<RestoreErrorKind | null>(null);
  let upload: RestoreUpload | undefined;
  let destroyed = false;

  let encrypt = $state(false);
  let passphrase = $state("");
  let repeat = $state("");
  let attempted = $state(false);
  let exporting = $state(false);
  let exportFailure = $state<"running" | "failed" | null>(null);
  let exportController: AbortController | undefined;
  const problem = $derived(passphraseProblem(passphrase, repeat));
  const showProblem = $derived(
    attempted || (repeat !== "" && passphrase !== "") ? problem : undefined,
  );

  let passDialog = $state<ReturnType<typeof ConfirmDialog>>();
  let needFile = $state<File | null>(null);
  let restorePassphrase = $state("");
  let wrongPassphrase = $state(false);

  onMount(() => {
    archive = readArchiveType();
    // Old Backups links and notifications land here as /account#backups.
    if (location.hash === "#backups") {
      requestAnimationFrame(() =>
        document
          .getElementById("backups")
          ?.scrollIntoView({ block: "start", behavior: "auto" }),
      );
    }
  });

  onDestroy(() => {
    destroyed = true;
    upload?.abort();
    exportController?.abort();
  });

  async function startEncryptedExport(event: SubmitEvent) {
    event.preventDefault();
    if (exporting) return;
    attempted = true;
    if (problem) return;
    exportController?.abort();
    const controller = new AbortController();
    exportController = controller;
    exporting = true;
    exportFailure = null;
    try {
      const body = await apiRequest<unknown>(exportPath, {
        method: "POST",
        csrfToken: session.data?.csrf_token ?? "",
        body: encryptedExportBody(archive, passphrase),
        signal: controller.signal,
      });
      if (exportController !== controller || destroyed) return;
      const url = parseExportTicket(body);
      if (!url) {
        exportFailure = "failed";
        return;
      }
      passphrase = "";
      repeat = "";
      attempted = false;
      const link = document.createElement("a");
      link.href = url;
      link.download = "";
      link.hidden = true;
      document.body.append(link);
      link.click();
      link.remove();
    } catch (error) {
      if (exportController !== controller || destroyed) return;
      if (error instanceof DOMException && error.name === "AbortError") return;
      exportFailure =
        error instanceof ApiError && error.status === 429
          ? "running"
          : "failed";
    } finally {
      if (exportController === controller) {
        exportController = undefined;
        exporting = false;
      }
    }
  }

  function toggleEncrypt() {
    if (encrypt) return;
    passphrase = "";
    repeat = "";
    attempted = false;
    exportFailure = null;
  }

  function chooseFile(event: Event & { currentTarget: HTMLInputElement }) {
    const file = event.currentTarget.files?.[0] ?? null;
    event.currentTarget.value = "";
    if (file) startRestore(file);
  }

  /** Shared by the picker and by a file dropped on the restore card. */
  function startRestore(file: File) {
    if (busy) return;
    pending = file;
    failure = null;
    result = null;
    dialog?.showModal();
  }

  function cancelRestore() {
    if (busy) return;
    dialog?.close();
  }

  function clearPending() {
    if (!busy) pending = null;
  }

  function restore() {
    const file = pending;
    if (!file || busy) return;
    dialog?.close();
    void run(file);
  }

  function submitPassphrase(event: SubmitEvent) {
    event.preventDefault();
    const file = needFile;
    const secret = restorePassphrase;
    if (!file || busy || !secret) return;
    restorePassphrase = "";
    passDialog?.close();
    void run(file, secret);
  }

  function cancelPassphrase() {
    if (busy) return;
    passDialog?.close();
  }

  function clearPassphrase() {
    if (busy) return;
    needFile = null;
    restorePassphrase = "";
    wrongPassphrase = false;
  }

  async function run(file: File, secret?: string) {
    busy = true;
    percent = 0;
    processing = false;
    failure = null;
    result = null;
    needFile = null;
    wrongPassphrase = false;
    const current = uploadRestore(
      file,
      session.data?.csrf_token ?? "",
      (value, done) => {
        if (upload !== current || destroyed) return;
        percent = value;
        processing = done;
      },
      secret,
    );
    secret = undefined;
    upload = current;
    let askAgain = false;
    try {
      const outcome = await current.promise;
      if (upload !== current || destroyed) return;
      result = outcome;
    } catch (error) {
      if (upload !== current || destroyed) return;
      const kind = error instanceof RestoreError ? error.kind : "failed";
      if (kind === "passphrase_required" || kind === "wrong_passphrase") {
        needFile = file;
        wrongPassphrase = kind === "wrong_passphrase";
        askAgain = true;
      } else {
        failure = kind;
      }
    } finally {
      if (upload === current) {
        upload = undefined;
        busy = false;
        processing = false;
        pending = null;
        if (askAgain) passDialog?.showModal();
      }
    }
  }

  function chooseArchive(type: ArchiveType) {
    archive = type;
    writeArchiveType(type);
  }
</script>

<div class="backups" id="backups">
  <section class="card" aria-labelledby="export-title">
    <header class="card__header">
      <span class="card__icon" aria-hidden="true"
        ><NavIcon name="download" /></span
      >
      <h2 id="export-title">{t("Your data", locale)}</h2>
    </header>
    <p class="card__intro">
      {t(
        "Projects, yarns, categories, photos and attachments in one archive, including items shared with you.",
        locale,
      )}
    </p>
    <SegmentedControl
      options={archiveOptions}
      value={archive}
      label={t("Archive type", locale)}
      onchange={chooseArchive}
    />
    <label class="check-field">
      <input
        type="checkbox"
        bind:checked={encrypt}
        disabled={exporting}
        onchange={toggleEncrypt}
      />
      <span>{t("Encrypt with a passphrase", locale)}</span>
    </label>
    {#if encrypt}
      <form class="passphrase-form" onsubmit={startEncryptedExport}>
        <label for="export-passphrase">{t("Passphrase", locale)}</label>
        <input
          id="export-passphrase"
          type="password"
          autocomplete="new-password"
          bind:value={passphrase}
          aria-describedby={showProblem === "too_short"
            ? "export-passphrase-hint export-passphrase-error"
            : "export-passphrase-hint"}
          aria-invalid={showProblem === "too_short" ? "true" : undefined}
          disabled={exporting}
        />
        <p class="field-hint" id="export-passphrase-hint">
          {t(
            "At least 12 characters. Without it the file cannot be opened; it is not stored anywhere.",
            locale,
          )}
        </p>
        {#if showProblem === "too_short"}
          <p class="field-error" id="export-passphrase-error">
            {t("The passphrase is too short.", locale)}
          </p>
        {/if}
        <label for="export-repeat">{t("Repeat passphrase", locale)}</label>
        <input
          id="export-repeat"
          type="password"
          autocomplete="new-password"
          bind:value={repeat}
          aria-describedby={showProblem === "mismatch"
            ? "export-repeat-error"
            : undefined}
          aria-invalid={showProblem === "mismatch" ? "true" : undefined}
          disabled={exporting}
        />
        {#if showProblem === "mismatch"}
          <p class="field-error" id="export-repeat-error">
            {t("The passphrases do not match.", locale)}
          </p>
        {/if}
        <div class="download-row">
          <Button
            type="submit"
            variant="primary"
            icon="download"
            disabled={exporting}
          >
            {t("Download", locale)}
          </Button>
          <span class="format">{t("Format version 1", locale)}</span>
        </div>
      </form>
      {#if exportFailure}
        <div class="notice-error" role="alert">
          <p>
            {#if exportFailure === "running"}
              {t("A download is already running.", locale)}
            {:else}
              {t("Download failed.", locale)}
            {/if}
          </p>
        </div>
      {/if}
    {:else}
      <div class="download-row">
        <a class="ui-button ui-button--primary ui-button--md" {href} download>
          <ActionLabel icon="download">{t("Download", locale)}</ActionLabel>
        </a>
        <span class="format">{t("Format version 1", locale)}</span>
      </div>
    {/if}
  </section>

  <section
    class="card"
    aria-labelledby="restore-title"
    use:fileDrop={{
      enabled: !busy,
      accept: restoreAccept,
      label: t("Drop to restore from this file", locale),
      onFiles: (files) => startRestore(files[0]),
      onRejected: () => {
        result = null;
        failure = "invalid_archive";
      },
    }}
  >
    <header class="card__header">
      <span class="card__icon" aria-hidden="true"
        ><NavIcon name="upload" /></span
      >
      <h2 id="restore-title">{t("Restore from a file", locale)}</h2>
    </header>
    <p class="card__intro">
      {t(
        "Adds the projects, yarns and photos from a Stricknani download as new items. Nothing you already have is changed.",
        locale,
      )}
    </p>
    <input
      bind:this={fileInput}
      class="file-input"
      type="file"
      accept={restoreAccept}
      tabindex="-1"
      aria-hidden="true"
      disabled={busy}
      onchange={chooseFile}
    />
    <div class="download-row">
      <Button
        variant="primary"
        icon="upload"
        disabled={busy}
        onclick={() => fileInput?.click()}
      >
        {t("Choose file", locale)}
      </Button>
    </div>

    {#if busy}
      <div class="progress" role="status" aria-live="polite">
        {#if processing}
          <p>{t("Restoring…", locale)}</p>
          <progress aria-label={t("Restoring…", locale)}></progress>
        {:else}
          <p>{percent}%</p>
          <progress max="100" value={percent} aria-label={`${percent}%`}
          ></progress>
        {/if}
      </div>
    {/if}

    {#if failure}
      <div class="notice-error" role="alert">
        <p>
          {#if failure === "invalid_archive"}
            {t("This file is not a Stricknani download.", locale)}
          {:else if failure === "unsupported_format_version"}
            {t(
              "This download is from a newer version of Stricknani. Update the server first.",
              locale,
            )}
          {:else if failure === "payload_too_large"}
            {t("The file is too large.", locale)}
          {:else if failure === "rate_limited"}
            {t("A restore is already running.", locale)}
          {:else}
            {t("Restore failed.", locale)}
          {/if}
        </p>
        <Button
          size="sm"
          icon="refresh"
          onclick={() => {
            failure = null;
            fileInput?.click();
          }}
        >
          {t("Try again", locale)}
        </Button>
      </div>
    {/if}

    {#if result}
      <div class="result" role="status">
        <h3>{t("Restore finished", locale)}</h3>
        <dl>
          <dt>{t("Projects", locale)}</dt>
          <dd>{result.projects}</dd>
          <dt>{t("Yarns", locale)}</dt>
          <dd>{result.yarns}</dd>
          <dt>{t("Photos and files", locale)}</dt>
          <dd>{result.mediaFiles}</dd>
          {#if result.mediaSkipped > 0}
            <dt>{t("Missing in the file", locale)}</dt>
            <dd>{result.mediaSkipped}</dd>
          {/if}
        </dl>
        <div class="download-row">
          <a
            class="ui-button ui-button--secondary ui-button--md"
            href={resolve("/projects")}
          >
            <ActionLabel icon="projects">{t("Projects", locale)}</ActionLabel>
          </a>
          <a
            class="ui-button ui-button--secondary ui-button--md"
            href={resolve("/yarns")}
          >
            <ActionLabel icon="yarns">{t("Yarns", locale)}</ActionLabel>
          </a>
        </div>
      </div>
    {/if}
  </section>

  <ConfirmDialog
    bind:this={dialog}
    titleId="restore-dialog-title"
    title={t("Restore this file?", locale)}
    {busy}
    onclose={clearPending}
  >
    <p class="file-name">{pending?.name ?? ""}</p>
    <p>
      {t(
        "Everything in it is added as new items, also if you restored it before.",
        locale,
      )}
    </p>
    {#snippet actions()}
      <Button variant="ghost" icon="cancel" onclick={cancelRestore}>
        {t("Cancel", locale)}
      </Button>
      <Button variant="primary" icon="upload" disabled={busy} onclick={restore}>
        {t("Restore", locale)}
      </Button>
    {/snippet}
  </ConfirmDialog>

  <ConfirmDialog
    bind:this={passDialog}
    titleId="passphrase-dialog-title"
    title={t("This file is encrypted", locale)}
    {busy}
    onclose={clearPassphrase}
  >
    <form id="restore-passphrase-form" onsubmit={submitPassphrase}>
      <p class="file-name">{needFile?.name ?? ""}</p>
      <label for="restore-passphrase">{t("Passphrase", locale)}</label>
      <input
        id="restore-passphrase"
        type="password"
        autocomplete="off"
        bind:value={restorePassphrase}
        aria-describedby={wrongPassphrase
          ? "restore-passphrase-error"
          : undefined}
        aria-invalid={wrongPassphrase ? "true" : undefined}
      />
      {#if wrongPassphrase}
        <p class="field-error" id="restore-passphrase-error" role="alert">
          {t("That passphrase does not open this file.", locale)}
        </p>
      {/if}
    </form>
    {#snippet actions()}
      <Button variant="ghost" icon="cancel" onclick={cancelPassphrase}>
        {t("Cancel", locale)}
      </Button>
      <Button
        variant="primary"
        icon="upload"
        type="submit"
        form="restore-passphrase-form"
        disabled={busy || !restorePassphrase}
      >
        {t("Restore", locale)}
      </Button>
    {/snippet}
  </ConfirmDialog>
</div>

<style>
  .backups {
    display: grid;
    gap: 1rem;
    scroll-margin-top: 1rem;
  }

  .card {
    display: grid;
    min-width: 0;
    gap: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    padding: 1rem;
    background: var(--surface);
  }

  .card__header {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.75rem;
  }

  h2 {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 660;
    letter-spacing: -0.025em;
  }

  .card__icon {
    display: inline-flex;
    width: 2.5rem;
    height: 2.5rem;
    flex: none;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    font-size: 1.35rem;
    color: var(--tone-categories);
    background: var(--tone-categories-wash);
  }

  .card__icon :global(svg) {
    width: 1.2em;
    height: 1.2em;
  }

  .card__intro {
    margin: -0.25rem 0 0;
    color: var(--ink-muted);
    line-height: 1.5;
  }

  .card :global(.segmented) {
    justify-self: start;
  }

  .download-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.75rem;
  }

  .format {
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .check-field {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    min-height: 44px;
  }

  .check-field input {
    width: 1.15rem;
    height: 1.15rem;
    accent-color: var(--accent-strong);
  }

  .passphrase-form,
  #restore-passphrase-form {
    display: grid;
    gap: 0.5rem;
  }

  .passphrase-form label,
  #restore-passphrase-form label {
    font-weight: 650;
  }

  .passphrase-form input,
  #restore-passphrase-form input {
    width: 100%;
  }

  .field-hint,
  .field-error {
    margin: 0;
    font-size: 0.9rem;
  }

  .field-hint {
    color: var(--ink-muted);
  }

  .field-error {
    color: var(--danger);
  }

  .file-input {
    display: none;
  }

  .progress {
    display: grid;
    gap: 0.4rem;
  }

  .progress p,
  .notice-error p {
    margin: 0;
  }

  .progress progress {
    width: 100%;
  }

  .notice-error {
    display: grid;
    justify-items: start;
    gap: 0.6rem;
    border-left: 3px solid var(--danger);
    padding: 0.55rem 0.75rem;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  .result {
    display: grid;
    gap: 0.75rem;
  }

  .result h3 {
    margin: 0;
    font-size: 1.05rem;
  }

  .result dl {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 0.35rem 1rem;
    margin: 0;
  }

  .result dd {
    margin: 0;
    font-weight: 600;
    text-align: end;
  }

  .file-name {
    overflow-wrap: anywhere;
    font-weight: 600;
  }
</style>
