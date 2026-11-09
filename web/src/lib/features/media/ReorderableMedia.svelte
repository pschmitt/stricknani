<script
  lang="ts"
  generics="T extends { id: number; thumbnail_url: string; alt_text: string | null }"
>
  import { onMount, tick, type Snippet } from "svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import {
    canMakeCover,
    deltaForKey,
    keyboardTarget,
    nearestIndex,
  } from "./media-reorder";

  /** Touch press time before a tile arms for drag. */
  const LONG_PRESS_MS = 400;
  /** Pointer travel in px that cancels a pending long press or starts a mouse drag. */
  const MOVE_SLOP = 8;

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
    onDelete,
    deleteDisabled = false,
    onEdit,
    editDisabled = false,
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
    /** Opens the caller's delete confirmation; omit to hide delete. */
    onDelete?: (item: T) => void;
    deleteDisabled?: boolean;
    /** Opens the photo editor; omit to hide the "Edit photo" button. */
    onEdit?: (item: T) => void;
    editDisabled?: boolean;
    badge?: Snippet<[T, number]>;
    actions?: Snippet<[T]>;
  } = $props();

  interface Gesture {
    id: number;
    index: number;
    pointerId: number;
    pointerType: string;
    startX: number;
    startY: number;
    timer: ReturnType<typeof setTimeout> | null;
    armed: boolean;
    tile: HTMLElement;
  }

  const hintId = $props.id();
  let listElement = $state<HTMLUListElement | null>(null);
  let dragFrom = $state<number | null>(null);
  let dropTo = $state<number | null>(null);
  // Tile id whose long press armed a touch drag, for a calm visual cue.
  let armedId = $state<number | null>(null);
  let announcement = $state("");
  let moving = $state(false);
  let gesture: Gesture | null = null;
  // The click that follows a drag or an armed long press must not open the photo.
  let suppressClick = false;
  let suppressTimer: ReturnType<typeof setTimeout> | null = null;

  const locked = $derived(disabled || moving);
  const sortable = $derived(reorderable && items.length > 1);

  function fill(template: string, position: number): string {
    return t(template, locale)
      .replace("{position}", String(position))
      .replace("{total}", String(items.length));
  }

  function tileLabel(item: T, index: number): string {
    const position = fill(msg("Photo {position} of {total}"), index + 1);
    return `${item.alt_text ?? fallbackAlt}: ${position}`;
  }

  onMount(() => {
    // Once armed, stop the browser from scrolling so the drag can follow the finger.
    const block = (event: TouchEvent) => {
      if (gesture?.armed || dragFrom !== null) event.preventDefault();
    };
    listElement?.addEventListener("touchmove", block, { passive: false });
    return () => {
      listElement?.removeEventListener("touchmove", block);
      clearSuppress();
    };
  });

  function focusTile(id: number): void {
    listElement
      ?.querySelector<HTMLElement>(`[data-tile="${id}"]`)
      ?.focus({ preventScroll: true });
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
    focusTile(id);
  }

  /** Make a photo the cover: the same single reorder request, to position 0. */
  async function makeCover(index: number): Promise<void> {
    if (!coverAllowed(index)) return;
    const from = index;
    const id = items[from].id;
    moving = true;
    let ok = false;
    try {
      ok = await onMove(from, 0);
    } finally {
      moving = false;
    }
    announcement = ok
      ? t("Photo set as cover.", locale)
      : t(
          "The new photo order could not be saved. The previous order was restored.",
          locale,
        );
    await tick();
    focusTile(id);
  }

  function coverAllowed(index: number): boolean {
    return (
      showCover && !locked && sortable && canMakeCover(index, items.length)
    );
  }

  function canDelete(): boolean {
    return onDelete !== undefined && !deleteDisabled && !locked;
  }

  function onTileKeydown(event: KeyboardEvent, index: number): void {
    // Only the tile itself; inner buttons keep their own keys.
    if (event.target !== event.currentTarget) return;
    if (event.key === "Escape") {
      cancelGesture();
      return;
    }
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    if ((event.key === "Enter" || event.key === " ") && onOpen) {
      event.preventDefault();
      onOpen(items[index]);
      return;
    }
    if (event.key === "Delete" || event.key === "Backspace") {
      if (!onDelete) return;
      event.preventDefault();
      if (canDelete()) onDelete(items[index]);
      return;
    }
    if (!sortable) return;
    const delta = deltaForKey(
      event.key,
      getComputedStyle(event.currentTarget as HTMLElement).direction === "rtl",
    );
    if (delta === null) return;
    event.preventDefault();
    void move(index, keyboardTarget(index, delta, items.length));
  }

  function clearSuppress(): void {
    if (suppressTimer) clearTimeout(suppressTimer);
    suppressTimer = null;
    suppressClick = false;
  }

  function onPointerDown(event: PointerEvent, index: number): void {
    // A new press never inherits the suppression of the previous one.
    clearSuppress();
    if (locked) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if ((event.target as Element).closest("[data-no-drag]")) return;
    cancelGesture();
    const tile = event.currentTarget as HTMLElement;
    // The grip drags straight away on touch; elsewhere a long press arms it.
    const onHandle =
      (event.target as Element).closest("[data-handle]") !== null;
    const current: Gesture = {
      id: items[index].id,
      index,
      pointerId: event.pointerId,
      pointerType: event.pointerType,
      startX: event.clientX,
      startY: event.clientY,
      timer: null,
      armed: onHandle && event.pointerType !== "mouse" && sortable,
      tile,
    };
    gesture = current;
    if (current.armed) {
      armedId = current.id;
      tile.setPointerCapture(current.pointerId);
    } else if (event.pointerType !== "mouse" && sortable) {
      current.timer = setTimeout(() => {
        current.timer = null;
        if (gesture !== current) return;
        current.armed = true;
        armedId = current.id;
        tile.setPointerCapture(current.pointerId);
      }, LONG_PRESS_MS);
    }
  }

  function onPointerMove(event: PointerEvent): void {
    const current = gesture;
    if (!current || event.pointerId !== current.pointerId) return;
    const travelled = Math.hypot(
      event.clientX - current.startX,
      event.clientY - current.startY,
    );
    if (dragFrom === null) {
      if (travelled <= MOVE_SLOP) return;
      if (current.pointerType === "mouse") {
        if (!sortable) return;
        current.tile.setPointerCapture(current.pointerId);
      } else if (!current.armed || !sortable) {
        // Moving before the long press means the user is scrolling.
        if (!current.armed) cancelGesture();
        return;
      }
      dragFrom = current.index;
    }
    if (!listElement) return;
    const tiles = Array.from(listElement.children) as HTMLElement[];
    dropTo = nearestIndex(
      tiles.map((tile) => {
        const rect = tile.getBoundingClientRect();
        return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
      }),
      event.clientX,
      event.clientY,
    );
  }

  function onPointerUp(event: PointerEvent): void {
    const current = gesture;
    if (!current || event.pointerId !== current.pointerId) return;
    const from = dragFrom;
    const to = dropTo;
    // A finished drag or an armed long press must not also open the photo: the
    // browser still fires a click on lift. If none arrives, drop the flag so the
    // next real tap is not swallowed.
    clearSuppress();
    if (from !== null || current.armed) {
      suppressClick = true;
      suppressTimer = setTimeout(clearSuppress, 600);
    }
    cancelGesture();
    if (from !== null && to !== null && from !== to) void move(from, to);
  }

  function cancelGesture(): void {
    const current = gesture;
    if (current?.timer) clearTimeout(current.timer);
    if (current?.tile.hasPointerCapture(current.pointerId))
      current.tile.releasePointerCapture(current.pointerId);
    gesture = null;
    armedId = null;
    dragFrom = null;
    dropTo = null;
  }

  function onClickCapture(event: MouseEvent): void {
    if (!suppressClick) return;
    clearSuppress();
    event.preventDefault();
    event.stopPropagation();
  }

  function onContextMenu(event: MouseEvent): void {
    // The long press must not open the browser image menu on touch.
    if (gesture && gesture.pointerType !== "mouse") event.preventDefault();
  }
