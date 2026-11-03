<script lang="ts">
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import QrCode from "#lib/components/QrCode.svelte";
  import { isAndroidUserAgent } from "#lib/app-links.ts";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { createApiToken, type ApiToken } from "./account-api.ts";
  import { setupTokenName, setupUri } from "./connect-app.ts";

  let {
    locale,
    oncreated,
  }: {
    locale: Locale;
    /** Called with the new token's metadata (never its secret). */
    oncreated?: (token: ApiToken) => void;
  } = $props();

  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let uri = $state("");
  let error = $state("");
  let busy = $state(false);
  let touch = $state(false);

  function isTouchDevice(): boolean {
    try {
      return (
        matchMedia("(pointer: coarse)").matches ||
        isAndroidUserAgent(navigator.userAgent)
      );
    } catch {
      return false;
    }
  }

  function failure(cause: unknown): string {
    if (cause instanceof ApiError && cause.code === "rate_limited") {
      return t(
        "Too many tokens were created recently. Try again later.",
        locale,
      );
    }
    if (cause instanceof ApiError && cause.code === "session_expired") {
      return t("Your session expired. Sign in again.", locale);
    }
    return t("The app could not be connected. Try again.", locale);
  }

  /** Create a setup token right away and show its QR code and app link. */
  export async function start(): Promise<void> {
    const current = session.data;
    if (busy || !current?.user) return;
    busy = true;
    error = "";
    uri = "";
    touch = isTouchDevice();
    dialog?.showModal();
    try {
      const created = await createApiToken(setupTokenName, current);
      const { token: secret, ...metadata } = created;
      uri = setupUri(location.origin, secret);
      oncreated?.(metadata);
    } catch (cause) {
      error = failure(cause);
    } finally {
      busy = false;
    }
  }

  function clear(): void {
    uri = "";
    error = "";
  }
</script>

<ConfirmDialog
  bind:this={dialog}
  titleId="connect-app-title"
  title={t("Connect the app", locale)}
  {busy}
  onclose={clear}
>
  {#if error}
    <p class="notice-error" role="alert">{error}</p>
  {:else if busy || !uri}
    <p aria-live="polite">{t("Working…", locale)}</p>
  {:else}
    <div class="connect-qr">
      <QrCode
        value={uri}
        label={t("QR code to connect the Stricknani app", locale)}
      />
    </div>
    <p class="connect-hint">
      {touch
        ? t("Open on this phone", locale)
        : t("Scan with the Stricknani app", locale)}
    </p>
  {/if}
  {#snippet actions()}
    {#if uri && touch}
      <a class="ui-button ui-button--primary ui-button--md" href={uri}>
        <ActionLabel icon="key">{t("Open in the app", locale)}</ActionLabel>
      </a>
    {/if}
    <Button
      variant={uri && touch ? "ghost" : "primary"}
      icon="close"
      onclick={() => dialog?.close()}
    >
      {t("Close", locale)}
    </Button>
  {/snippet}
</ConfirmDialog>

<style>
  .connect-qr {
    width: min(100%, 18rem);
    margin: 0 auto;
    padding: 0.5rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
  }

  .connect-hint {
    margin: 0.75rem 0 0;
    text-align: center;
  }
</style>
