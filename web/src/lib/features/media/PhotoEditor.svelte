<script lang="ts">
  import { untrack } from "svelte";
  import Button from "#lib/components/Button.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    buildEditBody,
    clampCrop,
    altTextChanged,
    editPhoto,
    fitSize,
    fullCrop,
    isUnchanged,
    MAX_ALT_TEXT_LENGTH,
    minCropFractions,
    moveCrop,
    nudgeBox,
    nudgeHandle,
    pointToFraction,
    resizeCrop,
    rotateBy,
    rotateCrop,
    rotatedSize,
    type CropBox,
    type CropHandle,
    type Rotation,
    type Size,
    updateAltText,
  } from "./photo-editor";

  interface Props {
    open: boolean;
    /** Original image URL, shown upright (the browser applies EXIF orientation). */
    src: string;
    alt: string;
    /** Stored description of the photo; leave out to hide the Description field. */
    altText?: string;
    /** Edit endpoint of the photo, see photo-editor.ts. */
    endpoint: string;
    locale: "en" | "de";
    /** Runs after the server saved the edit; reload the resource here. */
    onSaved: () => Promise<void> | void;
    onClose: () => void;
  }

  let { open, src, alt, altText, endpoint, locale, onSaved, onClose }: Props =
    $props();

  const hintId = $props.id();
  const descriptionId = `${hintId}-description`;
  const titleId = `${hintId}-title`;
  const handles: { id: CropHandle; label: string }[] = [
    { id: "nw", label: msg("Top left corner") },
    { id: "ne", label: msg("Top right corner") },
    { id: "sw", label: msg("Bottom left corner") },
    { id: "se", label: msg("Bottom right corner") },
  ];

  let dialog = $state<HTMLDialogElement | null>(null);
  let stageElement = $state<HTMLDivElement | null>(null);
  let areaWidth = $state(0);
  let areaHeight = $state(0);
  let natural = $state<Size | null>(null);
  let rotation = $state<Rotation>(0);
  let crop = $state<CropBox>(fullCrop());
  let saving = $state(false);
  let errorMessage = $state("");
  // The description field: what the server stores now, and what the field holds.
  let storedAlt = $state("");
  let describedAs = $state("");

  type Drag =
    | { kind: "handle"; pointerId: number; handle: CropHandle }
    | {
        kind: "box";
        pointerId: number;
        startX: number;
        startY: number;
        startCrop: CropBox;
      };
  let drag: Drag | null = null;

  /** Handle margin: corner handles reach 22 px outside the preview. */
  const MARGIN = 22;

  const rotated = $derived(natural ? rotatedSize(natural, rotation) : null);
  const stage = $derived(
    rotated
      ? fitSize(rotated, {
          width: Math.max(0, areaWidth - MARGIN * 2),
          height: Math.max(0, areaHeight - MARGIN * 2),
        })
      : { width: 0, height: 0 },
  );
  const minCrop = $derived(
    rotated ? minCropFractions(rotated) : { width: 0.08, height: 0.08 },
  );
  // The image element keeps its own orientation; the transform turns it.
  const imageStyle = $derived.by(() => {
    if (!natural) return "";
    const turned = rotation === 90 || rotation === 270;
    const width = turned ? stage.height : stage.width;
    const height = turned ? stage.width : stage.height;
    return `width:${width}px;height:${height}px;transform:translate(-50%,-50%) rotate(${rotation}deg);`;
  });
  const compact = $derived(
    crop.width * stage.width < 110 || crop.height * stage.height < 110,
  );
  const resultSize = $derived(
    rotated
      ? {
          width: Math.max(1, Math.round(rotated.width * crop.width)),
          height: Math.max(1, Math.round(rotated.height * crop.height)),
        }
      : null,
  );
  const unchanged = $derived(isUnchanged(rotation, crop));
  const showDescription = $derived(altText !== undefined);
  const altDirty = $derived(
    showDescription && altTextChanged(storedAlt, describedAs),
  );
  const canSave = $derived(
    ((natural !== null && !unchanged) || altDirty) && !saving,
  );

  $effect(() => {
    if (!dialog) return;
    if (open && !dialog.open) {
      untrack(() => {
        reset();
        storedAlt = altText ?? "";
        describedAs = storedAlt;
      });
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  });

  // A new picture has its own size; wait for it to load before drawing the box.
  $effect(() => {
    void src;
    untrack(() => {
      natural = null;
    });
  });

  function reset(): void {
    rotation = 0;
    crop = fullCrop();
    errorMessage = "";
    drag = null;
  }

  function onImageLoad(event: Event): void {
    const image = event.currentTarget;
    if (!(image instanceof HTMLImageElement)) return;
    if (image.naturalWidth > 0 && image.naturalHeight > 0)
      natural = { width: image.naturalWidth, height: image.naturalHeight };
  }

  function turn(quarterTurns: number): void {
    if (saving) return;
    rotation = rotateBy(rotation, quarterTurns);
    // Carry the box along so it keeps framing the same part of the picture.
    crop = clampCrop(rotateCrop(crop, quarterTurns), minCrop);
    errorMessage = "";
  }

  function resetAll(): void {
    if (saving) return;
    reset();
  }

  function close(): void {
    if (saving) return;
    onClose();
  }

  async function save(): Promise<void> {
    if (!canSave) return;
    const token = session.data?.csrf_token;
    if (!token) {
      errorMessage = t("The photo could not be saved.", locale);
      return;
    }
    saving = true;
    errorMessage = "";
    let altSaved = false;
    try {
      if (altDirty) {
        storedAlt = await updateAltText(endpoint, describedAs, token);
        describedAs = storedAlt;
        altSaved = true;
      }
      if (natural !== null && !unchanged)
        await editPhoto(endpoint, buildEditBody(rotation, crop), token);
    } catch {
      errorMessage = t("The photo could not be saved.", locale);
      saving = false;
      if (altSaved) {
        // The description is stored even though the crop was not: show it in the page.
        try {
          await onSaved();
        } catch {
          // Keep the dialog open with its error.
        }
      }
      return;
    }
    try {
      await onSaved();
    } catch {
      // The edit is stored; a failed reload must not look like a failed save.
    }
    saving = false;
    onClose();
  }

  function setCrop(next: CropBox): void {
    crop = clampCrop(next, minCrop);
  }

  function pointerPoint(event: PointerEvent): { x: number; y: number } {
    const rect = stageElement?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    return pointToFraction(event.clientX, event.clientY, rect);
  }

  function startHandle(event: PointerEvent, handle: CropHandle): void {
    if (saving || event.button > 0) return;
    event.preventDefault();
    event.stopPropagation();
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    drag = { kind: "handle", pointerId: event.pointerId, handle };
  }

  function startBox(event: PointerEvent): void {
    if (saving || event.button > 0 || event.target !== event.currentTarget)
      return;
    event.preventDefault();
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    drag = {
      kind: "box",
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startCrop: crop,
    };
  }

  function moveDrag(event: PointerEvent): void {
    if (!drag || drag.pointerId !== event.pointerId) return;
    if (drag.kind === "handle") {
      setCrop(resizeCrop(crop, drag.handle, pointerPoint(event), minCrop));
      return;
    }
    const rect = stageElement?.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return;
    setCrop(
      moveCrop(
        drag.startCrop,
        (event.clientX - drag.startX) / rect.width,
        (event.clientY - drag.startY) / rect.height,
      ),
    );
  }

  function endDrag(event: PointerEvent): void {
    if (drag?.pointerId === event.pointerId) drag = null;
  }

  function onHandleKey(event: KeyboardEvent, handle: CropHandle): void {
    if (saving || !event.key.startsWith("Arrow")) return;
    event.preventDefault();
    event.stopPropagation();
    setCrop(nudgeHandle(crop, handle, event.key, minCrop, event.shiftKey));
  }

  function onBoxKey(event: KeyboardEvent): void {
    if (saving || event.target !== event.currentTarget) return;
    if (!event.key.startsWith("Arrow")) return;
    event.preventDefault();
    setCrop(nudgeBox(crop, event.key, event.shiftKey));
  }
</script>

<dialog
  bind:this={dialog}
  class="photo-editor"
  aria-labelledby={titleId}
  oncancel={(event) => {
    if (saving) event.preventDefault();
  }}
  onclose={() => {
    if (open) onClose();
  }}
>
  <div class="editor-body">
    <header class="editor-header">
      <h2 id={titleId}>{t("Edit photo", locale)}</h2>
      <button
        type="button"
        class="editor-close"
        aria-label={t("Cancel", locale)}
        title={t("Cancel", locale)}
        disabled={saving}
        onclick={close}
      >
        <NavIcon name="close" />
      </button>
    </header>
    <p class="editor-hint" id={hintId}>
      {t("Drag the corners to crop.", locale)}
      {#if resultSize}
        <span class="editor-size" aria-hidden="true"
          >{resultSize.width} × {resultSize.height}</span
        >
      {/if}
    </p>

    <div
      class="stage-area"
      bind:clientWidth={areaWidth}
      bind:clientHeight={areaHeight}
    >
      <div
        class="stage"
        class:ready={natural !== null}
        bind:this={stageElement}
        style:width={`${stage.width}px`}
        style:height={`${stage.height}px`}
      >
        <img
          class="photo"
          {src}
          {alt}
          draggable="false"
          style={imageStyle}
          onload={onImageLoad}
        />
        {#if natural}
          <div class="dim" aria-hidden="true">
            <div
              class="dim-hole"
              style:left={`${crop.x * 100}%`}
              style:top={`${crop.y * 100}%`}
              style:width={`${crop.width * 100}%`}
              style:height={`${crop.height * 100}%`}
            ></div>
          </div>
          <!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
          <div
            class="crop-box"
            class:compact
            role="group"
            tabindex="0"
            aria-label={t("Drag the corners to crop.", locale)}
            aria-describedby={hintId}
            aria-keyshortcuts="ArrowUp ArrowDown ArrowLeft ArrowRight"
            style:left={`${crop.x * 100}%`}
            style:top={`${crop.y * 100}%`}
            style:width={`${crop.width * 100}%`}
            style:height={`${crop.height * 100}%`}
            onpointerdown={startBox}
            onpointermove={moveDrag}
            onpointerup={endDrag}
            onpointercancel={endDrag}
            onkeydown={onBoxKey}
          >
            {#each handles as handle (handle.id)}
              <button
                type="button"
                class={`handle handle-${handle.id}`}
                aria-label={t(handle.label, locale)}
                aria-describedby={hintId}
                disabled={saving}
                onpointerdown={(event) => startHandle(event, handle.id)}
                onpointermove={moveDrag}
                onpointerup={endDrag}
                onpointercancel={endDrag}
                onkeydown={(event) => onHandleKey(event, handle.id)}
              >
                <span class="corner"></span>
              </button>
            {/each}
          </div>
        {:else}
          <span class="stage-loading" aria-hidden="true"></span>
        {/if}
      </div>
    </div>

    <div class="editor-tools">
      <Button
        class="tool-button"
        variant="secondary"
        size="sm"
        icon="rotate-left"
        disabled={saving}
        onclick={() => turn(-1)}>{t("Rotate left", locale)}</Button
      >
      <Button
        class="tool-button"
        variant="secondary"
        size="sm"
        icon="rotate-right"
        disabled={saving}
        onclick={() => turn(1)}>{t("Rotate right", locale)}</Button
      >
      <Button
        class="tool-button"
        variant="ghost"
        size="sm"
        icon="reset"
        disabled={saving || unchanged}
        onclick={resetAll}>{t("Reset", locale)}</Button
      >
    </div>

    {#if showDescription}
      <div class="editor-description">
        <label for={descriptionId}>{t("Description", locale)}</label>
        <input
          id={descriptionId}
          type="text"
          bind:value={describedAs}
          maxlength={MAX_ALT_TEXT_LENGTH}
          disabled={saving}
          autocomplete="off"
          aria-describedby={`${descriptionId}-hint`}
        />
        <small id={`${descriptionId}-hint`}
          >{t(
            "Read aloud by screen readers and shown when the photo cannot load.",
            locale,
          )}</small
        >
      </div>
    {/if}

    <div class="editor-status" aria-live="polite">
      {#if errorMessage}
        <p class="editor-error" role="alert">{errorMessage}</p>
      {:else if saving}
        <p class="editor-saving" role="status">
          {t("Saving photo…", locale)}
        </p>
      {/if}
    </div>

    <footer class="editor-actions">
      <Button
        variant="secondary"
        icon="cancel"
        disabled={saving}
        onclick={close}>{t("Cancel", locale)}</Button
      >
      <Button
        variant="primary"
        icon="save"
        disabled={!canSave}
        aria-busy={saving}
        onclick={() => void save()}>{t("Save", locale)}</Button
      >
    </footer>
  </div>
</dialog>

<style>
  .photo-editor {
    box-sizing: border-box;
    width: min(36rem, calc(100vw - 1rem));
    max-width: none;
    max-height: calc(100dvh - 1rem);
    overflow: auto;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: calc(var(--radius-sm) * 2);
    color: var(--ink);
    background: var(--surface);
    box-shadow: var(--shadow);
  }

  .photo-editor[open] {
    animation: editor-pop 320ms cubic-bezier(0.34, 1.56, 0.64, 1);
  }

  .photo-editor::backdrop {
    background: rgb(15 28 44 / 60%);
  }

  .editor-body {
    display: grid;
    gap: 0.7rem;
    padding: 0.9rem 0.8rem 0.9rem;
  }

  .editor-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    padding-inline-start: 0.3rem;
  }

  h2 {
    margin: 0;
    font-size: 1.15rem;
  }

  .editor-close {
    display: grid;
    width: 44px;
    height: 44px;
    flex: 0 0 auto;
    place-items: center;
    border: 0;
    border-radius: 999px;
    color: var(--ink-muted);
    background: transparent;
    cursor: pointer;
  }

  .editor-close:hover:not(:disabled) {
    background: var(--accent-wash);
  }

  .editor-close:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .editor-close :global(svg) {
    width: 20px;
    height: 20px;
  }

  .editor-hint {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.3rem 0.8rem;
    margin: 0;
    padding-inline: 0.3rem;
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .editor-size {
    padding: 0.1rem 0.55rem;
    border-radius: 999px;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 0.78rem;
    font-variant-numeric: tabular-nums;
    font-weight: 700;
  }

  .stage-area {
    display: grid;
    height: min(52dvh, 26rem);
    min-height: 12rem;
    place-items: center;
    border-radius: var(--radius-sm);
    background: repeating-conic-gradient(
        var(--surface-raised) 0% 25%,
        var(--surface-container) 0% 50%
      )
      50% / 18px 18px;
  }

  .stage {
    position: relative;
    touch-action: none;
    user-select: none;
    -webkit-user-select: none;
  }

  .photo {
    position: absolute;
    top: 50%;
    left: 50%;
    max-width: none;
    max-height: none;
    opacity: 0;
    pointer-events: none;
    -webkit-user-drag: none;
  }

  .ready .photo {
    opacity: 1;
  }

  .stage:not(.ready) {
    width: 4rem;
    height: 4rem;
  }

  .stage-loading {
    position: absolute;
    inset: 0;
    border: 3px solid var(--accent-wash);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: editor-spin 800ms linear infinite;
  }

  .dim {
    position: absolute;
    inset: 0;
    overflow: hidden;
    pointer-events: none;
  }

  .dim-hole {
    position: absolute;
    box-shadow: 0 0 0 100vmax rgb(10 14 24 / 58%);
  }

  .crop-box {
    position: absolute;
    box-sizing: border-box;
    border: 1.5px solid #fff;
    background:
      linear-gradient(rgb(255 255 255 / 45%), rgb(255 255 255 / 45%)) 33.333%
        0 / 1px 100% no-repeat,
      linear-gradient(rgb(255 255 255 / 45%), rgb(255 255 255 / 45%)) 66.666%
        0 / 1px 100% no-repeat,
      linear-gradient(rgb(255 255 255 / 45%), rgb(255 255 255 / 45%)) 0
        33.333% / 100% 1px no-repeat,
      linear-gradient(rgb(255 255 255 / 45%), rgb(255 255 255 / 45%)) 0
        66.666% / 100% 1px no-repeat;
    box-shadow: 0 0 0 1px rgb(0 0 0 / 35%);
    cursor: move;
    touch-action: none;
  }

  .crop-box:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  .handle {
    position: absolute;
    display: grid;
    width: 44px;
    height: 44px;
    place-items: center;
    border: 0;
    padding: 0;
    background: transparent;
    cursor: pointer;
    touch-action: none;
  }

  .compact .handle {
    width: 30px;
    height: 30px;
  }

  .handle-nw {
    top: -22px;
    left: -22px;
    cursor: nwse-resize;
  }

  .handle-ne {
    top: -22px;
    right: -22px;
    cursor: nesw-resize;
  }

  .handle-sw {
    bottom: -22px;
    left: -22px;
    cursor: nesw-resize;
  }

  .handle-se {
    right: -22px;
    bottom: -22px;
    cursor: nwse-resize;
  }

  .compact .handle-nw {
    top: -15px;
    left: -15px;
  }

  .compact .handle-ne {
    top: -15px;
    right: -15px;
  }

  .compact .handle-sw {
    bottom: -15px;
    left: -15px;
  }

  .compact .handle-se {
    right: -15px;
    bottom: -15px;
  }

  .corner {
    display: block;
    width: 20px;
    height: 20px;
    border: 0 solid #fff;
    border-radius: 3px;
    filter: drop-shadow(0 0 2px rgb(0 0 0 / 55%));
    transition:
      transform 120ms ease,
      border-color 120ms ease;
  }

  .handle-nw .corner {
    border-width: 4px 0 0 4px;
    transform: translate(7px, 7px);
  }

  .handle-ne .corner {
    border-width: 4px 4px 0 0;
    transform: translate(-7px, 7px);
  }

  .handle-sw .corner {
    border-width: 0 0 4px 4px;
    transform: translate(7px, -7px);
  }

  .handle-se .corner {
    border-width: 0 4px 4px 0;
    transform: translate(-7px, -7px);
  }

  .handle:hover:not(:disabled) .corner,
  .handle:focus-visible .corner {
    border-color: var(--accent);
  }

  .handle:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: -4px;
    border-radius: var(--radius-xs);
  }

  .editor-tools {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0.45rem;
  }

  .editor-description {
    display: grid;
    gap: 0.25rem;
    padding-inline: 0.3rem;
  }

  .editor-description label {
    font-size: 0.9rem;
    font-weight: 650;
  }

  .editor-description input {
    box-sizing: border-box;
    width: 100%;
    min-height: 44px;
  }

  .editor-description small {
    color: var(--ink-muted);
  }

  .editor-status {
    min-height: 1.4rem;
    padding-inline: 0.3rem;
    text-align: center;
  }

  .editor-status p {
    margin: 0;
    font-size: 0.9rem;
  }

  .editor-error {
    color: var(--danger);
    font-weight: 650;
  }

  .editor-saving {
    color: var(--ink-muted);
  }

  .editor-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.6rem;
  }

  @keyframes editor-pop {
    from {
      opacity: 0;
      transform: scale(0.9) translateY(12px);
    }
  }

  @keyframes editor-spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .photo-editor,
    .corner,
    .stage-loading {
      animation: none !important;
      transition: none !important;
    }
  }
</style>
