<script lang="ts">
  import SharedButton from "#lib/components/Button.svelte";
  import { beforeNavigate, goto } from "$app/navigation";
  import { page } from "$app/state";
  import { onDestroy, onMount, tick } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import AdminTabs from "./AdminTabs.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import AdminUserIdentities from "#lib/features/auth/AdminUserIdentities.svelte";
  import {
    getAuthOptions,
    type OidcProvider,
  } from "#lib/features/auth/oidc-api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { refreshSession, session } from "#lib/session.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import {
    createAdminUser,
    listAdminUsers,
    resetUserPassword,
    setUserActive,
    type AdminUser,
  } from "./admin-api";

  type LoadState = "loading" | "ready" | "error";
  type Operation = "create" | "active" | "reset" | null;

  let users = $state<AdminUser[]>([]);
  let pageMeta = $state({ page: 1, per_page: 50, has_more: false });
  let loadState = $state<LoadState>("loading");
  let loadError = $state("");
  let pageStatus = $state("");
  let reload = $state(0);
  let operation = $state<Operation>(null);
  let operationGeneration = 0;
  let loadGeneration = 0;
  let ownerGeneration = 0;
  let currentOwner: number | null | undefined;
  let createDialog = $state<HTMLDialogElement | null>(null);
  let activeDialog = $state<HTMLDialogElement | null>(null);
  let resetDialog = $state<HTMLDialogElement | null>(null);
  let createOpener: HTMLButtonElement | null = null;
  let activeOpener: HTMLButtonElement | null = null;
  let resetOpener: HTMLButtonElement | null = null;
  let createEmailInput = $state<HTMLInputElement | null>(null);
  let resetPasswordInput = $state<HTMLInputElement | null>(null);
  let createEmail = $state("");
  let createPassword = $state("");
  let createIsAdmin = $state(false);
  let resetPasswordValue = $state("");
  let createError = $state("");
  let createEmailError = $state("");
  let createPasswordError = $state("");
  let activeError = $state("");
  let resetError = $state("");
  let resetPasswordError = $state("");
  let activeTarget = $state<AdminUser | null>(null);
  let activeNext = $state(false);
  let resetTarget = $state<AdminUser | null>(null);
  let oidcProviders = $state<OidcProvider[]>([]);

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const requestedPage = $derived(readPage(page.url.searchParams.get("page")));

  function readPage(value: string | null): number {
    const parsed = Number(value ?? "1");
    return Number.isSafeInteger(parsed) && parsed >= 1 && parsed <= 10000
      ? parsed
      : 1;
  }

  function hasPasswordDraft(): boolean {
    return createPassword.length > 0 || resetPasswordValue.length > 0;
  }

  function guardUnload(event: BeforeUnloadEvent) {
    if (!hasPasswordDraft()) return;
    event.preventDefault();
    event.returnValue = "";
  }

  onMount(() => {
    const providerRequest = new AbortController();
    void getAuthOptions(providerRequest.signal)
      .then((options) => (oidcProviders = options.oidc))
      .catch(() => undefined);
    window.addEventListener("beforeunload", guardUnload);
    const unregisterUpdateGuard = registerPwaUpdateGuard(() => ({
      dirty:
        hasPasswordDraft() ||
        createEmail.trim().length > 0 ||
        createIsAdmin ||
        activeTarget !== null ||
        resetTarget !== null,
      busy:
        operation !== null ||
        loadState === "loading" ||
        Boolean(createDialog?.open || activeDialog?.open || resetDialog?.open),
    }));
    return () => {
      providerRequest.abort();
      unregisterUpdateGuard();
      window.removeEventListener("beforeunload", guardUnload);
    };
  });

  beforeNavigate(({ cancel }) => {
    if (
      hasPasswordDraft() &&
      typeof window !== "undefined" &&
      !window.confirm(
        t("Leave administrator settings without saving the password?", locale),
      )
    )
      cancel();
  });

  $effect(() => {
    const ownerId = userId;
    const requested = requestedPage;
    reload;
    if (currentOwner !== ownerId) {
      currentOwner = ownerId;
      ownerGeneration += 1;
      operationGeneration += 1;
      operation = null;
      pageStatus = "";
      createPassword = "";
      resetPasswordValue = "";
      createDialog?.close();
      activeDialog?.close();
      resetDialog?.close();
      activeTarget = null;
      resetTarget = null;
    }
    const ownerRequest = ownerGeneration;
    const request = ++loadGeneration;
    const controller = new AbortController();
    users = [];
    loadError = "";
    if (ownerId === null || session.data?.user?.is_admin !== true) {
      loadState = "error";
      loadError = t("Administrator access is required.", locale);
      return () => controller.abort();
    }
    loadState = "loading";
    void listAdminUsers(requested, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          request !== loadGeneration ||
          ownerRequest !== ownerGeneration ||
          userId !== ownerId
        )
          return;
        users = result.data;
        pageMeta = result.meta;
        loadState = "ready";
        if (result.meta.page !== requested) void setPage(result.meta.page);
      })
      .catch((error: unknown) => {
        if (
          controller.signal.aborted ||
          request !== loadGeneration ||
          ownerRequest !== ownerGeneration ||
          userId !== ownerId
        )
          return;
        users = [];
        loadState = "error";
        loadError =
          error instanceof ApiError && error.status === 403
            ? t("Administrator access is required.", locale)
            : errorMessage(
                error,
                t("User accounts could not be loaded.", locale),
              );
      });
    return () => controller.abort();
  });

  onDestroy(() => {
    loadGeneration += 1;
    operationGeneration += 1;
    ownerGeneration += 1;
    createPassword = "";
    resetPasswordValue = "";
  });

  function errorMessage(error: unknown, fallback: string): string {
    if (error instanceof ApiError) {
      const known: Record<string, string> = {
        admin_required: t("Administrator access is required.", locale),
        invalid_email: t("Enter a valid email address.", locale),
        invalid_password: t(
          "Use a password with at least 12 characters.",
          locale,
        ),
        password_too_long: t("The password is too long.", locale),
        common_password: t("Choose a less common password.", locale),
        email_in_use: t("An account with that email already exists.", locale),
        admin_conflict: t(
          "This change would leave the account system without an active administrator.",
          locale,
        ),
        not_found: t("That user could not be found.", locale),
        invalid_query: t("Choose a valid page number.", locale),
        invalid_json: t(
          "The request could not be read. Refresh and try again.",
          locale,
        ),
        payload_too_large: t(
          "The request is larger than the allowed size.",
          locale,
        ),
      };
      return known[error.code] ?? error.message ?? fallback;
    }
    return error instanceof Error ? error.message : fallback;
  }

  function denyAdminAccess() {
    users = [];
    loadState = "error";
    loadError = t("Administrator access is required.", locale);
    activeDialog?.close();
    resetDialog?.close();
    createDialog?.close();
    createPassword = "";
    resetPasswordValue = "";
    activeTarget = null;
    resetTarget = null;
  }

  function hasCurrentOperation(
    operationId: number,
    ownerId: number,
    expected: Exclude<Operation, null>,
  ): boolean {
    return (
      operationGeneration === operationId &&
      userId === ownerId &&
      operation === expected
    );
  }

  function openCreate(opener: HTMLButtonElement) {
    createOpener = opener;
    createEmail = "";
    createPassword = "";
    createIsAdmin = false;
    clearCreateErrors();
    createDialog?.showModal();
    void tick().then(() => createEmailInput?.focus());
  }

  function clearCreateErrors() {
    createError = "";
    createEmailError = "";
    createPasswordError = "";
  }

  function closeDialog(
    dialog: HTMLDialogElement | null,
    passwordDraft: string,
  ) {
    if (operation !== null) return;
    if (
      passwordDraft &&
      !window.confirm(t("Discard this password draft?", locale))
    )
      return;
    if (dialog === createDialog) {
      createPassword = "";
      createEmail = "";
    } else if (dialog === resetDialog) {
      resetPasswordValue = "";
      resetTarget = null;
    }
    dialog?.close();
  }

  function onCreateCancel(event: Event) {
    if (operation !== null) {
      event.preventDefault();
      return;
    }
    if (!createPassword) {
      createEmail = "";
      return;
    }
    event.preventDefault();
    if (window.confirm(t("Discard this password draft?", locale))) {
      createPassword = "";
      createEmail = "";
      createDialog?.close();
    }
  }

  function onActiveCancel(event: Event) {
    if (operation !== null) {
      event.preventDefault();
      return;
    }
    activeTarget = null;
  }

  function onResetCancel(event: Event) {
    if (operation !== null) {
      event.preventDefault();
      return;
    }
    if (!resetPasswordValue) {
      resetTarget = null;
      return;
    }
    event.preventDefault();
    if (window.confirm(t("Discard this password draft?", locale))) {
      resetPasswordValue = "";
      resetTarget = null;
      resetDialog?.close();
    }
  }

  async function createUser(event: SubmitEvent) {
    event.preventDefault();
    const activeSession = session.data;
    const ownerId = activeSession?.user?.id;
    if (!activeSession || ownerId === undefined || operation !== null) return;
    clearCreateErrors();
    if (!createEmail.trim()) {
      createEmailError = t("Enter an email address.", locale);
      return;
    }
    if (!createPassword) {
      createPasswordError = t("Enter a temporary password.", locale);
      return;
    }
    const operationId = ++operationGeneration;
    const ownerRequest = ownerGeneration;
    operation = "create";
    pageStatus = "";
    try {
      await createAdminUser(
        createEmail.trim(),
        createPassword,
        createIsAdmin,
        activeSession,
      );
      if (
        !hasCurrentOperation(operationId, ownerId, "create") ||
        ownerRequest !== ownerGeneration
      )
        return;
      createPassword = "";
      createEmail = "";
      createIsAdmin = false;
      createDialog?.close();
      if (requestedPage !== 1) await setPage(1);
      else reload += 1;
      pageStatus = t("User account created.", locale);
      await tick();
      createOpener?.focus();
    } catch (error) {
      if (
        !hasCurrentOperation(operationId, ownerId, "create") ||
        ownerRequest !== ownerGeneration
      )
        return;
      if (error instanceof ApiError && error.status === 403) {
        denyAdminAccess();
        return;
      }
      if (error instanceof ApiError && error.fields?.email)
        createEmailError = errorMessage(
          error,
          t("Enter a valid email address.", locale),
        );
      else if (error instanceof ApiError && error.fields?.password)
        createPasswordError = errorMessage(
          error,
          t("Choose a different password.", locale),
        );
      else
        createError = errorMessage(
          error,
          t("The user account could not be created.", locale),
        );
    } finally {
      if (
        hasCurrentOperation(operationId, ownerId, "create") &&
        ownerRequest === ownerGeneration
      )
        operation = null;
    }
  }

  function askActive(
    user: AdminUser,
    next: boolean,
    opener: HTMLButtonElement,
  ) {
    activeTarget = user;
    activeNext = next;
    activeOpener = opener;
    activeError = "";
    activeDialog?.showModal();
  }

  async function updateActive() {
    const target = activeTarget;
    const activeSession = session.data;
    const ownerId = activeSession?.user?.id;
    if (
      !target ||
      !activeSession ||
      ownerId === undefined ||
      operation !== null
    )
      return;
    const targetId = target.id;
    const next = activeNext;
    const operationId = ++operationGeneration;
    const ownerRequest = ownerGeneration;
    operation = "active";
    activeError = "";
    pageStatus = "";
    try {
      const updated = await setUserActive(targetId, next, activeSession);
      if (
        !hasCurrentOperation(operationId, ownerId, "active") ||
        ownerRequest !== ownerGeneration ||
        activeTarget?.id !== targetId
      )
        return;
      users = users.map((user) => (user.id === targetId ? updated : user));
      activeDialog?.close();
      activeTarget = null;
      pageStatus = next
        ? t("User account enabled.", locale)
        : t("User account disabled.", locale);
      await tick();
      activeOpener?.focus();
    } catch (error) {
      if (
        !hasCurrentOperation(operationId, ownerId, "active") ||
        ownerRequest !== ownerGeneration ||
        activeTarget?.id !== targetId
      )
        return;
      if (error instanceof ApiError && error.status === 403) {
        denyAdminAccess();
        return;
      }
      activeError = errorMessage(
        error,
        t("The account status could not be changed.", locale),
      );
    } finally {
      if (
        hasCurrentOperation(operationId, ownerId, "active") &&
        ownerRequest === ownerGeneration
      )
        operation = null;
    }
  }

  function askReset(user: AdminUser, opener: HTMLButtonElement) {
    resetTarget = user;
    resetOpener = opener;
    resetPasswordValue = "";
    resetError = "";
    resetPasswordError = "";
    resetDialog?.showModal();
    void tick().then(() => resetPasswordInput?.focus());
  }

  async function resetPassword() {
    const target = resetTarget;
    const activeSession = session.data;
    const ownerId = activeSession?.user?.id;
    if (
      !target ||
      !activeSession ||
      ownerId === undefined ||
      operation !== null
    )
      return;
    resetError = "";
    resetPasswordError = "";
    if (!resetPasswordValue) {
      resetPasswordError = t("Enter a temporary password.", locale);
      return;
    }
    const targetId = target.id;
    const password = resetPasswordValue;
    const operationId = ++operationGeneration;
    const ownerRequest = ownerGeneration;
    operation = "reset";
    pageStatus = "";
    try {
      await resetUserPassword(targetId, password, activeSession);
      if (
        !hasCurrentOperation(operationId, ownerId, "reset") ||
        ownerRequest !== ownerGeneration ||
        resetTarget?.id !== targetId
      )
        return;
      resetPasswordValue = "";
      resetDialog?.close();
      resetTarget = null;
      pageStatus = t("Password reset. The user must sign in again.", locale);
      if (targetId === ownerId) {
        await refreshSession();
        return;
      }
      await tick();
      resetOpener?.focus();
    } catch (error) {
      if (
        !hasCurrentOperation(operationId, ownerId, "reset") ||
        ownerRequest !== ownerGeneration ||
        resetTarget?.id !== targetId
      )
        return;
      if (error instanceof ApiError && error.status === 403) {
        denyAdminAccess();
        return;
      }
      if (error instanceof ApiError && error.fields?.new_password)
        resetPasswordError = errorMessage(
          error,
          t("Choose a different password.", locale),
        );
      else
        resetError = errorMessage(
          error,
          t("The password could not be reset.", locale),
        );
    } finally {
      if (
        hasCurrentOperation(operationId, ownerId, "reset") &&
        ownerRequest === ownerGeneration
      )
        operation = null;
    }
  }

  async function setPage(nextPage: number) {
    pageStatus = "";
    const params = new URLSearchParams(page.url.search);
    if (nextPage <= 1) params.delete("page");
    else params.set("page", String(nextPage));
    const query = params.toString();
    await goto(`${page.url.pathname}${query ? `?${query}` : ""}`, {
      replaceState: true,
    });
  }
