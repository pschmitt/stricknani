<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { ResourceOwner } from "#lib/owner.ts";

  let {
    kind,
    owner,
    locale = undefined,
  }: {
    kind: "project" | "yarn";
    owner: ResourceOwner;
    locale?: "en" | "de";
  } = $props();

  const text = $derived(
    (kind === "project"
      ? t(
          "This is {name}'s project. Changes you make are saved to their project and recorded in the audit log.",
          locale,
        )
      : t(
          "This is {name}'s yarn. Changes you make are saved to their yarn and recorded in the audit log.",
          locale,
        )
    ).replace("{name}", owner.name),
  );
</script>

<p class="foreign-banner" role="note" data-testid="foreign-owner-banner">
  <span class="foreign-banner__icon" aria-hidden="true"
    ><NavIcon name="info" /></span
  >
  <span>{text}</span>
</p>

<style>
  .foreign-banner {
    display: flex;
    align-items: flex-start;
    gap: 0.55rem;
    margin: 0;
    border: 1px solid color-mix(in srgb, var(--accent-strong) 35%, var(--line));
    border-radius: var(--radius-sm, 12px);
    padding: 0.5rem 0.75rem;
    color: var(--ink);
    background: var(--accent-wash);
    font-size: 0.88rem;
    line-height: 1.4;
  }

  .foreign-banner__icon {
    display: inline-flex;
    flex: none;
    margin-top: 0.1rem;
    color: var(--accent-strong);
  }

  .foreign-banner__icon :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }
</style>
