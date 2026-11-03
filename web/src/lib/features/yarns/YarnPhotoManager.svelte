<script lang="ts">
  import { onMount, tick, untrack } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import OcrControl from "#lib/features/media/OcrControl.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { publishFeedback } from "#lib/feedback.ts";
  import ReorderableMedia from "../media/ReorderableMedia.svelte";
  import {
    applyOptimisticReorder,
    putOrder,
    yarnPhotoOrderPath,
  } from "../media/media-reorder";
  import { deleteYarnPhoto, uploadYarnPhoto, type YarnPhoto } from "./yarn-api";

  let {
    yarnId,
    photos: serverPhotos,
    locale,
    ownerId = null,
    ocrEnabled = false,
    canEdit = true,
    readOnly = false,
    onResourceUpdated,
    onMediaBusyChange,
  }: {
    yarnId: number;
    photos: YarnPhoto[];
    locale: "en" | "de";
    ownerId?: number | null;
    ocrEnabled?: boolean;
    canEdit?: boolean;
    readOnly?: boolean;
    onResourceUpdated: (yarnId: number, ownerId: number) => Promise<void>;
    onMediaBusyChange?: (
      busy: boolean,
      yarnId: number,
      ownerId: number,
    ) => void;
  } = $props();

  // Optimistic order shown until the server copy arrives through the photos prop.
  let optimisticPhotos = $state<YarnPhoto[] | null>(null);
  const photos = $derived(optimisticPhotos ?? serverPhotos);
  let reordering = $state(false);
  let uploading = $state(false);
  let uploadMessage = $state("");
  let errorMessage = $state("");
  let deleting = $state(false);
  let selectedPhoto = $state<YarnPhoto | null>(null);
  let deleteTarget = $state<YarnPhoto | null>(null);
  let deleteOwnerId = $state<number | null>(null);
  let deleteYarnId = $state<number | null>(null);
  let zoomDialog = $state<HTMLDialogElement | null>(null);
  let zoomTitle = $state<HTMLHeadingElement | null>(null);
  let deleteDialog = $state<ConfirmDialog | undefined>(undefined);
  let deleteDialogOpen = $state(false);
  let deleteGeneration = 0;

  const reorderAvailable = $derived(
    !readOnly &&
      canEdit &&
      !uploading &&
      !deleting &&
      session.data?.user !== null &&
      session.data?.user !== undefined,
  );
  const mediaUploadAvailable = $derived(
    !readOnly && canEdit && session.data?.capabilities.media_upload === true,
  );
  const mediaDeleteAvailable = $derived(
    !readOnly && canEdit && session.data?.capabilities.media_delete === true,
  );

  $effect(() => {
    const currentYarnId = yarnId;
    const currentOwnerId = session.data?.user?.id ?? null;
    untrack(() => {
      if (
        deleteTarget &&
        (deleteYarnId !== currentYarnId || deleteOwnerId !== currentOwnerId)
      ) {
        deleteGeneration += 1;
        untrack(() => deleteDialog?.close());
        deleteDialogOpen = false;
        deleteTarget = null;
        deleteOwnerId = null;
        deleteYarnId = null;
        deleting = false;
        errorMessage = "";
      }
    });
  });

  onMount(() => {
    const onKeydown = (event: KeyboardEvent) => {
      if (
        event.defaultPrevented ||
        event.key !== "Escape" ||
        !deleteDialogOpen ||
        deleting
      )
        return;
      event.preventDefault();
      closeDeleteDialog();
    };
    window.addEventListener("keydown", onKeydown);
    return () => window.removeEventListener("keydown", onKeydown);
  });

  function isCurrentOperation(resourceId: number, ownerId: number): boolean {
    return yarnId === resourceId && session.data?.user?.id === ownerId;
  }

  async function upload(event: Event) {
    const input = event.currentTarget;
    if (!(input instanceof HTMLInputElement)) return;
    if (!mediaUploadAvailable) {
      input.value = "";
      return;
    }
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
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
      await uploadYarnPhoto(resourceId, file, currentSession);
      await updateResource(resourceId, ownerId);
      if (isCurrentOperation(resourceId, ownerId))
        uploadMessage = t("Photo uploaded.", locale);
    } catch (error) {
      if (isCurrentOperation(resourceId, ownerId)) {
        uploadMessage = "";
        errorMessage =
          error instanceof Error
            ? error.message
            : t("The photo could not be uploaded.", locale);
      }
    } finally {
      if (isCurrentOperation(resourceId, ownerId)) {
        uploading = false;
        notifyMediaBusy?.(false, resourceId, ownerId);
      }
    }
  }

  function confirmDelete(photo: YarnPhoto) {
    const currentSession = session.data;
    if (
      !currentSession?.user ||
      !mediaDeleteAvailable ||
      uploading ||
      deleting ||
      !photos.some((candidate) => candidate.id === photo.id)
    )
      return;
    deleteGeneration += 1;
    deleteOwnerId = currentSession.user.id;
    deleteYarnId = yarnId;
    deleteTarget = photo;
    errorMessage = "";
    deleteDialog?.showModal();
    deleteDialogOpen = true;
  }

  function closeDeleteDialog(invalidate = true): void {
    if (invalidate) deleteGeneration += 1;
    untrack(() => deleteDialog?.close());
    deleteDialogOpen = false;
    deleteTarget = null;
    deleteOwnerId = null;
    deleteYarnId = null;
  }

  function isCurrentDelete(
    resourceId: number,
    ownerId: number,
    photoId: number,
    generation: number,
  ): boolean {
    return (
      deleteGeneration === generation &&
      deleteYarnId === resourceId &&
      deleteOwnerId === ownerId &&
      deleteTarget?.id === photoId &&
      isCurrentOperation(resourceId, ownerId)
    );
  }

  async function removePhoto() {
    const photo = deleteTarget;
    const currentSession = session.data;
    if (!photo || !currentSession?.user || !mediaDeleteAvailable || deleting)
      return;
    const resourceId = yarnId;
    const ownerId = currentSession.user.id;
    const photoId = photo.id;
    const generation = deleteGeneration;
    if (
      deleteYarnId !== resourceId ||
      deleteOwnerId !== ownerId ||
      deleteTarget?.id !== photoId ||
      !photos.some((candidate) => candidate.id === photoId)
    )
      return;
    const updateResource = onResourceUpdated;
    const notifyMediaBusy = onMediaBusyChange;
    let deleteSucceeded = false;
    deleting = true;
    errorMessage = "";
    notifyMediaBusy?.(true, resourceId, ownerId);
    try {
      await deleteYarnPhoto(resourceId, photoId, currentSession);
      if (!isCurrentDelete(resourceId, ownerId, photoId, generation)) return;
      deleteSucceeded = true;
      closeDeleteDialog(false);
      await updateResource(resourceId, ownerId);
      if (
        deleteGeneration === generation &&
        isCurrentOperation(resourceId, ownerId)
      )
        uploadMessage = t("Photo removed.", locale);
    } catch (error) {
      if (
        deleteSucceeded
          ? deleteGeneration === generation &&
            isCurrentOperation(resourceId, ownerId)
          : isCurrentDelete(resourceId, ownerId, photoId, generation)
      )
        errorMessage =
          error instanceof ApiError && error.status === 404
            ? t("This photo is no longer available.", locale)
            : error instanceof Error
              ? error.message
              : t("The photo could not be removed.", locale);
    } finally {
      if (
        deleteGeneration === generation &&
        isCurrentOperation(resourceId, ownerId)
      ) {
        deleting = false;
        notifyMediaBusy?.(false, resourceId, ownerId);
      }
    }
  }

  async function reorderPhotos(from: number, to: number): Promise<boolean> {
    const currentSession = session.data;
    if (!currentSession?.user || !reorderAvailable || reordering) return false;
    const resourceId = yarnId;
    const ownerId = currentSession.user.id;
    const updateResource = onResourceUpdated;
    const notifyMediaBusy = onMediaBusyChange;
    reordering = true;
    errorMessage = "";
    notifyMediaBusy?.(true, resourceId, ownerId);
    try {
      const ok = await applyOptimisticReorder({
        items: photos,
        from,
        to,
        setItems: (next) => (optimisticPhotos = next),
        send: (ids) =>
          putOrder(yarnPhotoOrderPath(resourceId), ids, currentSession),
        refetch: () => updateResource(resourceId, ownerId),
        onError: (error) => {
          if (!isCurrentOperation(resourceId, ownerId)) return;
          publishFeedback({
            ownerId,
            kind: "error",
            message:
              error instanceof Error
                ? error.message
                : t("The photo order could not be saved.", locale),
          });
        },
      });
      // Reorder bumps the yarn timestamp; reload so the editor stays in sync.
      if (ok) await updateResource(resourceId, ownerId);
      return ok;
    } finally {
      optimisticPhotos = null;
      if (isCurrentOperation(resourceId, ownerId)) {
        reordering = false;
        notifyMediaBusy?.(false, resourceId, ownerId);
      }
    }
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

<section class="photo-manager" aria-labelledby="photo-manager-title">
  <header class="photo-heading">
    <div>
      <h2 id="photo-manager-title">{t("Photos", locale)}</h2>
      <p>{t("Tap a photo to see it larger.", locale)}</p>
    </div>
  </header>

  {#if !readOnly}
    <div class="field upload-field">
      <label for={`yarn-photo-file-${yarnId}`}>
        <ActionLabel icon="upload">{t("Add photo", locale)}</ActionLabel>
      </label>
      <input
        id={`yarn-photo-file-${yarnId}`}
        class="shared-file-input"
        type="file"
        accept="image/*"
        aria-label={t("Choose a yarn photo", locale)}
        disabled={!mediaUploadAvailable || uploading}
        onchange={(event) => void upload(event)}
      />
    </div>
  {/if}

  {#if !readOnly}
    {#if !canEdit}
      <p class="upload-hint">
        {t("Save your yarn changes before editing photos.", locale)}
      </p>
    {:else if !mediaUploadAvailable || !mediaDeleteAvailable}
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
  {#if errorMessage && !deleteTarget}
    <p class="error-message" role="alert">{errorMessage}</p>
  {/if}

  {#if photos.length > 0}
    <ReorderableMedia
      items={photos}
      {locale}
      label={t("Photos", locale)}
      fallbackAlt={t("Yarn photo", locale)}
      showCover
      reorderable={reorderAvailable}
      disabled={reordering}
      onMove={reorderPhotos}
      onOpen={(photo) => void showPhoto(photo)}
    >
      {#snippet actions(photo)}
        {#if canEdit && !readOnly}
          <Button
            class="remove-photo"
            variant="danger"
            size="sm"
            icon="action-delete"
            type="button"
            disabled={!mediaDeleteAvailable ||
              uploading ||
              deleting ||
              reordering}
            aria-label={t("Remove photo", locale)}
            onclick={() => confirmDelete(photo)}
          >
            {t("Remove", locale)}
          </Button>
        {/if}
        <OcrControl
          src={photo.url}
          label={t("Recognized text from yarn photo", locale)}
          ownerId={ownerId ?? null}
          {locale}
          enabled={ocrEnabled}
        />
      {/snippet}
    </ReorderableMedia>
  {:else}
    <p class="no-photos">
      {t("No photos yet. Add one to recognize this yarn.", locale)}
    </p>
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

  <ConfirmDialog
    bind:this={deleteDialog}
    titleId="remove-photo-title"
    title={t("Remove this photo?", locale)}
    busy={deleting}
  >
    {#snippet children()}
      {#if deleteTarget}
        <p>
          {t("The photo will be removed from this yarn.", locale)}
        </p>
      {/if}
      {#if deleteTarget && errorMessage}
        <p class="error-message" role="alert">{errorMessage}</p>
      {/if}
    {/snippet}
    {#snippet actions()}
      <Button
        type="button"
        class="quiet-button"
        variant="secondary"
        icon="cancel"
        onclick={() => closeDeleteDialog()}
        disabled={deleting}
      >
        {t("Keep photo", locale)}
      </Button>
      <Button
        type="button"
        class="danger-button"
        variant="danger"
        icon="action-delete"
        disabled={!mediaDeleteAvailable || deleting}
        onclick={() => void removePhoto()}
      >
        {deleting ? t("Removing…", locale) : t("Remove photo", locale)}
      </Button>
    {/snippet}
  </ConfirmDialog>
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

  .upload-field {
    display: grid;
    max-width: 32rem;
    gap: 0.35rem;
    color: var(--ink-muted);
    font-size: 0.86rem;
    font-weight: 620;
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

  :global(.remove-photo) {
    flex: 0 0 auto;
    max-width: 100%;
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
