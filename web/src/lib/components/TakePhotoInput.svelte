<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import { cameraCaptureLikely } from "#lib/components/camera-capture.ts";
  import { t, type Locale } from "#lib/i18n.svelte.ts";

  let {
    locale,
    onchange,
    disabled = false,
    size = "md",
  }: {
    locale: Locale;
    onchange: (event: Event) => void;
    disabled?: boolean;
    size?: "md" | "sm";
  } = $props();

  let input = $state<HTMLInputElement | null>(null);
  let available = $state(false);

  $effect(() => {
    available = cameraCaptureLikely(window);
  });
</script>

{#if available}
  <!-- The same upload handler as the picker next to it receives the photo. -->
  <Button
    type="button"
    variant="secondary"
    icon="camera"
    {size}
    {disabled}
    onclick={() => input?.click()}
  >
    {t("Take photo", locale)}
  </Button>
  <input
    bind:this={input}
    type="file"
    accept="image/*"
    capture="environment"
    hidden
    tabindex="-1"
    aria-hidden="true"
    {disabled}
    {onchange}
  />
{/if}