</script>

<div class="reorderable">
  <ul
    class="reorder-list"
    class:dragging={dragFrom !== null}
    bind:this={listElement}
    aria-label={label}
  >
    {#each items as item, index (item.id)}
      <!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
      <li
        class="reorder-item"
        class:sortable
        class:drag-source={dragFrom === index}
        class:armed={armedId === item.id}
        class:drop-target={dragFrom !== null &&
          dropTo === index &&
          dropTo !== dragFrom}
        data-tile={item.id}
        tabindex="0"
        aria-label={tileLabel(item, index)}
        aria-describedby={sortable ? hintId : undefined}
        aria-keyshortcuts={sortable
          ? "ArrowUp ArrowDown ArrowLeft ArrowRight"
          : undefined}
        onpointerdown={(event) => onPointerDown(event, index)}
        onpointermove={onPointerMove}
        onpointerup={onPointerUp}
        onpointercancel={cancelGesture}
        onclickcapture={onClickCapture}
        oncontextmenu={onContextMenu}
        onkeydown={(event) => onTileKeydown(event, index)}
      >
        <div class="tile-media">
          {#if onOpen}
            <button
              type="button"
              class="thumb thumb-button"
              tabindex="-1"
              aria-label={item.alt_text ?? fallbackAlt}
              onclick={() => onOpen(item)}
            >
              <img
                src={item.thumbnail_url}
                alt=""
                loading="lazy"
                draggable="false"
              />
            </button>
          {:else}
            <div class="thumb">
              <img
                src={item.thumbnail_url}
                alt=""
                loading="lazy"
                draggable="false"
              />
            </div>
          {/if}
          {#if showCover && index === 0}
            <span class="cover-badge" title={t("Cover photo", locale)}>
              <NavIcon name="star" />
              <span class="visually-hidden">{t("Cover photo", locale)}</span>
            </span>
          {:else if badge}
            <span class="cover-badge">{@render badge(item, index)}</span>
          {/if}
          {#if sortable}
            <span class="grip" data-handle aria-hidden="true">
              <NavIcon name="grip" />
            </span>
          {/if}
          {#if onDelete || onEdit || coverAllowed(index)}
            <div class="tile-actions" data-no-drag>
              {#if coverAllowed(index)}
                <button
                  type="button"
                  class="tile-button make-cover-button"
                  aria-label={t("Make cover", locale)}
                  title={t("Make cover", locale)}
                  onclick={() => void makeCover(index)}
                >
                  <NavIcon name="star" />
                </button>
              {/if}
              {#if onEdit}
                <button
                  type="button"
                  class="tile-button edit-photo-button"
                  disabled={editDisabled || locked}
                  aria-label={t("Edit photo", locale)}
                  title={t("Edit photo", locale)}
                  onclick={() => onEdit(item)}
                >
                  <NavIcon name="edit" />
                </button>
              {/if}
              {#if onDelete}
                <button
                  type="button"
                  class="tile-button delete-button"
                  disabled={deleteDisabled || locked}
                  aria-label={t("Remove photo", locale)}
                  title={t("Remove photo", locale)}
                  onclick={() => onDelete(item)}
                >
                  <NavIcon name="action-delete" />
                </button>
              {/if}
            </div>
          {/if}
        </div>
        {#if sortable}
          <!-- Visually hidden, but reachable for keyboard and screen readers. -->
          <div class="visually-hidden-controls" data-no-drag>
            <button
              type="button"
              disabled={locked || index === 0}
              onclick={() => void move(index, index - 1)}
            >
              {fill(msg("Move photo {position} earlier"), index + 1)}
            </button>
            <button
              type="button"
              disabled={locked || index === items.length - 1}
              onclick={() => void move(index, index + 1)}
            >
              {fill(msg("Move photo {position} later"), index + 1)}
            </button>
          </div>
        {/if}
        {#if actions}
          <div class="reorder-actions" data-no-drag>
            {@render actions(item)}
          </div>
        {/if}
      </li>
    {/each}
  </ul>
  {#if sortable}
    <p class="reorder-hint" id={hintId}>
      {showCover
        ? t("Drag to reorder. The first photo is the cover.", locale)
        : t("Drag to reorder.", locale)}
    </p>
  {/if}
  <p class="visually-hidden" role="status" aria-live="polite">
    {announcement}
  </p>
</div>

<style>
  .reorderable {
    display: grid;
    gap: 0.4rem;
  }

  .reorder-hint {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.8rem;
  }

  .reorder-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 7.5rem), 1fr));
    gap: 0.5rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .reorder-item {
    position: relative;
    display: grid;
    min-width: 0;
    align-content: start;
    gap: 0.3rem;
    -webkit-touch-callout: none;
    user-select: none;
  }

  .reorder-item.sortable {
    cursor: grab;
  }

  .reorder-item:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
    border-radius: var(--radius-xs);
  }

  .tile-media {
    position: relative;
  }

  .thumb {
    display: block;
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0;
    background: var(--surface-raised);
  }

  .thumb-button {
    cursor: zoom-in;
  }

  .thumb img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    pointer-events: none;
    user-select: none;
    -webkit-user-drag: none;
  }

  .cover-badge {
    position: absolute;
    inset-block-end: 6px;
    inset-inline-start: 6px;
    display: inline-flex;
    max-width: calc(100% - 12px);
    align-items: center;
    justify-content: center;
    border-radius: 999px;
    padding: 0.15rem 0.4rem;
    color: var(--accent-strong);
    background: var(--surface);
    font-size: 0.75rem;
    font-weight: 650;
    pointer-events: none;
  }

  .cover-badge :global(svg) {
    width: 1rem;
    height: 1rem;
  }

  .tile-actions {
    position: absolute;
    inset-block-start: 6px;
    inset-inline-end: 6px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .grip {
    position: absolute;
    inset-block-start: 6px;
    inset-inline-start: 6px;
    display: inline-flex;
    width: 2rem;
    height: 2rem;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    color: var(--ink);
    background: color-mix(in srgb, var(--surface) 88%, transparent);
    cursor: grab;
    opacity: 0;
    touch-action: none;
  }

  .grip :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }

  .tile-button {
    position: relative;
    display: inline-flex;
    width: 2rem;
    height: 2rem;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: 50%;
    padding: 0;
    color: var(--ink);
    background: color-mix(in srgb, var(--surface) 92%, transparent);
    font-size: 1.1rem;
    cursor: pointer;
    opacity: 0;
    pointer-events: none;
  }

  /* Extend the hit area to 44px without growing the visible button. */
  .tile-button::after {
    position: absolute;
    inset: -6px;
    content: "";
  }

  .delete-button {
    color: var(--danger);
  }

  .tile-button:disabled {
    opacity: 0;
    cursor: not-allowed;
  }

  .reorder-item:focus-within .tile-button:not(:disabled),
  .reorder-item:focus-within .grip {
    opacity: 1;
    pointer-events: auto;
  }

  .tile-button:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  @media (hover: hover) {
    .reorder-item:hover .tile-button:not(:disabled),
    .reorder-item:hover .grip {
      opacity: 1;
      pointer-events: auto;
    }
  }

  /* Touch devices have no hover: keep the compact actions visible. */
  @media (hover: none) {
    .tile-button:not(:disabled),
    .grip {
      opacity: 1;
      pointer-events: auto;
    }
  }

  .dragging,
  .dragging .reorder-item {
    cursor: grabbing;
  }

  .reorder-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.35rem;
  }

  .drag-source {
    opacity: 0.55;
  }

  .armed .thumb,
  .drop-target .thumb {
    outline: 3px solid var(--accent-strong);
    outline-offset: 1px;
  }

  .visually-hidden,
  .visually-hidden-controls {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }

  @media (prefers-reduced-motion: no-preference) {
    .reorder-item,
    .tile-button,
    .grip {
      transition:
        opacity 120ms ease,
        outline-color 120ms ease;
    }
  }
</style>
