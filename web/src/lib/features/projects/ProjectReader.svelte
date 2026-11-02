<script lang="ts">
  import { onDestroy, tick } from "svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import ArchiveControl from "#lib/features/media/ArchiveControl.svelte";
  import OcrControl from "#lib/features/media/OcrControl.svelte";
  import ProjectLightbox from "#lib/features/media/ProjectLightbox.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    getProject,
    getYarn,
    setProjectFavorite,
    type ProjectDetail,
    type ProjectImage,
    type ProjectYarn,
  } from "./project-api";
  import {
    rememberReadingStep,
    resolveReadingStep,
    stepHash,
    stepIdFromHash,
  } from "./reader-progress";
  import {
    mergeArchiveDetailPreservingFavorite,
    mergeFavoriteMutationIntoCurrent,
  } from "./reader-state";

  interface Props {
    id: number;
  }

  type LoadState = "loading" | "ready" | "not-found" | "error";

  let { id }: Props = $props();
  let loadState = $state<LoadState>("loading");
  let project = $state<ProjectDetail | null>(null);
  let currentStepId = $state<number | null>(null);
  let yarns = $state<ProjectYarn[]>([]);
  let errorMessage = $state("");
  let favoriteError = $state("");
  let yarnWarning = $state(false);
  let favoriteBusy = $state(false);
  let selectedImageId = $state<number | null>(null);
  let readerElement = $state<HTMLElement | null>(null);
  let materialsDetailsElement = $state<HTMLDetailsElement | null>(null);
  let textSize = $state(1);
  let printPreparing = $state(false);
  let printError = $state("");
  let reload = $state(0);
  let lastArchiveRefreshMarker: string | null = null;
  let favoriteGeneration = 0;
  let resourceGeneration = 0;
  let printGeneration = 0;
  let archiveRefreshGeneration = 0;
  let archiveRefreshController: AbortController | null = null;
  let activePrintImages: HTMLImageElement[] = [];
  let printDialogActive = false;
  let materialsOpenBeforePrint = false;
  let materialsOpenStateCaptured = false;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const canUseOcr = $derived(session.data?.capabilities.ocr === true);
  const canArchive = $derived(session.data?.capabilities.archive === true);
  const canReadArchiveState = $derived(
    session.data?.capabilities.archive_state === true,
  );
  const textSizeClass = $derived(
    ["reading-small", "reading-medium", "reading-large"][textSize] ??
      "reading-medium",
  );
  const orderedSteps = $derived(
    project
      ? [...project.steps].sort(
          (left, right) => left.step_number - right.step_number,
        )
      : [],
  );
  const coverImage = $derived(
    project?.images.find(
      (image) => image.step_id === null && image.is_title_image,
    ) ??
      project?.images.find((image) => image.step_id === null) ??
      null,
  );
  const galleryImages = $derived(
    project?.images.filter(
      (image) => image.step_id === null && image.id !== coverImage?.id,
    ) ?? [],
  );
  const selectedImage = $derived(
    project?.images.find((image) => image.id === selectedImageId) ?? null,
  );
  const currentStepIndex = $derived(
    orderedSteps.findIndex((step) => step.id === currentStepId),
  );

  $effect(() => {
    const currentUserId = userId;
    const generation = ++resourceGeneration;
    reload;
    archiveRefreshGeneration += 1;
    archiveRefreshController?.abort();
    archiveRefreshController = null;
    lastArchiveRefreshMarker = null;
    printGeneration += 1;
    clearPrintImageSources(activePrintImages);
    printDialogActive = false;
    printPreparing = false;
    printError = "";
    const controller = new AbortController();
    favoriteGeneration += 1;
    favoriteBusy = false;
    favoriteError = "";
    project = null;
    currentStepId = null;
    yarns = [];
    yarnWarning = false;
    errorMessage = "";
    loadState = "loading";
    if (currentUserId === null) {
      loadState = "error";
      errorMessage = t("Sign in to open this project.", locale);
      return () => {
        controller.abort();
        if (resourceGeneration === generation) resourceGeneration += 1;
      };
    }
    if (!Number.isSafeInteger(id) || id <= 0) {
      loadState = "not-found";
      return () => {
        controller.abort();
        if (resourceGeneration === generation) resourceGeneration += 1;
      };
    }
    void getProject(id, controller.signal)
      .then(async (result) => {
        if (controller.signal.aborted) return;
        project = result;
        const stepIds = [...result.steps]
          .sort((left, right) => left.step_number - right.step_number)
          .map((step) => step.id);
        const restoredStepId = resolveReadingStep(
          currentUserId,
          result.id,
          page.url.hash,
          stepIds,
        );
        currentStepId = restoredStepId;
        if (restoredStepId !== null) {
          rememberReadingStep(currentUserId, result.id, restoredStepId);
          const hash = stepHash(restoredStepId);
          if (page.url.hash !== hash) {
            void goto(`${page.url.pathname}${page.url.search}${hash}`, {
              replaceState: true,
            });
          }
        }
        loadState = "ready";
        const linkedYarns = await Promise.allSettled(
          result.yarn_ids.map((yarnId) => getYarn(yarnId, controller.signal)),
        );
        if (controller.signal.aborted) return;
        yarns = linkedYarns.flatMap((item) =>
          item.status === "fulfilled" ? [item.value] : [],
        );
        yarnWarning = linkedYarns.some((item) => item.status === "rejected");
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 404) {
          loadState = "not-found";
          return;
        }
        errorMessage =
          error instanceof Error
            ? error.message
            : t("This project could not be loaded.", locale);
        loadState = "error";
      });
    return () => {
      controller.abort();
      if (resourceGeneration === generation) resourceGeneration += 1;
      archiveRefreshGeneration += 1;
      archiveRefreshController?.abort();
      archiveRefreshController = null;
    };
  });

  $effect(() => {
    const currentProject = project;
    const currentUserId = userId;
    if (!currentProject || currentUserId === null) return;
    const stepId = stepIdFromHash(
      page.url.hash,
      orderedSteps.map((step) => step.id),
    );
    if (stepId === null) return;
    currentStepId = stepId;
    rememberReadingStep(currentUserId, currentProject.id, stepId);
  });

  async function toggleFavorite() {
    const currentProject = project;
    const currentSession = session.data;
    if (!currentProject || !currentSession?.user || favoriteBusy) return;
    const currentUserId = currentSession.user.id;
    const favorite = !currentProject.is_favorite;
    const generation = ++favoriteGeneration;
    favoriteBusy = true;
    favoriteError = "";
    try {
      await setProjectFavorite(currentProject.id, favorite, currentSession);
      if (isCurrentFavorite(generation, currentProject.id, currentUserId)) {
        const latestProject = project;
        if (latestProject)
          project = mergeFavoriteMutationIntoCurrent(latestProject, favorite);
      }
    } catch (error) {
      if (!isCurrentFavorite(generation, currentProject.id, currentUserId))
        return;
      favoriteError =
        error instanceof ApiError && error.status === 404
          ? t("This project is no longer available.", locale)
          : error instanceof Error
            ? error.message
            : t("The favorite could not be updated.", locale);
    } finally {
      if (generation === favoriteGeneration) favoriteBusy = false;
    }
  }

  function isCurrentFavorite(
    generation: number,
    projectId: number,
    currentUserId: number,
  ): boolean {
    return (
      generation === favoriteGeneration &&
      project?.id === projectId &&
      session.data?.user?.id === currentUserId
    );
  }

  function safeExternalUrl(value: string | null): string | null {
    if (!value) return null;
    try {
      const url = new URL(value);
      return url.protocol === "https:" || url.protocol === "http:"
        ? value
        : null;
    } catch {
      return null;
    }
  }

  function formatDate(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.valueOf())) return "";
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(
      date,
    );
  }

  function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} ${t("bytes", locale)}`;
    if (bytes < 1024 * 1024)
      return `${(bytes / 1024).toLocaleString(locale, { maximumFractionDigits: 1 })} KB`;
    return `${(bytes / (1024 * 1024)).toLocaleString(locale, { maximumFractionDigits: 1 })} MB`;
  }

  function stepImages(stepId: number): ProjectImage[] {
    return project?.images.filter((image) => image.step_id === stepId) ?? [];
  }

  function openImage(image: ProjectImage) {
    selectedImageId = image.id;
  }

  function printImages(): HTMLImageElement[] {
    return Array.from(
      readerElement?.querySelectorAll<HTMLImageElement>(
        ".print-reference-image",
      ) ?? [],
    );
  }

  function attachPrintImageSources(): HTMLImageElement[] {
    const images = printImages();
    for (const image of images) {
      const source = image.dataset.printSrc;
      if (!source) throw new Error("missing print image source");
      const url = new URL(source, window.location.href);
      if (url.origin !== window.location.origin)
        throw new Error("print image source is not same-origin");
      image.dataset.printGeneration = String(printGeneration);
      image.dataset.printProjectId = String(project?.id ?? "");
      image.dataset.printOwnerId = String(userId ?? "");
      image.loading = "eager";
      image.src = url.href;
    }
    activePrintImages = images;
    return images;
  }

  function clearPrintImageSources(images: HTMLImageElement[]) {
    for (const image of images) {
      image.removeAttribute("src");
      image.loading = "lazy";
      delete image.dataset.printGeneration;
      delete image.dataset.printProjectId;
      delete image.dataset.printOwnerId;
    }
    if (activePrintImages === images) activePrintImages = [];
  }

  function waitForPrintImage(
    image: HTMLImageElement,
    signal: AbortSignal,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      if (image.complete) {
        if (image.naturalWidth > 0) resolve();
        else reject(new Error("print image failed to load"));
        return;
      }

      let settled = false;
      const timeout = setTimeout(
        () => finish(new Error("print image load timed out")),
        15_000,
      );
      const cleanup = () => {
        clearTimeout(timeout);
        image.removeEventListener("error", onError);
        image.removeEventListener("load", onLoad);
        signal.removeEventListener("abort", onAbort);
      };
      const finish = (error?: Error) => {
        if (settled) return;
        settled = true;
        cleanup();
        if (error) reject(error);
        else resolve();
      };
      const onError = () => finish(new Error("print image failed to load"));
      const onAbort = () => finish(new Error("print image wait was cancelled"));
      const onLoad = () => {
        if (image.naturalWidth <= 0) {
          onError();
          return;
        }
        finish();
      };

      image.addEventListener("error", onError, { once: true });
      image.addEventListener("load", onLoad, { once: true });
      signal.addEventListener("abort", onAbort, { once: true });
      if (signal.aborted) {
        onAbort();
        return;
      }
    });
  }

  async function waitForPrintImages(images: HTMLImageElement[]) {
    const controller = new AbortController();
    try {
      await Promise.all(
        images.map((image) => waitForPrintImage(image, controller.signal)),
      );
    } catch (error) {
      controller.abort();
      throw error;
    }
  }

  function handleBeforePrint() {
    printDialogActive = true;
    if (!materialsOpenStateCaptured) {
      materialsOpenBeforePrint = materialsDetailsElement?.open ?? false;
      materialsOpenStateCaptured = true;
    }
    if (materialsDetailsElement) materialsDetailsElement.open = true;
    try {
      attachPrintImageSources();
    } catch {
      printError = t(
        "A reference photo could not be prepared for printing. Try again.",
        locale,
      );
    }
  }

  function handleAfterPrint() {
    printGeneration += 1;
    printDialogActive = false;
    clearPrintImageSources(activePrintImages);
    printPreparing = false;
    if (materialsOpenStateCaptured && materialsDetailsElement) {
      materialsDetailsElement.open = materialsOpenBeforePrint;
    }
    materialsOpenStateCaptured = false;
  }

  function handlePrintImageError(event: Event) {
    const image = event.currentTarget;
    if (
      !(image instanceof HTMLImageElement) ||
      image.dataset.printGeneration !== String(printGeneration) ||
      image.dataset.printProjectId !== String(project?.id ?? "") ||
      image.dataset.printOwnerId !== String(userId ?? "")
    )
      return;
    printError = t(
      "A reference photo could not be prepared for printing. Try again.",
      locale,
    );
  }

  function isCurrentPrintOperation(
    generation: number,
    projectId: number | undefined,
    currentUserId: number | null,
    currentResourceGeneration: number,
  ): boolean {
    return (
      generation === printGeneration &&
      currentResourceGeneration === resourceGeneration &&
      project?.id === projectId &&
      userId === currentUserId
    );
  }

  async function printPattern() {
    if (printPreparing) return;
    const generation = ++printGeneration;
    const projectId = project?.id;
    const currentUserId = userId;
    const currentResourceGeneration = resourceGeneration;
    printPreparing = true;
    printError = "";
    let images: HTMLImageElement[] = [];
    let printStarted = false;
    try {
      images = attachPrintImageSources();
      await waitForPrintImages(images);
      if (
        !isCurrentPrintOperation(
          generation,
          projectId,
          currentUserId,
          currentResourceGeneration,
        )
      )
        return;
      if (images.some((image) => image.naturalWidth <= 0))
        throw new Error("print image did not decode");
      await tick();
      if (
        !isCurrentPrintOperation(
          generation,
          projectId,
          currentUserId,
          currentResourceGeneration,
        )
      )
        return;
      window.print();
      printStarted = true;
    } catch {
      if (
        isCurrentPrintOperation(
          generation,
          projectId,
          currentUserId,
          currentResourceGeneration,
        )
      ) {
        printError = t(
          "A reference photo could not be prepared for printing. Try again.",
          locale,
        );
      }
    } finally {
      if (!printStarted || !printDialogActive) {
        clearPrintImageSources(images);
        if (generation === printGeneration) printPreparing = false;
      }
    }
  }

  onDestroy(() => {
    printGeneration += 1;
    printDialogActive = false;
    clearPrintImageSources(activePrintImages);
  });

  async function refreshAfterArchive(marker: string) {
    if (marker === lastArchiveRefreshMarker) return;
    const currentProject = project;
    const currentUserId = userId;
    if (!currentProject || currentUserId === null) return;
    const generation = ++archiveRefreshGeneration;
    const currentResourceGeneration = resourceGeneration;
    const favoriteStateAtRequest = {
      generation: favoriteGeneration,
      busy: favoriteBusy,
    };
    const controller = new AbortController();
    archiveRefreshController?.abort();
    archiveRefreshController = controller;
    const refreshed = await getProject(currentProject.id, controller.signal);
    if (
      controller.signal.aborted ||
      generation !== archiveRefreshGeneration ||
      currentResourceGeneration !== resourceGeneration ||
      project?.id !== currentProject.id ||
      userId !== currentUserId
    )
      return;
    const latestProject = project;
    if (!latestProject) return;
    lastArchiveRefreshMarker = marker;
    project = mergeArchiveDetailPreservingFavorite(
      refreshed,
      latestProject,
      favoriteStateAtRequest,
      { generation: favoriteGeneration, busy: favoriteBusy },
    );
    if (
      currentStepId !== null &&
      !refreshed.steps.some((step) => step.id === currentStepId)
    ) {
      const firstStep = [...refreshed.steps].sort(
        (left, right) => left.step_number - right.step_number,
      )[0];
      currentStepId = firstStep?.id ?? null;
    }
    archiveRefreshController = null;
  }
</script>

<svelte:window
  onbeforeprint={handleBeforePrint}
  onafterprint={handleAfterPrint}
/>

<svelte:head>
  <title>
    Stricknani{project ? ` · ${project.name}` : ` · ${t("Project", locale)}`}
  </title>
</svelte:head>

{#if loadState === "loading"}
  <section class="reader-loading" aria-busy="true" aria-live="polite">
    <a class="back-link" href={resolve("/projects")}
      >‹ {t("Back to projects", locale)}</a
    >
    <div class="loading-mark" aria-hidden="true"></div>
    <p>{t("Opening your pattern…", locale)}</p>
  </section>
{:else if loadState === "not-found"}
  <section class="reader-state" aria-labelledby="reader-state-title">
    <a class="back-link" href={resolve("/projects")}
      >‹ {t("Back to projects", locale)}</a
    >
    <div class="state-card">
      <span class="state-mark" aria-hidden="true">⌕</span>
      <h1 id="reader-state-title">{t("Project not found", locale)}</h1>
      <p>
        {t(
          "This project may have been removed or may not belong to your account.",
          locale,
        )}
      </p>
    </div>
  </section>
{:else if loadState === "error"}
  <section class="reader-state" aria-labelledby="reader-state-title">
    <a class="back-link" href={resolve("/projects")}
      >‹ {t("Back to projects", locale)}</a
    >
    <div class="state-card" role="alert">
      <span class="state-mark" aria-hidden="true">!</span>
      <h1 id="reader-state-title">
        {t("The pattern could not be opened.", locale)}
      </h1>
      <p>{errorMessage}</p>
      <button
        class="secondary-button"
        type="button"
        onclick={() => (reload += 1)}
      >
        <ActionLabel icon="reset">{t("Try again", locale)}</ActionLabel>
      </button>
    </div>
  </section>
{:else if project}
  <article class="reader" bind:this={readerElement}>
    <nav class="reader-topline" aria-label={t("Project actions", locale)}>
      <a class="back-link" href={resolve("/projects")}
        >‹ {t("Back to projects", locale)}</a
      >
      <div class="reader-actions">
        <div
          class="text-size-controls"
          aria-label={t("Pattern text size", locale)}
        >
          <button
            type="button"
            aria-label={t("Decrease text size", locale)}
            disabled={textSize === 0}
            onclick={() => (textSize = Math.max(0, textSize - 1))}>A−</button
          >
          <button
            type="button"
            aria-label={t("Increase text size", locale)}
            disabled={textSize === 2}
            onclick={() => (textSize = Math.min(2, textSize + 1))}>A+</button
          >
        </div>
        <button
          class="print-button"
          type="button"
          disabled={printPreparing}
          aria-busy={printPreparing}
          onclick={() => void printPattern()}
        >
          <ActionLabel icon="print"
            >{printPreparing
              ? t("Preparing full-resolution photos…", locale)
              : t("Print pattern", locale)}</ActionLabel
          >
        </button>
      </div>
    </nav>
    {#if printError}
      <p class="print-error" role="alert">{printError}</p>
    {/if}

    <header class="reader-heading">
      <div>
        <p class="section-mark">
          {project.category ?? t("Knitting project", locale)}
        </p>
        <h1>{project.name}</h1>
        <div class="reader-subtitle">
          <span>{t("Updated", locale)} {formatDate(project.updated_at)}</span>
          {#if project.tags.length}
            <ul class="reader-tags" aria-label={t("Project tags", locale)}>
              {#each project.tags as tag, index (`${index}:${tag}`)}
                <li>{tag}</li>
              {/each}
            </ul>
          {/if}
        </div>
      </div>
      <div class="reader-heading-actions">
        <a
          class="reader-edit-link"
          href={resolve("/projects/[id]/edit", { id: String(project.id) })}
        >
          <ActionLabel icon="action-update"
            >{t("Edit project", locale)}</ActionLabel
          >
        </a>
        <button
          class="reader-favorite"
          type="button"
          disabled={favoriteBusy}
          aria-pressed={project.is_favorite}
          aria-label={project.is_favorite
            ? t("Remove from favorites", locale)
            : t("Add to favorites", locale)}
          onclick={toggleFavorite}
        >
          <span aria-hidden="true">{project.is_favorite ? "★" : "☆"}</span>
          {project.is_favorite
            ? t("Favorite", locale)
            : t("Add to favorites", locale)}
        </button>
      </div>
    </header>

    <div class="reader-overview" class:has-cover={coverImage !== null}>
      {#if coverImage}
        <figure class="reader-cover">
          <button
            class="gallery-image reader-cover-image"
            type="button"
            aria-label={t("Open project photo", locale) +
              `: ${coverImage.alt_text || project.name}`}
            onclick={() => openImage(coverImage)}
          >
            <img
              class="screen-reference-image"
              src={coverImage.thumbnail_url}
              alt={coverImage.alt_text || project.name}
              loading="lazy"
            />
            <img
              class="print-reference-image"
              data-print-src={coverImage.url}
              alt={coverImage.alt_text || project.name}
              loading="lazy"
              onerror={handlePrintImageError}
            />
            {#if coverImage.is_title_image}
              <span class="title-image-label">{t("Cover photo", locale)}</span>
            {:else if coverImage.is_stitch_sample}
              <span class="title-image-label">
                {t("Stitch sample", locale)}
              </span>
            {/if}
          </button>
          <OcrControl
            src={coverImage.url}
            label={t("Recognized text from project photo", locale)}
            ownerId={userId}
            {locale}
            enabled={canUseOcr}
          />
        </figure>
      {/if}
      <dl class="reader-keyfacts">
        <div>
          <dt>{t("Yarn", locale)}</dt>
          <dd>
            {#if yarns.length}
              {yarns[0].name}{#if yarns.length > 1}
                +{yarns.length - 1}{/if}
            {:else}
              {project.yarn || t("Not recorded", locale)}
            {/if}
          </dd>
        </div>
        {#if project.needles || yarns.some((yarn) => yarn.recommended_needles)}
          <div>
            <dt>{t("Needles", locale)}</dt>
            <dd>
              {project.needles ||
                yarns.find((yarn) => yarn.recommended_needles)
                  ?.recommended_needles}
            </dd>
          </div>
        {/if}
        {#if project.stitch_sample}
          <div>
            <dt>{t("Gauge", locale)}</dt>
            <dd>{project.stitch_sample}</dd>
          </div>
        {/if}
      </dl>
    </div>

    <details
      class="details-card materials-card"
      bind:this={materialsDetailsElement}
    >
      <summary>{t("Materials", locale)}</summary>
      <dl>
        {#if yarns.length}
          {#each yarns as yarn (yarn.id)}
            <div>
              <dt>{t("Yarn", locale)}</dt>
              <dd>
                <ResourceCard
                  kind="yarn"
                  href={`${resolve("/yarns")}/${yarn.id}`}
                  imageUrl={yarn.preview_url}
                  name={yarn.name}
                  subtitle={[yarn.brand, yarn.colorway]
                    .filter(Boolean)
                    .join(" · ")}
                  meta={[
                    yarn.fiber_content,
                    yarn.weight_category,
                    yarn.weight_grams ? `${yarn.weight_grams} g` : null,
                    yarn.length_meters ? `${yarn.length_meters} m` : null,
                  ].filter((value): value is string => Boolean(value))}
                  titleTag="h3"
                />
                {#if yarn.notes_html}
                  <div class="rich-text yarn-notes">
                    {@html yarn.notes_html}
                  </div>
                {/if}
                {#if safeExternalUrl(yarn.link)}
                  <a
                    href={safeExternalUrl(yarn.link)}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {t("Yarn source", locale)} ↗
                  </a>
                {/if}
              </dd>
            </div>
          {/each}
        {:else if project.yarn}
          <div>
            <dt>{t("Yarn", locale)}</dt>
            <dd>{project.yarn}</dd>
          </div>
        {:else}
          <div>
            <dt>{t("Yarn", locale)}</dt>
            <dd>{t("Not recorded", locale)}</dd>
          </div>
        {/if}
        {#if yarnWarning}
          <p class="muted-copy">
            {t("Some yarn details could not be loaded.", locale)}
          </p>
        {/if}
        {#if project.needles || yarns.some((yarn) => yarn.recommended_needles)}
          <div>
            <dt>{t("Needles", locale)}</dt>
            <dd>
              {project.needles ??
                yarns.find((yarn) => yarn.recommended_needles)
                  ?.recommended_needles}
            </dd>
          </div>
        {/if}
        {#if project.stitch_sample}
          <div>
            <dt>{t("Gauge", locale)}</dt>
            <dd>{project.stitch_sample}</dd>
          </div>
        {/if}
      </dl>
    </details>

    {#if favoriteError}
      <p class="inline-error" role="alert">{favoriteError}</p>
    {/if}

    <div class="reader-columns">
      <div class="pattern-column">
        <section class="pattern-section" aria-labelledby="pattern-heading">
          <div class="section-heading-row">
            <h2 id="pattern-heading">{t("Pattern and notes", locale)}</h2>
            <span>{t("Your reference", locale)}</span>
          </div>
          <div class="reading-copy {textSizeClass}">
            {#if project.description_html}
              <div class="rich-text">{@html project.description_html}</div>
            {:else}
              <p class="muted-copy">
                {t("No pattern notes have been added yet.", locale)}
              </p>
            {/if}
          </div>
          {#if project.notes_html}
            <section class="note-section" aria-labelledby="notes-heading">
              <h3 id="notes-heading">{t("Notes", locale)}</h3>
              <div class="reading-copy {textSizeClass}">
                <div class="rich-text">{@html project.notes_html}</div>
              </div>
            </section>
          {/if}
          {#if project.other_materials_html}
            <section class="note-section" aria-labelledby="materials-heading">
              <h3 id="materials-heading">{t("Other materials", locale)}</h3>
              <div class="rich-text">{@html project.other_materials_html}</div>
            </section>
          {/if}
        </section>

        <section class="steps-section" aria-labelledby="steps-heading">
          <div class="section-heading-row">
            <h2 id="steps-heading">{t("Steps", locale)}</h2>
            <span>{orderedSteps.length}</span>
          </div>
          {#if orderedSteps.length}
            <nav
              class="step-navigation"
              aria-label={t("Pattern step navigation", locale)}
            >
              {#if currentStepIndex > 0}
                {@const previousStep = orderedSteps[currentStepIndex - 1]}
                <a
                  class="step-navigation-link"
                  href={stepHash(previousStep.id)}
                >
                  <span
                    ><NavIcon name="previous" />{t(
                      "Previous step",
                      locale,
                    )}</span
                  >
                  <strong>{previousStep.title}</strong>
                </a>
              {:else}
                <span class="step-navigation-spacer"></span>
              {/if}
              <p class="step-progress" aria-live="polite" aria-atomic="true">
                {t("Step", locale)}
                {currentStepIndex + 1}
                {t("of", locale)}
                {orderedSteps.length}
              </p>
              {#if currentStepIndex >= 0 && currentStepIndex < orderedSteps.length - 1}
                {@const nextStep = orderedSteps[currentStepIndex + 1]}
                <a
                  class="step-navigation-link next-step-link"
                  href={stepHash(nextStep.id)}
                >
                  <span>{t("Next step", locale)}<NavIcon name="next" /></span>
                  <strong>{nextStep.title}</strong>
                </a>
              {:else}
                <span class="step-navigation-spacer"></span>
              {/if}
            </nav>
            <ol class="steps-list">
              {#each orderedSteps as step (step.id)}
                <li
                  id={`step-${step.id}`}
                  class:current-step={step.id === currentStepId}
                  aria-current={step.id === currentStepId ? "step" : undefined}
                >
                  <span class="step-number" aria-hidden="true"
                    >{step.step_number}</span
                  >
                  <div class="step-content">
                    <h3>{step.title}</h3>
                    {#if step.description_html}
                      <div class="reading-copy {textSizeClass}">
                        <div class="rich-text">
                          {@html step.description_html}
                        </div>
                      </div>
                    {/if}
                    {#if stepImages(step.id).length}
                      <div
                        class="step-images"
                        aria-label={t("Step photos", locale)}
                      >
                        {#each stepImages(step.id) as image (image.id)}
                          <div class="step-photo-card">
                            <button
                              class="step-image"
                              type="button"
                              aria-label={t("Open step photo", locale) +
                                `: ${image.alt_text || step.title}`}
                              onclick={() => openImage(image)}
                            >
                              <img
                                class="screen-reference-image"
                                src={image.thumbnail_url}
                                alt={image.alt_text || step.title}
                                loading="lazy"
                              />
                              <img
                                class="print-reference-image"
                                data-print-src={image.url}
                                alt={image.alt_text || step.title}
                                loading="lazy"
                                onerror={handlePrintImageError}
                              />
                            </button>
                            <OcrControl
                              src={image.url}
                              label={t(
                                "Recognized text from step photo",
                                locale,
                              )}
                              ownerId={userId}
                              {locale}
                              enabled={canUseOcr}
                            />
                          </div>
                        {/each}
                      </div>
                    {/if}
                  </div>
                </li>
              {/each}
            </ol>
          {:else}
            <p class="muted-copy">
              {t("No steps have been added yet.", locale)}
            </p>
          {/if}
        </section>
      </div>

      <aside class="details-column" aria-label={t("Project details", locale)}>
        <section class="details-card source-card">
          <h2>{t("Pattern source", locale)}</h2>
          {#if safeExternalUrl(project.link)}
            <a
              href={safeExternalUrl(project.link)}
              target="_blank"
              rel="noopener noreferrer"
            >
              {t("Open original pattern", locale)} ↗
            </a>
          {:else}
            <p class="muted-copy">
              {t("No pattern link has been saved.", locale)}
            </p>
          {/if}
          {#if safeExternalUrl(project.link_archive)}
            <a
              href={safeExternalUrl(project.link_archive)}
              target="_blank"
              rel="noopener noreferrer"
            >
              {t("Open archived copy", locale)} ↗
            </a>
          {/if}
          {#if safeExternalUrl(project.link) && (canArchive || canReadArchiveState)}
            <ArchiveControl
              entity="projects"
              id={project.id}
              sourceLink={project.link ?? ""}
              detailHasArchive={project.link_archive !== null}
              ownerId={userId}
              {locale}
              enabled={canArchive}
              readStateEnabled={canReadArchiveState}
              onArchived={refreshAfterArchive}
            />
          {/if}
        </section>

        <section class="details-card attachments-card">
          <h2>{t("Attachments", locale)}</h2>
          {#if project.attachments.length}
            <ul>
              {#each project.attachments as attachment (attachment.id)}
                <li>
                  <a
                    href={attachment.url}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    <span class="file-icon" aria-hidden="true">↗</span>
                    <span class="file-description">
                      <strong
                        >{attachment.original_filename ??
                          attachment.filename}</strong
                      >
                      <small
                        >{attachment.content_type} · {formatFileSize(
                          attachment.size_bytes,
                        )}</small
                      >
                    </span>
                  </a>
                </li>
              {/each}
            </ul>
          {:else}
            <p class="muted-copy">
              {t("No attachments have been added yet.", locale)}
            </p>
          {/if}
        </section>
      </aside>
    </div>

    {#if galleryImages.length}
      <section class="image-gallery" aria-labelledby="gallery-heading">
        <div class="section-heading-row">
          <h2 id="gallery-heading">{t("Project photos", locale)}</h2>
          <span>{galleryImages.length}</span>
        </div>
        <div class="gallery-grid">
          {#each galleryImages as image (image.id)}
            <div class="gallery-photo-card">
              <button
                class="gallery-image"
                type="button"
                aria-label={t("Open project photo", locale) +
                  `: ${image.alt_text || project.name}`}
                onclick={() => openImage(image)}
              >
                <img
                  class="screen-reference-image"
                  src={image.thumbnail_url}
                  alt={image.alt_text || project.name}
                  loading="lazy"
                />
                <img
                  class="print-reference-image"
                  data-print-src={image.url}
                  alt={image.alt_text || project.name}
                  loading="lazy"
                  onerror={handlePrintImageError}
                />
                {#if image.is_title_image}
                  <span class="title-image-label"
                    >{t("Cover photo", locale)}</span
                  >
                {:else if image.is_stitch_sample}
                  <span class="title-image-label"
                    >{t("Stitch sample", locale)}</span
                  >
                {/if}
              </button>
              <OcrControl
                src={image.url}
                label={t("Recognized text from project photo", locale)}
                ownerId={userId}
                {locale}
                enabled={canUseOcr}
              />
            </div>
          {/each}
        </div>
      </section>
    {/if}

    <footer class="reader-footer">
      <span>{t("Last updated", locale)} {formatDate(project.updated_at)}</span>
      <a href={resolve("/projects")}>{t("Back to project library", locale)}</a>
    </footer>
  </article>
  <ProjectLightbox
    images={project.images}
    selectedId={selectedImage?.id ?? null}
    title={project.name}
    {locale}
    onClose={() => (selectedImageId = null)}
    onSelect={(image) => (selectedImageId = image.id)}
  />
{/if}

<style>
  .reader-loading,
  .reader-state {
    display: grid;
    gap: 1rem;
  }

  .back-link {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    width: fit-content;
    color: var(--accent-strong);
    font-weight: 640;
    text-decoration: none;
  }

  .loading-mark {
    width: 100%;
    height: 13rem;
    border-radius: 1rem;
    background: linear-gradient(135deg, var(--surface), var(--accent-wash));
  }

  .reader-loading p {
    color: var(--ink-muted);
  }

  .state-card {
    display: grid;
    max-width: 46rem;
    justify-items: start;
    gap: 0.5rem;
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: clamp(1.4rem, 5vw, 2.4rem);
    background: var(--surface);
  }

  .state-mark {
    display: grid;
    width: 3rem;
    aspect-ratio: 1;
    place-items: center;
    border-radius: 0.8rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 1.5rem;
  }

  .state-card h1 {
    margin: 0.5rem 0 0;
    font-size: clamp(1.6rem, 5vw, 2.3rem);
    letter-spacing: -0.05em;
  }

  .state-card p {
    margin: 0 0 0.6rem;
    color: var(--ink-muted);
  }

  .secondary-button {
    min-height: 44px;
  }

  .reader {
    display: grid;
    max-width: 70ch;
    margin-inline: auto;
    gap: 1.5rem;
  }

  .reader-topline,
  .reader-actions,
  .text-size-controls {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }

  .reader-actions {
    flex-wrap: wrap;
  }

  .reader-topline {
    justify-content: space-between;
    gap: 1rem;
    border-bottom: 1px solid var(--line);
    padding-bottom: 0.6rem;
  }

  .text-size-controls {
    gap: 0.3rem;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    padding: 0.2rem;
    background: var(--surface);
  }

  .text-size-controls button,
  .print-button,
  .reader-favorite,
  .reader-edit-link {
    min-height: 44px;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.45rem 0.7rem;
    color: var(--accent-strong);
    background: var(--surface);
    font: inherit;
    font-weight: 640;
    cursor: pointer;
  }

  .print-button:disabled {
    cursor: wait;
  }

  .print-error {
    flex-basis: 100%;
    margin: 0;
    color: var(--danger);
    font-size: 0.9rem;
  }

  .reader-edit-link {
    display: inline-flex;
    align-items: center;
    text-decoration: none;
  }

  .text-size-controls button {
    min-width: 44px;
    border-color: transparent;
    font-size: 1.04rem;
  }

  .reader-heading {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 1rem;
  }

  .reader-heading-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
  }

  .reader-overview {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    align-items: start;
  }

  .reader-overview.has-cover {
    grid-template-columns: minmax(0, 9rem) minmax(0, 1fr);
    gap: 1rem;
  }

  .reader-cover {
    min-width: 0;
    margin: 0;
  }

  .reader-cover-image {
    width: 100%;
  }

  .reader-keyfacts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-content: start;
    gap: 0.65rem 1rem;
    min-width: 0;
    margin: 0;
  }

  .reader-keyfacts > div {
    min-width: 0;
  }

  .reader-keyfacts dt {
    color: var(--ink-muted);
    font-size: 0.78rem;
    font-weight: 650;
  }

  .reader-keyfacts dd {
    display: -webkit-box;
    margin: 0;
    line-height: 1.4;
    overflow-wrap: anywhere;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
    line-clamp: 3;
  }

  .reader-heading h1 {
    max-width: 55rem;
    margin: 0.3rem 0 0.6rem;
    font-size: clamp(2rem, 6vw, 3.3rem);
    letter-spacing: -0.06em;
    line-height: 1.05;
    overflow-wrap: anywhere;
  }

  .reader-subtitle {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.7rem;
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .reader-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .reader-tags li {
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.1rem 0.55rem;
    color: var(--ink-muted);
  }

  .reader-favorite {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 0.35rem;
    color: var(--ink-muted);
  }

  .reader-favorite[aria-pressed="true"] {
    color: #9e4f24;
    border-color: color-mix(in srgb, #9e4f24 30%, var(--line));
    background: color-mix(in srgb, #fff2e6 65%, var(--surface));
  }

  .reader-favorite span {
    font-size: 1.25rem;
  }

  .inline-error {
    margin: 0;
    color: var(--danger);
  }

  .image-gallery,
  .pattern-section,
  .steps-section,
  .details-card {
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: clamp(1rem, 3vw, 1.4rem);
    background: var(--surface);
  }

  .section-heading-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.8rem;
    margin-bottom: 0.9rem;
  }

  .section-heading-row h2,
  .details-card h2 {
    margin: 0;
    font-size: 1.1rem;
    letter-spacing: -0.02em;
  }

  .materials-card > summary {
    min-height: 44px;
    align-content: center;
    color: var(--ink);
    font-size: 1.1rem;
    font-weight: 650;
    cursor: pointer;
  }

  .materials-card[open] > summary {
    margin-bottom: 0.9rem;
  }

  .section-heading-row > span {
    color: var(--ink-muted);
    font-size: 0.83rem;
  }

  .step-navigation {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
    align-items: center;
    gap: 0.6rem;
    margin-bottom: 0.8rem;
  }

  .step-navigation-link {
    display: grid;
    min-width: 0;
    min-height: 44px;
    align-content: center;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    padding: 0.35rem 0.6rem;
    color: var(--accent-strong);
    background: var(--surface-raised);
    text-decoration: none;
  }

  .step-navigation-link strong {
    overflow: hidden;
    color: var(--ink);
    font-size: 0.82rem;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .next-step-link {
    text-align: right;
  }

  .step-navigation-spacer {
    min-height: 44px;
  }

  .step-progress {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.82rem;
    font-weight: 650;
    white-space: nowrap;
  }

  .gallery-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 9rem), 1fr));
    gap: 0.65rem;
  }

  .gallery-photo-card {
    display: grid;
    min-width: 0;
    align-content: start;
  }

  .gallery-image,
  .step-image {
    position: relative;
    display: block;
    min-width: 0;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    padding: 0;
    background: var(--surface-raised);
    cursor: zoom-in;
  }

  .gallery-image {
    min-height: 7rem;
    aspect-ratio: 1.3;
  }

  .gallery-image img,
  .step-image img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .print-reference-image {
    display: none !important;
  }

  .gallery-image img {
    position: absolute;
    inset: 0;
  }

  .title-image-label {
    position: absolute;
    right: 0.4rem;
    bottom: 0.4rem;
    left: 0.4rem;
    overflow: hidden;
    border-radius: 0.4rem;
    padding: 0.2rem 0.45rem;
    color: #18302d;
    background: rgb(255 255 255 / 88%);
    font-size: 0.72rem;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .reader-columns {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    align-items: start;
  }

  .pattern-column,
  .details-column {
    display: grid;
    min-width: 0;
    gap: 1.1rem;
  }

  .details-column {
    grid-template-columns: minmax(0, 1fr);
  }

  .reading-copy {
    color: var(--ink);
    overflow-wrap: anywhere;
  }

  .reading-small {
    font-size: 0.94rem;
  }

  .reading-medium {
    font-size: 1.06rem;
  }

  .reading-large {
    font-size: 1.22rem;
  }

  .rich-text {
    line-height: 1.8;
  }

  .rich-text :global(p:first-child) {
    margin-top: 0;
  }

  .rich-text :global(p:last-child) {
    margin-bottom: 0;
  }

  .rich-text :global(pre) {
    overflow-x: auto;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.8rem;
    background: var(--surface-raised);
  }

  .rich-text :global(img) {
    max-width: 100%;
    height: auto;
  }

  .muted-copy {
    color: var(--ink-muted);
  }

  .note-section {
    margin-top: 1.2rem;
    border-top: 1px solid var(--line);
    padding-top: 1rem;
  }

  .note-section h3,
  .step-content h3 {
    margin: 0 0 0.45rem;
    font-size: 1rem;
  }

  .steps-list {
    display: grid;
    gap: 0.2rem;
    margin: 0;
    padding: 0;
    list-style: none;
    counter-reset: none;
  }

  .steps-list > li {
    display: grid;
    grid-template-columns: 2.3rem minmax(0, 1fr);
    gap: 0.75rem;
    border-top: 1px solid var(--line);
    padding: 1rem 0;
    break-inside: avoid;
    scroll-margin-block: 1rem;
  }

  .steps-list > li.current-step {
    border-radius: 0.7rem;
    background: var(--surface-raised);
  }

  .steps-list > li:first-child {
    border-top: 0;
    padding-top: 0.25rem;
  }

  .step-number {
    display: grid;
    width: 2.2rem;
    height: 2.2rem;
    place-items: center;
    border-radius: 50%;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-weight: 700;
  }

  .step-images {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin-top: 0.8rem;
  }

  .step-photo-card {
    width: min(10rem, 40vw);
    min-width: 0;
  }

  .step-image {
    width: 100%;
    aspect-ratio: 1.2;
  }

  .details-card h2 {
    margin-bottom: 0.9rem;
  }

  .details-card dl {
    display: grid;
    gap: 0.8rem;
    margin: 0;
  }

  .details-card dl > div {
    display: grid;
    gap: 0.2rem;
    border-top: 1px solid var(--line);
    padding-top: 0.7rem;
  }

  .details-card dl > div:first-child {
    border-top: 0;
    padding-top: 0;
  }

  .details-card dt {
    color: var(--ink-muted);
    font-size: 0.78rem;
    font-weight: 650;
    text-transform: uppercase;
    letter-spacing: 0.045em;
  }

  .details-card dd {
    display: grid;
    gap: 0.22rem;
    margin: 0;
    line-height: 1.45;
    overflow-wrap: anywhere;
  }

  .details-card :global(.resource-card__link) {
    display: grid;
    min-height: 0;
    align-items: center;
  }

  .yarn-notes {
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .details-card a,
  .reader-footer a {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    width: fit-content;
    color: var(--accent-strong);
    font-weight: 640;
  }

  .source-card {
    display: grid;
    gap: 0.35rem;
  }

  .source-card h2 {
    margin-bottom: 0.25rem;
  }

  .source-card p {
    margin: 0;
  }

  .attachments-card ul {
    display: grid;
    gap: 0.5rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .attachments-card li a {
    display: flex;
    width: 100%;
    align-items: center;
    gap: 0.6rem;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.45rem 0.55rem;
    color: var(--ink);
    text-decoration: none;
  }

  .file-icon {
    display: grid;
    width: 2rem;
    height: 2rem;
    flex: 0 0 auto;
    place-items: center;
    border-radius: 0.55rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
  }

  .file-description {
    display: grid;
    min-width: 0;
  }

  .file-description strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .file-description small {
    color: var(--ink-muted);
    font-weight: 400;
  }

  .reader-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    border-top: 1px solid var(--line);
    padding-top: 0.7rem;
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  @media (max-width: 900px) {
    .details-column {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  @media (max-width: 600px) {
    .reader-overview.has-cover {
      grid-template-columns: minmax(0, 8rem) minmax(0, 1fr);
      gap: 0.75rem;
    }

    .reader-keyfacts {
      gap: 0.5rem 0.75rem;
    }

    .reader-keyfacts dt {
      font-size: 0.72rem;
    }

    .pattern-section,
    .steps-section {
      padding-inline: 0.5rem;
    }

    .steps-list > li {
      gap: 0.5rem;
    }

    .reader {
      gap: 1rem;
    }

    .reader-topline {
      align-items: flex-start;
    }

    .reader-actions {
      flex-wrap: wrap;
      justify-content: flex-end;
    }

    .print-button {
      font-size: 0.84rem;
    }

    .reader-heading {
      align-items: flex-start;
      flex-direction: column;
    }

    .reader-heading-actions {
      align-self: flex-start;
    }

    .step-navigation {
      grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
      gap: 0.35rem;
    }

    .step-navigation-link {
      padding: 0.25rem 0.4rem;
      font-size: 0.82rem;
    }

    .details-column {
      grid-template-columns: minmax(0, 1fr);
    }

    .reader-footer {
      align-items: flex-start;
      flex-direction: column;
      gap: 0;
    }
  }

  @media print {
    :global(.desktop-rail),
    :global(.page-header),
    :global(.app-header),
    :global(.mobile-navigation),
    :global(.skip-link) {
      display: none !important;
    }

    :global(.app-shell) {
      display: block;
      min-height: 0;
    }

    :global(.content-main) {
      width: 100%;
      max-width: none;
      padding: 0;
    }

    .reader {
      display: block;
      max-width: none;
      color: #111;
      background: #fff;
      font-size: 11pt;
    }

    .reader-overview {
      display: block;
    }

    .reader-keyfacts {
      display: none;
    }

    .reader-topline,
    .reader-favorite,
    .reader-footer a,
    .lightbox {
      display: none !important;
    }

    .image-gallery {
      display: block;
      break-inside: auto;
    }

    .gallery-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.16in;
    }

    .step-images {
      break-inside: avoid;
    }

    .gallery-image,
    .step-image {
      display: block !important;
      width: 100%;
      min-height: 0;
      height: auto;
      aspect-ratio: auto;
      overflow: visible;
      border: 0;
      border-radius: 0;
      padding: 0;
      background: transparent;
      cursor: default;
      break-inside: avoid;
    }

    .gallery-image img,
    .step-image img {
      position: static;
      display: block;
      width: 100%;
      height: auto;
      max-height: 7in;
      object-fit: contain;
    }

    .screen-reference-image {
      display: none !important;
    }

    .gallery-image .print-reference-image,
    .step-image .print-reference-image {
      display: block !important;
    }

    .print-error {
      display: block;
    }

    .title-image-label {
      display: none !important;
    }

    .materials-card > summary {
      display: none;
    }

    .reader-heading,
    .reader-columns,
    .pattern-column,
    .details-column {
      display: block;
    }

    .reader-heading,
    .pattern-section,
    .steps-section,
    .details-card {
      border: 0;
      border-radius: 0;
      padding: 0.25in 0;
      box-shadow: none;
      break-inside: auto;
    }

    .section-heading-row,
    .steps-list > li,
    .details-card dl > div {
      break-inside: avoid;
    }

    .rich-text,
    .step-content {
      color: #111;
      font-size: 11pt;
    }

    .reader-footer {
      border-top: 1px solid #777;
      color: #333;
    }

    .reader-footer::after {
      content: "Stricknani";
      margin-left: auto;
      color: #555;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .gallery-image,
    .step-image {
      transition: none;
    }
  }
</style>
