<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import PhotoEditor from "./PhotoEditor.svelte";
  import type { ProjectImage } from "#lib/features/projects/project-api.ts";
  import { isTextEntryTarget, viewerKeyTarget } from "./viewer-keys";
  import {
    IDENTITY,
    panBy,
    toggleZoom,
    wheelZoomFactor,
    zoomAt,
    zoomKeyScale,
    type ZoomState,
  } from "./viewer-zoom";

  interface Props {
    images: ProjectImage[];
    selectedId: number | null;
    title: string;
    locale: "en" | "de";
    onClose: () => void;
    onSelect: (image: ProjectImage) => void;
    /** Edit endpoint of an image; omit when the viewer may not edit photos. */
    editPath?: (image: ProjectImage) => string;
    /** Reload the resource after an edit so every view shows the new picture. */
    onEdited?: () => Promise<void> | void;
  }

  let {
    images,
    selectedId,
    title,
    locale,
    onClose,
    onSelect,
    editPath,
    onEdited,
  }: Props = $props();
  let editing = $state(false);
  let dialog: HTMLDialogElement;
  let frame = $state<HTMLDivElement>();
  let zoom = $state<ZoomState>(IDENTITY);
  let gesturing = $state(false);
  const pointers = new Map<number, { x: number; y: number }>();
  let activeIndex = $derived(
    images.findIndex((image) => image.id === selectedId),
  );
  let activeImage = $derived(images[activeIndex]);

  $effect(() => {
    if (!dialog) return;
    if (selectedId !== null && !dialog.open) dialog.showModal();
    else if (selectedId === null && dialog.open) dialog.close();
  });

  // Back to fit when the photo changes, the viewer closes or the editor opens.
  $effect(() => {
    void selectedId;
    void editing;
    resetZoom();
  });

  function resetZoom() {
    zoom = IDENTITY;
    pointers.clear();
    gesturing = false;
  }

  /** Frame geometry; the photo fills the frame, so it is also the content size. */
  function geometry() {
    if (!frame) return null;
    const rect = frame.getBoundingClientRect();
    const size = { width: rect.width, height: rect.height };
    return {
      size,
      cx: rect.left + rect.width / 2,
      cy: rect.top + rect.height / 2,
    };
  }

  function zoomTo(
    scale: number,
    clientX: number | null = null,
    clientY: number | null = null,
  ) {
    const geo = geometry();
    if (!geo) return;
    const anchor =
      clientX === null || clientY === null
        ? { x: 0, y: 0 }
        : { x: clientX - geo.cx, y: clientY - geo.cy };
    zoom = zoomAt(zoom, scale, anchor, geo.size, geo.size);
  }

  /** Ctrl + wheel (and trackpad pinch) zooms; plain wheel is left alone. */
  function wheelZoom(node: HTMLElement) {
    const handler = (event: WheelEvent) => {
      if (!event.ctrlKey) return;
      // Keep the browser's page zoom out while the viewer is open.
      event.preventDefault();
      if (selectedId === null || editing) return;
      const open = document.querySelectorAll("dialog[open]");
      if (open[open.length - 1] !== dialog) return;
      zoomTo(
        zoom.scale * wheelZoomFactor(event.deltaY, event.deltaMode),
        event.clientX,
        event.clientY,
      );
    };
    node.addEventListener("wheel", handler, { passive: false });
    return { destroy: () => node.removeEventListener("wheel", handler) };
  }

  function pointerDistance() {
    const [a, b] = [...pointers.values()];
    return a && b ? Math.hypot(a.x - b.x, a.y - b.y) : 0;
  }

  function onPointerDown(event: PointerEvent) {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    frame?.setPointerCapture(event.pointerId);
    gesturing = pointers.size > 1 || zoom.scale > 1;
  }

  function onPointerMove(event: PointerEvent) {
    const previous = pointers.get(event.pointerId);
    const geo = geometry();
    if (!previous || !geo) return;
    const next = { x: event.clientX, y: event.clientY };
    if (pointers.size === 2) {
      const beforeDistance = pointerDistance();
      const [a, b] = [...pointers.values()];
      const beforeMid =
        a && b ? { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } : next;
      pointers.set(event.pointerId, next);
      const [c, d] = [...pointers.values()];
      if (!c || !d || beforeDistance === 0) return;
      const mid = { x: (c.x + d.x) / 2, y: (c.y + d.y) / 2 };
      const zoomed = zoomAt(
        zoom,
        zoom.scale * (pointerDistance() / beforeDistance),
        { x: mid.x - geo.cx, y: mid.y - geo.cy },
        geo.size,
        geo.size,
      );
      zoom = panBy(
        zoomed,
        mid.x - beforeMid.x,
        mid.y - beforeMid.y,
        geo.size,
        geo.size,
      );
      return;
    }
    pointers.set(event.pointerId, next);
    if (zoom.scale > 1) {
      zoom = panBy(
        zoom,
        next.x - previous.x,
        next.y - previous.y,
        geo.size,
        geo.size,
      );
    }
  }

  function onPointerEnd(event: PointerEvent) {
    pointers.delete(event.pointerId);
    if (frame?.hasPointerCapture(event.pointerId)) {
      frame.releasePointerCapture(event.pointerId);
    }
    gesturing = pointers.size > 0 && zoom.scale > 1;
  }

  function onDoubleClick(event: MouseEvent) {
    const geo = geometry();
    if (!geo) return;
    zoom = toggleZoom(
      zoom,
      { x: event.clientX - geo.cx, y: event.clientY - geo.cy },
      geo.size,
      geo.size,
    );
  }

  function move(direction: -1 | 1) {
    if (images.length < 2 || activeIndex < 0) return;
    const nextIndex = (activeIndex + direction + images.length) % images.length;
    const nextImage = images[nextIndex];
    if (nextImage) onSelect(nextImage);
  }

  function close() {
    onClose();
  }

  function onKeydown(event: KeyboardEvent) {
    if (!dialog?.open || selectedId === null || editing) return;
    if (
      event.defaultPrevented ||
      event.altKey ||
      event.ctrlKey ||
      event.metaKey
    )
      return;
    if (isTextEntryTarget(event.target)) return;
    // Leave the keys to a dialog stacked above the viewer (photo editor, confirms).
    const open = document.querySelectorAll("dialog[open]");
    if (open[open.length - 1] !== dialog) return;
    const zoomScale = zoomKeyScale(event.key, zoom.scale);
    if (zoomScale !== null) {
      event.preventDefault();
      zoomTo(zoomScale);
      return;
    }
    const target = viewerKeyTarget(event.key, activeIndex, images.length);
    const image = target === null ? undefined : images[target];
    if (!image) return;
    event.preventDefault();
    onSelect(image);
  }
