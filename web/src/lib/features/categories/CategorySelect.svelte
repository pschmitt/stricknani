<script lang="ts">
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import {
    NEW_CATEGORY_VALUE,
    categoryOptions,
    selectedCategory,
  } from "./category-options";

  interface Props {
    id: string;
    value: string;
    categories: ReadonlyArray<{ name: string }>;
    loading?: boolean;
    locale: Locale;
  }

  let {
    id,
    value = $bindable(),
    categories,
    loading = false,
    locale,
  }: Props = $props();

  let adding = $state(false);
  let newName = $state("");
  let input = $state<HTMLInputElement | null>(null);

  const options = $derived(categoryOptions(categories, value));
  const selected = $derived(selectedCategory(options, value));

  function onChange(event: Event) {
    const next = (event.currentTarget as HTMLSelectElement).value;
    if (next === NEW_CATEGORY_VALUE) {
      newName = "";
      adding = true;
      queueMicrotask(() => input?.focus());
      return;
    }
    value = next;
  }

  function confirm() {
    if (!adding) return;
    const name = newName.trim();
    adding = false;
    if (name) value = name;
  }

  function cancel() {
    adding = false;
    newName = "";
  }

  function onKeydown(event: KeyboardEvent) {
    if (event.key === "Enter") {
      event.preventDefault();
      confirm();
    } else if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      cancel();
    }
  }
</script>

<select
  {id}
  value={adding ? NEW_CATEGORY_VALUE : selected}
  onchange={onChange}
  disabled={loading}
>
  <option value="">{t("No category", locale)}</option>
  {#each options as name (name)}
    <option value={name}>{name}</option>
  {/each}
  <option value={NEW_CATEGORY_VALUE}>{t("New category…", locale)}</option>
</select>
{#if adding}
  <input
    id="{id}-new"
    bind:this={input}
    bind:value={newName}
    onkeydown={onKeydown}
    onblur={confirm}
    maxlength="128"
    autocomplete="off"
    aria-label={t("New category", locale)}
    placeholder={t("New category", locale)}
  />
{/if}
