<script lang="ts">
  import { onMount } from "svelte";
  import { replaceState } from "$app/navigation";
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    NO_AUTH_OPTIONS,
    cleanOidcQuery,
    formatLinkedDate,
    getAuthOptions,
    isReauthRequired,
    listIdentities,
    navigateToProvider,
    readOidcError,
    readOidcLinked,
    startLinkIdentity,
    unlinkIdentity,
    type AuthOptions,
    type LinkedIdentity,
    type OidcProvider,
    type ReauthRequest,
  } from "./oidc-api";
  import ReauthDialog from "./ReauthDialog.svelte";
  import { takePendingReauth } from "./reauth-flow";

  /**
   * "Linked logins" section of the Account page. Hidden entirely while the
   * server advertises no OIDC provider.
   */
  type Load = "loading" | "ready" | "error";

  let options = $state<AuthOptions>(NO_AUTH_OPTIONS);
  let identities = $state<LinkedIdentity[]>([]);
  let passwordEnabled = $state(true);
  let load = $state<Load>("loading");
  let loadError = $state("");
  let actionError = $state("");
  let busy = $state(false);
  let unlinkTarget = $state<LinkedIdentity | null>(null);
  let confirmDialog = $state<ReturnType<typeof ConfirmDialog>>();
  let reauthDialog = $state<ReturnType<typeof ReauthDialog>>();
  let resume = $state<ReauthRequest | null>(null);
  let generation = 0;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const enabled = $derived(options.oidc.length > 0);
  const providerById = $derived(
    new Map(options.oidc.map((provider) => [provider.id, provider])),
  );
  const unlinkedProviders = $derived(
    options.oidc.filter(
      (provider) => !identities.some((item) => item.provider === provider.id),
    ),
  );
  // Provider confirmation is only possible through an identity already linked.
  const reauthProvider = $derived<OidcProvider | null>(
    identities
      .map((identity) => providerById.get(identity.provider))
      .find((provider) => provider !== undefined) ?? null,
  );

  const providerName = (id: string): string => providerById.get(id)?.name ?? id;

  async function refresh(): Promise<void> {
    const request = ++generation;
    try {
      const result = await listIdentities();
      if (request !== generation) return;
      identities = result.identities;
      passwordEnabled = result.passwordEnabled;
      load = "ready";
      loadError = "";
    } catch (error) {
      if (request !== generation) return;
      load = "error";
      loadError =
        error instanceof ApiError
          ? error.message
          : t("The request could not be completed.", locale);
    }
  }

  onMount(() => {
    const search = window.location.search;
    const linked = readOidcLinked(search);
    const failed = readOidcError(search) !== null;
    const cleaned = cleanOidcQuery(window.location.href);
    if (cleaned !== null) {
      try {
        replaceState(cleaned, {});
      } catch {
        window.history.replaceState(window.history.state, "", cleaned);
      }
    }
    resume = takePendingReauth(
      typeof sessionStorage === "undefined" ? undefined : sessionStorage,
    );
    const controller = new AbortController();
    void getAuthOptions(controller.signal)
      .then(async (result) => {
        options = result;
        if (result.oidc.length === 0) return;
        await refresh();
        if (linked && userId !== null) {
          publishFeedback({
            ownerId: userId,
            kind: "success",
            message: t("Your login was linked to your account.", locale),
          });
        } else if (failed && userId !== null) {
          publishFeedback({
            ownerId: userId,
            kind: "error",
            message: t("Linking the login did not work. Try again.", locale),
          });
        }
      })
      .catch(() => undefined);
    return () => {
      controller.abort();
      generation += 1;
    };
  });

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
      if (isReauthRequired(error)) {
        busy = false;
        reauthDialog?.open(request);
        return;
      }
      actionError =
        error instanceof ApiError
          ? error.message
          : t("The request could not be completed.", locale);
    } finally {
      busy = false;
    }
  }

  async function startLink(providerId: string): Promise<void> {
    const request: ReauthRequest = {
      action: "link_identity",
      target: { provider: providerId },
    };
    resume = null;
    await withReauth(request, async () => {
      const start = await startLinkIdentity(
        providerId,
        session.data?.csrf_token ?? "",
      );
      navigateToProvider(start);
    });
  }

  function askUnlink(identity: LinkedIdentity): void {
    actionError = "";
    unlinkTarget = identity;
    confirmDialog?.showModal();
  }

  async function runUnlink(identity: LinkedIdentity): Promise<void> {
    const request: ReauthRequest = {
      action: "unlink_identity",
      target: { identity_id: identity.id },
    };
    await withReauth(request, async () => {
      await unlinkIdentity(identity.id, session.data?.csrf_token ?? "");
      if (userId !== null)
        publishFeedback({
          ownerId: userId,
          kind: "success",
          message: t(
            "{name} was unlinked from your account.",
            locale,
          ).replaceAll("{name}", providerName(identity.provider)),
        });
      await refresh();
    });
  }

  async function confirmUnlink(): Promise<void> {
    const target = unlinkTarget;
    confirmDialog?.close();
    if (target) await runUnlink(target);
  }

  /** The server stamped the session: retry what triggered the dialog. */
  async function afterReauth(request: ReauthRequest): Promise<void> {
    resume = null;
    if (request.action === "link_identity" && request.target?.provider)
      return startLink(request.target.provider);
    if (request.action === "unlink_identity") {
      const identity = identities.find(
        (item) => item.id === request.target?.identity_id,
      );
      if (identity) return runUnlink(identity);
    }
  }

  const resumeLabel = $derived(
    resume?.action === "unlink_identity"
      ? t("Continue unlinking", locale)
      : t("Continue linking", locale),
  );
