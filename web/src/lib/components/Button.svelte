<script lang="ts">
  import type { Snippet } from "svelte";
  import type { HTMLButtonAttributes } from "svelte/elements";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";

  type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
  type ButtonSize = "md" | "sm";
  type ButtonContent =
    | { children: Snippet; icon?: NavIconName }
    | { children?: undefined; icon: NavIconName; "aria-label": string };

  type ButtonProps = Omit<
    HTMLButtonAttributes,
    "children" | "class" | "type"
  > & {
    class?: string;
    element?: HTMLButtonElement | null;
    size?: ButtonSize;
    type?: "button" | "submit" | "reset";
    variant?: ButtonVariant;
  } & ButtonContent;

  let {
    children,
    class: className = "",
    element = $bindable(null),
    icon,
    size = "md",
    type = "button",
    variant = "secondary",
    ...restProps
  }: ButtonProps = $props();

  const classes = $derived(
    ["ui-button", `ui-button--${variant}`, `ui-button--${size}`, className]
      .filter(Boolean)
      .join(" "),
  );
</script>

<button bind:this={element} {...restProps} {type} class={classes}>
  {#if children}
    {#if icon}
      <ActionLabel {icon} {size}>{@render children()}</ActionLabel>
    {:else}
      {@render children()}
    {/if}
  {:else if icon}
    <NavIcon name={icon} />
  {/if}
</button>
