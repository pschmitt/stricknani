<script lang="ts">
  import { onMount, tick } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    formatLinkedDate,
    isReauthRequired,
    listIdentities,
    type ReauthRequest,
  } from "./oidc-api";
  import {
    deletePasskey,
    isCancelled,
    listPasskeys,
    PasskeyCeremonyError,
    passkeysSupported,
    registerPasskey,
    renamePasskey,
    type Passkey,
  } from "./passkey-api";
  import ReauthDialog from "./ReauthDialog.svelte";

  /**
   * "Passkeys" section of the Account page: sign in with a fingerprint, face
   * or screen lock instead of the password. Hidden while the server has
   * passkeys off, and without the Add button where the browser cannot create
   * them (existing passkeys can still be renamed and removed).
   */
  type Load = "loading" | "ready" | "error";

  let supported = $state(false);
  let passkeys = $state<Passkey[]>([]);
  let limit = $state(10);
  let load = $state<Load>("loading");
  let loadError = $state("");
  let actionError = $state("");
  let busy = $state(false);
  let passwordEnabled = $state(true);
  let renamingId = $state<number | null>(null);
  let renameValue = $state("");
  let removeTarget = $state<Passkey | null>(null);
  let pendingRemove: Passkey | null = null;
  let renameInput = $state<HTMLInputElement | null>(null);
  let removeDialog = $state<ReturnType<typeof ConfirmDialog>>();
  let reauthDialog = $state<ReturnType<typeof ReauthDialog>>();
  let generation = 0;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const enabled = $derived(session.data?.capabilities.passkeys === true);
  const visible = $derived(
    enabled && (supported || (load === "ready" && passkeys.length > 0)),
  );
  const canAdd = $derived(supported && passkeys.length < limit);

  async function refresh(): Promise<void> {
    const request = ++generation;
    try {
      const result = await listPasskeys();
      if (request !== generation) return;
      passkeys = result.passkeys;
      limit = result.limit;
      load = "ready";
      loadError = "";
    } catch (error) {
      if (request !== generation) return;
      load = "error";
      loadError = describe(error);
    }
  }

  onMount(() => {
    supported = passkeysSupported();
    // The step-up dialog only offers a password when the account has one.
    void listIdentities()
      .then((result) => (passwordEnabled = result.passwordEnabled))
      .catch(() => undefined);
    void refresh();
    return () => {
      generation += 1;
    };
  });

  function describe(error: unknown): string {
    if (error instanceof PasskeyCeremonyError) {
      switch (error.failure) {
        case "already_registered":
          return t(
            "This device already has a passkey for your account.",
            locale,
          );
        case "cancelled":
          return t("Adding the passkey was cancelled.", locale);
        case "unsupported":
        case "unavailable":
          return t("This browser cannot create a passkey here.", locale);
        default:
          return t("The passkey could not be created. Try again.", locale);
      }
    }
    if (error instanceof ApiError) {
      if (error.code === "passkey_limit")
        return t(
          "You can have up to {count} passkeys. Remove one to add another.",
          locale,
        ).replaceAll("{count}", String(limit));
      if (error.code === "passkey_rejected" || error.code === "passkey_exists")
        return t("The passkey could not be created. Try again.", locale);
      return error.message;
    }
    return t("The request could not be completed.", locale);
  }

  const csrf = (): string => session.data?.csrf_token ?? "";

  function defaultName(): string {
    return t("Passkey {number}", locale).replaceAll(
      "{number}",
      String(passkeys.length + 1),
    );
  }

  /** Run an action once, asking for fresh proof when the server wants it. */
  async function withReauth(
    request: ReauthRequest,
    run: () => Promise<void>,
  ): Promise<void> {
    actionError = "";
    busy = true;
    try {
      await run();
    } catch (error) {
      busy = false;
      if (isReauthRequired(error)) {
        reauthDialog?.open(request);
        return;
      }
      // A dismissed system prompt is not an error worth shouting about.
      if (!isCancelled(error)) actionError = describe(error);
    } finally {
      busy = false;
    }
  }

  async function add(): Promise<void> {
    pendingRemove = null;
    await withReauth({ action: "manage_passkeys" }, async () => {
      const created = await registerPasskey(defaultName(), csrf());
      passkeys = [...passkeys, created];
      if (userId !== null)
        publishFeedback({
          ownerId: userId,
          kind: "success",
          message: t("Passkey added. You can rename it now.", locale),
        });
      startRename(created);
    });
  }

  function startRename(passkey: Passkey): void {
    renamingId = passkey.id;
    renameValue = passkey.nickname;
    void tick().then(() => renameInput?.select());
  }

  async function saveRename(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const id = renamingId;
    const name = renameValue.trim();
    if (id === null || busy) return;
    if (!name) {
      actionError = t("Give the passkey a name.", locale);
      return;
    }
    actionError = "";
    busy = true;
    try {
      const updated = await renamePasskey(id, name, csrf());
      passkeys = passkeys.map((item) => (item.id === id ? updated : item));
      renamingId = null;
    } catch (error) {
      actionError = describe(error);
    } finally {
      busy = false;
    }
  }

  function askRemove(passkey: Passkey): void {
    actionError = "";
    removeTarget = passkey;
    removeDialog?.showModal();
  }

  async function runRemove(passkey: Passkey): Promise<void> {
    pendingRemove = passkey;
    await withReauth({ action: "manage_passkeys" }, async () => {
      await deletePasskey(passkey.id, csrf());
      pendingRemove = null;
      passkeys = passkeys.filter((item) => item.id !== passkey.id);
      if (userId !== null)
        publishFeedback({
          ownerId: userId,
          kind: "deleted",
          message: t("Passkey removed.", locale),
        });
    });
  }

  async function confirmRemove(): Promise<void> {
    const target = removeTarget;
    removeDialog?.close();
    if (target) await runRemove(target);
  }

  /** The server stamped the session: retry what triggered the dialog. */
  async function afterReauth(): Promise<void> {
    if (pendingRemove) return runRemove(pendingRemove);
    return add();
  }

  function usage(passkey: Passkey): string {
    return passkey.last_used_at
      ? t("Last used {date}", locale).replaceAll(
          "{date}",
          formatLinkedDate(passkey.last_used_at, locale),
        )
      : t("Never used to sign in", locale);
  }
