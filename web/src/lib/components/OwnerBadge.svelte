<script lang="ts">
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { ResourceOwner } from "#lib/owner.ts";

  let {
    owner,
    locale = undefined,
  }: { owner: ResourceOwner; locale?: "en" | "de" } = $props();

  const label = $derived(
    t("Owned by {name}", locale).replace("{name}", owner.name),
  );
</script>

<span class="owner-badge" role="img" aria-label={label} title={label}>
  <UserAvatar avatarUrl={owner.avatar_url} name={owner.name} />
</span>

<style>
  .owner-badge {
    display: inline-flex;
    flex: none;
    border-radius: 50%;
    box-shadow: 0 0 0 2px var(--surface);
  }

  .owner-badge :global(.user-avatar) {
    flex-basis: 1.5rem;
    width: 1.5rem;
    height: 1.5rem;
    border-color: var(--accent-strong);
    font-size: 0.72rem;
  }
</style>
