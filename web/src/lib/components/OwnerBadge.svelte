<script lang="ts">
  import UserAvatar from "#lib/components/UserAvatar.svelte";
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
    compact = false,
  }: {
    owner: ResourceOwner;
    locale?: "en" | "de";
    compact?: boolean;
  } = $props();

  const shown = $derived(locale ?? session.data?.locale ?? "en");
  const label = $derived(
    t("Owned by {name}", shown).replace("{name}", owner.name),
  );
</script>

<span
  class="owner-badge"
  class:compact
  role={compact ? "img" : undefined}
  title={label}
  aria-label={label}
  data-testid="owner-badge"
>
  {#if compact}
    <UserAvatar avatarUrl={owner.avatar_url} name={owner.name} />
  {:else}
    <UserLabel user={personUser(owner)} locale={shown} size="inline" />
  {/if}
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

  .owner-badge.compact {
    padding: 0.12rem;
    border-radius: 50%;
  }

  .owner-badge.compact :global(.user-avatar) {
    width: 1.4rem;
    height: 1.4rem;
    flex-basis: 1.4rem;
    font-size: 0.7rem;
  }

  .owner-badge :global(.user-label__name) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