</script>

{#if enabled}
  <section class="linked-logins" aria-labelledby="linked-logins-title">
    <header class="section-heading">
      <div>
        <h3 id="linked-logins-title">{t("Linked logins", locale)}</h3>
        <p>
          {t(
            "Sign in with a login provider instead of typing your password.",
            locale,
          )}
        </p>
      </div>
    </header>

    {#if actionError}
      <p class="notice-error" role="alert">{actionError}</p>
    {/if}

    {#if resume}
      <div class="resume" role="status">
        <p>
          {t("You confirmed it is you. Continue where you left off.", locale)}
        </p>
        <SharedButton
          variant="primary"
          size="sm"
          disabled={busy}
          onclick={() => void afterReauth(resume as ReauthRequest)}
          icon="check"
        >
          {resumeLabel}
        </SharedButton>
      </div>
    {/if}

    {#if load === "loading"}
      <p class="muted">{t("Loading linked logins…", locale)}</p>
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
      {#if !passwordEnabled}
        <p class="muted">
          {t("Password sign-in is not enabled for your account.", locale)}
        </p>
      {/if}
      {#if identities.length === 0}
        <p class="muted">{t("No logins are linked yet.", locale)}</p>
      {:else}
        <ul class="identity-list">
          {#each identities as identity (identity.id)}
            <li class="identity-row">
              <div class="identity-copy">
                <strong>{providerName(identity.provider)}</strong>
                <span class="identity-hint">{identity.subject_hint}</span>
                {#if identity.email}
                  <span class="identity-email">{identity.email}</span>
                {/if}
                <span class="muted">
                  {t("Linked on {date}", locale).replaceAll(
                    "{date}",
                    formatLinkedDate(identity.linked_at, locale),
                  )}
                </span>
                {#if !identity.can_unlink}
                  <span class="muted" id={`unlink-hint-${identity.id}`}>
                    {t(
                      "This is your only way to sign in, so it cannot be unlinked.",
                      locale,
                    )}
                  </span>
                {/if}
              </div>
              <SharedButton
                variant="ghost"
                size="sm"
                disabled={busy || !identity.can_unlink}
                aria-describedby={identity.can_unlink
                  ? undefined
                  : `unlink-hint-${identity.id}`}
                title={identity.can_unlink
                  ? undefined
                  : t(
                      "This is your only way to sign in, so it cannot be unlinked.",
                      locale,
                    )}
                onclick={() => askUnlink(identity)}
                icon="action-remove"
              >
                {t("Unlink", locale)}
              </SharedButton>
            </li>
          {/each}
        </ul>
      {/if}

      {#each unlinkedProviders as provider (provider.id)}
        <SharedButton
          variant="secondary"
          disabled={busy}
          onclick={() => void startLink(provider.id)}
          icon="link"
        >
          {t("Link {name}", locale).replaceAll("{name}", provider.name)}
        </SharedButton>
      {/each}
    {/if}
  </section>

  <ConfirmDialog
    bind:this={confirmDialog}
    titleId="unlink-login-title"
    title={unlinkTarget
      ? t("Unlink {name}?", locale).replaceAll(
          "{name}",
          providerName(unlinkTarget.provider),
        )
      : t("Unlink this login?", locale)}
    onclose={() => (unlinkTarget = null)}
  >
    <p>
      {t(
        "You will no longer be able to sign in with this login. You can link it again later.",
        locale,
      )}
    </p>
    {#snippet actions()}
      <SharedButton
        variant="ghost"
        onclick={() => confirmDialog?.close()}
        icon="cancel"
      >
        {t("Keep linked", locale)}
      </SharedButton>
      <SharedButton
        variant="danger"
        onclick={() => void confirmUnlink()}
        icon="action-remove"
      >
        {t("Unlink", locale)}
      </SharedButton>
    {/snippet}
  </ConfirmDialog>

  <ReauthDialog
    bind:this={reauthDialog}
    {locale}
    {passwordEnabled}
    oidcProvider={reauthProvider}
    onconfirmed={afterReauth}
  />
{/if}

<style>
  .linked-logins {
    display: grid;
    min-width: 0;
    gap: 0.9rem;
  }

  .section-heading {
    display: flex;
    align-items: flex-start;
    gap: 0.75rem;
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

  .resume {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.7rem 0.9rem;
    background: var(--surface-raised);
  }

  .resume p {
    margin: 0;
  }

  .identity-list {
    display: grid;
    gap: 0.6rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .identity-row {
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

  .identity-copy {
    display: grid;
    min-width: 0;
    gap: 0.15rem;
  }

  .identity-hint {
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: 0.9rem;
    overflow-wrap: anywhere;
  }

  .identity-email {
    overflow-wrap: anywhere;
  }
</style>
