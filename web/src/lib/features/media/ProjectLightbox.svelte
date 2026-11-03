<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { ProjectImage } from "#lib/features/projects/project-api.ts";

  interface Props {
    images: ProjectImage[];
    selectedId: number | null;
    title: string;
    locale: "en" | "de";
    onClose: () => void;
    onSelect: (image: ProjectImage) => void;
  }

  let { images, selectedId, title, locale, onClose, onSelect }: Props =
    $props();
  let dialog: HTMLDialogElement;
  let activeIndex = $derived(
    images.findIndex((image) => image.id === selectedId),
  );
  let activeImage = $derived(images[activeIndex]);

  $effect(() => {
    if (!dialog) return;
    if (selectedId !== null && !dialog.open) dialog.showModal();
    else if (selectedId === null && dialog.open) dialog.close();
  });

  function move(direction: -1 | 1) {
    if (images.length < 2 || activeIndex < 0) return;
    const nextIndex = (activeIndex + direction + images.length) % images.length;
    const nextImage = images[nextIndex];
    if (nextImage) onSelect(nextImage);
  }

  function close() {
    onClose();
  }
</script>

<dialog
  bind:this={dialog}
  class="lightbox"
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
          <img src={activeImage.url} alt={activeImage.alt_text || title} />
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

<style>
  .lightbox {
    width: min(94vw, 70rem);
    max-width: 70rem;
    max-height: min(92svh, 56rem);
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
    max-height: min(92svh, 56rem);
    grid-template-rows: auto minmax(0, 1fr);
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

  .lightbox-stage {
    display: grid;
    min-height: 12rem;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: clamp(0.4rem, 2vw, 1rem);
    padding: 0.75rem;
  }

  figure {
    display: grid;
    min-width: 0;
    max-height: calc(92svh - 5rem);
    grid-template-rows: minmax(0, 1fr) auto;
    justify-items: center;
    gap: 0.7rem;
    margin: 0;
  }

  .lightbox-stage figure.single-image {
    grid-column: 1 / -1;
  }

  figure img {
    display: block;
    max-width: 100%;
    max-height: calc(92svh - 9rem);
    border-radius: 0.4rem;
    object-fit: contain;
  }

  figcaption {
    display: flex;
    width: 100%;
    justify-content: space-between;
    gap: 1rem;
    color: var(--ink-muted);
    font-size: 0.86rem;
  }

  @media (max-width: 420px) {
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
