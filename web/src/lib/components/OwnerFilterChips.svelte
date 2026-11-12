<script lang="ts">
  // The owner part of a library's filter chips: "Mine", "Shared with me" and one chip per other
  // owner (name and avatar, never the email), then Trash. Android shows the same chips in the
  // same order.
  import FilterChip from "#lib/components/FilterChip.svelte";
  import UserLabel from "#lib/components/UserLabel.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { OwnerChoice } from "#lib/owner-filter.ts";
  import type { ResourceOwner } from "#lib/owner.ts";
  import { personUser } from "#lib/user-label.ts";

  interface Props {
    choice: OwnerChoice;
    /** The Trash chip that follows the owners; absent for lists without one. */
    trash?: { selected: boolean; ontoggle: () => void };
    owners: ResourceOwner[];
    locale: "en" | "de";
    /** `project` or `yarn`, for the test ids. */
    kind: string;
    onselect: (choice: OwnerChoice) => void;
  }

  let { choice, trash, owners, locale, kind, onselect }: Props = $props();

  function toggle(next: OwnerChoice) {
    onselect(choice === next ? "" : next);
  }
</script>

<FilterChip
  label={t("Mine", locale)}
  selected={choice === "me"}
  testid={`${kind}-filter-mine`}
  onclick={() => toggle("me")}
/>
<FilterChip
  label={t("Shared with me", locale)}
  selected={choice === "shared"}
  testid={`${kind}-filter-shared`}
  onclick={() => toggle("shared")}
/>
{#each owners as owner (owner.id)}
  <FilterChip
    label={owner.name}
    selected={choice === String(owner.id)}
    testid={`${kind}-filter-owner`}
    onclick={() => toggle(String(owner.id) as OwnerChoice)}
  >
    {#snippet content()}
      <UserLabel user={personUser(owner)} {locale} size="inline" />
    {/snippet}
  </FilterChip>
{/each}
{#if trash}
  <FilterChip
    label={t("Trash", locale)}
    selected={trash.selected}
    testid={`${kind}-filter-trash`}
    onclick={() => trash.ontoggle()}
  />
{/if}
