<script lang="ts">
  import { ApiError } from "#lib/api.ts";
  import OcrControl from "#lib/features/media/OcrControl.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { deleteYarnPhoto, uploadYarnPhoto, type YarnPhoto } from "./yarn-api";

  let {
    yarnId,
    photos,
    locale,
    ownerId = null,
    ocrEnabled = false,
    canEdit = true,
    onResourceUpdated,
    onMediaBusyChange,
  }: {
    yarnId: number;
    photos: YarnPhoto[];
    locale: "en" | "de";
    ownerId?: number | null;
    ocrEnabled?: boolean;
    canEdit?: boolean;
    onResourceUpdated: (yarnId: number, ownerId: number) => Promise<void>;
    onMediaBusyChange?: (
      busy: boolean,
      yarnId: number,
      ownerId: number,
    ) => void;
  } = $props();

  let uploading = $state(false);
  let uploadMessage = $state("");
  let errorMessage = $state("");
  let deleting = $state(false);
  let selectedPhoto = $state<YarnPhoto | null>(null);
  let deleteTarget = $state<YarnPhoto | null>(null);
  let zoomDialog = $state<HTMLDialogElement | null>(null);
  let deleteDialog = $state<HTMLDialogElement | null>(null);

  function isCurrentOperation(resourceId: number, ownerId: number): boolean {
    return yarnId === resourceId && session.data?.user?.id === ownerId;
  }

  async function upload(event: Event) {
    const input = event.currentTarget;
    if (!(input instanceof HTMLInputElement)) return;
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
    deleteTarget = photo;
    deleteDialog?.showModal();
  }

  async function removePhoto() {
    const photo = deleteTarget;
    const currentSession = session.data;
    if (!photo || !currentSession?.user || !canEdit || deleting) return;
    const resourceId = yarnId;
    const ownerId = currentSession.user.id;
    const updateResource = onResourceUpdated;
    const notifyMediaBusy = onMediaBusyChange;
    deleting = true;
    errorMessage = "";
    notifyMediaBusy?.(true, resourceId, ownerId);
    try {
      await deleteYarnPhoto(resourceId, photo.id, currentSession);
      deleteDialog?.close();
      deleteTarget = null;
      await updateResource(resourceId, ownerId);
      if (isCurrentOperation(resourceId, ownerId))
        uploadMessage = t("Photo removed.", locale);
    } catch (error) {
      if (isCurrentOperation(resourceId, ownerId))
        errorMessage =
          error instanceof ApiError && error.status === 404
            ? t("This photo is no longer available.", locale)
            : error instanceof Error
              ? error.message
              : t("The photo could not be removed.", locale);
    } finally {
      if (isCurrentOperation(resourceId, ownerId)) {
        deleting = false;
        notifyMediaBusy?.(false, resourceId, ownerId);
      }
    }
  }

  function showPhoto(photo: YarnPhoto) {
    selectedPhoto = photo;
    zoomDialog?.showModal();
  }
</script>

