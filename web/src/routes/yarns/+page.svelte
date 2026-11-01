<script lang="ts">
  import PreviewSection from "#lib/components/PreviewSection.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("Yarns", locale)}</title></svelte:head>
<PreviewSection
  title={t("Yarns", locale)}
  description={t(
    "Yarn inventory and project links are planned for a later slice.",
    locale,
  )}
  {locale}
/>