</script>

<svelte:head><title>Stricknani · {t("Admin", locale)}</title></svelte:head>

<section
  class="admin-page page-container page-container--wide"
  aria-labelledby="users-title"
>
  <div class="admin-heading">
    <PageHeader title={t("Admin", locale)} titleId="users-title">
      {#snippet actions()}
        <SharedButton
          variant="primary"
          type="button"
          onclick={(event) => openCreate(event.currentTarget)}
          disabled={loadState !== "ready" || operation !== null}
          icon="user-add"
        >
          {t("Create user", locale)}
        </SharedButton>
      {/snippet}
    </PageHeader>
  </div>
  <AdminTabs active="users" {locale} />

  {#if pageStatus}<p class="notice notice-success" role="status">
      {pageStatus}
    </p>{/if}
  {#if loadState === "loading"}
    <p class="notice-panel" aria-live="polite">
      {t("Loading user accounts…", locale)}
    </p>
  {:else if loadState === "error"}
    <section
      class="notice-panel"
      role="alert"
      aria-labelledby="users-error-title"
    >
      <h2 id="users-error-title">{loadError}</h2>
      <SharedButton
        variant="secondary"
        type="button"
        onclick={() => (reload += 1)}
        icon="refresh"
      >
        {t("Try again", locale)}
      </SharedButton>
    </section>
  {:else}
    <div class="user-ledger" aria-label={t("User accounts", locale)}>
      <div class="ledger-head" aria-hidden="true">
        <span>{t("Email", locale)}</span><span>{t("Role", locale)}</span>
        <span>{t("Status", locale)}</span><span>{t("Actions", locale)}</span>
      </div>
      {#each users as user (user.id)}
        <article
          class="user-row"
          data-user-id={user.id}
          aria-label={`${user.email}, ${user.is_admin ? t("Administrator", locale) : t("Member", locale)}, ${user.active ? t("Active", locale) : t("Disabled", locale)}`}
        >
          <div class="user-identity">
            <UserAvatar avatarUrl={user.avatar_url} name={user.email} />
            <strong class="user-email" title={user.email}>{user.email}</strong>
            {#if user.id === userId}<span class="you-marker"
                >{t("You", locale)}</span
              >{/if}
          </div>
          <span class="role-value" class:admin={user.is_admin}
            ><span class="cell-label">{t("Role", locale)}:</span>
            {user.is_admin
              ? t("Administrator", locale)
              : t("Member", locale)}</span
          >
          <span class:inactive={!user.active} class="status-pill">
            <span class="cell-label">{t("Status", locale)}:</span>
            {user.active ? t("Active", locale) : t("Disabled", locale)}
          </span>
          <div class="row-actions">
            {#if user.id !== userId}
              <span class="action-slot">
                <SharedButton
                  variant="secondary"
                  size="sm"
                  class={user.active ? "user-action--danger" : ""}
                  type="button"
                  disabled={operation !== null}
                  onclick={(event) =>
                    askActive(user, !user.active, event.currentTarget)}
                  icon={user.active ? "user-disable" : "user-enable"}
                >
                  {user.active ? t("Disable", locale) : t("Enable", locale)}
                </SharedButton>
              </span>
            {:else}
              <span class="action-slot action-slot--empty" aria-hidden="true"
              ></span>
            {/if}
            <SharedButton
              variant="ghost"
              size="sm"
              type="button"
              disabled={operation !== null}
              onclick={(event) => askReset(user, event.currentTarget)}
              icon="key"
            >
              {t("Reset password", locale)}
            </SharedButton>
            {#if oidcProviders.length > 0 && (user.linked_identities === undefined || user.linked_identities > 0)}
              <AdminUserIdentities
                {user}
                providers={oidcProviders}
                disabled={operation !== null}
              />
            {:else}
              <span class="action-slot action-slot--empty" aria-hidden="true"
              ></span>
            {/if}
          </div>
        </article>
      {:else}
        <p class="empty-ledger">{t("No user accounts were found.", locale)}</p>
      {/each}
    </div>

    {#if pageMeta.page > 1 || pageMeta.has_more}<nav
        class="page-controls"
        aria-label={t("User account pages", locale)}
      >
        <SharedButton
          variant="secondary"
          type="button"
          disabled={pageMeta.page <= 1 || operation !== null}
          onclick={() => void setPage(pageMeta.page - 1)}
          icon="previous"
        >
          {t("Previous page", locale)}
        </SharedButton>
        <span>{t("Page", locale)} {pageMeta.page}</span>
        <SharedButton
          variant="secondary"
          type="button"
          disabled={!pageMeta.has_more || operation !== null}
          onclick={() => void setPage(pageMeta.page + 1)}
        >
          <span class="button-content">
            <span>{t("Next page", locale)}</span>
            <NavIcon name="next" />
          </span>
        </SharedButton>
      </nav>{/if}
  {/if}
</section>

<dialog
  bind:this={createDialog}
  aria-labelledby="create-user-title"
  oncancel={onCreateCancel}
  onclose={() => {
    void tick().then(() => createOpener?.focus());
  }}
>
  <form class="admin-dialog" onsubmit={createUser}>
    <h2 id="create-user-title">{t("Create a user account", locale)}</h2>
    <p>
      {t(
        "New accounts are members unless you choose administrator access.",
        locale,
      )}
    </p>
    {#if createError}<p class="notice notice-error" role="alert">
        {createError}
      </p>{/if}
    <label for="admin-create-email">{t("Email address", locale)}</label>
    <input
      id="admin-create-email"
      bind:this={createEmailInput}
      bind:value={createEmail}
      type="email"
      autocomplete="off"
      required
      aria-invalid={createEmailError ? "true" : undefined}
      aria-describedby={createEmailError
        ? "admin-create-email-error"
        : undefined}
    />
    {#if createEmailError}<p class="field-error" id="admin-create-email-error">
        {createEmailError}
      </p>{/if}
    <label for="admin-create-password">{t("Temporary password", locale)}</label>
    <input
      id="admin-create-password"
      bind:value={createPassword}
      type="password"
      autocomplete="new-password"
      required
      aria-invalid={createPasswordError ? "true" : undefined}
      aria-describedby={createPasswordError
        ? "admin-create-password-error"
        : undefined}
    />
    {#if createPasswordError}<p
        class="field-error"
        id="admin-create-password-error"
      >
        {createPasswordError}
      </p>{/if}
    <label class="check-field"
      ><input bind:checked={createIsAdmin} type="checkbox" />
      <span>{t("Administrator access", locale)}</span></label
    >
    <div class="dialog-actions">
      <SharedButton
        variant="secondary"
        type="button"
        disabled={operation !== null}
        onclick={() => closeDialog(createDialog, createPassword)}
        icon="cancel">{t("Cancel", locale)}</SharedButton
      >
      <SharedButton
        variant="primary"
        type="submit"
        disabled={operation !== null}
        icon="user-add"
      >
        {operation === "create"
          ? t("Creating…", locale)
          : t("Create user", locale)}
      </SharedButton>
    </div>
  </form>
</dialog>

<dialog
  bind:this={activeDialog}
  aria-labelledby="active-title"
  oncancel={onActiveCancel}
  onclose={() => {
    void tick().then(() => activeOpener?.focus());
  }}
>
  <section class="admin-dialog">
    <h2 id="active-title">
      {activeNext
        ? t("Enable this account?", locale)
        : t("Disable this account?", locale)}
    </h2>
    {#if activeTarget}
      <div class="target-identity">
        <UserAvatar
          avatarUrl={activeTarget.avatar_url}
          name={activeTarget.email}
          size="large"
        />
        <strong>{activeTarget.email}</strong>
      </div>
    {/if}
    <p>
      {activeNext
        ? t("The user will be able to sign in again.", locale)
        : t(
            "The user will be signed out and will not be able to sign in.",
            locale,
          )}
    </p>
    {#if activeError}<p class="notice notice-error" role="alert">
        {activeError}
      </p>{/if}
    <div class="dialog-actions">
      <SharedButton
        variant="secondary"
        type="button"
        disabled={operation !== null}
        onclick={() => {
          activeTarget = null;
          activeDialog?.close();
        }}
        icon="cancel">{t("Cancel", locale)}</SharedButton
      >
      <SharedButton
        variant={activeNext ? "primary" : "danger"}
        type="button"
        disabled={operation !== null}
        onclick={() => void updateActive()}
        icon={activeNext ? "user-enable" : "user-disable"}
      >
        {operation === "active"
          ? t("Saving…", locale)
          : activeNext
            ? t("Enable account", locale)
            : t("Disable account", locale)}
      </SharedButton>
    </div>
  </section>
</dialog>

<dialog
  bind:this={resetDialog}
  aria-labelledby="reset-password-title"
  oncancel={onResetCancel}
  onclose={() => {
    void tick().then(() => resetOpener?.focus());
  }}
>
  <form
    class="admin-dialog"
    onsubmit={(event) => {
      event.preventDefault();
      void resetPassword();
    }}
  >
    <h2 id="reset-password-title">{t("Reset this password?", locale)}</h2>
    {#if resetTarget}
      <div class="target-identity">
        <UserAvatar
          avatarUrl={resetTarget.avatar_url}
          name={resetTarget.email}
          size="large"
        />
        <strong>{resetTarget.email}</strong>
      </div>
    {/if}
    <p>{t("The user will need to sign in with the new password.", locale)}</p>
    {#if resetError}<p class="notice notice-error" role="alert">
        {resetError}
      </p>{/if}
    <label for="admin-reset-password"
      >{t("New temporary password", locale)}</label
    >
    <input
      id="admin-reset-password"
      bind:this={resetPasswordInput}
      bind:value={resetPasswordValue}
      type="password"
      autocomplete="new-password"
      required
      aria-invalid={resetPasswordError ? "true" : undefined}
      aria-describedby={resetPasswordError
        ? "admin-reset-password-error"
        : undefined}
    />
    {#if resetPasswordError}<p
        class="field-error"
        id="admin-reset-password-error"
      >
        {resetPasswordError}
      </p>{/if}
    <div class="dialog-actions">
      <SharedButton
        variant="secondary"
        type="button"
        disabled={operation !== null}
        onclick={() => closeDialog(resetDialog, resetPasswordValue)}
        icon="cancel">{t("Cancel", locale)}</SharedButton
      >
      <SharedButton
        variant="danger"
        type="submit"
        disabled={operation !== null}
        icon="key"
      >
        {operation === "reset"
          ? t("Resetting…", locale)
          : t("Reset password", locale)}
      </SharedButton>
    </div>
  </form>
</dialog>

<style>
  .admin-heading {
    margin-bottom: 1.35rem;
  }
  .user-ledger {
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
  }
  .ledger-head,
  .user-row {
    display: grid;
    /* Fixed tracks (no content-sized ones): every row is its own grid, so any auto or
       max-content track would differ per row and misalign the columns. */
    grid-template-columns: minmax(0, 1fr) 9rem 6.5rem 24rem;
    align-items: center;
    gap: 0.8rem;
    padding: 0.6rem 0.75rem 0.6rem 1rem;
  }
  .user-row {
    min-height: 3.75rem;
    transition: background-color 120ms ease;
  }
  .user-row:hover {
    background: var(--surface-raised);
  }
  .ledger-head {
    color: var(--ink-muted);
    background: var(--surface-raised);
    font-size: 0.84rem;
    font-weight: 650;
  }
  .user-row + .user-row {
    border-top: 1px solid var(--line);
  }
  .user-identity {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    min-width: 0;
  }
  .user-email {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 620;
  }
  .you-marker {
    flex: none;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.05rem 0.5rem;
    color: var(--ink-muted);
    font-size: 0.75rem;
    font-weight: 650;
  }
  /* Role as a quiet label; administrators get the accent so they stand out in the list. */
  .role-value {
    justify-self: start;
    border-radius: 999px;
    padding: 0.25rem 0.6rem;
    color: var(--ink-muted);
    background: var(--surface-raised);
    box-shadow: inset 0 0 0 1px var(--line);
    font-size: 0.83rem;
    font-weight: 650;
  }
  .role-value.admin {
    color: var(--accent-strong);
    background: var(--accent-wash);
    box-shadow: none;
  }
  .cell-label {
    display: none;
  }
  .status-pill {
    justify-self: start;
    border-radius: 999px;
    padding: 0.25rem 0.6rem;
    color: var(--success);
    background: var(--success-surface);
    font-size: 0.83rem;
    font-weight: 650;
  }
  .status-pill.inactive {
    color: var(--warning);
    background: var(--warning-surface);
  }
  /* Fixed slots: the disable/enable slot keeps its width in the current user's row so
     Reset password and the identities link stay in the same columns. */
  .row-actions {
    display: grid;
    grid-template-columns: 7.25rem max-content max-content;
    align-items: center;
    justify-content: start;
    gap: 0.35rem;
  }
  .action-slot {
    display: flex;
  }
  .action-slot :global(.ui-button) {
    width: 100%;
  }
  /* Disabling is a rare, destructive action: outlined in the danger colour instead of a solid
     pink block on every row. The confirmation dialog stays the real safeguard. */
  .row-actions :global(.user-action--danger) {
    border-color: color-mix(in srgb, var(--danger) 55%, transparent);
    color: var(--danger);
  }
  .row-actions :global(.user-action--danger:hover:not(:disabled)) {
    background: color-mix(in srgb, var(--danger) 12%, transparent);
  }
  .button-content {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.45rem;
  }
  .button-content :global(svg) {
    flex: 0 0 1rem;
    width: 1rem;
    height: 1rem;
  }
  .target-identity {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.7rem;
    overflow-wrap: anywhere;
    color: var(--ink);
  }
  .page-controls {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 1rem;
    margin: 1rem 0;
  }
  .page-controls span {
    min-width: 5rem;
    text-align: center;
    font-variant-numeric: tabular-nums;
  }
  .empty-ledger,
  .notice-panel {
    margin: 0;
    padding: 1.2rem;
    color: var(--ink-muted);
  }
  .notice {
    border-radius: var(--radius-xs);
    padding: 0.75rem 0.9rem;
  }
  .notice-success {
    color: var(--success);
    background: var(--success-surface);
  }
  .notice-error,
  .field-error {
    color: var(--danger-strong, #9b2929);
  }
  dialog {
    width: min(100% - 2rem, 34rem);
    max-height: min(90dvh, 48rem);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0;
    color: var(--ink);
    background: var(--surface);
    box-shadow: 0 1.5rem 5rem #17242d40;
  }
  dialog::backdrop {
    background: #17242d80;
  }
  .admin-dialog {
    display: grid;
    gap: 0.7rem;
    padding: clamp(1.15rem, 5vw, 1.8rem);
  }
  .admin-dialog h2 {
    margin: 0;
    font-size: 1.35rem;
  }
  .admin-dialog p {
    margin: 0;
    color: var(--ink-muted);
    line-height: 1.5;
  }
  .admin-dialog label:not(.check-field) {
    margin-top: 0.2rem;
    font-weight: 650;
  }
  .admin-dialog input:not([type="checkbox"]) {
    width: 100%;
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
  .dialog-actions {
    display: flex;
    justify-content: end;
    flex-wrap: wrap;
    gap: 0.6rem;
    margin-top: 0.4rem;
  }
  :focus-visible {
    outline: 3px solid #e2a61f;
    outline-offset: 3px;
  }
  @media (max-width: 850px) {
    .admin-heading {
      align-items: start;
      flex-direction: column;
    }
    .admin-heading > :global(.ui-button) {
      width: 100%;
    }
    .ledger-head {
      display: none;
    }
    .user-row {
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.45rem 0.7rem;
      padding: 0.95rem;
    }
    .user-identity {
      grid-column: 1 / -1;
    }
    .role-value {
      grid-column: 1;
      grid-row: 2;
    }
    .cell-label {
      font-weight: 650;
    }
    .cell-label {
      display: inline;
    }
    .status-pill {
      grid-column: 2;
      grid-row: 2;
    }
    .row-actions {
      display: flex;
      grid-column: 1 / -1;
      justify-content: stretch;
    }
    .action-slot {
      display: contents;
    }
    .action-slot--empty {
      display: none;
    }
    .row-actions :global(.ui-button) {
      flex: 1;
    }
    .page-controls {
      gap: 0.4rem;
    }
    .page-controls :global(.ui-button) {
      padding-inline: 0.55rem;
    }
  }
</style>
