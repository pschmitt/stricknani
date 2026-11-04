<script lang="ts">
  import SharedButton from "#lib/components/Button.svelte";
  import { beforeNavigate, goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { onDestroy, onMount, tick } from "svelte";
  import { ApiError, type SessionData } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import { chooseLocale } from "#lib/components/LocaleSwitcher.svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";
  import ConnectAppDialog from "./ConnectAppDialog.svelte";
  import { formatLastUsed } from "./connect-app.ts";
  import LinkedLogins from "#lib/features/auth/LinkedLogins.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import {
    patchSessionAvatar,
    refreshSession,
    session,
    signOut,
  } from "#lib/session.svelte.ts";
  import { theme, setTheme } from "#lib/theme.svelte.ts";
  import { themeChoices, type ThemeChoice } from "#lib/theme.ts";
  import { textSize } from "#lib/text-size.svelte.ts";
  import {
    changePassword,
    createApiToken,
    deleteAvatar,
    getAccount,
    listApiTokens,
    revokeApiToken,
    setUseGravatar,
    uploadAvatar,
    type AccountProfile,
    type ApiToken,
    type CreatedApiToken,
  } from "./account-api";

  type PageLoad = "loading" | "ready" | "error";
  type Operation =
    "token" | "avatar-upload" | "avatar-delete" | "gravatar" | "password";

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
  let brokenAvatarUrl = $state<string | null>(null);
  let passwordError = $state("");
  let pageStatus = $state("");
  let operation = $state<Operation | null>(null);
  let signOutBusy = $state(false);
  let signOutError = $state("");
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
  let passwordOpen = $state(false);
  let tokenFormOpen = $state(false);
  let newTokenButton = $state<HTMLButtonElement | null>(null);
  let connectDialog = $state<ReturnType<typeof ConnectAppDialog>>();
  let passwordButton = $state<HTMLButtonElement | null>(null);
  let currentPasswordInput = $state<HTMLInputElement | null>(null);
  let sessionGeneration = 0;
  let profileGeneration = 0;
  let tokenGeneration = 0;
  let operationGeneration = 0;
  let allowSignOutNavigation = false;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const canManageAdmin = $derived(
    session.data?.user?.is_admin === true &&
      session.data?.capabilities.admin_actions === true,
  );
  const accountActionsEnabled = $derived(
    session.data?.capabilities.account_actions === true,
  );
  const avatarUploadEnabled = $derived(
    accountActionsEnabled && session.data?.capabilities.media_upload === true,
  );
  const avatarDeleteEnabled = $derived(
    accountActionsEnabled && session.data?.capabilities.media_delete === true,
  );

  const themeIcons = {
    system: "monitor",
    light: "sun",
    dark: "moon",
  } as const satisfies Record<ThemeChoice, NavIconName>;
  const localeOptions: { value: Locale; label: string }[] = [
    { value: "en", label: "English" },
    { value: "de", label: "Deutsch" },
  ];
  const themeOptions = $derived(
    themeChoices.map((choice) => ({
      value: choice,
      icon: themeIcons[choice],
      label:
        choice === "system"
          ? t("System", locale)
          : choice === "light"
            ? t("Light", locale)
            : t("Dark", locale),
    })),
  );
  const sizeOptions = $derived([
    { value: 0, label: t("Small", locale) },
    { value: 1, label: t("Default", locale) },
    { value: 2, label: t("Large", locale) },
  ]);

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: hasSensitiveDraft(),
      busy:
        operation !== null ||
        signOutBusy ||
        revokingId !== null ||
        profileLoad === "loading" ||
        tokenLoad === "loading",
    })),
  );

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
    if (allowSignOutNavigation || !hasSensitiveDraft()) return;
    event.preventDefault();
    event.returnValue = "";
  }

  beforeNavigate(({ cancel }) => {
    if (
      !allowSignOutNavigation &&
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
    passwordOpen = false;
    tokenFormOpen = false;
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

  async function signOutFromAccount() {
    const activeSession = session.data;
    const capturedUserId = activeSession?.user?.id;
    if (
      session.status !== "ready" ||
      !activeSession ||
      capturedUserId === undefined ||
      signOutBusy
    )
      return;
    if (
      hasSensitiveDraft() &&
      !window.confirm(
        t("Leave this account page without saving your changes?", locale),
      )
    )
      return;

    signOutError = "";
    signOutBusy = true;
    try {
      await signOut();
      if (session.data?.user !== null) {
        if (session.data === activeSession && userId === capturedUserId)
          signOutError = t("Could not sign out.", locale);
        return;
      }
      allowSignOutNavigation = true;
      await goto(resolve("/login"));
    } catch {
      if (session.data === activeSession && userId === capturedUserId)
        signOutError = t("Could not sign out.", locale);
    } finally {
      signOutBusy = false;
    }
  }

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

  async function togglePasswordForm() {
    if (passwordOpen) {
      cancelPasswordForm();
      return;
    }
    passwordOpen = true;
    await tick();
    currentPasswordInput?.focus();
  }

  function cancelPasswordForm() {
    currentPassword = "";
    newPassword = "";
    confirmPassword = "";
    passwordError = "";
    currentPasswordError = "";
    newPasswordError = "";
    confirmPasswordError = "";
    passwordOpen = false;
    passwordButton?.focus();
  }

  async function toggleTokenForm() {
    if (tokenFormOpen) {
      cancelTokenForm();
      return;
    }
    tokenFormOpen = true;
    await tick();
    tokenNameInput?.focus();
  }

  function cancelTokenForm() {
    tokenName = "";
    tokenNameError = "";
    tokenFormOpen = false;
    newTokenButton?.focus();
  }

  async function addToken(event: SubmitEvent) {
    event.preventDefault();
    const activeSession = session.data;
    const capturedUserId = activeSession?.user?.id;
    if (
      !accountActionsEnabled ||
      !activeSession ||
      capturedUserId === undefined ||
      operation !== null
    )
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
      tokenFormOpen = false;
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
      !accountActionsEnabled ||
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
          else if (tokenFormOpen) tokenNameInput?.focus();
          else newTokenButton?.focus();
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
      !avatarUploadEnabled ||
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
      profile = { ...profile, avatar_url: avatarUrl, avatar_source: "upload" };
      patchSessionAvatar(activeSession, avatarUrl, "upload");
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
    if (
      !avatarDeleteEnabled ||
      !activeSession ||
      capturedUserId === undefined ||
      operation !== null
    )
      return;
    avatarError = "";
    pageStatus = "";
    operation = "avatar-delete";
    const mutationId = ++operationGeneration;
    try {
      await deleteAvatar(activeSession);
      // Without an upload the server may fall back to the Gravatar.
      const refreshed = await getAccount().catch(() => null);
      if (
        userId !== capturedUserId ||
        mutationId !== operationGeneration ||
        !profile
      )
        return;
      profile = refreshed ?? {
        ...profile,
        avatar_url: null,
        avatar_source: null,
      };
      patchSessionAvatar(
        activeSession,
        profile.avatar_url,
        profile.avatar_source,
      );
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

  async function changeGravatar(enabled: boolean) {
    const activeSession = session.data;
    const capturedUserId = activeSession?.user?.id;
    if (
      !accountActionsEnabled ||
      !activeSession ||
      capturedUserId === undefined ||
      operation !== null
    )
      return;
    avatarError = "";
    pageStatus = "";
    operation = "gravatar";
    const mutationId = ++operationGeneration;
    try {
      const next = await setUseGravatar(enabled, activeSession);
      if (
        userId !== capturedUserId ||
        mutationId !== operationGeneration ||
        !profile
      )
        return;
      profile = next;
      brokenAvatarUrl = null;
      patchSessionAvatar(activeSession, next.avatar_url, next.avatar_source);
      pageStatus = enabled
        ? t("Gravatar enabled.", locale)
        : t("Gravatar disabled.", locale);
    } catch (error) {
      if (userId !== capturedUserId || mutationId !== operationGeneration)
        return;
      avatarError = errorMessage(
        error,
        t("The Gravatar setting could not be saved.", locale),
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
    if (
      !accountActionsEnabled ||
      !activeSession ||
      capturedUserId === undefined ||
      operation !== null
    )
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
      passwordOpen = false;
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
  <PageHeader title={t("Account", locale)} titleId="account-title"></PageHeader>

  {#if pageStatus}
    <p class="page-status" role="status" aria-live="polite">{pageStatus}</p>
  {/if}

  <section class="card" aria-labelledby="profile-title">
    <header class="card__header">
      <span class="card__icon card__icon--profile" aria-hidden="true"
        ><NavIcon name="account" /></span
      >
      <h2 id="profile-title">{t("Profile", locale)}</h2>
    </header>

    {#if profileLoad === "loading"}
      <p class="muted">{t("Loading account details…", locale)}</p>
    {:else if profileLoad === "error"}
      <p class="notice-error" role="alert">{profileError}</p>
      <div>
        <SharedButton
          variant="secondary"
          type="button"
          onclick={() => void retryProfile()}
          icon="refresh"
        >
          {t("Try again", locale)}
        </SharedButton>
      </div>
    {:else if profile}
      <div class="profile">
        <div class="avatar">
          <div class="avatar__portrait" aria-hidden="true">
            {#if profile.avatar_url && profile.avatar_url !== brokenAvatarUrl}
              {@const source = profile.avatar_url}
              {#key source}
                <img
                  src={source}
                  alt=""
                  onerror={() => (brokenAvatarUrl = source)}
                />
              {/key}
            {:else}
              <span>{profile.email.slice(0, 1).toLocaleUpperCase(locale)}</span>
            {/if}
          </div>
          <label class="avatar__badge" title={t("Choose image", locale)}>
            <input
              bind:this={avatarInput}
              type="file"
              accept="image/*"
              aria-label={t("Choose a profile picture", locale)}
              disabled={operation !== null || !avatarUploadEnabled}
              onchange={(event) => {
                if (!avatarUploadEnabled) {
                  event.currentTarget.value = "";
                  return;
                }
                avatarFile = event.currentTarget.files?.[0] ?? null;
                avatarError = "";
              }}
            />
            <NavIcon name="camera" />
          </label>
        </div>
        <div class="profile__copy">
          <p class="profile__email">{profile.email}</p>
          <p class="role-chip">
            <NavIcon name={profile.is_admin ? "admin" : "account"} />
            <span
              >{profile.is_admin
                ? t("Administrator", locale)
                : t("Member", locale)}</span
            >
          </p>
          {#if canManageAdmin}
            <a
              class="ui-button ui-button--secondary ui-button--sm"
              href={resolve("/admin/users")}
            >
              <ActionLabel icon="admin"
                >{t("Manage users and audit log", locale)}</ActionLabel
              >
            </a>
          {/if}
          {#if profile.avatar_url && profile.avatar_source !== "gravatar"}
            <SharedButton
              variant="ghost"
              size="sm"
              class="quiet-danger"
              type="button"
              disabled={operation !== null || !avatarDeleteEnabled}
              onclick={() => void removeAvatar()}
              icon="action-delete"
            >
              {operation === "avatar-delete"
                ? t("Removing…", locale)
                : t("Remove picture", locale)}
            </SharedButton>
          {/if}
        </div>
      </div>

      {#if profile.gravatar_enabled}
        <label class="gravatar-toggle">
          <input
            type="checkbox"
            checked={profile.use_gravatar}
            disabled={operation !== null || !accountActionsEnabled}
            onchange={(event) =>
              void changeGravatar(event.currentTarget.checked)}
          />
          <span class="gravatar-toggle__copy">
            <span>{t("Use Gravatar when no picture is uploaded", locale)}</span>
            <small class="muted"
              >{t(
                "The server fetches it from Gravatar using your email address.",
                locale,
              )}</small
            >
          </span>
        </label>
      {/if}

      {#if avatarFile}
        <div class="avatar-pending">
          <span class="selected-file">{avatarFile.name}</span>
          <div class="button-row">
            <SharedButton
              variant="primary"
              type="button"
              disabled={operation !== null || !avatarUploadEnabled}
              onclick={() => void uploadSelectedAvatar()}
              icon="upload"
            >
              {operation === "avatar-upload"
                ? t("Uploading…", locale)
                : t("Upload picture", locale)}
            </SharedButton>
            <SharedButton
              variant="ghost"
              type="button"
              disabled={operation !== null}
              onclick={() => {
                avatarFile = null;
                if (avatarInput) avatarInput.value = "";
              }}
            >
              {t("Cancel", locale)}
            </SharedButton>
          </div>
        </div>
      {/if}
      {#if avatarError}<p class="field-error" role="alert">
          {avatarError}
        </p>{/if}
    {/if}
  </section>

  <section class="card" aria-labelledby="preferences-title">
    <header class="card__header">
      <span class="card__icon card__icon--prefs" aria-hidden="true"
        ><NavIcon name="sliders" /></span
      >
      <h2 id="preferences-title">{t("Preferences", locale)}</h2>
    </header>
    <div class="rows">
      <div class="row">
        <span class="row__label" id="pref-language"
          >{t("Language", locale)}</span
        >
        <div class="row__control">
          <SegmentedControl
            options={localeOptions}
            value={locale}
            label={t("Language", locale)}
            disabled={session.status !== "ready" || !session.data}
            onchange={chooseLocale}
          />
        </div>
      </div>
      <div class="row">
        <span class="row__label" id="pref-theme">{t("Appearance", locale)}</span
        >
        <div class="row__control">
          <SegmentedControl
            options={themeOptions}
            value={theme.choice}
            label={t("Theme", locale)}
            onchange={setTheme}
          />
        </div>
      </div>
      <div class="row">
        <div class="row__text">
          <span class="row__label" id="pref-text-size"
            >{t("Text size", locale)}</span
          >
          <p class="row__hint">
            {t("Used when reading projects and yarns.", locale)}
          </p>
        </div>
        <div class="row__control">
          <SegmentedControl
            options={sizeOptions}
            value={textSize.index}
            label={t("Text size", locale)}
            onchange={(next) => textSize.set(next)}
          />
        </div>
      </div>
    </div>
  </section>

  <section class="card" aria-labelledby="security-title">
    <header class="card__header">
      <span class="card__icon card__icon--security" aria-hidden="true"
        ><NavIcon name="lock" /></span
      >
      <h2 id="security-title">{t("Sign-in and security", locale)}</h2>
    </header>
    <div class="rows">
      <div class="row row--top">
        <div class="row__text">
          <span class="row__label">{t("Password", locale)}</span>
          <p class="row__hint">
            {t(
              "Changing your password signs out this browser and other sessions.",
              locale,
            )}
          </p>
        </div>
        <div class="row__control">
          <SharedButton
            bind:element={passwordButton}
            variant="secondary"
            type="button"
            aria-expanded={passwordOpen}
            aria-controls="password-panel"
            disabled={!accountActionsEnabled}
            onclick={() => void togglePasswordForm()}
            icon="key"
          >
            {t("Change password", locale)}
          </SharedButton>
        </div>
      </div>
      <div class="disclosure" class:disclosure--open={passwordOpen}>
        <div class="disclosure__clip" id="password-panel" inert={!passwordOpen}>
          {#if passwordError}<p class="notice-error" role="alert">
              {passwordError}
            </p>{/if}
          <form
            class="stack-form"
            onsubmit={(event) => void submitPassword(event)}
          >
            <label class="form-field" for="current-password">
              <span>{t("Current password", locale)}</span>
              <input
                id="current-password"
                bind:this={currentPasswordInput}
                bind:value={currentPassword}
                type="password"
                autocomplete="current-password"
                aria-invalid={currentPasswordError ? "true" : undefined}
                aria-describedby={currentPasswordError
                  ? "current-password-error"
                  : undefined}
                disabled={operation !== null || !accountActionsEnabled}
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
                disabled={operation !== null || !accountActionsEnabled}
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
                disabled={operation !== null || !accountActionsEnabled}
              />
              {#if confirmPasswordError}<small
                  id="confirm-password-error"
                  class="field-error"
                  role="alert">{confirmPasswordError}</small
                >{/if}
            </label>
            <div class="button-row">
              <SharedButton
                variant="primary"
                class="password-submit"
                type="submit"
                disabled={operation !== null || !accountActionsEnabled}
                icon="save"
              >
                {operation === "password"
                  ? t("Changing password…", locale)
                  : t("Save new password", locale)}
              </SharedButton>
              <SharedButton
                variant="ghost"
                type="button"
                disabled={operation !== null}
                onclick={cancelPasswordForm}
              >
                {t("Cancel", locale)}
              </SharedButton>
            </div>
          </form>
        </div>
      </div>
      <LinkedLogins />
    </div>
  </section>

  <section class="card" aria-labelledby="tokens-title">
    <header class="card__header card__header--split">
      <div class="card__title">
        <span class="card__icon card__icon--apps" aria-hidden="true"
          ><NavIcon name="device" /></span
        >
        <h2 id="tokens-title">{t("Apps and devices", locale)}</h2>
      </div>
      <div class="button-row">
        <SharedButton
          variant="primary"
          size="sm"
          type="button"
          disabled={operation !== null || !accountActionsEnabled}
          onclick={() => void connectDialog?.start()}
          icon="key"
        >
          {t("Connect the app", locale)}
        </SharedButton>
        <SharedButton
          bind:element={newTokenButton}
          variant="secondary"
          size="sm"
          type="button"
          aria-expanded={tokenFormOpen}
          aria-controls="token-panel"
          disabled={operation !== null || !accountActionsEnabled}
          onclick={() => void toggleTokenForm()}
          icon="action-create"
        >
          {t("New token", locale)}
        </SharedButton>
      </div>
    </header>
    <p class="card__intro">
      {t(
        "Use a token for a trusted app or device. You can only see a new token once.",
        locale,
      )}
    </p>

    {#if tokenError}<p class="notice-error" role="alert">{tokenError}</p>{/if}

    <div class="disclosure" class:disclosure--open={tokenFormOpen}>
      <div class="disclosure__clip" id="token-panel" inert={!tokenFormOpen}>
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
              disabled={operation !== null || !accountActionsEnabled}
            />
            <div class="button-row">
              <SharedButton
                variant="primary"
                type="submit"
                disabled={operation !== null ||
                  !accountActionsEnabled ||
                  tokenLoad === "loading"}
                icon="key"
              >
                {operation === "token"
                  ? t("Working…", locale)
                  : t("Create token", locale)}
              </SharedButton>
              <SharedButton
                variant="ghost"
                type="button"
                disabled={operation !== null}
                onclick={cancelTokenForm}
              >
                {t("Cancel", locale)}
              </SharedButton>
            </div>
          </div>
          {#if tokenNameError}<p
              id="token-name-error"
              class="field-error"
              role="alert"
            >
              {tokenNameError}
            </p>{/if}
        </form>
      </div>
    </div>

    {#if tokenLoad === "loading"}
      <p class="muted" aria-live="polite">
        {t("Loading API tokens…", locale)}
      </p>
    {:else if tokenLoad === "error"}
      <div class="inline-error" role="alert">
        <p>{tokenLoadError}</p>
        <SharedButton
          variant="ghost"
          type="button"
          onclick={() => void retryTokens()}
          icon="refresh"
        >
          {t("Try again", locale)}
        </SharedButton>
      </div>
    {:else if tokens.length === 0}
      <p class="empty-state">
        {t("No API tokens yet. Create one when an app needs access.", locale)}
      </p>
    {:else}
      <ul class="token-list" aria-label={t("Active API tokens", locale)}>
        {#each tokens as token (token.id)}
          <li class="token-row">
            <span class="token-row__icon" aria-hidden="true"
              ><NavIcon name="device" /></span
            >
            <div class="token-detail">
              <strong>{token.name}</strong>
              <span>{t("Created", locale)} {formatDate(token.created_at)}</span>
              {#if token.last_used_at}
                <span
                  >{t("Last used", locale)}
                  {formatLastUsed(token.last_used_at, locale)}</span
                >
              {:else}
                <span>{t("Never used", locale)}</span>
              {/if}
              {#if token.expires_at}
                <span
                  >{t("Expires", locale)} {formatDate(token.expires_at)}</span
                >
              {/if}
            </div>
            <SharedButton
              variant="ghost"
              size="sm"
              class="quiet-danger"
              type="button"
              icon="action-delete"
              aria-label={t("Revoke token", locale) + ` ${token.name}`}
              title={t("Revoke", locale)}
              aria-busy={revokingId === token.id ? "true" : undefined}
              disabled={operation !== null || !accountActionsEnabled}
              onclick={(event) => askToRevoke(token, event.currentTarget)}
            />
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <section class="card card--footer" aria-label={t("Sign out", locale)}>
    {#if signOutError}
      <p class="field-error" role="alert">{signOutError}</p>
    {/if}
    <SharedButton
      variant="secondary"
      class="signout-button"
      type="button"
      disabled={signOutBusy ||
        session.status !== "ready" ||
        !session.data?.user}
      onclick={() => void signOutFromAccount()}
      icon="sign-out"
    >
      {signOutBusy ? t("Working…", locale) : t("Sign out", locale)}
    </SharedButton>
  </section>
</section>

<ConnectAppDialog
  bind:this={connectDialog}
  {locale}
  oncreated={(token) => {
    tokens = [token, ...tokens];
    tokenLoad = "ready";
  }}
/>

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
      <SharedButton
        variant="primary"
        class="copy-button"
        type="button"
        onclick={() => void copyToken()}
        icon="copy"
      >
        {t("Copy token", locale)}
      </SharedButton>
      {#if copyStatus}<p class="copy-status" role="status" aria-live="polite">
          {copyStatus}
        </p>{/if}
      <label class="acknowledge-token">
        <input type="checkbox" bind:checked={tokenAcknowledged} />
        <span>{t("I saved this token somewhere safe.", locale)}</span>
      </label>
      <SharedButton
        variant="ghost"
        class="acknowledge-button"
        type="button"
        disabled={!tokenAcknowledged}
        onclick={hideRevealedToken}
        icon="close"
      >
        {t("Hide token", locale)}
      </SharedButton>
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
        <SharedButton
          variant="ghost"
          type="button"
          disabled={operation === "token"}
          onclick={() => revokeDialog?.close()}
          icon="cancel"
        >
          {t("Keep token", locale)}
        </SharedButton>
        <SharedButton
          variant="danger"
          type="button"
          disabled={operation !== null}
          onclick={() => void confirmRevoke()}
          icon="action-delete"
        >
          {operation === "token"
            ? t("Revoking…", locale)
            : t("Revoke token", locale)}
        </SharedButton>
      </div>
    </div>
  {/if}
</dialog>

<style>
  .account-page {
    display: grid;
    width: 100%;
    max-width: 44rem;
    margin-inline: auto;
    gap: 1rem;
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

  .card__header--split {
    justify-content: space-between;
    gap: 0.75rem;
  }

  .card__title {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.75rem;
  }

  .card__icon,
  .token-row__icon {
    display: inline-flex;
    width: 2.5rem;
    height: 2.5rem;
    flex: none;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    font-size: 1.35rem;
  }

  .card__icon--profile {
    color: var(--tone-home);
    background: var(--tone-home-wash);
  }

  .card__icon--prefs {
    color: var(--tone-categories);
    background: var(--tone-categories-wash);
  }

  .card__icon--security {
    color: var(--tone-audit);
    background: var(--tone-audit-wash);
  }

  .card__icon--apps,
  .token-row__icon {
    color: var(--tone-gauge);
    background: var(--tone-gauge-wash);
  }

  .token-row__icon {
    width: 2.25rem;
    height: 2.25rem;
    font-size: 1.2rem;
  }

  h2,
  p {
    margin-top: 0;
  }

  h2 {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 660;
    letter-spacing: -0.025em;
  }

  .card__intro {
    margin: -0.25rem 0 0;
    color: var(--ink-muted);
    line-height: 1.5;
  }

  .muted,
  .row__hint {
    margin: 0;
    color: var(--ink-muted);
  }

  .card--footer {
    padding-block: 0.75rem;
  }

  :global(.signout-button) {
    width: 100%;
  }

  /* Profile */
  .profile {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 1rem;
  }

  .avatar {
    position: relative;
    flex: none;
  }

  .avatar__portrait {
    display: grid;
    width: 5rem;
    aspect-ratio: 1;
    place-items: center;
    overflow: hidden;
    border-radius: 50%;
    color: var(--accent-strong);
    background: var(--accent-wash);
    box-shadow:
      0 0 0 3px var(--surface),
      0 0 0 5px var(--line);
    font-size: 1.9rem;
    font-weight: 700;
  }

  .avatar__portrait img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .avatar__badge {
    position: absolute;
    right: -0.35rem;
    bottom: -0.35rem;
    display: inline-flex;
    width: 2.25rem;
    height: 2.25rem;
    align-items: center;
    justify-content: center;
    border: 2px solid var(--surface);
    border-radius: 50%;
    color: var(--on-accent);
    background: var(--accent-strong);
    font-size: 1.15rem;
    cursor: pointer;
    transition: transform 160ms ease;
  }

  .avatar__badge :global(svg) {
    flex: none;
    width: 20px;
    height: 20px;
  }

  /* The visible hit area stays 36px, the tap target grows to 44px. */
  .avatar__badge::after {
    position: absolute;
    inset: -0.35rem;
    content: "";
  }

  .avatar__badge:hover {
    transform: scale(1.08);
  }

  .avatar__badge:has(input:disabled) {
    cursor: not-allowed;
    opacity: 0.62;
  }

  .avatar__badge:focus-within {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .avatar__badge input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    /* WebKit sizes the native file button from the font and lets it spill past
       the box, where it would swallow clicks meant for neighbouring controls. */
    overflow: hidden;
    cursor: inherit;
    font-size: 0;
    opacity: 0;
  }

  .profile__copy {
    display: grid;
    min-width: 0;
    gap: 0.4rem;
  }

  .profile__email {
    margin: 0;
    overflow-wrap: anywhere;
    font-size: 1.1rem;
    font-weight: 650;
  }

  .role-chip {
    display: inline-flex;
    justify-self: start;
    align-items: center;
    gap: 0.35rem;
    margin: 0;
    border-radius: 999px;
    padding: 0.2rem 0.7rem 0.2rem 0.55rem;
    color: var(--on-primary-container);
    background: var(--primary-container);
    font-size: 0.85rem;
    font-weight: 700;
  }

  .role-chip :global(svg) {
    width: 1rem;
    height: 1rem;
  }

  .avatar-pending {
    display: grid;
    gap: 0.6rem;
    border-radius: var(--radius-sm);
    padding: 0.75rem;
    background: var(--surface-raised);
  }

  .gravatar-toggle {
    display: flex;
    align-items: flex-start;
    gap: 0.65rem;
    cursor: pointer;
  }

  .gravatar-toggle input {
    flex: 0 0 auto;
    width: 1.2rem;
    height: 1.2rem;
    margin-top: 0.15rem;
    accent-color: var(--accent-strong);
  }

  .gravatar-toggle__copy {
    display: grid;
    gap: 0.15rem;
  }

  .selected-file {
    overflow: hidden;
    color: var(--ink-muted);
    font-size: 0.9rem;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .button-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
  }

  .profile__copy :global(.quiet-danger) {
    margin-left: -0.7rem;
    justify-self: start;
  }

  :global(.quiet-danger) {
    color: var(--ink-muted);
  }

  /* Icon-only revoke: a 20px glyph inside the 44px target. */
  .token-row :global(.quiet-danger.ui-button.ui-button > svg) {
    width: 20px;
    height: 20px;
  }

  :global(.quiet-danger:hover:not(:disabled)) {
    border-color: transparent;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 10%, transparent);
  }

  :global(.quiet-danger:focus-visible) {
    color: var(--danger);
  }

  /* Rows */
  .rows {
    display: grid;
  }

  .rows > :global(*) {
    border-top: 1px solid var(--line);
    padding-block: 0.9rem;
  }

  .rows > :global(:first-child) {
    border-top: 0;
    padding-top: 0;
  }

  .rows > :global(:last-child),
  .rows > .row:has(+ .disclosure:last-child) {
    padding-bottom: 0;
  }

  .row {
    display: flex;
    min-width: 0;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
  }

  .row--top {
    align-items: flex-start;
  }

  .row__text {
    display: grid;
    min-width: 0;
    gap: 0.2rem;
  }

  .row__label {
    font-weight: 700;
  }

  .row__hint {
    font-size: 0.9rem;
    line-height: 1.45;
  }

  .row__control {
    flex: none;
  }

  /* Smooth disclosure: the grid row animates between 0fr and 1fr. */
  .disclosure {
    display: grid;
    grid-template-rows: 0fr;
    transition: grid-template-rows 240ms ease;
  }

  .disclosure--open {
    grid-template-rows: 1fr;
  }

  /* A closed disclosure must not add two grid gaps inside a card. */
  .card > .disclosure {
    margin-block: -0.5rem;
    transition:
      grid-template-rows 240ms ease,
      margin 240ms ease;
  }

  .card > .disclosure--open {
    margin-block: 0;
  }

  .rows > .disclosure:last-child .stack-form {
    padding-bottom: 0;
  }

  .disclosure__clip {
    min-height: 0;
    margin: -4px;
    padding: 4px;
    overflow: hidden;
    visibility: hidden;
    transition: visibility 0s 240ms;
  }

  .disclosure--open .disclosure__clip {
    visibility: visible;
    transition-delay: 0s;
  }

  .rows > .disclosure {
    padding-block: 0;
    border-top: 0;
  }

  .stack-form {
    display: grid;
    gap: 1rem;
    padding: 0.9rem 0 0.9rem;
  }

  .form-field {
    display: grid;
    gap: 0.4rem;
  }

  .form-field small {
    line-height: 1.4;
  }

  .form-field > span,
  .token-create label {
    font-size: 0.9rem;
    font-weight: 650;
  }

  input:not([type="checkbox"]):not([type="file"]) {
    width: 100%;
    min-width: 0;
  }

  /* Tokens */
  .token-create {
    display: grid;
    gap: 0.45rem;
    padding: 0.5rem 0 0.75rem;
  }

  .token-create-row {
    display: grid;
    gap: 0.6rem;
  }

  .token-list {
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .token-row {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 0.75rem;
    border-top: 1px solid var(--line);
    padding-block: 0.65rem;
  }

  .token-row:first-child {
    padding-top: 0;
    border-top: 0;
  }

  .token-row:last-child {
    padding-bottom: 0;
  }

  .token-detail {
    display: flex;
    min-width: 0;
    flex-wrap: wrap;
    gap: 0 0.75rem;
  }

  .token-detail strong {
    flex-basis: 100%;
    overflow-wrap: anywhere;
  }

  .token-detail span {
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .empty-state {
    margin: 0;
    color: var(--ink-muted);
  }

  .field-error {
    margin: 0;
    color: var(--danger);
    font-size: 0.87rem;
  }

  .notice-error,
  .inline-error {
    border-left: 3px solid var(--danger);
    margin: 0;
    padding: 0.55rem 0.75rem;
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 7%, var(--surface));
  }

  .inline-error p {
    margin-bottom: 0.25rem;
  }

  .page-status {
    margin: 0;
    color: var(--accent-strong);
    font-weight: 600;
  }

  /* Dialogs */
  .section-mark {
    margin: 0 0 0.35rem;
    color: var(--accent-strong);
    font-size: 0.9rem;
    font-weight: 650;
  }

  .account-dialog {
    width: min(34rem, calc(100vw - 2rem));
    max-height: min(90svh, 48rem);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
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

  :global(.copy-button),
  :global(.acknowledge-button) {
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

  @media (min-width: 40rem) {
    .account-page {
      gap: 1.25rem;
    }

    .card {
      padding: 1.5rem;
    }

    .card--footer {
      padding-block: 1rem;
    }

    .avatar__portrait {
      width: 6rem;
      font-size: 2.2rem;
    }

    .token-create-row {
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: start;
    }
  }

  @media (max-width: 39.99rem) {
    .row {
      flex-direction: column;
      align-items: stretch;
      gap: 0.6rem;
    }

    .row__control {
      flex: initial;
    }

    .row__control :global(.segmented),
    .row__control :global(.ui-button) {
      display: flex;
      width: 100%;
    }

    .row__control :global(.ui-button) {
      justify-content: center;
    }

    .stack-form .button-row :global(.ui-button),
    .token-create .button-row :global(.ui-button) {
      flex: 1 1 auto;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .disclosure,
    .card > .disclosure,
    .disclosure__clip,
    .avatar__badge {
      transition: none;
    }
  }
</style>
