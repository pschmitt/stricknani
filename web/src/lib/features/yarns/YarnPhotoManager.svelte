<script lang="ts">
  import { onMount, tick, untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import OcrControl from "#lib/features/media/OcrControl.svelte";
  import PhotoEditor from "#lib/features/media/PhotoEditor.svelte";
  import { yarnPhotoEditPath } from "#lib/features/media/photo-editor.ts";
  import AddMediaControl from "#lib/components/AddMediaControl.svelte";
  import { uploadSequentially } from "../media/upload-queue";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { publishFeedback } from "#lib/feedback.ts";
  import ReorderableMedia from "../media/ReorderableMedia.svelte";
  import RemovedMediaList from "../media/RemovedMediaList.svelte";
  import {
    YARN_GROUP,
    applyStagedImages,
    stageImageRemoval,
    stageMove,
    unstageImageRemoval,
    type StagedMedia,
  } from "../media/media-staging";
  import { fileDrop } from "#lib/components/file-drop.ts";
  import { uploadYarnPhoto, type YarnPhoto } from "./yarn-api";

  let {
    yarnId,
    photos: serverPhotos,
    locale,
    ownerId = null,
    ocrEnabled = false,
    canEdit = true,
    canStage = true,
    staged = $bindable(),
    readOnly = false,
    onResourceUpdated,
    onMediaBusyChange,
  }: {
    yarnId: number;
    photos: YarnPhoto[];
    locale: "en" | "de";
    ownerId?: number | null;
    ocrEnabled?: boolean;
    /** Adding and editing photos: only on a saved form, as they reload the yarn. */
    canEdit?: boolean;
    /** Removing and reordering: allowed on a dirty form too, they wait for Save. */
    canStage?: boolean;
    /** Removals and the order of the photos that wait for Save. */
    staged: StagedMedia;
    readOnly?: boolean;
    onResourceUpdated: (yarnId: number, ownerId: number) => Promise<void>;
    onMediaBusyChange?: (
      busy: boolean,
      yarnId: number,
      ownerId: number,
    ) => void;
  } = $props();

  /** The photos as the form shows them: staged removals hidden, the staged order applied. */
  const photos = $derived(
    applyStagedImages(serverPhotos, staged, () => YARN_GROUP),
  );
  const removedEntries = $derived(
    serverPhotos
      .filter((photo) => staged.removedImageIds.includes(photo.id))
      .map((photo) => ({
        key: String(photo.id),
        thumbnailUrl: photo.thumbnail_url,
        label: photo.alt_text ?? t("Yarn photo", locale),
        restoreLabel: t("Undo removing this photo", locale),
      })),
  );
  let uploading = $state(false);
  let uploadMessage = $state("");
  let errorMessage = $state("");
  let selectedPhoto = $state<YarnPhoto | null>(null);
  let editedPhoto = $state<YarnPhoto | null>(null);
  let editorOpen = $state(false);
  let zoomDialog = $state<HTMLDialogElement | null>(null);
  let zoomTitle = $state<HTMLHeadingElement | null>(null);

  const reorderAvailable = $derived(
    !readOnly &&
      canStage &&
      !uploading &&
      session.data?.user !== null &&
      session.data?.user !== undefined,
  );
  const mediaUploadAvailable = $derived(
    !readOnly && canEdit && session.data?.capabilities.media_upload === true,
  );
  const mediaDeleteAvailable = $derived(
    !readOnly && canStage && session.data?.capabilities.media_delete === true,
  );

  function isCurrentOperation(resourceId: number, ownerId: number): boolean {
    return yarnId === resourceId && session.data?.user?.id === ownerId;
  }

  /** Shared by the picker and by files dropped on the photo section; one request per file. */
  async function uploadFiles(files: File[]) {
    if (!mediaUploadAvailable || files.length === 0) return;
    if (!canEdit) {
      errorMessage = t("Save your yarn changes before editing photos.", locale);
      return;
    }
    const currentSession = session.data;
    if (!currentSession?.user) return;
    const resourceId = yarnId;
    const ownerId = currentSession.user.id;
    const updateResource = onResourceUpdated;
    const notifyMediaBusy = onMediaBusyChange;
    uploadMessage = t("Uploading photo…", locale);
    errorMessage = "";
    uploading = true;
    notifyMediaBusy?.(true, resourceId, ownerId);
    try {
      const result = await uploadSequentially(
        files,
        (file) => uploadYarnPhoto(resourceId, file, currentSession),
        (progress) => {
          if (
            progress.done < progress.total &&
            isCurrentOperation(resourceId, ownerId)
          )
            uploadMessage = `${t("Uploading photo", locale)} ${progress.current}/${progress.total}…`;
        },
        () => !isCurrentOperation(resourceId, ownerId),
      );
      if (result.uploaded > 0) await updateResource(resourceId, ownerId);
      if (isCurrentOperation(resourceId, ownerId)) {
        if (result.failures.length > 0) {
          const first = result.failures[0]?.error;
          const names = result.failures
            .map((entry) => entry.file.name)
            .join(", ");
          uploadMessage = "";
          errorMessage = `${t("Not uploaded:", locale)} ${names}${
            first instanceof Error ? `. ${first.message}` : ""
          }`;
        } else uploadMessage = t("Photo uploaded.", locale);
      }
    } finally {
      if (isCurrentOperation(resourceId, ownerId)) {
        uploading = false;
        notifyMediaBusy?.(false, resourceId, ownerId);
      }
    }
  }

  const editBusy = $derived(uploading);

  function openPhotoEditor(photo: YarnPhoto) {
    if (!mediaUploadAvailable || editBusy) return;
    editedPhoto = photo;
    editorOpen = true;
  }

  /** Reload the yarn after a photo edit; the edited file has new URLs. */
  async function reloadAfterPhotoEdit() {
    const currentSession = session.data;
    if (!currentSession?.user) return;
    const resourceId = yarnId;
    const ownerId = currentSession.user.id;
    const notifyMediaBusy = onMediaBusyChange;
    notifyMediaBusy?.(true, resourceId, ownerId);
    try {
      await onResourceUpdated(resourceId, ownerId);
    } finally {
      if (isCurrentOperation(resourceId, ownerId))
        notifyMediaBusy?.(false, resourceId, ownerId);
    }
  }

  /** Takes a photo out of the form; Save moves it to the trash, Undo puts it back. */
  function removePhoto(photo: YarnPhoto) {
    const ownerId = session.data?.user?.id;
    if (!mediaDeleteAvailable || ownerId === undefined) return;
    staged = stageImageRemoval(staged, photo.id);
    publishFeedback({
      ownerId,
      kind: "success",
      message: t("Removed. It goes to the trash when you save.", locale),
      action: {
        label: t("Undo", locale),
        onAction: () => {
          staged = unstageImageRemoval(staged, photo.id);
        },
      },
    });
  }

  /** Stages a move; Save sends the new order. */
  function reorderPhotos(from: number, to: number): Promise<boolean> {
    if (!reorderAvailable) return Promise.resolve(false);
    const next = stageMove(staged, YARN_GROUP, photos, from, to);
    if (next === staged) return Promise.resolve(false);
    staged = next;
    return Promise.resolve(true);
  }

  async function showPhoto(photo: YarnPhoto) {
    const requestedYarnId = yarnId;
    const requestedUserId = session.data?.user?.id ?? null;
    const requestedPhotoId = photo.id;
    selectedPhoto = photo;
    await tick();
    const dialog = zoomDialog;
    const title = zoomTitle;
    if (
      requestedYarnId !== yarnId ||
      requestedUserId !== (session.data?.user?.id ?? null) ||
      selectedPhoto?.id !== requestedPhotoId ||
      !dialog ||
      !title ||
      !dialog.isConnected ||
      !dialog.contains(title) ||
      dialog.open
    )
      return;
    const previousAutofocus = title.autofocus;
    title.autofocus = true;
    try {
      dialog.showModal();
    } finally {
      title.autofocus = previousAutofocus;
    }
    await tick();
    if (
      requestedYarnId === yarnId &&
      requestedUserId === (session.data?.user?.id ?? null) &&
      selectedPhoto?.id === requestedPhotoId &&
      dialog === zoomDialog &&
      title === zoomTitle &&
      dialog.isConnected &&
      dialog.open &&
      dialog.contains(title)
    ) {
      if (document.activeElement === title) return;
      title.focus();
    }
  }
</script>

<section
  class="photo-manager"
  aria-labelledby="photo-manager-title"
  use:fileDrop={{
    enabled: mediaUploadAvailable && !uploading,
    accept: "image/*",
    label: t("Drop to add photos", locale),
    multiple: true,
    onFiles: (files) => void uploadFiles(files),
    onRejected: () => {
      uploadMessage = "";
      errorMessage = t("Choose a supported image file.", locale);
    },
  }}
>
  <header class="photo-heading">
    <div>
      <h2 id="photo-manager-title">{t("Photos", locale)}</h2>
      <p>{t("Tap a photo to see it larger.", locale)}</p>
    </div>
  </header>

  {#if !readOnly}
    <AddMediaControl
      id={`yarn-photo-file-${yarnId}`}
      {locale}
      camera
      empty={photos.length === 0}
      variant={photos.length === 0 ? "primary" : "secondary"}
      label={t("Add photos", locale)}
      inputLabel={t("Choose a yarn photo", locale)}
      dropHint={t("or drop images here", locale)}
      disabled={!mediaUploadAvailable || uploading}
      hint={canEdit ? "" : t("Save your changes first to add photos.", locale)}
      onFiles={(files) => void uploadFiles(files)}
    />
    {#if canEdit && (!mediaUploadAvailable || !mediaDeleteAvailable)}
      <p class="upload-hint">
        {t("Media storage is unavailable. Try again later.", locale)}
      </p>
    {/if}
  {/if}
  {#if uploading}
    <div class="upload-state" role="status" aria-live="polite">
      <progress aria-label={t("Uploading photo", locale)}></progress>
      <span>{t("Uploading photo…", locale)}</span>
    </div>
  {:else if uploadMessage}
    <p class="upload-state" role="status" aria-live="polite">{uploadMessage}</p>
  {/if}
  {#if errorMessage}
    <p class="error-message" role="alert">{errorMessage}</p>
  {/if}

  {#if photos.length > 0}
    <ReorderableMedia
      items={photos}
      {locale}
      label={t("Photos", locale)}
      fallbackAlt={t("Yarn photo", locale)}
      firstIsCover
      reorderable={reorderAvailable}
      onMove={reorderPhotos}
      onOpen={(photo) => void showPhoto(photo)}
      onEdit={readOnly ? undefined : openPhotoEditor}
      editDisabled={!mediaUploadAvailable || editBusy}
      onDelete={readOnly ? undefined : removePhoto}
      deleteDisabled={!mediaDeleteAvailable || uploading}
    >
      {#snippet actions(photo)}
        <OcrControl
          src={photo.url}
          label={t("Recognized text from yarn photo", locale)}
          ownerId={ownerId ?? null}
          {locale}
          enabled={ocrEnabled}
        />
      {/snippet}
    </ReorderableMedia>
  {:else if readOnly}
    <p class="no-photos">{t("No photos yet.", locale)}</p>
  {/if}

  <dialog
    bind:this={zoomDialog}
    class="zoom-dialog"
    aria-labelledby={`yarn-photo-dialog-title-${yarnId}`}
    onclose={() => (selectedPhoto = null)}
  >
    <header class="zoom-dialog-header">
      <h2
        bind:this={zoomTitle}
        id={`yarn-photo-dialog-title-${yarnId}`}
        tabindex="-1"
      >
        {t("Yarn photo", locale)}
      </h2>
      <form method="dialog">
        <Button
          class="zoom-close"
          variant="secondary"
          size="sm"
          icon="close"
          type="submit"
          aria-label={t("Close photo", locale)}
        />
      </form>
    </header>
    {#if selectedPhoto}
      <img
        src={selectedPhoto.url}
        alt={selectedPhoto.alt_text ?? t("Yarn photo", locale)}
      />
      {#if selectedPhoto.alt_text}<p>{selectedPhoto.alt_text}</p>{/if}
    {/if}
  </dialog>

  {#if editedPhoto}
    <PhotoEditor
      open={editorOpen}
      src={editedPhoto.url}
      alt={editedPhoto.alt_text ?? t("Yarn photo", locale)}
      altText={editedPhoto.alt_text ?? ""}
      endpoint={yarnPhotoEditPath(yarnId, editedPhoto.id)}
      {locale}
      onSaved={reloadAfterPhotoEdit}
      onClose={() => (editorOpen = false)}
    />
  {/if}

  <RemovedMediaList
    entries={removedEntries}
    {locale}
    onRestore={(key) => (staged = unstageImageRemoval(staged, Number(key)))}
  />
</section>

<style>
  .photo-manager {
    display: grid;
    gap: 0.8rem;
  }

  .photo-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
  }

  .photo-heading h2,
  .photo-heading p {
    margin: 0;
  }

  .photo-heading p,
  .no-photos,
  .upload-hint {
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .upload-state {
    display: flex;
    min-height: 44px;
    align-items: center;
    gap: 0.65rem;
    margin: 0;
    color: var(--ink-muted);
  }

  progress {
    width: 2.6rem;
    accent-color: var(--accent-strong);
  }

  .error-message {
    margin: 0;
    color: var(--danger);
  }

  .zoom-dialog {
    max-width: min(94vw, 48rem);
    max-height: 90svh;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 1rem;
    color: var(--ink);
    background: var(--surface);
  }

  .zoom-dialog::backdrop {
    background: rgb(15 28 27 / 72%);
  }

  .zoom-dialog form {
    display: flex;
    justify-content: end;
  }

  .zoom-dialog-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
  }

  .zoom-dialog-header h2 {
    margin: 0;
    font-size: 1.05rem;
    line-height: 1.2;
  }

  .zoom-dialog-header h2:focus {
    outline: 3px solid var(--focus);
    outline-offset: 3px;
    border-radius: 0.15rem;
  }

  .zoom-dialog-header form {
    flex: 0 0 auto;
  }

  :global(.zoom-dialog form .zoom-close) {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    padding: 0;
  }

  .zoom-dialog img {
    display: block;
    max-width: 100%;
    max-height: 76svh;
    margin: 0 auto;
    object-fit: contain;
  }

  .zoom-dialog p {
    margin-bottom: 0;
    color: var(--ink-muted);
  }

  @media (max-width: 420px) {
    .photo-heading {
      align-items: start;
      flex-direction: column;
    }
  }
</style>
