<script lang="ts">
  import PreviewSection from "#lib/components/PreviewSection.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("Admin", locale)}</title></svelte:head>
<PreviewSection
  title={t("Admin", locale)}
  description={t("User administration is reserved for a later slice.", locale)}
  {locale}
/>
