<script
  lang="ts"
  generics="T extends { id: number; thumbnail_url: string; alt_text: string | null }"
>
  import { tick, type Snippet } from "svelte";
  import Button from "#lib/components/Button.svelte";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { deltaForKey, keyboardTarget, nearestIndex } from "./media-reorder";

  let {
    items,
    locale,
    label,
    fallbackAlt,
    showCover = false,
    disabled = false,
    reorderable = true,
    onMove,
    onOpen,
    badge,
    actions,
  }: {
    items: readonly T[];
    locale: "en" | "de";
    /** Accessible name of the group, for example the step title. */
    label: string;
    fallbackAlt: string;
    /** Label the first item as the cover. */
    showCover?: boolean;
    disabled?: boolean;
    /** False hides the reorder controls, for read-only views. */
    reorderable?: boolean;
    /** Resolve true when the server accepted the new order. */
    onMove: (from: number, to: number) => Promise<boolean>;
    onOpen?: (item: T) => void;
    badge?: Snippet<[T, number]>;
    actions?: Snippet<[T]>;
  } = $props();

  let listElement = $state<HTMLUListElement | null>(null);
  let dragFrom = $state<number | null>(null);
  let dropTo = $state<number | null>(null);
  let announcement = $state("");
  let moving = $state(false);

  const locked = $derived(disabled || moving);
  const sortable = $derived(reorderable && items.length > 1);

  function fill(template: string, position: number): string {
    return t(template, locale)
      .replace("{position}", String(position))
      .replace("{total}", String(items.length));
  }

  async function move(from: number, to: number): Promise<void> {
    if (locked || from === to || to < 0 || to >= items.length) return;
    const id = items[from].id;
    moving = true;
    let ok = false;
    try {
      ok = await onMove(from, to);
    } finally {
      moving = false;
    }
    announcement = ok
      ? fill(msg("Photo moved to position {position} of {total}."), to + 1)
      : t(
          "The new photo order could not be saved. The previous order was restored.",
          locale,
        );
    await tick();
    listElement
      ?.querySelector<HTMLElement>(`[data-handle="${id}"]`)
      ?.focus({ preventScroll: true });
  }

  function onKeydown(event: KeyboardEvent, index: number): void {
    if (event.key === "Escape" && dragFrom !== null) {
      cancelDrag();
      return;
    }
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const delta = deltaForKey(
      event.key,
      getComputedStyle(event.currentTarget as HTMLElement).direction === "rtl",
    );
    if (delta === null) return;
    event.preventDefault();
    void move(index, keyboardTarget(index, delta, items.length));
  }

  function startDrag(event: PointerEvent, index: number): void {
    if (locked || !sortable) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    event.preventDefault();
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    dragFrom = index;
    dropTo = index;
  }

  function dragOver(event: PointerEvent): void {
    if (dragFrom === null || !listElement) return;
    const tiles = Array.from(listElement.children) as HTMLElement[];
    const centers = tiles.map((tile) => {
      const rect = tile.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    });
    dropTo = nearestIndex(centers, event.clientX, event.clientY);
  }

  function endDrag(event: PointerEvent): void {
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId))
      target.releasePointerCapture(event.pointerId);
    const from = dragFrom;
    const to = dropTo;
    dragFrom = null;
    dropTo = null;
    if (from !== null && to !== null && from !== to) void move(from, to);
  }

  function cancelDrag(): void {
    dragFrom = null;
    dropTo = null;
  }
</script>

