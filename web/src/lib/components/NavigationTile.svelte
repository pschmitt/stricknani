<script lang="ts">
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";
  import type { HTMLAnchorAttributes } from "svelte/elements";

  type NavigationTileProps = Omit<
    HTMLAnchorAttributes,
    "children" | "class" | "href"
  > & {
    class?: string;
    description: string;
    href: string;
    icon: NavIconName;
    title: string;
  };

  let {
    class: className = "",
    description,
    href,
    icon,
    title,
    ...restProps
  }: NavigationTileProps = $props();

  const classes = $derived(
    ["ui-navigation-tile", className].filter(Boolean).join(" "),
  );
</script>

<a {href} class={classes} {...restProps}>
  <span class="ui-navigation-tile__icon" aria-hidden="true">
    <NavIcon name={icon} />
  </span>
  <span class="ui-navigation-tile__copy">
    <span class="ui-navigation-tile__title">{title}</span>
    <span class="ui-navigation-tile__description">{description}</span>
  </span>
</a>

<style>
  .ui-navigation-tile__icon :global(svg) {
    width: var(--ui-navigation-icon-size);
    height: var(--ui-navigation-icon-size);
    stroke-width: 2;
  }
</style>
