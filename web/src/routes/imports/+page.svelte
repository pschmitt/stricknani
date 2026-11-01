<script lang="ts">
  import PreviewSection from "#lib/components/PreviewSection.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("Imports", locale)}</title></svelte:head>
<PreviewSection
  title={t("Imports", locale)}
  description={t("Import tools will be added in a later slice.", locale)}
  {locale}
/>