<div class="reorderable">
  {#if sortable}
    <p class="reorder-hint">
      {showCover
        ? t(
            "The first photo is the cover. Drag the handle or use the arrow keys to change the order.",
            locale,
          )
        : t(
            "Drag the handle or use the arrow keys to change the order.",
            locale,
          )}
    </p>
  {/if}
  <ul
    class="reorder-list"
    class:dragging={dragFrom !== null}
    bind:this={listElement}
    aria-label={label}
  >
    {#each items as item, index (item.id)}
      <li
        class="reorder-item"
        class:drag-source={dragFrom === index}
        class:drop-target={dragFrom !== null &&
          dropTo === index &&
          dropTo !== dragFrom}
      >
        {#if onOpen}
          <button
            type="button"
            class="thumb thumb-button"
            aria-label={item.alt_text ?? fallbackAlt}
            onclick={() => onOpen(item)}
          >
            <img
              src={item.thumbnail_url}
              alt={item.alt_text ?? fallbackAlt}
              loading="lazy"
              draggable="false"
            />
          </button>
        {:else}
          <div class="thumb">
            <img
              src={item.thumbnail_url}
              alt={item.alt_text ?? fallbackAlt}
              loading="lazy"
              draggable="false"
            />
          </div>
        {/if}
        <div class="reorder-meta">
          {#if showCover && index === 0}
            <span class="cover-badge">{t("Cover photo", locale)}</span>
          {:else if badge}
            {@render badge(item, index)}
          {/if}
        </div>
        {#if sortable}
          <div class="reorder-controls">
            <Button
              type="button"
              size="sm"
              variant="ghost"
              icon="previous"
              disabled={locked || index === 0}
              aria-label={fill(msg("Move photo {position} earlier"), index + 1)}
              onclick={() => void move(index, index - 1)}
            />
            <button
              type="button"
              class="drag-handle"
              data-handle={item.id}
              disabled={locked}
              aria-label={fill(
                msg("Reorder photo {position} of {total}"),
                index + 1,
              )}
              aria-keyshortcuts="ArrowUp ArrowDown ArrowLeft ArrowRight"
              onpointerdown={(event) => startDrag(event, index)}
              onpointermove={dragOver}
              onpointerup={endDrag}
              onpointercancel={cancelDrag}
              onkeydown={(event) => onKeydown(event, index)}
            >
              <svg
                viewBox="0 0 16 16"
                width="18"
                height="18"
                aria-hidden="true"
              >
                <circle cx="5" cy="3" r="1.4" /><circle
                  cx="11"
                  cy="3"
                  r="1.4"
                />
                <circle cx="5" cy="8" r="1.4" /><circle
                  cx="11"
                  cy="8"
                  r="1.4"
                />
                <circle cx="5" cy="13" r="1.4" /><circle
                  cx="11"
                  cy="13"
                  r="1.4"
                />
              </svg>
            </button>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              icon="next"
              disabled={locked || index === items.length - 1}
              aria-label={fill(msg("Move photo {position} later"), index + 1)}
              onclick={() => void move(index, index + 1)}
            />
          </div>
        {/if}
        {#if actions}
          <div class="reorder-actions">{@render actions(item)}</div>
        {/if}
      </li>
    {/each}
  </ul>
  <p class="visually-hidden" role="status" aria-live="polite">
    {announcement}
  </p>
</div>

<style>
  .reorderable {
    display: grid;
    gap: 0.5rem;
  }

  .reorder-hint {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .reorder-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 8rem), 1fr));
    gap: 0.65rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .reorder-item {
    display: grid;
    min-width: 0;
    align-content: start;
    gap: 0.35rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0.35rem;
    background: var(--surface-raised);
  }

  .thumb {
    display: block;
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    border: 0;
    border-radius: var(--radius-xs);
    padding: 0;
    background: var(--surface);
  }

  .thumb-button {
    cursor: zoom-in;
  }

  .thumb img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    user-select: none;
    -webkit-user-drag: none;
  }

  .reorder-meta {
    min-height: 1.2rem;
    color: var(--ink-muted);
    font-size: 0.8rem;
  }

  .cover-badge {
    display: inline-block;
    border-radius: 999px;
    padding: 0.05rem 0.5rem;
    color: var(--accent-strong);
    background: var(--surface);
    font-weight: 650;
  }

  .reorder-controls {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.25rem;
  }

  .reorder-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.35rem;
  }

  .drag-handle {
    display: inline-flex;
    min-width: 44px;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0;
    color: var(--ink-muted);
    background: var(--surface);
    cursor: grab;
    touch-action: none;
    user-select: none;
  }

  .drag-handle svg {
    fill: currentColor;
  }

  .drag-handle:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .drag-handle:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }

  .dragging .drag-handle {
    cursor: grabbing;
  }

  .drag-source {
    opacity: 0.55;
  }

  .drop-target {
    outline: 3px solid var(--accent-strong);
    outline-offset: 1px;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }

  @media (prefers-reduced-motion: no-preference) {
    .reorder-item {
      transition:
        opacity 120ms ease,
        outline-color 120ms ease;
    }
  }
</style>
