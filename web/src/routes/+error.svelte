<script lang="ts">
  import { page } from "$app/state";
  import { ERROR_COPY, errorKindForStatus } from "#lib/error-copy.ts";
  import ErrorPanel from "#lib/components/ErrorPanel.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  const online = $derived(
    typeof navigator === "undefined" ? true : navigator.onLine,
  );
  const kind = $derived(
    errorKindForStatus(page.status, page.error?.message ?? "", online),
  );
  // 5xx and dropped connections can recover; a missing page cannot.
  const retryable = $derived(kind === "server" || kind === "offline");
  const detail = $derived(
    kind === "server" && page.status >= 500
      ? `${page.status} ${page.error?.message ?? ""}`.trim()
      : "",
  );
</script>

<svelte:head>
  <title>{t(ERROR_COPY[kind].title, locale)} · Stricknani</title>
</svelte:head>

<ErrorPanel
  {kind}
  seed={page.url.pathname}
  {detail}
  onretry={retryable ? () => location.reload() : undefined}
/>
