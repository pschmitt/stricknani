<script lang="ts">
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import QrCode from "#lib/components/QrCode.svelte";
  import { androidAction } from "#lib/app-links.ts";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import ConnectAppDialog from "./ConnectAppDialog.svelte";

  let {
    locale,
    onclose,
  }: {
    locale: Locale;
    /** Runs when the dialog closes without handing over to "Connect the app". */
    onclose?: () => void;
  } = $props();

  const action = $derived(androidAction());
  let dialog = $state<ReturnType<typeof ConfirmDialog>>();
  let connectDialog = $state<ReturnType<typeof ConnectAppDialog>>();
  let connecting = false;

  export function show(): void {
    connecting = false;
    dialog?.showModal();
  }

  function connect(): void {
    connecting = true;
    dialog?.close();
    void connectDialog?.start();
  }

  function closed(): void {
    if (!connecting) onclose?.();
  }
</script>

<ConfirmDialog
  bind:this={dialog}
  titleId="android-app-title"
  title={t("Get the Android app", locale)}
  onclose={closed}
>
  <div class="android-qr">
    <QrCode
      value={action.url}
      label={t("QR code for the Android app download", locale)}
    />
  </div>
  <p class="android-hint">
    {t("Scan with your phone to get the app.", locale)}
  </p>
  {#snippet actions()}
    <a
      class="ui-button ui-button--primary ui-button--md"
      href={action.url}
      target="_blank"
      rel="noopener noreferrer"
    >
      <ActionLabel icon="download">
        {action.kind === "play"
          ? t("Get it on Google Play", locale)
          : t("Download APK", locale)}
      </ActionLabel>
    </a>
    <Button variant="secondary" icon="key" onclick={connect}>
      {t("Connect the app", locale)}
    </Button>
    <Button variant="ghost" icon="close" onclick={() => dialog?.close()}>
      {t("Close", locale)}
    </Button>
  {/snippet}
</ConfirmDialog>

<ConnectAppDialog bind:this={connectDialog} {locale} />

<style>
  .android-qr {
    box-sizing: border-box;
    width: min(100%, 14rem);
    margin: 0 auto;
    padding: 0.5rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: #ffffff;
  }

  .android-hint {
    margin: 0.75rem 0 0;
    color: var(--ink-muted);
    text-align: center;
  }
</style>
