<script lang="ts">
  import UserLabel from "#lib/components/UserLabel.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { ResourceOwner } from "#lib/owner.ts";
  import { session } from "#lib/session.svelte.ts";
  import { personUser } from "#lib/user-label.ts";

  // Who owns a card's project or yarn: the shared `UserLabel` (avatar + name) on a small pill that
  // sits over the card's picture. The name shortens before the pill outgrows the picture.
  let {
    owner,
    locale = undefined,
  }: { owner: ResourceOwner; locale?: "en" | "de" } = $props();

  const shown = $derived(locale ?? session.data?.locale ?? "en");
  const label = $derived(
    t("Owned by {name}", shown).replace("{name}", owner.name),
  );
</script>

<span class="owner-badge" title={label} data-testid="owner-badge">
  <UserLabel user={personUser(owner)} locale={shown} size="inline" />
</span>

<style>
  .owner-badge {
    display: inline-flex;
    min-width: 0;
    max-width: 100%;
    border-radius: 999px;
    padding: 0.12rem 0.5rem 0.12rem 0.15rem;
    color: var(--ink);
    font-size: 0.72rem;
    font-weight: 650;
    background: color-mix(in srgb, var(--surface) 92%, transparent);
    box-shadow: 0 0 0 1px var(--line);
  }

  .owner-badge :global(.user-label__name) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
