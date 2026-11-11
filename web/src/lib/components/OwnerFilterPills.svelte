<script lang="ts">
  // The owner part of a library's filter pills: "Mine", "Shared with me" and one pill per other
  // owner (name and avatar, never the email). Android shows the same chips in the same order.
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { OwnerChoice } from "#lib/owner-filter.ts";
  import type { ResourceOwner } from "#lib/owner.ts";

  interface Props {
    choice: OwnerChoice;
    owners: ResourceOwner[];
    locale: "en" | "de";
    /** `project` or `yarn`, for the test ids. */
    kind: string;
    onselect: (choice: OwnerChoice) => void;
  }

  let { choice, owners, locale, kind, onselect }: Props = $props();

  function toggle(next: OwnerChoice) {
    onselect(choice === next ? "" : next);
  }
</script>

<button
  class="filter-pill"
  class:filter-pill--selected={choice === "me"}
  type="button"
  aria-pressed={choice === "me"}
  data-testid={`${kind}-filter-mine`}
  onclick={() => toggle("me")}
>
  {t("Mine", locale)}
</button>
<button
  class="filter-pill"
  class:filter-pill--selected={choice === "shared"}
  type="button"
  aria-pressed={choice === "shared"}
  data-testid={`${kind}-filter-shared`}
  onclick={() => toggle("shared")}
>
  {t("Shared with me", locale)}
</button>
{#each owners as owner (owner.id)}
  <button
    class="filter-pill"
    class:filter-pill--selected={choice === String(owner.id)}
    type="button"
    aria-pressed={choice === String(owner.id)}
    data-testid={`${kind}-filter-owner`}
    onclick={() => toggle(String(owner.id) as OwnerChoice)}
  >
    <UserAvatar avatarUrl={owner.avatar_url} name={owner.name} />
    {owner.name}
  </button>
{/each}

<style>
  .filter-pill {
    display: inline-flex;
    min-height: 44px;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.45rem 0.85rem;
    color: var(--ink);
    background: var(--surface);
    font: inherit;
    font-size: 0.9rem;
    cursor: pointer;
  }

  .filter-pill:hover {
    border-color: var(--accent-strong);
  }

  .filter-pill:focus-visible {
    outline: 3px solid color-mix(in srgb, var(--accent-strong) 45%, transparent);
    outline-offset: 2px;
  }

  .filter-pill--selected {
    border-color: var(--primary-container);
    color: var(--on-primary-container);
    background: var(--primary-container);
    font-weight: 650;
  }
</style>