</script>

{#if visible}
  <section class="passkeys" aria-labelledby="passkeys-title">
    <header class="section-heading">
      <div>
        <h3 id="passkeys-title">{t("Passkeys", locale)}</h3>
        <p>
          {t(
            "Sign in with your fingerprint, face or screen lock instead of typing your password. A passkey stays on your devices and cannot be phished.",
            locale,
          )}
        </p>
      </div>
    </header>

    {#if actionError}
      <p class="notice-error" role="alert">{actionError}</p>
    {/if}

    {#if load === "loading"}
      <p class="muted">{t("Loading passkeys…", locale)}</p>
    {:else if load === "error"}
      <p class="notice-error" role="alert">{loadError}</p>
      <SharedButton
        variant="ghost"
        onclick={() => void refresh()}
        icon="refresh"
      >
        {t("Try again", locale)}
      </SharedButton>
    {:else}
      {#if passkeys.length === 0}
        <p class="muted">{t("No passkeys yet.", locale)}</p>
      {:else}
        <ul class="passkey-list">
          {#each passkeys as passkey (passkey.id)}
            <li class="passkey-row">
              {#if renamingId === passkey.id}
                <form class="rename-form" onsubmit={saveRename}>
                  <label class="sr-only" for={`passkey-name-${passkey.id}`}>
                    {t("Passkey name", locale)}
                  </label>
                  <input
                    id={`passkey-name-${passkey.id}`}
                    bind:this={renameInput}
                    bind:value={renameValue}
                    maxlength="100"
                    autocomplete="off"
                    disabled={busy}
                  />
                  <SharedButton
                    variant="primary"
                    size="sm"
                    type="submit"
                    disabled={busy}
                    icon="save"
                  >
                    {t("Save", locale)}
                  </SharedButton>
                  <SharedButton
                    variant="ghost"
                    size="sm"
                    type="button"
                    disabled={busy}
                    onclick={() => (renamingId = null)}
                    icon="cancel"
                  >
                    {t("Cancel", locale)}
                  </SharedButton>
                </form>
              {:else}
                <div class="passkey-copy">
                  <strong>{passkey.nickname}</strong>
                  <span class="muted">
                    {t("Added {date}", locale).replaceAll(
                      "{date}",
                      formatLinkedDate(passkey.created_at, locale),
                    )}
                    · {usage(passkey)}
                  </span>
                  <span class="muted">
                    {passkey.backup_state
                      ? t("Synced across your devices", locale)
                      : t("Stored on one device", locale)}
                  </span>
                </div>
                <div class="passkey-actions">
                  <SharedButton
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onclick={() => startRename(passkey)}
                    icon="key"
                  >
                    {t("Rename", locale)}
                  </SharedButton>
                  <SharedButton
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onclick={() => askRemove(passkey)}
                    icon="action-remove"
                  >
                    {t("Remove", locale)}
                  </SharedButton>
                </div>
              {/if}
            </li>
          {/each}
        </ul>
      {/if}

      {#if canAdd}
        <div>
          <SharedButton
            variant="secondary"
            disabled={busy}
            onclick={() => void add()}
            icon="key"
          >
            {busy
              ? t("Waiting for your passkey…", locale)
              : t("Add a passkey", locale)}
          </SharedButton>
        </div>
      {:else if supported}
        <p class="muted">
          {t(
            "You can have up to {count} passkeys. Remove one to add another.",
            locale,
          ).replaceAll("{count}", String(limit))}
        </p>
      {:else}
        <p class="muted">
          {t(
            "This browser cannot create passkeys. Open Stricknani in a current browser to add one.",
            locale,
          )}
        </p>
      {/if}
    {/if}
  </section>

  <ConfirmDialog
    bind:this={removeDialog}
    titleId="remove-passkey-title"
    title={removeTarget
      ? t("Remove {name}?", locale).replaceAll("{name}", removeTarget.nickname)
      : t("Remove this passkey?", locale)}
    onclose={() => (removeTarget = null)}
  >
    <p>
      {t(
        "You will no longer be able to sign in with this passkey. You can add it again later. Your password keeps working.",
        locale,
      )}
    </p>
    {#snippet actions()}
      <SharedButton
        variant="ghost"
        onclick={() => removeDialog?.close()}
        icon="cancel"
      >
        {t("Keep passkey", locale)}
      </SharedButton>
      <SharedButton
        variant="danger"
        onclick={() => void confirmRemove()}
        icon="action-remove"
      >
        {t("Remove", locale)}
      </SharedButton>
    {/snippet}
  </ConfirmDialog>

  <ReauthDialog
    bind:this={reauthDialog}
    {locale}
    {passwordEnabled}
    passkeyAvailable={supported && passkeys.length > 0}
    message={t(
      "For your security, confirm your identity before changing your passkeys.",
      locale,
    )}
    onconfirmed={afterReauth}
  />
{/if}

<style>
  .passkeys {
    display: grid;
    min-width: 0;
    gap: 0.9rem;
  }

  .section-heading h3 {
    margin: 0;
    font-family: var(--font-body);
    font-size: 1rem;
    font-weight: 700;
    letter-spacing: 0;
  }

  .section-heading p {
    margin: 0.2rem 0 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
    line-height: 1.45;
  }

  .muted {
    margin: 0;
    color: var(--ink-muted);
  }

  .notice-error {
    margin: 0;
    color: var(--danger);
    font-weight: 650;
  }

  .passkey-list {
    display: grid;
    gap: 0.6rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .passkey-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.8rem 1rem;
    background: var(--surface);
  }

  .passkey-copy {
    display: grid;
    min-width: 0;
    gap: 0.15rem;
  }

  .passkey-copy strong {
    overflow-wrap: anywhere;
  }

  .passkey-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.25rem;
  }

  .rename-form {
    display: flex;
    flex: 1;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
  }

  .rename-form input {
    flex: 1 1 12rem;
    min-width: 0;
  }

  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
</style>
