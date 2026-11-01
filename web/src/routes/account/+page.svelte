<script lang="ts">
  import PreviewSection from "#lib/components/PreviewSection.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("Account", locale)}</title></svelte:head>
<PreviewSection
  title={t("Account", locale)}
  description={t(
    "Account settings will be added after the authentication foundation.",
    locale,
  )}
  {locale}
/>
