<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";

  let {
    locale,
    gravatarEnabled,
    canChangeGravatar,
    avatarSource,
    busy,
    ongravatar,
    oncropped,
  }: {
    locale: Locale;
    gravatarEnabled: boolean;
    canChangeGravatar: boolean;
    avatarSource: "upload" | "gravatar" | null;
    busy: boolean;
    ongravatar: (enabled: boolean) => void;
    oncropped: (file: File) => void;
  } = $props();

  let chooser = $state<ReturnType<typeof ConfirmDialog>>();
  let editor = $state<ReturnType<typeof ConfirmDialog>>();
  let fileInput = $state<HTMLInputElement>();
  let sourceFile = $state<File | null>(null);
  let imageUrl = $state<string | null>(null);
  let sourceDimensions = $state<{ width: number; height: number } | null>(null);
  let rotation = $state(0);
  let zoom = $state(1);
  let offsetX = $state(0);
  let offsetY = $state(0);
  let error = $state("");
  const offsetLimits = $derived.by(() => {
    if (!sourceDimensions) return { x: 0, y: 0 };
    const quarterTurn = rotation % 180 !== 0;
    const width = quarterTurn
      ? sourceDimensions.height
      : sourceDimensions.width;
    const height = quarterTurn
      ? sourceDimensions.width
      : sourceDimensions.height;
    const base = Math.min(sourceDimensions.width, sourceDimensions.height);
    return {
      x: Math.max(0, (width / base) * zoom - 1),
      y: Math.max(0, (height / base) * zoom - 1),
    };
  });

  export function open(): void {
    error = "";
    chooser?.showModal();
  }

  export function openFile(file: File): void {
    chooser?.close();
    startEditing(file);
  }

  function chooseFile(): void {
    if (!busy) fileInput?.click();
  }

  function startEditing(file: File): void {
    error = "";
    if (!file.type.startsWith("image/")) {
      error = t("Choose a supported image file.", locale);
      chooser?.showModal();
      return;
    }
    if (file.size > 25 * 1024 * 1024) {
      error = t("Choose an image smaller than 25 MiB.", locale);
      chooser?.showModal();
      return;
    }
    releaseImageUrl();
    sourceFile = file;
    sourceDimensions = null;
    imageUrl = URL.createObjectURL(file);
    rotation = 0;
    zoom = 1;
    offsetX = 0;
    offsetY = 0;
    editor?.showModal();
  }

  function releaseImageUrl(): void {
    if (imageUrl) URL.revokeObjectURL(imageUrl);
    imageUrl = null;
    sourceFile = null;
    sourceDimensions = null;
  }

  function closeEditor(): void {
    editor?.close();
    releaseImageUrl();
    rotation = 0;
    zoom = 1;
    offsetX = 0;
    offsetY = 0;
    error = "";
  }

  async function useCroppedImage(): Promise<void> {
    if (!imageUrl || !sourceFile || busy) return;
    error = "";
    try {
      const image = new Image();
      image.src = imageUrl;
      await image.decode();
      const size = 512;
      const canvas = document.createElement("canvas");
      canvas.width = size;
      canvas.height = size;
      const context = canvas.getContext("2d");
      if (!context) throw new Error("canvas unavailable");
      context.translate(
        size / 2 + (offsetX * offsetLimits.x * size) / 2,
        size / 2 + (offsetY * offsetLimits.y * size) / 2,
      );
      context.rotate((rotation * Math.PI) / 180);
      const scale =
        (size / Math.min(image.naturalWidth, image.naturalHeight)) * zoom;
      context.drawImage(
        image,
        (-image.naturalWidth * scale) / 2,
        (-image.naturalHeight * scale) / 2,
        image.naturalWidth * scale,
        image.naturalHeight * scale,
      );
      const blob = await new Promise<Blob | null>((resolve) =>
        canvas.toBlob(resolve, "image/jpeg", 0.9),
      );
      if (!blob) throw new Error("image export failed");
      const baseName = sourceFile.name.replace(/\.[^.]+$/, "") || "profile";
      const cropped = new File([blob], `${baseName}.jpg`, {
        type: "image/jpeg",
        lastModified: Date.now(),
      });
      closeEditor();
      oncropped(cropped);
    } catch {
      error = t(
        "This image could not be edited. Choose another image.",
        locale,
      );
    }
  }

  function onEditorClose(): void {
    releaseImageUrl();
    rotation = 0;
    zoom = 1;
    offsetX = 0;
    offsetY = 0;
  }
</script>

<input
  bind:this={fileInput}
  class="file-input"
  type="file"
  accept="image/*"
  aria-label={t("Choose a profile picture", locale)}
  onchange={(event) => {
    const input = event.currentTarget;
    const file = input.files?.[0];
    input.value = "";
    if (file) startEditing(file);
  }}
/>

