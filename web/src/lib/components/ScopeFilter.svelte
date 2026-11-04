<script lang="ts">
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import {
    scopeFor,
    setScope,
    type LibraryScope,
  } from "#lib/library-scope.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  let { locale = undefined }: { locale?: "en" | "de" } = $props();

  const user = $derived(session.data?.user ?? null);
  const scope = $derived(scopeFor(user));
  const options = $derived<{ value: LibraryScope; label: string }[]>([
    { value: "mine", label: t("Mine", locale) },
    { value: "all", label: t("Everyone", locale) },
  ]);
</script>

{#if user?.is_admin}
  <div class="scope-filter" data-testid="scope-filter">
    <SegmentedControl
      {options}
      value={scope}
      label={t("Show", locale)}
      onchange={(value) => user && setScope(user, value)}
    />
  </div>
{/if}

<style>
  .scope-filter {
    display: flex;
    min-width: 0;
  }
</style>