<section class="photo-manager" aria-labelledby="photo-manager-title">
  <header class="photo-heading">
    <div>
      <h2 id="photo-manager-title">{t("Photos", locale)}</h2>
      <p>{t("Tap a photo to see it larger.", locale)}</p>
    </div>
    <label class="upload-action" class:disabled={!canEdit || uploading}>
      <span>{uploading ? t("Uploading…", locale) : t("Add photo", locale)}</span
      >
      <input
        type="file"
        accept="image/*"
        aria-label={t("Choose a yarn photo", locale)}
        disabled={!canEdit || uploading}
        onchange={(event) => void upload(event)}
      />
    </label>
  </header>

  {#if !canEdit}
    <p class="upload-hint">
      {t("Save your yarn changes before editing photos.", locale)}
    </p>
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
    <ul class="photo-list">
      {#each photos as photo (photo.id)}
        <li class="photo-item">
          <button
            class="photo-open"
            type="button"
            aria-label={photo.alt_text
              ? `${t("Open photo", locale)}: ${photo.alt_text}`
              : t("Open yarn photo", locale)}
            onclick={() => showPhoto(photo)}
          >
            <img
              src={photo.thumbnail_url}
              alt={photo.alt_text ?? ""}
              loading="lazy"
            />
          </button>
          <div class="photo-caption">
            {#if photo.is_primary}<span>{t("Cover photo", locale)}</span>{/if}
            {#if canEdit}
              <button
                class="remove-photo"
                type="button"
                disabled={uploading || deleting}
                aria-label={t("Remove photo", locale)}
                onclick={() => confirmDelete(photo)}
              >
                {t("Remove", locale)}
              </button>
            {/if}
          </div>
          <OcrControl
            src={photo.url}
            label={t("Recognized text from yarn photo", locale)}
            ownerId={ownerId ?? null}
            {locale}
            enabled={ocrEnabled}
          />
        </li>
      {/each}
    </ul>
  {:else}
    <p class="no-photos">
      {t("No photos yet. Add one to recognize this yarn.", locale)}
    </p>
  {/if}

  <dialog
    bind:this={zoomDialog}
    class="zoom-dialog"
    aria-label={t("Yarn photo", locale)}
    onclose={() => (selectedPhoto = null)}
  >
    <form method="dialog">
      <button aria-label={t("Close photo", locale)}>×</button>
    </form>
    {#if selectedPhoto}
      <img
        src={selectedPhoto.url}
        alt={selectedPhoto.alt_text ?? t("Yarn photo", locale)}
      />
      {#if selectedPhoto.alt_text}<p>{selectedPhoto.alt_text}</p>{/if}
    {/if}
  </dialog>

  <dialog
    bind:this={deleteDialog}
    class="confirm-dialog"
    aria-labelledby="remove-photo-title"
  >
    <h2 id="remove-photo-title">{t("Remove this photo?", locale)}</h2>
    <p>{t("The photo will be removed from this yarn.", locale)}</p>
    <div class="dialog-actions">
      <button
        type="button"
        class="quiet-button"
        onclick={() => deleteDialog?.close()}
      >
        {t("Keep photo", locale)}
      </button>
      <button
        type="button"
        class="danger-button"
        disabled={deleting}
        onclick={() => void removePhoto()}
      >
        {deleting ? t("Removing…", locale) : t("Remove photo", locale)}
      </button>
    </div>
  </dialog>
</section>

<style>
  .photo-manager {
    display: grid;
    gap: 0.8rem;
  }

  .photo-heading,
  .photo-caption,
  .dialog-actions {
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

  .upload-action,
  .remove-photo,
  .quiet-button,
  .danger-button {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.55rem 0.75rem;
    color: var(--accent-strong);
    background: var(--surface);
    font: inherit;
    font-weight: 620;
    text-decoration: none;
    cursor: pointer;
  }

  .upload-action input {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    clip-path: inset(50%);
    white-space: nowrap;
  }

  .upload-action:focus-within {
    outline: 3px solid var(--focus);
    outline-offset: 3px;
  }

  .upload-action.disabled {
    color: var(--ink-muted);
    cursor: not-allowed;
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

  .photo-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 8rem), 1fr));
    gap: 0.65rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .photo-item {
    display: grid;
    min-width: 0;
    gap: 0.35rem;
  }

  .photo-open {
    display: block;
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 0.75rem;
    padding: 0;
    background: var(--surface-raised);
    cursor: zoom-in;
  }

  .photo-open img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .photo-caption {
    min-height: 44px;
    color: var(--ink-muted);
    font-size: 0.8rem;
  }

  .remove-photo {
    min-height: 40px;
    padding: 0.4rem 0.55rem;
    font-size: 0.82rem;
  }

  .zoom-dialog,
  .confirm-dialog {
    max-width: min(94vw, 48rem);
    max-height: 90svh;
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: 1rem;
    color: var(--ink);
    background: var(--surface);
  }

  .zoom-dialog::backdrop,
  .confirm-dialog::backdrop {
    background: rgb(15 28 27 / 72%);
  }

  .zoom-dialog form {
    display: flex;
    justify-content: end;
  }

  .zoom-dialog form button {
    width: 44px;
    height: 44px;
    border: 1px solid var(--line);
    border-radius: 50%;
    color: var(--ink);
    background: var(--surface);
    font: inherit;
    font-size: 1.4rem;
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

  .confirm-dialog h2 {
    margin-top: 0;
  }

  .confirm-dialog p {
    max-width: 28rem;
    color: var(--ink-muted);
  }

  .danger-button {
    border-color: var(--danger);
    color: var(--on-danger);
    background: var(--danger);
  }

  @media (max-width: 420px) {
    .photo-heading {
      align-items: start;
      flex-direction: column;
    }

    .upload-action {
      width: 100%;
    }

    .dialog-actions {
      align-items: stretch;
      flex-direction: column-reverse;
    }
  }
</style>
