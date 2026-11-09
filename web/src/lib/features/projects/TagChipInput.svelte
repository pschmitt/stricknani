<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { onMount, tick } from "svelte";
  import { listProjectTags } from "./project-api";
  import {
    mergeTags,
    splitTagInput,
    suggestTags,
    type TagCount,
  } from "./tag-input";

  interface Props {
    /** The finished tags, shown as chips. */
    tags: string[];
    /** The text still being typed after the last chip. */
    input: string;
    id: string;
    locale: Locale;
  }

  let { tags = $bindable(), input = $bindable(), id, locale }: Props = $props();

  let field = $state<HTMLInputElement | null>(null);
  let known = $state<TagCount[]>([]);
  let focused = $state(false);
  let dismissed = $state(false);
  let active = $state(-1);

  const listboxId = $derived(`${id}-listbox`);
  const suggestions = $derived(suggestTags(known, input, tags));
  const open = $derived(focused && !dismissed && suggestions.length > 0);
  const activeId = $derived(
    open && active >= 0 && active < suggestions.length
      ? `${id}-option-${active}`
      : undefined,
  );

  /** The tags are fetched once when the editor opens; no suggestions is no problem. */
  onMount(() => {
    const controller = new AbortController();
    listProjectTags(controller.signal)
      .then((found) => {
        known = found;
      })
      .catch(() => {});
    return () => controller.abort();
  });

  $effect(() => {
    // Any edit of the text starts a new list with nothing highlighted.
    void input;
    active = -1;
  });

  $effect(() => {
    if (!activeId) return;
    document.getElementById(activeId)?.scrollIntoView({ block: "nearest" });
  });

  /** Chips the typed text at spaces and commas; `commitAll` also takes the last piece. */
  function addTyped(commitAll = true): void {
    const split = splitTagInput(input, commitAll);
    if (split.tags.length) tags = mergeTags(tags, split.tags);
    input = split.rest;
  }

  /** Takes what is still typed as tags (before saving). */
  export function commit(): void {
    addTyped();
  }

  export function focus(): void {
    field?.focus();
  }

  function removeTag(index: number): void {
    tags = tags.filter((_, tagIndex) => tagIndex !== index);
  }

  async function pick(tag: string): Promise<void> {
    tags = mergeTags(tags, [tag]);
    input = "";
    active = -1;
    await tick();
    field?.focus();
  }

  function onKeydown(event: KeyboardEvent): void {
    const count = suggestions.length;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      if (!count) return;
      event.preventDefault();
      if (!open) {
        dismissed = false;
        active = event.key === "ArrowDown" ? 0 : count - 1;
        return;
      }
      const step = event.key === "ArrowDown" ? 1 : -1;
      active =
        active < 0 && step < 0 ? count - 1 : (active + step + count) % count;
    } else if (event.key === "Escape") {
      if (!open) return;
      event.preventDefault();
      event.stopPropagation();
      dismissed = true;
      active = -1;
    } else if (event.key === "Enter") {
      event.preventDefault();
      const chosen = activeId ? suggestions[active] : undefined;
      if (chosen) void pick(chosen.tag);
      else addTyped();
    } else if (event.key === "Tab") {
      const chosen = activeId ? suggestions[active] : undefined;
      if (chosen) {
        event.preventDefault();
        void pick(chosen.tag);
      }
    } else if (event.key === "Backspace" && input === "" && tags.length) {
      removeTag(tags.length - 1);
    }
  }
</script>

