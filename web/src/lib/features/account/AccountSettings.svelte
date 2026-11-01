<script lang="ts">
  import { beforeNavigate } from "$app/navigation";
  import { onDestroy, tick } from "svelte";
  import { ApiError, type SessionData } from "#lib/api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { refreshSession, session } from "#lib/session.svelte.ts";
  import {
    changePassword,
    createApiToken,
    deleteAvatar,
    getAccount,
    listApiTokens,
    revokeApiToken,
    uploadAvatar,
    type AccountProfile,
    type ApiToken,
    type CreatedApiToken,
  } from "./account-api";

  type PageLoad = "loading" | "ready" | "error";
  type Operation = "token" | "avatar-upload" | "avatar-delete" | "password";

  let profile = $state<AccountProfile | null>(null);
  let tokens = $state<ApiToken[]>([]);
  let profileLoad = $state<PageLoad>("loading");
  let tokenLoad = $state<PageLoad>("loading");
  let profileError = $state("");
  let tokenLoadError = $state("");
  let tokenName = $state("");
  let tokenNameError = $state("");
  let tokenError = $state("");
  let avatarError = $state("");
  let passwordError = $state("");
  let pageStatus = $state("");
  let operation = $state<Operation | null>(null);
  let revokingId = $state<number | null>(null);
  let avatarFile = $state<File | null>(null);
  let tokenNameInput = $state<HTMLInputElement | null>(null);
  let avatarInput = $state<HTMLInputElement | null>(null);
  let tokenDialog = $state<HTMLDialogElement | null>(null);
  let revokeDialog = $state<HTMLDialogElement | null>(null);
  let revokeTarget = $state<ApiToken | null>(null);
  let revokeOpener: HTMLButtonElement | null = null;
  let revealedToken = $state<CreatedApiToken | null>(null);
  let tokenSecretInput = $state<HTMLInputElement | null>(null);
  let tokenAcknowledged = $state(false);
  let copyStatus = $state("");
  let currentPassword = $state("");
  let newPassword = $state("");
  let confirmPassword = $state("");
  let currentPasswordError = $state("");
  let newPasswordError = $state("");
  let confirmPasswordError = $state("");
  let sessionGeneration = 0;
  let profileGeneration = 0;
  let tokenGeneration = 0;
  let operationGeneration = 0;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);

  function hasSensitiveDraft(): boolean {
    return (
      tokenName.trim().length > 0 ||
      avatarFile !== null ||
      currentPassword.length > 0 ||
      newPassword.length > 0 ||
      confirmPassword.length > 0 ||
      revealedToken !== null
    );
  }

  function beforeUnload(event: BeforeUnloadEvent) {
    if (!hasSensitiveDraft()) return;
    event.preventDefault();
    event.returnValue = "";
  }

  beforeNavigate(({ cancel }) => {
    if (
      hasSensitiveDraft() &&
      typeof window !== "undefined" &&
      !window.confirm(
        t("Leave this account page without saving your changes?", locale),
      )
    )
      cancel();
  });
  $effect(() => {
    const capturedUserId = userId;
    const generation = ++sessionGeneration;
    profileGeneration += 1;
    tokenGeneration += 1;
    operationGeneration += 1;
    const controller = new AbortController();
    profile = null;
    tokens = [];
    operation = null;
    revokingId = null;
    revokeOpener = null;
    avatarFile = null;
    tokenName = "";
    currentPassword = "";
    newPassword = "";
    confirmPassword = "";
    revealedToken = null;
    tokenAcknowledged = false;
    if (tokenDialog?.open) tokenDialog.close();
    if (revokeDialog?.open) revokeDialog.close();
    profileError = "";
    tokenLoadError = "";
    profileLoad = "loading";
    tokenLoad = "loading";
    pageStatus = "";
    if (capturedUserId === null) {
      profileLoad = "error";
      tokenLoad = "error";
      profileError = t("Sign in to manage your account.", locale);
      tokenLoadError = t("Sign in to manage your account.", locale);
      return () => controller.abort();
    }

    void getAccount(controller.signal)
      .then((value) => {
        if (controller.signal.aborted || generation !== sessionGeneration)
          return;
        profile = value;
        profileLoad = "ready";
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || generation !== sessionGeneration)
          return;
        profileError = errorMessage(
          error,
          t("Your account details could not be loaded.", locale),
        );
        profileLoad = "error";
      });
    void listApiTokens(controller.signal)
      .then((value) => {
        if (controller.signal.aborted || generation !== sessionGeneration)
          return;
        tokens = value;
        tokenLoad = "ready";
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || generation !== sessionGeneration)
          return;
        tokenLoadError = errorMessage(
          error,
          t("Your API tokens could not be loaded.", locale),
        );
        tokenLoad = "error";
      });
    return () => controller.abort();
  });

  onDestroy(() => {
    sessionGeneration += 1;
    operationGeneration += 1;
    tokenName = "";
    avatarFile = null;
    currentPassword = "";
    newPassword = "";
    confirmPassword = "";
    revealedToken = null;
    if (avatarInput) avatarInput.value = "";
  });

  function askToRevoke(token: ApiToken, opener: HTMLButtonElement) {
    revokeTarget = token;
    revokeOpener = opener;
    tokenError = "";
    revokeDialog?.showModal();
  }

  async function retryProfile() {
    const capturedUserId = userId;
    if (capturedUserId === null) return;
    profileLoad = "loading";
    profileError = "";
    const generation = ++profileGeneration;
    try {
      const value = await getAccount();
      if (userId !== capturedUserId || generation !== profileGeneration) return;
      profile = value;
      profileLoad = "ready";
    } catch (error) {
      if (userId !== capturedUserId || generation !== profileGeneration) return;
      profileError = errorMessage(
        error,
        t("Your account details could not be loaded.", locale),
      );
      profileLoad = "error";
    }
  }

  async function retryTokens() {
    const capturedUserId = userId;
    if (capturedUserId === null) return;
    tokenLoad = "loading";
    tokenLoadError = "";
    const generation = ++tokenGeneration;
    try {
      const value = await listApiTokens();
      if (userId !== capturedUserId || generation !== tokenGeneration) return;
      tokens = value;
      tokenLoad = "ready";
    } catch (error) {
      if (userId !== capturedUserId || generation !== tokenGeneration) return;
      tokenLoadError = errorMessage(
        error,
        t("Your API tokens could not be loaded.", locale),
      );
      tokenLoad = "error";
    }
  }

  async function addToken(event: SubmitEvent) {
    event.preventDefault();
    const activeSession = session.data;
    const capturedUserId = activeSession?.user?.id;
    if (!activeSession || capturedUserId === undefined || operation !== null)
      return;
    tokenNameError = "";
    tokenError = "";
    pageStatus = "";
    const mutationId = ++operationGeneration;
    operation = "token";
    try {
      const created = await createApiToken(tokenName.trim(), activeSession);
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      const { token: _secret, ...metadata } = created;
      tokens = [metadata, ...tokens];
      tokenLoad = "ready";
      tokenLoadError = "";
      tokenName = "";
      revealedToken = created;
      tokenAcknowledged = false;
      copyStatus = "";
      await tick();
      tokenDialog?.showModal();
    } catch (error) {
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      if (error instanceof ApiError && error.fields?.name) {
        tokenNameError = t("Token names must be 255 bytes or fewer.", locale);
      } else {
        tokenError = errorMessage(
          error,
          t("The API token could not be created.", locale),
        );
      }
    } finally {
      if (userId === capturedUserId && mutationId === operationGeneration)
        operation = null;
    }
  }

  async function confirmRevoke() {
    const target = revokeTarget;
    const activeSession = session.data;
    const capturedUserId = activeSession?.user?.id;
    if (
      !target ||
      !activeSession ||
      capturedUserId === undefined ||
      operation !== null
    )
      return;
    tokenError = "";
    pageStatus = "";
    const mutationId = ++operationGeneration;
    operation = "token";
    revokingId = target.id;
    try {
      await revokeApiToken(target.id, activeSession);
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      tokens = tokens.filter((item) => item.id !== target.id);
      revokeDialog?.close();
      revokeTarget = null;
      pageStatus = t("API token revoked.", locale);
    } catch (error) {
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      tokenError = errorMessage(
        error,
        t("The API token could not be revoked.", locale),
      );
      revokeDialog?.close();
      revokeTarget = null;
    } finally {
      if (userId === capturedUserId && mutationId === operationGeneration) {
        operation = null;
        revokingId = null;
        await tick();
        if (userId === capturedUserId && mutationId === operationGeneration) {
          if (revokeOpener?.isConnected) revokeOpener.focus();
          else tokenNameInput?.focus();
          revokeOpener = null;
        }
      }
    }
  }

  async function uploadSelectedAvatar() {
    const file = avatarFile;
    const activeSession = session.data;
    const capturedUserId = activeSession?.user?.id;
    if (
      !file ||
      !activeSession ||
      capturedUserId === undefined ||
      operation !== null
    )
      return;
    avatarError = "";
    pageStatus = "";
    if (file.size > 25 * 1024 * 1024) {
      avatarError = t("Choose an image smaller than 25 MiB.", locale);
      return;
    }
    operation = "avatar-upload";
    const mutationId = ++operationGeneration;
    try {
      const avatarUrl = await uploadAvatar(file, activeSession);
      if (
        userId !== capturedUserId ||
        mutationId !== operationGeneration ||
        !profile
      )
        return;
      profile = { ...profile, avatar_url: avatarUrl };
      avatarFile = null;
      if (avatarInput) avatarInput.value = "";
      pageStatus = t("Profile picture updated.", locale);
    } catch (error) {
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      avatarError = errorMessage(
        error,
        t("The profile picture could not be uploaded.", locale),
      );
    } finally {
      if (userId === capturedUserId && mutationId === operationGeneration)
        operation = null;
    }
  }

  async function removeAvatar() {
    const activeSession = session.data;
    const capturedUserId = activeSession?.user?.id;
    if (!activeSession || capturedUserId === undefined || operation !== null)
      return;
    avatarError = "";
    pageStatus = "";
    operation = "avatar-delete";
    const mutationId = ++operationGeneration;
    try {
      await deleteAvatar(activeSession);
      if (
        userId !== capturedUserId ||
        mutationId !== operationGeneration ||
        !profile
      )
        return;
      profile = { ...profile, avatar_url: null };
      avatarFile = null;
      if (avatarInput) avatarInput.value = "";
      pageStatus = t("Profile picture removed.", locale);
    } catch (error) {
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      avatarError = errorMessage(
        error,
        t("The profile picture could not be removed.", locale),
      );
    } finally {
      if (userId === capturedUserId && mutationId === operationGeneration)
        operation = null;
    }
  }

  async function submitPassword(event: SubmitEvent) {
    event.preventDefault();
    const activeSession = session.data;
    const capturedUserId = activeSession?.user?.id;
    if (!activeSession || capturedUserId === undefined || operation !== null)
      return;
    passwordError = "";
    currentPasswordError = "";
    newPasswordError = "";
    confirmPasswordError = "";
    pageStatus = "";
    if (!currentPassword) {
      currentPasswordError = t("Enter your current password.", locale);
      return;
    }
    if (new TextEncoder().encode(newPassword).length > 1024) {
      newPasswordError = t("Passwords must be 1024 bytes or fewer.", locale);
      return;
    }
    if ([...newPassword].length < 12) {
      newPasswordError = t(
        "Use at least 12 characters for your new password.",
        locale,
      );
      return;
    }
    if (newPassword !== confirmPassword) {
      confirmPasswordError = t("The new passwords do not match.", locale);
      return;
    }
    operation = "password";
    const mutationId = ++operationGeneration;
    try {
      await changePassword(currentPassword, newPassword, activeSession);
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      currentPassword = "";
      newPassword = "";
      confirmPassword = "";
      await refreshSession();
    } catch (error) {
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      if (error instanceof ApiError && error.fields?.current_password) {
        currentPasswordError = t("Current password is incorrect.", locale);
      } else if (error instanceof ApiError && error.fields?.new_password) {
        newPasswordError =
          error.code === "validation_error"
            ? t("Choose a password that is not commonly used.", locale)
            : t("The new password could not be accepted.", locale);
      } else {
        passwordError = errorMessage(
          error,
          t("The password could not be changed.", locale),
        );
      }
    } finally {
      if (userId === capturedUserId && mutationId === operationGeneration)
        operation = null;
    }
  }

  async function copyToken() {
    const token = revealedToken;
    const capturedUserId = userId;
    if (!token) return;
    copyStatus = "";
    try {
      await navigator.clipboard.writeText(token.token);
      if (revealedToken?.id !== token.id || userId !== capturedUserId) return;
      copyStatus = t("Token copied. Keep it somewhere safe.", locale);
    } catch {
      if (revealedToken?.id !== token.id || userId !== capturedUserId) return;
      copyStatus = t(
        "Copy was blocked. Select and copy the token manually.",
        locale,
      );
      await tick();
      if (revealedToken?.id !== token.id || userId !== capturedUserId) return;
      tokenSecretInput?.focus();
      tokenSecretInput?.select();
    }
  }

  function clearRevealedToken() {
    revealedToken = null;
    tokenAcknowledged = false;
    copyStatus = "";
  }

  function hideRevealedToken() {
    if (!tokenAcknowledged) return;
    clearRevealedToken();
    tokenDialog?.close();
  }

  function formatDate(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(
      date,
    );
  }

  function errorMessage(error: unknown, fallback: string): string {
    if (!(error instanceof ApiError)) {
      return error instanceof Error ? error.message : fallback;
    }
    switch (error.code) {
      case "network_error":
        return error.message;
      case "session_expired":
        return t("Your session expired. Sign in again.", locale);
      case "rate_limited":
        return t(
          "Too many tokens were created recently. Try again later.",
          locale,
        );
      case "payload_too_large":
        return t("Choose an image smaller than 25 MiB.", locale);
      case "invalid_image":
        return t("Choose a supported image file.", locale);
      case "media_unavailable":
        return t("Media storage is unavailable. Try again later.", locale);
      case "invalid_multipart":
        return t(
          "The uploaded image could not be read. Choose it again.",
          locale,
        );
      case "conflict":
        return t(
          "Your account changed in another window. Refresh and try again.",
          locale,
        );
      case "validation_error":
        return t("Check the highlighted fields and try again.", locale);
      default:
        return fallback;
    }
  }
