<script lang="ts">
  import PreviewSection from "#lib/components/PreviewSection.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head
  ><title>Stricknani · {t("Gauge calculator", locale)}</title></svelte:head
>
<PreviewSection
  title={t("Gauge calculator", locale)}
  description={t(
    "The gauge calculator will be brought into the new web UI.",
    locale,
  )}
  {locale}
/>
