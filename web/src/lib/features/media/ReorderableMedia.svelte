<script
  lang="ts"
  generics="T extends { id: number; thumbnail_url: string; alt_text: string | null }"
>
  import { onMount, tick, type Snippet } from "svelte";
  import { flip } from "svelte/animate";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import {
    canMoveToFirst,
    edgeScrollDelta,
    gridColumns,
    keyboardMoveTarget,
    LONG_PRESS_MS,
    moveItem,
    MOUSE_DRAG_SLOP,
    MOVE_SLOP,
    nearestIndex,
    orderByIds,
  } from "./media-reorder";

  let {
    items,
    locale,
    label,
    fallbackAlt,
    firstIsCover = false,
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
    /** The first item is the cover: the reorder hint says so. */
    firstIsCover?: boolean;
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
    pointerId: number;
    pointerType: string;
    startX: number;
    startY: number;
    /** Where inside the tile the pointer grabbed it. */
    offsetX: number;
    offsetY: number;
    x: number;
    y: number;
    timer: ReturnType<typeof setTimeout> | null;
    /** A long press entered reorder mode; the rest of the gesture does nothing. */
    entered: boolean;
    dragging: boolean;
    tile: HTMLElement;
    frame: number | null;
    scroller: HTMLElement | null;
  }

  const hintId = $props.id();
  let listElement = $state<HTMLUListElement | null>(null);
  // Tile ids in their live order while a tile is lifted; null otherwise.
  let preview = $state<number[] | null>(null);
  let dragId = $state<number | null>(null);
  let announcement = $state("");
  let moving = $state(false);
  let gesture: Gesture | null = null;
  // The click that follows a drag or a long press must not open the photo.
  let suppressClick = false;
  let suppressTimer: ReturnType<typeof setTimeout> | null = null;

  const locked = $derived(disabled || moving);
  const sortable = $derived(reorderable && items.length > 1);
  // Reorder mode, as on Android: the photos grow into a larger grid until Done.
  // The order is staged by the move that ends a drag, as outside the mode.
  let reorderMode = $state(false);
  const expanded = $derived(reorderMode && sortable);
  const shown = $derived(preview ? orderByIds(items, preview) : items);
  const hint = $derived(
    firstIsCover
      ? t("Drag to reorder. The first photo is the cover.", locale)
      : t("Drag to reorder.", locale),
  );

  $effect(() => {
    if (!sortable) reorderMode = false;
  });

  function reducedMotion(): boolean {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  /** Live reflow of the other tiles while one is lifted; the lifted tile follows the pointer. */
  function reflow(
    node: Element,
    rects: { from: DOMRect; to: DOMRect },
    params: { off: boolean },
  ) {
    if (params.off || reducedMotion()) return { duration: 0 };
    return flip(node, rects, { duration: 200 });
  }

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
    // Once lifted, stop the browser from scrolling so the drag follows the finger.
    const block = (event: TouchEvent) => {
      if (gesture?.dragging) event.preventDefault();
    };
    // Keyboard focus is lost when a lifted tile moves in the DOM, so Escape
    // cancels a drag from the window.
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && gesture?.dragging) {
        event.preventDefault();
        cancelGesture();
      }
    };
    // A lifted tile can lose pointer capture when it moves in the DOM, so the
    // pointer is followed on the window for the whole gesture.
    const cancel = () => cancelGesture();
    listElement?.addEventListener("touchmove", block, { passive: false });
    window.addEventListener("keydown", escape);
    window.addEventListener("pointermove", onPointerMove);
    window.addEventListener("pointerup", onPointerUp);
    window.addEventListener("pointercancel", cancel);
    return () => {
      listElement?.removeEventListener("touchmove", block);
      window.removeEventListener("keydown", escape);
      window.removeEventListener("pointermove", onPointerMove);
      window.removeEventListener("pointerup", onPointerUp);
      window.removeEventListener("pointercancel", cancel);
      clearSuppress();
      cancelGesture();
    };
  });

  function focusTile(id: number): void {
    listElement
      ?.querySelector<HTMLElement>(`[data-tile="${id}"]`)
      ?.focus({ preventScroll: true });
  }

  function haptic(ms: number): void {
    try {
      navigator.vibrate?.(ms);
    } catch {
      // Not every browser allows vibration.
    }
  }

  function tileElements(): HTMLElement[] {
    return listElement
      ? (Array.from(listElement.children) as HTMLElement[])
      : [];
  }

  function columns(): number {
    return gridColumns(tileElements().map((tile) => tile.offsetTop));
  }

  function enterReorderMode(): void {
    if (!sortable || locked || reorderMode) return;
    reorderMode = true;
    announcement = hint;
  }

  function leaveReorderMode(refocus = false): void {
    cancelGesture();
    const focused = document.activeElement;
    const id =
      focused instanceof HTMLElement ? Number(focused.dataset.tile) : NaN;
    reorderMode = false;
    if (refocus && Number.isFinite(id)) void tick().then(() => focusTile(id));
  }

  async function move(from: number, to: number): Promise<boolean> {
    if (locked || from === to || to < 0 || to >= items.length) return false;
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
    return ok;
  }

  function canDelete(): boolean {
    return onDelete !== undefined && !deleteDisabled && !locked;
  }

  function onRootKeydown(event: KeyboardEvent): void {
    if (event.key !== "Escape") return;
    if (gesture?.dragging) return;
    if (expanded) {
      event.preventDefault();
      leaveReorderMode(true);
    }
  }

  function onTileKeydown(event: KeyboardEvent, index: number): void {
    // Only the tile itself; inner buttons keep their own keys.
    if (event.target !== event.currentTarget) return;
    if (event.key === "Enter" || event.key === " ") {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      event.preventDefault();
      if (expanded) return;
      if (onOpen) onOpen(items[index]);
      else enterReorderMode();
      return;
    }
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    if (event.key === "Delete" || event.key === "Backspace") {
      if (!onDelete) return;
      event.preventDefault();
      if (canDelete()) onDelete(items[index]);
      return;
    }
    if (!sortable) return;
    const target = keyboardMoveTarget(
      event,
      index,
      items.length,
      columns(),
      getComputedStyle(event.currentTarget as HTMLElement).direction === "rtl",
    );
    if (target === null) return;
    event.preventDefault();
    void move(index, target);
  }

  function clearSuppress(): void {
    if (suppressTimer) clearTimeout(suppressTimer);
    suppressTimer = null;
    suppressClick = false;
  }

  function onPointerDown(event: PointerEvent, id: number): void {
    // A new press never inherits the suppression of the previous one.
    clearSuppress();
    if (locked || !sortable) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if ((event.target as Element).closest("[data-no-drag]")) return;
    cancelGesture();
    const tile = event.currentTarget as HTMLElement;
    const rect = tile.getBoundingClientRect();
    const current: Gesture = {
      id,
      pointerId: event.pointerId,
      pointerType: event.pointerType,
      startX: event.clientX,
      startY: event.clientY,
      offsetX: event.clientX - rect.left,
      offsetY: event.clientY - rect.top,
      x: event.clientX,
      y: event.clientY,
      timer: null,
      entered: false,
      dragging: false,
      tile,
      frame: null,
      scroller: null,
    };
    gesture = current;
    if (expanded && event.pointerType === "mouse") return;
    // A press-and-hold enters reorder mode (outside it) or lifts the tile (inside
    // it, on touch, so that swiping still scrolls the page).
    current.timer = setTimeout(() => {
      current.timer = null;
      if (gesture !== current) return;
      haptic(15);
      if (expanded) {
        liftTile(current);
        updateDrag(current);
      } else {
        current.entered = true;
        enterReorderMode();
      }
    }, LONG_PRESS_MS);
  }

  function liftTile(current: Gesture): void {
    current.dragging = true;
    try {
      current.tile.setPointerCapture(current.pointerId);
    } catch {
      // The pointer already ended; the drag still follows pointermove events.
    }
    current.tile.style.transition = "none";
    current.scroller = scrollParent(current.tile);
    dragId = current.id;
    preview = items.map((item) => item.id);
    announcement = "";
    startScrollLoop(current);
  }

  function onPointerMove(event: PointerEvent): void {
    const current = gesture;
    if (!current || event.pointerId !== current.pointerId) return;
    current.x = event.clientX;
    current.y = event.clientY;
    if (current.entered) return;
    if (!current.dragging) {
      const travelled = Math.hypot(
        event.clientX - current.startX,
        event.clientY - current.startY,
      );
      if (expanded && current.pointerType === "mouse") {
        if (travelled <= MOUSE_DRAG_SLOP) return;
        liftTile(current);
      } else {
        if (travelled <= MOVE_SLOP) return;
        // Moving before the long press means the user is scrolling.
        cancelGesture();
        return;
      }
    }
    scheduleDragUpdate(current);
  }

  function scheduleDragUpdate(current: Gesture): void {
    if (current.frame !== null) return;
    current.frame = requestAnimationFrame(() => {
      current.frame = null;
      updateDrag(current);
    });
  }

  /** Follow the pointer and move the lifted tile to the slot it is nearest to. */
  function updateDrag(current: Gesture): void {
    if (gesture !== current || !current.dragging || !listElement || !preview)
      return;
    const list = listElement.getBoundingClientRect();
    const tile = current.tile;
    const left = current.x - current.offsetX;
    const top = current.y - current.offsetY;
    tile.style.transform = `translate(${left - list.left - tile.offsetLeft}px, ${
      top - list.top - tile.offsetTop
    }px) scale(1.05)`;
    const tiles = tileElements();
    const target = nearestIndex(
      tiles.map((slot) => ({
        x: list.left + slot.offsetLeft + slot.offsetWidth / 2,
        y: list.top + slot.offsetTop + slot.offsetHeight / 2,
      })),
      left + tile.offsetWidth / 2,
      top + tile.offsetHeight / 2,
    );
    const index = preview.indexOf(current.id);
    if (target < 0 || target === index) return;
    preview = moveItem(preview, index, target);
    haptic(5);
    // The tile now sits in another slot: re-anchor it once the DOM has moved.
    void tick().then(() => updateDrag(current));
  }

  function scrollParent(node: HTMLElement): HTMLElement | null {
    for (let el = node.parentElement; el; el = el.parentElement) {
      const overflow = getComputedStyle(el).overflowY;
      if (
        (overflow === "auto" || overflow === "scroll") &&
        el.scrollHeight > el.clientHeight
      )
        return el;
    }
    return null;
  }

  /** Scroll the page while the lifted tile is held near its top or bottom edge. */
  function startScrollLoop(current: Gesture): void {
    const step = () => {
      if (gesture !== current || !current.dragging) return;
      const bounds = current.scroller?.getBoundingClientRect();
      const delta = edgeScrollDelta(
        current.y,
        bounds ? Math.max(bounds.top, 0) : 0,
        bounds
          ? Math.min(bounds.bottom, window.innerHeight)
          : window.innerHeight,
      );
      if (delta !== 0) {
        if (current.scroller) current.scroller.scrollTop += delta;
        else window.scrollBy(0, delta);
        updateDrag(current);
      }
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  async function onPointerUp(event: PointerEvent): Promise<void> {
    const current = gesture;
    if (!current || event.pointerId !== current.pointerId) return;
    // A finished drag or a long press must not also open the photo: the browser
    // still fires a click on lift. If none arrives, drop the flag so the next
    // real tap is not swallowed.
    clearSuppress();
    if (current.dragging || current.entered) {
      suppressClick = true;
      suppressTimer = setTimeout(clearSuppress, 600);
    }
    if (!current.dragging) {
      cancelGesture();
      return;
    }
    const order = preview;
    const from = items.findIndex((item) => item.id === current.id);
    const to = order ? order.indexOf(current.id) : from;
    drop(current);
    if (from !== to) await move(from, to);
    // Keep the live order until the staged order has replaced it.
    if (gesture === null) {
      preview = null;
      dragId = null;
    }
  }

  /** Settle the lifted tile into its slot and end the gesture. */
  function drop(current: Gesture): void {
    const tile = current.tile;
    if (current.frame !== null) cancelAnimationFrame(current.frame);
    if (!reducedMotion()) {
      tile.style.transition = "transform 160ms ease";
      tile.style.transform = "";
      setTimeout(() => {
        tile.style.transition = "";
      }, 180);
    } else {
      tile.style.transition = "";
      tile.style.transform = "";
    }
    if (current.tile.hasPointerCapture(current.pointerId))
      current.tile.releasePointerCapture(current.pointerId);
    gesture = null;
  }

  function cancelGesture(): void {
    const current = gesture;
    if (current) {
      if (current.timer) clearTimeout(current.timer);
      if (current.frame !== null) cancelAnimationFrame(current.frame);
      if (current.tile.hasPointerCapture(current.pointerId))
        current.tile.releasePointerCapture(current.pointerId);
      current.tile.style.transform = "";
      current.tile.style.transition = "";
    }
    gesture = null;
    preview = null;
    dragId = null;
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

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="reorderable" class:expanded onkeydown={onRootKeydown}>
  {#if sortable}
    <div class="reorder-header">
      <p class="reorder-hint" class:visually-hidden={!expanded} id={hintId}>
        {hint}
      </p>
      {#if expanded}
        <button
          type="button"
          class="reorder-done"
          onclick={() => leaveReorderMode(true)}
        >
          {t("Done", locale)}
        </button>
      {:else}
        <!-- Keyboard and screen reader entry: shown only while focused. -->
        <button
          type="button"
          class="reorder-enter"
          {disabled}
          onclick={enterReorderMode}
        >
          {t("Reorder photos", locale)}
        </button>
      {/if}
    </div>
  {/if}
  <ul
    class="reorder-list"
    class:dragging={dragId !== null}
    bind:this={listElement}
    aria-label={label}
  >
    {#each shown as item, index (item.id)}
      <!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
      <li
        class="reorder-item"
        class:sortable
        class:lifted={dragId === item.id}
        data-tile={item.id}
        tabindex="0"
        aria-label={tileLabel(item, index)}
        aria-describedby={sortable ? hintId : undefined}
        aria-keyshortcuts={sortable
          ? "ArrowUp ArrowDown ArrowLeft ArrowRight"
          : undefined}
        animate:reflow={{ off: dragId === item.id }}
        onpointerdown={(event) => onPointerDown(event, item.id)}
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
          {#if badge && !expanded}
            <span class="cover-badge">{@render badge(item, index)}</span>
          {/if}
          {#if onDelete || (onEdit && !expanded)}
            <div class="tile-actions" data-no-drag>
              {#if onEdit && !expanded}
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
            <button
              type="button"
              disabled={locked || !canMoveToFirst(index, items.length)}
              onclick={() => void move(index, 0)}
            >
              {fill(msg("Move photo {position} to first"), index + 1)}
            </button>
          </div>
        {/if}
        {#if actions && !expanded}
          <div class="reorder-actions" data-no-drag>
            {@render actions(item)}
          </div>
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
    gap: 0.4rem;
  }

  /* Hint and Done, as on Android: the hint on the left, Done on the right. */
  .reorder-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
  }

  .reorder-hint {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.875rem;
  }

  .reorder-done {
    min-height: 2.75rem;
    flex: none;
    border: 0;
    border-radius: var(--radius-xs);
    padding: 0 0.75rem;
    color: var(--accent-strong);
    background: transparent;
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }

  /* Reachable with the keyboard and screen readers, invisible otherwise. */
  .reorder-enter:not(:focus-visible) {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }

  .reorder-enter {
    min-height: 2.75rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0 1rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }

  .reorder-done:focus-visible,
  .reorder-enter:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .reorder-list {
    position: relative;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 7.5rem), 1fr));
    gap: 0.5rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  /* Reorder mode, the Android grid: tiles of at least 110px, never fewer than
     three columns, 10px apart. */
  .expanded .reorder-list {
    grid-template-columns: repeat(
      auto-fill,
      minmax(min(110px, calc((100% - 20px) / 3)), 1fr)
    );
    gap: 10px;
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

  .expanded .reorder-item {
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

  .expanded .thumb-button {
    cursor: inherit;
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

  .cover-badge:empty {
    display: none;
  }

  .tile-actions {
    position: absolute;
    inset-block-start: 6px;
    inset-inline-end: 6px;
    display: flex;
    flex-direction: column;
    gap: 8px;
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
  .expanded .tile-button:not(:disabled) {
    opacity: 1;
    pointer-events: auto;
  }

  .tile-button:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  @media (hover: hover) {
    .reorder-item:hover .tile-button:not(:disabled) {
      opacity: 1;
      pointer-events: auto;
    }
  }

  /* Touch devices have no hover: keep the compact actions visible. */
  @media (hover: none) {
    .tile-button:not(:disabled) {
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

  /* The lifted tile: above the others, larger, with a shadow and a blue ring. */
  .lifted {
    z-index: 2;
    will-change: transform;
  }

  .lifted .thumb {
    border-color: var(--accent-strong);
    box-shadow: 0 12px 24px rgb(0 0 0 / 0.3);
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
    .tile-button {
      transition: opacity 120ms ease;
    }
  }
</style>