</script>

<svelte:window onbeforeunload={beforeUnload} />

<svelte:head>
  <title>Stricknani · {t("Account", locale)}</title>
  <meta
    name="description"
    content={t("Manage your profile and sign-in security.", locale)}
  />
</svelte:head>

<section class="account-page" aria-labelledby="account-title">
  <header class="page-heading">
    <div>
      <p class="section-mark">{t("Your workspace", locale)}</p>
      <h1 id="account-title">{t("Account", locale)}</h1>
      <p>{t("Manage your profile, API access, and password.", locale)}</p>
    </div>
  </header>

  {#if pageStatus}
    <p class="page-status" role="status" aria-live="polite">{pageStatus}</p>
  {/if}

  <section class="profile-section" aria-labelledby="profile-title">
    <div class="profile-portrait" aria-hidden="true">
      {#if profile?.avatar_url}
        <img src={profile.avatar_url} alt="" />
      {:else}
        <span
          >{profile?.email.slice(0, 1).toLocaleUpperCase(locale) ?? "…"}</span
        >
      {/if}
    </div>
    <div class="profile-copy">
      <h2 id="profile-title">{t("Profile", locale)}</h2>
      {#if profileLoad === "loading"}
        <p class="muted">{t("Loading account details…", locale)}</p>
      {:else if profileLoad === "error"}
        <p class="notice-error" role="alert">{profileError}</p>
        <button
          class="quiet-button"
          type="button"
          onclick={() => void retryProfile()}
        >
          {t("Try again", locale)}
        </button>
      {:else if profile}
        <p class="profile-email">{profile.email}</p>
        <p class="role-line">
          {profile.is_admin ? t("Administrator", locale) : t("Member", locale)}
        </p>
        <div class="avatar-actions">
          <label class="file-picker">
            <input
              bind:this={avatarInput}
              type="file"
              accept="image/*"
              aria-label={t("Choose a profile picture", locale)}
              disabled={operation !== null}
              onchange={(event) => {
                avatarFile = event.currentTarget.files?.[0] ?? null;
                avatarError = "";
              }}
            />
            {t("Choose image", locale)}
          </label>
          {#if avatarFile}
            <span class="selected-file">{avatarFile.name}</span>
            <button
              class="primary-button"
              type="button"
              disabled={operation !== null}
              onclick={() => void uploadSelectedAvatar()}
            >
              {operation === "avatar-upload"
                ? t("Uploading…", locale)
                : t("Upload picture", locale)}
            </button>
          {/if}
          {#if profile.avatar_url}
            <button
              class="quiet-button danger-text"
              type="button"
              disabled={operation !== null}
              onclick={() => void removeAvatar()}
            >
              {operation === "avatar-delete"
                ? t("Removing…", locale)
                : t("Remove picture", locale)}
            </button>
          {/if}
        </div>
        {#if avatarError}<p class="field-error" role="alert">
            {avatarError}
          </p>{/if}
      {/if}
    </div>
  </section>

  <div class="settings-columns">
    <section
      class="settings-section token-section"
      aria-labelledby="tokens-title"
    >
      <header class="section-heading">
        <div>
          <h2 id="tokens-title">{t("API tokens", locale)}</h2>
          <p>
            {t(
              "Use a token for a trusted app or device. You can only see a new token once.",
              locale,
            )}
          </p>
        </div>
      </header>

      {#if tokenError}<p class="notice-error" role="alert">{tokenError}</p>{/if}
      <form class="token-create" onsubmit={(event) => void addToken(event)}>
        <label for="token-name">{t("Token name", locale)}</label>
        <div class="token-create-row">
          <input
            id="token-name"
            bind:this={tokenNameInput}
            bind:value={tokenName}
            maxlength="255"
            autocomplete="off"
            placeholder={t("For example, my tablet", locale)}
            aria-describedby={tokenNameError ? "token-name-error" : undefined}
            aria-invalid={tokenNameError ? "true" : undefined}
            disabled={operation !== null}
          />
          <button
            class="primary-button"
            type="submit"
            disabled={operation !== null || tokenLoad === "loading"}
          >
            {operation === "token"
              ? t("Working…", locale)
              : t("Create token", locale)}
          </button>
        </div>
        {#if tokenNameError}<p
            id="token-name-error"
            class="field-error"
            role="alert"
          >
            {tokenNameError}
          </p>{/if}
      </form>

      {#if tokenLoad === "loading"}
        <p class="muted" aria-live="polite">
          {t("Loading API tokens…", locale)}
        </p>
      {:else if tokenLoad === "error"}
        <div class="inline-error" role="alert">
          <p>{tokenLoadError}</p>
          <button
            class="quiet-button"
            type="button"
            onclick={() => void retryTokens()}
          >
            {t("Try again", locale)}
          </button>
        </div>
      {:else if tokens.length === 0}
        <p class="empty-state">
          {t("No API tokens yet. Create one when an app needs access.", locale)}
        </p>
      {:else}
        <ul class="token-list" aria-label={t("Active API tokens", locale)}>
          {#each tokens as token (token.id)}
            <li class="token-row">
              <div class="token-detail">
                <strong>{token.name}</strong>
                <span
                  >{t("Created", locale)} {formatDate(token.created_at)}</span
                >
                {#if token.expires_at}
                  <span
                    >{t("Expires", locale)} {formatDate(token.expires_at)}</span
                  >
                {/if}
              </div>
              <button
                class="quiet-button danger-text"
                type="button"
                aria-label={t("Revoke token", locale) + ` ${token.name}`}
                disabled={operation !== null}
                onclick={(event) => askToRevoke(token, event.currentTarget)}
              >
                {revokingId === token.id
                  ? t("Revoking…", locale)
                  : t("Revoke", locale)}
              </button>
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <section
      class="settings-section password-section"
      aria-labelledby="password-title"
    >
      <header class="section-heading">
        <div>
          <h2 id="password-title">{t("Change password", locale)}</h2>
          <p>
            {t(
              "Changing your password signs out this browser and other sessions.",
              locale,
            )}
          </p>
        </div>
      </header>
      {#if passwordError}<p class="notice-error" role="alert">
          {passwordError}
        </p>{/if}
      <form
        class="password-form"
        onsubmit={(event) => void submitPassword(event)}
      >
        <label class="form-field" for="current-password">
          <span>{t("Current password", locale)}</span>
          <input
            id="current-password"
            bind:value={currentPassword}
            type="password"
            autocomplete="current-password"
            aria-invalid={currentPasswordError ? "true" : undefined}
            aria-describedby={currentPasswordError
              ? "current-password-error"
              : undefined}
            disabled={operation !== null}
          />
          {#if currentPasswordError}<small
              id="current-password-error"
              class="field-error"
              role="alert">{currentPasswordError}</small
            >{/if}
        </label>
        <label class="form-field" for="new-password">
          <span>{t("New password", locale)}</span>
          <input
            id="new-password"
            bind:value={newPassword}
            type="password"
            autocomplete="new-password"
            aria-invalid={newPasswordError ? "true" : undefined}
            aria-describedby={newPasswordError
              ? "new-password-error"
              : "password-rule"}
            disabled={operation !== null}
          />
          <small id="password-rule" class="muted"
            >{t(
              "At least 12 characters. Avoid common passwords.",
              locale,
            )}</small
          >
          {#if newPasswordError}<small
              id="new-password-error"
              class="field-error"
              role="alert">{newPasswordError}</small
            >{/if}
        </label>
        <label class="form-field" for="confirm-password">
          <span>{t("Confirm new password", locale)}</span>
          <input
            id="confirm-password"
            bind:value={confirmPassword}
            type="password"
            autocomplete="new-password"
            aria-invalid={confirmPasswordError ? "true" : undefined}
            aria-describedby={confirmPasswordError
              ? "confirm-password-error"
              : undefined}
            disabled={operation !== null}
          />
          {#if confirmPasswordError}<small
              id="confirm-password-error"
              class="field-error"
              role="alert">{confirmPasswordError}</small
            >{/if}
        </label>
        <button
          class="primary-button password-submit"
          type="submit"
          disabled={operation !== null}
        >
          {operation === "password"
            ? t("Changing password…", locale)
            : t("Save new password", locale)}
        </button>
      </form>
    </section>
  </div>
</section>

<dialog
  bind:this={tokenDialog}
  class="account-dialog"
  aria-labelledby="token-created-title"
  oncancel={(event) => {
    if (!tokenAcknowledged) event.preventDefault();
  }}
  onclose={clearRevealedToken}
>
  {#if revealedToken}
    <div class="dialog-content">
      <p class="section-mark">{t("One-time display", locale)}</p>
      <h2 id="token-created-title">{t("Your new API token", locale)}</h2>
      <p>
        {t(
          "Copy this token now. It will not be shown again after you close this message.",
          locale,
        )}
      </p>
      <p class="muted">
        {t(
          "Acknowledge that you saved the token before closing this dialog.",
          locale,
        )}
      </p>
      <input
        bind:this={tokenSecretInput}
        class="secret-value"
        type="text"
        value={revealedToken.token}
        readonly
        autocomplete="off"
        autocapitalize="off"
        spellcheck="false"
        aria-label={t("New API token", locale)}
        data-secret-value
      />
      <button
        class="primary-button copy-button"
        type="button"
        onclick={() => void copyToken()}
      >
        {t("Copy token", locale)}
      </button>
      {#if copyStatus}<p class="copy-status" role="status" aria-live="polite">
          {copyStatus}
        </p>{/if}
      <label class="acknowledge-token">
        <input type="checkbox" bind:checked={tokenAcknowledged} />
        <span>{t("I saved this token somewhere safe.", locale)}</span>
      </label>
      <button
        class="quiet-button acknowledge-button"
        type="button"
        disabled={!tokenAcknowledged}
        onclick={hideRevealedToken}
      >
        {t("Hide token", locale)}
      </button>
    </div>
  {/if}
</dialog>

<dialog
  bind:this={revokeDialog}
  class="account-dialog"
  aria-labelledby="revoke-token-title"
  oncancel={(event) => {
    if (operation === "token") event.preventDefault();
  }}
  onclose={() => (revokeTarget = null)}
>
  {#if revokeTarget}
    <div class="dialog-content">
      <p class="section-mark">{t("API access", locale)}</p>
      <h2 id="revoke-token-title">{t("Revoke this token?", locale)}</h2>
      <p>
        {t("Apps using this token will no longer be able to connect.", locale)}
      </p>
      <div class="dialog-actions">
        <button
          class="quiet-button"
          type="button"
          disabled={operation === "token"}
          onclick={() => revokeDialog?.close()}
        >
          {t("Keep token", locale)}
        </button>
        <button
          class="danger-button"
          type="button"
          disabled={operation !== null}
          onclick={() => void confirmRevoke()}
        >
          {operation === "token"
            ? t("Revoking…", locale)
            : t("Revoke token", locale)}
        </button>
      </div>
    </div>
  {/if}
</dialog>

<style>
  .account-page {
    display: grid;
    gap: clamp(1.5rem, 3vw, 2.4rem);
    max-width: 70rem;
    margin-inline: auto;
  }

  .page-heading {
    display: flex;
    align-items: end;
    justify-content: space-between;
    gap: 1rem;
  }

  .section-mark {
    margin: 0 0 0.35rem;
    color: var(--accent-strong);
    font-size: 0.9rem;
    font-weight: 650;
  }

  h1,
  h2,
  p {
    margin-top: 0;
  }

  h1 {
    margin-bottom: 0.35rem;
    font-size: clamp(2rem, 5vw, 2.8rem);
    font-weight: 680;
    letter-spacing: -0.055em;
    line-height: 1.1;
  }

  h2 {
    margin-bottom: 0.4rem;
    font-size: 1.25rem;
    font-weight: 660;
    letter-spacing: -0.025em;
  }

  .page-heading p:last-child,
  .section-heading p {
    margin-bottom: 0;
    color: var(--ink-muted);
  }

  .profile-section {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    align-items: center;
    gap: 1.5rem;
    border-block: 1px solid var(--line);
    padding-block: 1.5rem;
  }

  .profile-portrait {
    display: grid;
    width: clamp(5.5rem, 12vw, 7rem);
    aspect-ratio: 1;
    place-items: center;
    overflow: hidden;
    border: 2px solid var(--accent);
    border-radius: 50%;
    outline: 1px dashed var(--accent);
    outline-offset: 4px;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 2rem;
    font-weight: 700;
  }

  .profile-portrait img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .profile-copy h2 {
    margin-bottom: 0.3rem;
  }

  .profile-email {
    margin-bottom: 0.1rem;
    overflow-wrap: anywhere;
    font-size: 1.08rem;
    font-weight: 620;
  }

  .role-line,
  .muted {
    margin-bottom: 0;
    color: var(--ink-muted);
  }

  .avatar-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.45rem;
    margin-top: 0.8rem;
  }

  .file-picker,
  .primary-button,
  .quiet-button,
  .danger-button {
    display: inline-flex;
    min-height: 2.85rem;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.55rem 0.85rem;
    font: inherit;
    font-size: 0.93rem;
    font-weight: 630;
    text-align: center;
    text-decoration: none;
    cursor: pointer;
  }

  .primary-button {
    border-color: var(--accent-strong);
    color: #fff;
    background: var(--accent-strong);
  }

  @media (prefers-color-scheme: dark) {
    .primary-button {
      color: #14201e;
      background: var(--accent);
    }
  }

  .file-picker,
  .quiet-button {
    color: var(--accent-strong);
    background: var(--surface);
  }

  .quiet-button:hover,
  .file-picker:hover {
    background: var(--accent-wash);
  }

  .danger-button {
    border-color: var(--danger);
    color: #fff;
    background: var(--danger);
  }

  .danger-text {
    color: var(--danger);
  }

  .file-picker {
    position: relative;
    overflow: hidden;
  }

  .file-picker input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    cursor: pointer;
    opacity: 0;
  }

  .file-picker:focus-within {
    outline: 3px solid var(--focus);
    outline-offset: 3px;
  }

  .selected-file {
    max-width: 15rem;
    overflow: hidden;
    color: var(--ink-muted);
    font-size: 0.85rem;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .settings-columns {
    display: grid;
    grid-template-columns: minmax(0, 1.15fr) minmax(18rem, 0.85fr);
    align-items: start;
    gap: clamp(1.5rem, 4vw, 3.2rem);
  }

  .settings-section {
    min-width: 0;
    border-top: 2px solid var(--line);
    padding-top: 1rem;
  }

  .section-heading {
    margin-bottom: 1.25rem;
  }

  .section-heading p {
    max-width: 38rem;
    line-height: 1.55;
  }

  .token-create {
    display: grid;
    gap: 0.45rem;
    margin-bottom: 1.2rem;
  }

  .token-create label,
  .form-field > span {
    font-size: 0.9rem;
    font-weight: 620;
  }

  .token-create-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 0.5rem;
  }

  input:not([type="checkbox"]):not([type="file"]) {
    width: 100%;
    min-width: 0;
    min-height: 3rem;
    border: 1px solid var(--line);
    border-radius: 0.55rem;
    padding: 0.65rem 0.75rem;
    color: var(--ink);
    background: var(--surface);
    font-size: 1rem;
  }

  input[aria-invalid="true"] {
    border-color: var(--danger) !important;
  }

  .token-list {
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .token-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 0.75rem;
    border-top: 1px solid var(--line);
    padding-block: 0.8rem;
  }

  .token-detail {
    display: grid;
    min-width: 0;
    gap: 0.12rem;
  }

  .token-detail strong {
    overflow-wrap: anywhere;
  }

  .token-detail span {
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .empty-state {
    border-top: 1px solid var(--line);
    margin: 0;
    padding: 0.8rem 0;
    color: var(--ink-muted);
  }

  .password-form {
    display: grid;
    gap: 1rem;
  }

  .form-field {
    display: grid;
    gap: 0.4rem;
  }

  .form-field small {
    line-height: 1.4;
  }

  .password-submit {
    justify-self: start;
    margin-top: 0.15rem;
  }

  .field-error {
    color: var(--danger);
    font-size: 0.87rem;
  }

  .notice-error,
  .inline-error {
    border-left: 3px solid var(--danger);
    margin: 0.5rem 0;
    padding: 0.55rem 0.75rem;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  .inline-error p {
    margin-bottom: 0.25rem;
  }

  .page-status {
    margin: -1rem 0 0;
    color: var(--accent-strong);
    font-weight: 600;
  }

  .account-dialog {
    width: min(34rem, calc(100vw - 2rem));
    max-height: min(90svh, 48rem);
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: 0;
    color: var(--ink);
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  .account-dialog::backdrop {
    background: rgb(8 24 21 / 55%);
    backdrop-filter: blur(2px);
  }

  .dialog-content {
    display: grid;
    gap: 0.75rem;
    padding: clamp(1.25rem, 4vw, 2rem);
  }

  .dialog-content h2,
  .dialog-content p {
    margin-bottom: 0;
  }

  .secret-value {
    display: block;
    width: 100%;
    box-sizing: border-box;
    max-height: 7rem;
    overflow: auto;
    overflow-wrap: anywhere;
    border: 1px solid var(--line);
    border-radius: 0.55rem;
    padding: 0.8rem;
    color: var(--ink);
    background: var(--surface-raised);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.88rem;
    user-select: all;
  }

  .copy-button,
  .acknowledge-button {
    justify-self: start;
  }

  .copy-status {
    color: var(--accent-strong);
    font-weight: 600;
  }

  .acknowledge-token {
    display: flex;
    min-height: 2.9rem;
    align-items: center;
    gap: 0.65rem;
    cursor: pointer;
  }

  .acknowledge-token input {
    width: 1.2rem;
    height: 1.2rem;
    accent-color: var(--accent-strong);
  }

  .dialog-actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: end;
    gap: 0.55rem;
    margin-top: 0.3rem;
  }

  @media (max-width: 800px) {
    .settings-columns {
      grid-template-columns: minmax(0, 1fr);
      gap: 1.8rem;
    }
  }

  @media (max-width: 540px) {
    .profile-section {
      grid-template-columns: 4.4rem minmax(0, 1fr);
      gap: 1rem;
      align-items: start;
    }

    .profile-portrait {
      width: 4.25rem;
      font-size: 1.4rem;
    }

    .avatar-actions {
      align-items: stretch;
    }

    .file-picker,
    .avatar-actions .primary-button,
    .avatar-actions .quiet-button {
      min-height: 2.9rem;
    }

    .token-create-row {
      grid-template-columns: minmax(0, 1fr);
    }

    .token-create-row .primary-button {
      justify-self: start;
    }

    .token-row {
      align-items: start;
    }

    .token-row .quiet-button {
      align-self: center;
    }

    .page-status {
      margin-top: -1.1rem;
    }
  }
</style>