</script>

<svelte:window onkeydown={onKeydown} />

<dialog
  bind:this={dialog}
  class="lightbox"
  use:wheelZoom
  aria-label={t("Project image viewer", locale)}
  onclick={(event) => {
    if (event.target === dialog) close();
  }}
  onclose={() => {
    if (selectedId !== null) onClose();
  }}
>
  {#if activeImage}
    <div class="lightbox-shell">
      <header class="lightbox-topbar">
        <p>{title}</p>
        {#if editPath}
          <Button
            class="lightbox-edit"
            variant="secondary"
            size="md"
            icon="crop"
            type="button"
            aria-label={t("Edit photo", locale)}
            onclick={() => (editing = true)}
          >
            <span class="lightbox-edit-label">{t("Edit", locale)}</span>
          </Button>
        {/if}
        <Button
          class="lightbox-close"
          variant="secondary"
          size="md"
          icon="close"
          type="button"
          aria-label={t("Close image viewer", locale)}
          onclick={close}
        />
      </header>
      <div class="lightbox-stage">
        {#if images.length > 1}
          <Button
            class="lightbox-step previous"
            variant="secondary"
            size="md"
            icon="previous"
            type="button"
            aria-label={t("Previous image", locale)}
            onclick={() => move(-1)}
          />
        {/if}
        <figure class:single-image={images.length === 1}>
          <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
          <div
            class="zoom-frame"
            class:zoomed={zoom.scale > 1}
            class:gesturing
            bind:this={frame}
            role="presentation"
            onpointerdown={onPointerDown}
            onpointermove={onPointerMove}
            onpointerup={onPointerEnd}
            onpointercancel={onPointerEnd}
            ondblclick={onDoubleClick}
          >
            <img
              src={activeImage.url}
              alt={activeImage.alt_text || title}
              draggable="false"
              style:transform="translate({zoom.x}px, {zoom.y}px) scale({zoom.scale})"
            />
          </div>
          <figcaption>
            {activeImage.alt_text || title}
            {#if images.length > 1}
              <span>{activeIndex + 1} / {images.length}</span>
            {/if}
          </figcaption>
        </figure>
        {#if images.length > 1}
          <Button
            class="lightbox-step next"
            variant="secondary"
            size="md"
            icon="next"
            type="button"
            aria-label={t("Next image", locale)}
            onclick={() => move(1)}
          />
        {/if}
      </div>
    </div>
  {/if}
</dialog>

{#if editPath && activeImage}
  <PhotoEditor
    open={editing}
    src={activeImage.url}
    alt={activeImage.alt_text || title}
    endpoint={editPath(activeImage)}
    {locale}
    onSaved={async () => {
      await onEdited?.();
    }}
    onClose={() => (editing = false)}
  />
{/if}

<style>
  .lightbox {
    width: min(64rem, calc(100vw - 2rem));
    max-width: none;
    max-height: none;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    padding: 0;
    color: var(--ink);
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  .lightbox::backdrop {
    background: rgb(10 20 18 / 80%);
  }

  .lightbox-shell {
    display: grid;
    grid-template-rows: auto auto;
  }

  .lightbox-topbar {
    display: flex;
    min-height: 3.75rem;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    border-bottom: 1px solid var(--line);
    padding: 0.4rem 0.7rem 0.4rem 1rem;
  }

  .lightbox-topbar p {
    overflow: hidden;
    margin: 0;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :global(.lightbox-step) {
    align-self: center;
  }

  :global(.lightbox-close),
  :global(.lightbox-step) {
    width: 44px;
    height: 44px;
    min-width: 44px;
    min-height: 44px;
    flex: 0 0 auto;
    border-radius: 50%;
    padding: 0;
  }

  .lightbox-topbar p {
    flex: 1 1 auto;
    min-width: 0;
  }

  :global(.lightbox-edit) {
    flex: 0 0 auto;
  }

  .lightbox-stage {
    display: grid;
    /* Fixed stage: the dialog keeps one size for every photo. */
    height: min(44rem, calc(100dvh - 8rem));
    min-height: 12rem;
    box-sizing: border-box;
    grid-template-columns: auto minmax(0, 1fr) auto;
    grid-template-rows: minmax(0, 1fr);
    align-items: center;
    gap: clamp(0.4rem, 2vw, 1rem);
    padding: 0.75rem;
  }

  figure {
    display: grid;
    min-width: 0;
    height: 100%;
    min-height: 0;
    width: 100%;
    grid-template-rows: minmax(0, 1fr) auto;
    justify-items: center;
    gap: 0.7rem;
    margin: 0;
  }

  .lightbox-stage figure.single-image {
    grid-column: 1 / -1;
  }

  .zoom-frame {
    display: grid;
    width: 100%;
    height: 100%;
    max-width: 100%;
    min-height: 0;
    place-items: center;
    overflow: hidden;
    border-radius: 0.4rem;
    touch-action: none;
  }

  .zoom-frame.zoomed {
    cursor: grab;
  }

  .zoom-frame.zoomed.gesturing {
    cursor: grabbing;
  }

  figure img {
    display: block;
    max-width: 100%;
    max-height: 100%;
    border-radius: 0.4rem;
    object-fit: contain;
    transform-origin: center;
    transition: transform 90ms ease-out;
    user-select: none;
    -webkit-user-drag: none;
  }

  .zoom-frame.gesturing img {
    transition: none;
  }

  @media (prefers-reduced-motion: reduce) {
    figure img {
      transition: none;
    }
  }

  figcaption {
    display: flex;
    width: 100%;
    justify-content: space-between;
    gap: 1rem;
    color: var(--ink-muted);
    font-size: 0.86rem;
  }

  @media (max-width: 480px) {
    .lightbox-edit-label {
      display: none;
    }

    :global(.lightbox-edit) {
      width: 44px;
      min-width: 44px;
      border-radius: 50%;
      padding: 0;
    }
  }

  @media (max-width: 420px) {
    .lightbox {
      width: calc(100vw - 0.75rem);
    }

    .lightbox-stage {
      grid-template-columns: 44px minmax(0, 1fr) 44px;
      gap: 0.15rem;
      padding: 0.45rem;
    }

    :global(.lightbox-step) {
      width: 44px;
      height: 44px;
      border-radius: var(--radius-xs);
    }
  }
</style>
