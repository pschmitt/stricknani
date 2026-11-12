<script lang="ts">
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ActivityFeed from "#lib/features/home/ActivityFeed.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
</script>

<svelte:head><title>Stricknani · {t("My activity", locale)}</title></svelte:head
>

<section
  class="page-container page-container--compact"
  aria-labelledby="activity-title"
>
  <PageHeader title={t("My activity", locale)} titleId="activity-title" />
  {#if userId !== null}
    <ActivityFeed ownerId={userId} {locale} full />
  {/if}
</section>
