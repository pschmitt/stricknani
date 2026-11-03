<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import { t } from "#lib/i18n.svelte.ts";

  let {
    favorite,
    busy,
    locale,
    onclick,
  }: {
    favorite: boolean;
    busy: boolean;
    locale: "en" | "de";
    onclick: () => void;
  } = $props();

  const label = $derived(
    favorite
      ? t("Remove from favorites", locale)
      : t("Add to favorites", locale),
  );
</script>

<Button
  class="favorite-button"
  icon="favorite"
  size="md"
  variant="secondary"
  aria-label={label}
  aria-pressed={favorite}
  aria-busy={busy}
  disabled={busy}
  {onclick}
/>

<style>
  :global(.favorite-button.ui-button) {
    width: 44px;
    min-width: 44px;
    height: 44px;
    min-height: 44px;
    padding: 0;
  }

  :global(.favorite-button[aria-pressed="true"] svg) {
    fill: currentColor;
  }
</style>
