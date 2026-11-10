<script lang="ts">
  import ActionOverflow from "#lib/components/ActionOverflow.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import {
    scopeFor,
    setScope,
    type LibraryScope,
  } from "#lib/library-scope.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  let {
    locale = undefined,
    variant = "segmented",
  }: {
    locale?: "en" | "de";
    /** "menu": a ⋮ button whose menu holds the choice (the home page's header). */
    variant?: "segmented" | "menu";
  } = $props();

  const user = $derived(session.data?.user ?? null);
  const scope = $derived(scopeFor(user));
  const options = $derived<{ value: LibraryScope; label: string }[]>([
    { value: "mine", label: t("Mine", locale) },
    { value: "all", label: t("All", locale) },
  ]);
</script>

{#if user?.is_admin && variant === "menu"}
  <div class="scope-filter" data-testid="scope-filter">
    <ActionOverflow label={t("Show", locale)}>
      {#snippet children(close)}
        <div
          class="action-overflow__menu"
          role="menu"
          aria-label={t("Show", locale)}
        >
          {#each options as option (option.value)}
            <button
              class="action-overflow__item"
              role="menuitemradio"
              aria-checked={scope === option.value}
              type="button"
              onclick={() => {
                close();
                if (user) setScope(user, option.value);
              }}
            >
              <span class="scope-filter__check" aria-hidden="true">
                {#if scope === option.value}<NavIcon name="check" />{/if}
              </span>
              <span>{option.label}</span>
            </button>
          {/each}
        </div>
      {/snippet}
    </ActionOverflow>
  </div>
{:else if user?.is_admin}
  <div class="scope-filter" data-testid="scope-filter">
    <SegmentedControl
      {options}
      value={scope}
      label={t("Show", locale)}
      onchange={(value) => user && setScope(user, value)}
    />
  </div>
{/if}

<style>
  .scope-filter {
    display: flex;
    min-width: 0;
  }

  .scope-filter__check {
    display: inline-grid;
    width: 1.1rem;
    place-items: center;
  }
</style>