<div class="tag-field">
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
  <div
    class="tag-box"
    onclick={(event) => {
      if (event.target === event.currentTarget) field?.focus();
    }}
  >
    {#each tags as tag, index (`${index}:${tag}`)}
      <span class="tag-chip">
        <span class="tag-chip-text">{tag}</span>
        <button
          type="button"
          class="tag-chip-remove"
          aria-label={t("Remove tag {name}", locale).replace("{name}", tag)}
          onclick={() => {
            removeTag(index);
            field?.focus();
          }}
        >
          <NavIcon name="close" />
        </button>
      </span>
    {/each}
    <input
      {id}
      class="tag-box-input"
      bind:this={field}
      bind:value={input}
      role="combobox"
      aria-autocomplete="list"
      aria-expanded={open}
      aria-controls={listboxId}
      aria-activedescendant={activeId}
      autocomplete="off"
      autocapitalize="none"
      enterkeyhint="done"
      placeholder={tags.length ? "" : t("Add tags…", locale)}
      onfocus={() => {
        focused = true;
        dismissed = false;
      }}
      oninput={(event) => {
        dismissed = false;
        const { inputType } = event as unknown as InputEvent;
        addTyped(inputType === "insertFromPaste");
      }}
      onblur={() => {
        focused = false;
        addTyped();
      }}
      onkeydown={onKeydown}
    />
  </div>
  {#if open}
    <!-- Options keep the focus in the field: a press must not blur it. -->
    <ul
      class="tag-suggestions"
      id={listboxId}
      role="listbox"
      aria-label={t("Tag suggestions", locale)}
    >
      {#each suggestions as suggestion, index (suggestion.tag)}
        <!-- svelte-ignore a11y_click_events_have_key_events -->
        <li
          id={`${id}-option-${index}`}
          class="tag-suggestion"
          class:active={index === active}
          role="option"
          aria-selected={index === active}
          aria-label={suggestion.tag}
          onmousedown={(event) => event.preventDefault()}
          onclick={() => void pick(suggestion.tag)}
        >
          <span class="tag-suggestion-name">{suggestion.tag}</span>
          <span class="tag-suggestion-count" aria-hidden="true"
            >{suggestion.count}</span
          >
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .tag-field {
    position: relative;
  }

  /* The tag box looks like the other inputs and holds the tag chips in front of the cursor. */
  .tag-box {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.35rem;
    box-sizing: border-box;
    min-height: var(--ui-input-height);
    border: 1px solid var(--control-border);
    border-radius: var(--ui-input-radius);
    padding: 0.3rem 0.5rem;
    background: var(--surface-raised);
    cursor: text;
  }

  .tag-box:focus-within {
    outline: 3px solid var(--focus);
    outline-offset: 3px;
  }

  .tag-chip {
    display: inline-flex;
    align-items: center;
    min-width: 0;
    max-width: 100%;
    border-radius: 999px;
    padding: 0.1rem 0.15rem 0.1rem 0.65rem;
    background: var(--accent-wash);
    color: var(--accent-strong);
    font-size: 0.9rem;
    font-weight: 700;
    line-height: 1.3;
  }

  .tag-chip-text {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .tag-chip-remove {
    display: inline-grid;
    flex: none;
    width: 1.75rem;
    height: 1.75rem;
    margin-inline-start: 0.15rem;
    place-items: center;
    border: 0;
    border-radius: 999px;
    padding: 0;
    background: transparent;
    color: inherit;
    cursor: pointer;
  }

  .tag-chip-remove:hover {
    background: color-mix(in srgb, currentcolor 16%, transparent);
  }

  .tag-chip-remove :global(svg) {
    width: 0.85rem;
    height: 0.85rem;
  }

  .tag-box .tag-box-input {
    flex: 1 1 6rem;
    min-width: 6rem;
    min-height: 2rem;
    border: 0;
    padding: 0.2rem 0.25rem;
    background: transparent;
  }

  .tag-box .tag-box-input:focus-visible {
    outline: none;
  }

  .tag-suggestions {
    position: absolute;
    z-index: 20;
    inset-inline: 0;
    top: calc(100% + 0.35rem);
    box-sizing: border-box;
    max-height: min(16rem, 50vh);
    margin: 0;
    padding: 0.25rem;
    overflow-y: auto;
    list-style: none;
    border: 1px solid var(--control-border);
    border-radius: var(--ui-input-radius);
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  .tag-suggestion {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    min-height: 2.75rem;
    box-sizing: border-box;
    border-radius: calc(var(--ui-input-radius) - 4px);
    padding: 0.3rem 0.75rem;
    cursor: pointer;
  }

  .tag-suggestion:hover,
  .tag-suggestion.active {
    background: var(--accent-wash);
    color: var(--accent-strong);
  }

  .tag-suggestion.active {
    outline: 2px solid var(--focus);
    outline-offset: -2px;
  }

  .tag-suggestion-name {
    min-width: 0;
    overflow-wrap: anywhere;
    font-weight: 700;
  }

  .tag-suggestion-count {
    flex: none;
    color: var(--ink-muted);
    font-size: 0.8rem;
  }
</style>
