<script lang="ts">
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";
  import type { Snippet } from "svelte";
  import type { HTMLAnchorAttributes } from "svelte/elements";

  type NavigationTileProps = Omit<
    HTMLAnchorAttributes,
    "children" | "class" | "href"
  > & {
    class?: string;
    description: string;
    href: string;
    icon?: NavIconName;
    /** Replaces the icon (for example the account avatar), same slot size. */
    leading?: Snippet;
    title: string;
  };

  let {
    class: className = "",
    description,
    href,
    icon,
    leading,
    title,
    ...restProps
  }: NavigationTileProps = $props();

  const classes = $derived(
    ["ui-navigation-tile", className].filter(Boolean).join(" "),
  );
</script>

<a {href} class={classes} {...restProps}>
  <span class="ui-navigation-tile__icon" aria-hidden="true">
    {#if leading}
      {@render leading()}
    {:else if icon}
      <NavIcon name={icon} />
    {/if}
  </span>
  <span class="ui-navigation-tile__copy">
    <span class="ui-navigation-tile__title">{title}</span>
    <span class="ui-navigation-tile__description">{description}</span>
  </span>
  <span class="ui-navigation-tile__chevron" aria-hidden="true">
    <NavIcon name="next" />
  </span>
</a>

<style>
  .ui-navigation-tile__icon :global(.user-avatar) {
    flex-basis: var(--ui-navigation-icon-size);
    width: var(--ui-navigation-icon-size);
    height: var(--ui-navigation-icon-size);
    font-size: 0.7rem;
  }

  .ui-navigation-tile__chevron :global(svg),
  .ui-navigation-tile__icon :global(svg) {
    width: var(--ui-navigation-icon-size);
    height: var(--ui-navigation-icon-size);
    stroke-width: 2;
  }

  .ui-navigation-tile__chevron :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }
</style>