<ConfirmDialog
  bind:this={chooser}
  titleId="avatar-source-title"
  title={t("Profile picture", locale)}
  {busy}
>
  <p>{t("Choose how your profile picture should look.", locale)}</p>
  {#if error}<p class="avatar-error" role="alert">{error}</p>{/if}
  {#snippet actions()}
    {#if gravatarEnabled}
      <Button
        variant="secondary"
        disabled={busy || !canChangeGravatar}
        onclick={() => {
          chooser?.close();
          ongravatar(avatarSource !== "gravatar");
        }}
        icon="account"
      >
        {avatarSource === "gravatar"
          ? t("Turn off Gravatar", locale)
          : t("Use Gravatar", locale)}
      </Button>
    {/if}
    <Button
      variant="primary"
      disabled={busy}
      onclick={chooseFile}
      icon="upload"
    >
      {t("Upload a photo", locale)}
    </Button>
    <Button variant="ghost" disabled={busy} onclick={() => chooser?.close()}>
      {t("Cancel", locale)}
    </Button>
  {/snippet}
</ConfirmDialog>

<ConfirmDialog
  bind:this={editor}
  titleId="avatar-editor-title"
  title={t("Crop your profile picture", locale)}
  onclose={onEditorClose}
>
  {#if imageUrl}
    <div class="crop-stage" aria-label={t("Square photo crop preview", locale)}>
      <img
        src={imageUrl}
        alt=""
        onload={(event) => {
          const image = event.currentTarget as HTMLImageElement;
          sourceDimensions = {
            width: image.naturalWidth,
            height: image.naturalHeight,
          };
        }}
        style={`transform: translate(${offsetX * offsetLimits.x * 50}%, ${offsetY * offsetLimits.y * 50}%) rotate(${rotation}deg) scale(${zoom})`}
      />
      <span aria-hidden="true"></span>
    </div>
    <div class="crop-controls">
      <div class="rotate-controls" aria-label={t("Rotate photo", locale)}>
        <Button
          variant="secondary"
          size="sm"
          onclick={() => (rotation = (rotation + 270) % 360)}
          icon="rotate-left"
        >
          {t("Rotate left", locale)}
        </Button>
        <Button
          variant="secondary"
          size="sm"
          onclick={() => (rotation = (rotation + 90) % 360)}
          icon="rotate-right"
        >
          {t("Rotate right", locale)}
        </Button>
      </div>
      <label class="zoom-control">
        <span>{t("Zoom", locale)}</span>
        <input
          type="range"
          min="1"
          max="3"
          step="0.05"
          bind:value={zoom}
          aria-label={t("Zoom", locale)}
        />
      </label>
      <label class="zoom-control">
        <span>{t("Move photo left or right", locale)}</span>
        <input
          type="range"
          min="-1"
          max="1"
          step="0.02"
          bind:value={offsetX}
          disabled={offsetLimits.x === 0}
          aria-label={t("Move photo left or right", locale)}
        />
      </label>
      <label class="zoom-control">
        <span>{t("Move photo up or down", locale)}</span>
        <input
          type="range"
          min="-1"
          max="1"
          step="0.02"
          bind:value={offsetY}
          disabled={offsetLimits.y === 0}
          aria-label={t("Move photo up or down", locale)}
        />
      </label>
    </div>
  {/if}
  {#if error}<p class="avatar-error" role="alert">{error}</p>{/if}
  {#snippet actions()}
    <Button variant="ghost" onclick={closeEditor} icon="cancel">
      {t("Cancel", locale)}
    </Button>
    <Button
      variant="primary"
      onclick={() => void useCroppedImage()}
      icon="crop"
    >
      {t("Use this crop", locale)}
    </Button>
  {/snippet}
</ConfirmDialog>

<style>
  .file-input {
    position: fixed;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
  }

  .avatar-error {
    margin: 0.5rem 0 0;
    color: var(--danger, #b3261e);
  }

  .crop-stage {
    position: relative;
    width: min(100%, 22rem);
    aspect-ratio: 1;
    margin-inline: auto;
    overflow: hidden;
    border-radius: var(--radius-sm);
    background: #111;
  }

  .crop-stage img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: transform 160ms ease;
  }

  .crop-stage span {
    position: absolute;
    inset: 0;
    border: 2px solid rgb(255 255 255 / 82%);
    box-shadow: 0 0 0 999px rgb(10 20 30 / 22%);
    pointer-events: none;
  }

  .crop-controls {
    display: grid;
    gap: 1rem;
    margin-top: 1rem;
  }

  .rotate-controls {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .zoom-control {
    display: grid;
    gap: 0.4rem;
    font-weight: 650;
  }

  .zoom-control input {
    width: 100%;
    accent-color: var(--accent-strong);
  }

  @media (prefers-reduced-motion: reduce) {
    .crop-stage img {
      transition: none;
    }
  }
</style>
