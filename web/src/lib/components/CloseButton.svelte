<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { HTMLButtonAttributes } from "svelte/elements";

  type CloseButtonProps = Omit<
    HTMLButtonAttributes,
    "children" | "class" | "type"
  > & {
    class?: string;
    element?: HTMLButtonElement | null;
    /** Accessible name; defaults to "Close". Say what closes ("Dismiss message") when it helps. */
    label?: string;
    locale?: "en" | "de";
    type?: "button" | "submit" | "reset";
  };

  let {
    class: className = "",
    element = $bindable(null),
    label,
    locale,
    type = "button",
    ...restProps
  }: CloseButtonProps = $props();

  const name = $derived(label ?? t("Close", locale));
</script>

<button
  bind:this={element}
  {...restProps}
  {type}
  class={["close-button", className].filter(Boolean).join(" ")}
  aria-label={name}
  title={name}
>
  <NavIcon name="close" />
</button>
