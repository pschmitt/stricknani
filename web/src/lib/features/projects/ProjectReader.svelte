<script lang="ts">
  import StickyNote from "#lib/components/StickyNote.svelte";
  import { onDestroy, tick, untrack } from "svelte";
  import ErrorPanel from "#lib/components/ErrorPanel.svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import { ApiError } from "#lib/api.ts";
  import ActionOverflow from "#lib/components/ActionOverflow.svelte";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import Disclosure from "#lib/components/Disclosure.svelte";
  import FavoriteButton from "#lib/components/FavoriteButton.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import SectionHeading from "#lib/components/SectionHeading.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import ShareMenuItem from "#lib/components/ShareMenuItem.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import ArchiveControl from "#lib/features/media/ArchiveControl.svelte";
  import PhotoStrip from "#lib/components/PhotoStrip.svelte";
  import OcrControl from "#lib/features/media/OcrControl.svelte";
  import HistorySection from "#lib/features/history/HistorySection.svelte";
  import ProjectLightbox from "#lib/features/media/ProjectLightbox.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { extraCount, plainTextSummary } from "./facts-summary.ts";
  import { session } from "#lib/session.svelte.ts";
  import { textSize } from "#lib/text-size.svelte.ts";
  import {
    deleteProject,
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
    adminData?: {
      project: ProjectDetail;
      yarns: ProjectYarn[];
      owner: { id: number; email: string; avatar_url: string | null } | null;
      yarnWarning?: boolean;
    };
  }

  type LoadState = "loading" | "ready" | "not-found" | "error";

  let { id, adminData }: Props = $props();
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
  const needlesText = $derived(
    plainTextSummary(
      project?.needles ||
        yarns.find((yarn) => yarn.recommended_needles)?.recommended_needles,
    ),
  );
  const gaugeText = $derived(plainTextSummary(project?.stitch_sample));
  // Sanitized server HTML wins; null falls back to the plain-text rendering.
  const gaugeHtml = $derived(project?.stitch_sample_html ?? null);
  const yarnText = $derived(plainTextSummary(project?.yarn));
  const moreYarns = $derived(extraCount(yarns.length));
  let materialsDetailsElement = $state<HTMLDetailsElement | null>(null);
  let printPreparing = $state(false);
  let printError = $state("");
  let deleteDialog = $state<ConfirmDialog | undefined>(undefined);
  let deleting = $state(false);
  let deleteError = $state("");
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
  const textSizeClass = $derived(textSize.cssClass);
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
  const stripImages = $derived(
    coverImage ? [coverImage, ...galleryImages] : [],
  );
  const stripItems = $derived(
    stripImages.map((image) => ({
      id: image.id,
      url: image.url,
      thumbnail_url: image.thumbnail_url,
      alt: image.alt_text || project?.name || "",
    })),
  );
  const selectedImage = $derived(
    project?.images.find((image) => image.id === selectedImageId) ?? null,
  );
  const currentStepIndex = $derived(
    orderedSteps.findIndex((step) => step.id === currentStepId),
  );

  function openDeleteDialog() {
    if (adminData || !session.data?.user || !project || deleting) return;
    deleteError = "";
    deleteDialog?.showModal();
  }

  async function removeProject() {
    const currentSession = session.data;
    const current = project;
    if (adminData || !currentSession?.user || !current || deleting) return;
    const ownerId = currentSession.user.id;
    const generation = resourceGeneration;
    deleting = true;
    deleteError = "";
    try {
      await deleteProject(current.id, currentSession);
      if (generation !== resourceGeneration) return;
      untrack(() => deleteDialog?.close());
      await goto(resolve("/projects"));
    } catch (error) {
      if (generation !== resourceGeneration) return;
      deleteError =
        error instanceof ApiError
          ? error.message
          : t("The project could not be deleted.", locale);
      publishFeedback({ ownerId, kind: "error", message: deleteError });
    } finally {
      if (generation === resourceGeneration) deleting = false;
    }
  }

  $effect(() => {
    const currentUserId = userId;
    const currentUserIsAdmin = session.data?.user?.is_admin === true;
    const currentAdminData = adminData;
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
    if (currentAdminData) {
      if (currentUserId === null || !currentUserIsAdmin) {
        loadState = "error";
        errorMessage = t("Administrator access is required.", locale);
        return () => {
          if (resourceGeneration === generation) resourceGeneration += 1;
        };
      }
      if (
        !Number.isSafeInteger(id) ||
        id <= 0 ||
        currentAdminData.project.id !== id
      ) {
        loadState = "not-found";
        return () => {
          if (resourceGeneration === generation) resourceGeneration += 1;
        };
      }
      project = currentAdminData.project;
      yarns = currentAdminData.yarns;
      yarnWarning = currentAdminData.yarnWarning ?? false;
      const stepIds = [...currentAdminData.project.steps]
        .sort((left, right) => left.step_number - right.step_number)
        .map((step) => step.id);
      const initialStepId =
        stepIdFromHash(page.url.hash, stepIds) ?? stepIds[0] ?? null;
      currentStepId = initialStepId;
      if (initialStepId !== null) {
        const hash = stepHash(initialStepId);
        if (page.url.hash !== hash) {
          void goto(`${page.url.pathname}${page.url.search}${hash}`, {
            replaceState: true,
          });
        }
      }
      loadState = "ready";
      return () => {
        if (resourceGeneration === generation) resourceGeneration += 1;
        archiveRefreshGeneration += 1;
        archiveRefreshController?.abort();
        archiveRefreshController = null;
      };
    }
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
    if (!adminData) {
      rememberReadingStep(currentUserId, currentProject.id, stepId);
    }
  });

  async function toggleFavorite() {
    const currentProject = project;
    const currentSession = session.data;
    if (adminData || !currentProject || !currentSession?.user || favoriteBusy)
      return;
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
      !adminData &&
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
    if (adminData) return;
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
      adminData !== undefined ||
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
  <div class="page-container page-container--standard">
    <ErrorPanel
      kind="project-missing"
      seed={String(id)}
      backHref={resolve("/projects")}
      backLabel={msg("Back to projects")}
    />
  </div>
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
      <Button
        class="secondary-button"
        variant="secondary"
        icon="reset"
        type="button"
        onclick={() => (reload += 1)}
      >
        {t("Try again", locale)}
      </Button>
    </div>
  </section>
{:else if project}
  {@const currentProject = project}
  <article
    class="reader page-container page-container--standard"
    bind:this={readerElement}
  >
    <PageHeader title={currentProject.name} stackedActions>
      {#snippet context()}
        <div class="reader-subtitle">
          <span>{currentProject.category ?? t("Knitting project", locale)}</span
          >
          <span
            >{t("Updated", locale)}
            {formatDate(currentProject.updated_at)}</span
          >
          {#if currentProject.tags.length}
            <ul class="reader-tags" aria-label={t("Project tags", locale)}>
              {#each currentProject.tags as tag, index (`${index}:${tag}`)}
                <li>{tag}</li>
              {/each}
            </ul>
          {/if}
          {#if adminData}
            <div class="reader-admin-context">
              <span>{t("Read-only view", locale)}</span>
              {#if adminData.owner}
                <span
                  class="reader-admin-owner"
                  aria-label={`${t("Owner", locale)}: ${adminData.owner.email}`}
                >
                  <UserAvatar
                    avatarUrl={adminData.owner.avatar_url}
                    name={adminData.owner.email}
                  />
                  <span>{adminData.owner.email}</span>
                </span>
              {/if}
            </div>
          {/if}
        </div>
      {/snippet}
      {#snippet actions()}
        <div class="reader-heading-actions">
          {#if !adminData}
            <FavoriteButton
              favorite={currentProject.is_favorite}
              busy={favoriteBusy}
              {locale}
              onclick={() => void toggleFavorite()}
            />
            <a
              class="reader-edit-link ui-button ui-button--primary ui-button--md"
              href={resolve("/projects/[id]/edit", {
                id: String(currentProject.id),
              })}
            >
              <ActionLabel icon="action-update"
                >{t("Edit project", locale)}</ActionLabel
              >
            </a>
          {/if}
          <ActionOverflow label={t("More actions", locale)}>
            {#snippet children(close)}
              <div class="action-overflow__section">
                <p class="action-overflow__heading" id="reader-text-size-label">
                  {t("Text size", locale)}
                </p>
                <div
                  class="action-overflow__segmented text-size-controls"
                  role="group"
                  aria-labelledby="reader-text-size-label"
                >
                  <button
                    class="action-overflow__segment"
                    type="button"
                    aria-label={t("Decrease text size", locale)}
                    disabled={textSize.isMin}
                    onclick={() => textSize.down()}>A−</button
                  >
                  <span class="action-overflow__segment action-overflow__value"
                    >{textSize.label}</span
                  >
                  <button
                    class="action-overflow__segment"
                    type="button"
                    aria-label={t("Increase text size", locale)}
                    disabled={textSize.isMax}
                    onclick={() => textSize.up()}>A+</button
                  >
                </div>
                {#if !textSize.isDefault}
                  <button
                    class="text-size-reset"
                    type="button"
                    onclick={() => textSize.reset()}
                  >
                    {t("Reset text size", locale)}
                  </button>
                {/if}
              </div>
              <hr class="action-overflow__divider" />
              <div
                class="action-overflow__menu"
                role="menu"
                aria-label={t("Project actions", locale)}
              >
                {#if !adminData}
                  <ShareMenuItem
                    title={currentProject.name}
                    ownerId={userId}
                    {locale}
                    {close}
                  />
                {/if}
                <button
                  class="action-overflow__item print-button"
                  role="menuitem"
                  type="button"
                  disabled={printPreparing}
                  aria-busy={printPreparing}
                  onclick={() => {
                    close();
                    void printPattern();
                  }}
                >
                  <NavIcon name="print" />
                  <span
                    >{printPreparing
                      ? t("Preparing full-resolution photos…", locale)
                      : t("Print pattern", locale)}</span
                  >
                </button>
                {#if !adminData}
                  <hr class="action-overflow__divider" />
                  <button
                    class="action-overflow__item action-overflow__item--danger"
                    role="menuitem"
                    type="button"
                    onclick={() => {
                      close();
                      openDeleteDialog();
                    }}
                  >
                    <NavIcon name="action-delete" />
                    <span>{t("Delete project", locale)}</span>
                  </button>
                {/if}
              </div>
            {/snippet}
          </ActionOverflow>
          {#if printPreparing}
            <span class="print-preparing-status" role="status">
              {t("Preparing full-resolution photos…", locale)}
            </span>
          {/if}
        </div>
      {/snippet}
    </PageHeader>
    {#if printError}
      <p class="print-error" role="alert">{printError}</p>
    {/if}

    <div class="reader-overview">
      {#if stripImages.length}
        <PhotoStrip
          items={stripItems}
          label={t("Photos", locale)}
          openLabel={t("Open project photo", locale)}
          {locale}
          printable
          onprinterror={handlePrintImageError}
          onopen={(item) => (selectedImageId = item.id)}
        >
          {#snippet tile(item)}
            {#if !adminData}
              <OcrControl
                src={item.url}
                label={t("Recognized text from project photo", locale)}
                ownerId={userId}
                {locale}
                enabled={canUseOcr}
              />
            {/if}
          {/snippet}
        </PhotoStrip>
      {/if}
      <section class="reader-facts-card" aria-labelledby="reader-facts-title">
        <SectionHeading id="reader-facts-title" icon="info"
          >{t("Project details", locale)}</SectionHeading
        >
        <dl class="reader-keyfacts">
          <div>
            <dt>{t("Yarn", locale)}</dt>
            <dd>
              {#if yarns.length}
                {yarns[0].name}{#if moreYarns > 0}
                  <span
                    class="more-pill"
                    title={`${moreYarns} ${t("more yarns", locale)}`}
                  >
                    <span aria-hidden="true">+{moreYarns}</span>
                    <span class="visually-hidden"
                      >{moreYarns} {t("more yarns", locale)}</span
                    ></span
                  >{/if}
              {:else}
                {yarnText || t("Not recorded", locale)}
              {/if}
            </dd>
          </div>
          {#if needlesText}
            <div>
              <dt>{t("Needles", locale)}</dt>
              <dd>{needlesText}</dd>
            </div>
          {/if}
          {#if gaugeHtml || gaugeText}
            <div>
              <dt>{t("Gauge", locale)}</dt>
              <dd>
                {#if gaugeHtml}<div class="rich-text">
                    {@html gaugeHtml}
                  </div>{:else}{gaugeText}{/if}
              </dd>
            </div>
          {/if}
        </dl>
      </section>
    </div>

    <Disclosure
      class="materials-card"
      icon="needle"
      label={t("Materials", locale)}
      bind:element={materialsDetailsElement}
    >
      <dl class="materials-body">
        {#if yarns.length}
          {#each yarns as yarn (yarn.id)}
            <div>
              <dt>{t("Yarn", locale)}</dt>
              <dd>
                <ResourceCard
                  kind="yarn"
                  href={adminData
                    ? resolve("/admin/resources/[kind]/[id]", {
                        kind: "yarn",
                        id: String(yarn.id),
                      })
                    : `${resolve("/yarns")}/${yarn.id}`}
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
        {:else if yarnText}
          <div>
            <dt>{t("Yarn", locale)}</dt>
            <dd>{yarnText}</dd>
          </div>
        {:else}
          <div>
            <dt>{t("Yarn", locale)}</dt>
            <dd>{t("Not recorded", locale)}</dd>
          </div>
        {/if}
        {#if adminData && yarnWarning}
          {#each project.yarn_ids.filter((yarnId) => !yarns.some((yarn) => yarn.id === yarnId)) as yarnId (yarnId)}
            <div>
              <dt>{t("Yarn", locale)}</dt>
              <dd>
                <ResourceCard
                  kind="yarn"
                  href={null}
                  imageUrl={null}
                  name={`${t("Yarn", locale)} #${yarnId}`}
                  subtitle={null}
                  titleTag="h3"
                />
              </dd>
            </div>
          {/each}
        {/if}
        {#if yarnWarning}
          <p class="muted-copy">
            {t("Some yarn details could not be loaded.", locale)}
          </p>
        {/if}
        {#if needlesText}
          <div>
            <dt>{t("Needles", locale)}</dt>
            <dd>{needlesText}</dd>
          </div>
        {/if}
        {#if gaugeHtml || gaugeText}
          <div>
            <dt>{t("Gauge", locale)}</dt>
            <dd>
              {#if gaugeHtml}<div class="rich-text">
                  {@html gaugeHtml}
                </div>{:else}{gaugeText}{/if}
            </dd>
          </div>
        {/if}
      </dl>
    </Disclosure>

    {#if favoriteError}
      <p class="inline-error" role="alert">{favoriteError}</p>
    {/if}

    <div class="reader-columns">
      <div class="pattern-column">
        <section class="pattern-section" aria-labelledby="pattern-heading">
          <div class="section-heading-row">
            <SectionHeading id="pattern-heading" icon="notebook"
              >{t("Pattern and notes", locale)}</SectionHeading
            >
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
            <StickyNote
              title={t("Notes", locale)}
              headingId="notes-heading"
              headingLevel={3}
              closeLabel={t("Close notes", locale)}
              moveHint={t("Long-press and drag to move", locale)}
              movedLabels={{
                left: t("Notes moved to the left", locale),
                right: t("Notes moved to the right", locale),
                up: t("Notes moved up", locale),
                down: t("Notes moved down", locale),
              }}
            >
              <div class="reading-copy {textSizeClass}">
                <div class="rich-text">{@html project.notes_html}</div>
              </div>
            </StickyNote>
          {:else if !adminData}
            <StickyNote
              title={t("Notes", locale)}
              headingId="notes-heading"
              headingLevel={3}
              emptyLabel={t("Add a note", locale)}
              emptyHref={`${resolve("/projects/[id]/edit", {
                id: String(currentProject.id),
              })}#project-notes`}
            />
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
            <SectionHeading id="steps-heading" icon="list-bullets"
              >{t("Steps", locale)}</SectionHeading
            >
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
                  class="step-navigation-link ui-button ui-button--ghost ui-button--md"
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
                  class="step-navigation-link next-step-link ui-button ui-button--ghost ui-button--md"
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
                            <Button
                              class="step-image"
                              variant="ghost"
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
                            </Button>
                            {#if !adminData}
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
                            {/if}
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
        <section
          class="details-card source-card"
          aria-labelledby="source-heading"
        >
          <SectionHeading id="source-heading" icon="link"
            >{t("Pattern source", locale)}</SectionHeading
          >
          {#if safeExternalUrl(project.link)}
            <a
              class="source-link"
              href={safeExternalUrl(project.link)}
              target="_blank"
              rel="noopener noreferrer"
            >
              <span class="source-link__icon" aria-hidden="true"
                ><NavIcon name="notebook" /></span
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
              class="source-link"
              href={safeExternalUrl(project.link_archive)}
              target="_blank"
              rel="noopener noreferrer"
            >
              <span class="source-link__icon" aria-hidden="true"
                ><NavIcon name="history" /></span
              >
              {t("Open archived copy", locale)} ↗
            </a>
          {/if}
          {#if !adminData && safeExternalUrl(project.link) && (canArchive || canReadArchiveState)}
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

        <section
          class="details-card attachments-card"
          aria-labelledby="attachments-heading"
        >
          <SectionHeading id="attachments-heading" icon="paperclip"
            >{t("Attachments", locale)}</SectionHeading
          >
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

    {#if !adminData}
      <HistorySection
        kind="projects"
        id={project.id}
        {locale}
        currentEmail={session.data?.user?.email ?? null}
      />
    {/if}

    <footer class="reader-footer">
      <span>{t("Last updated", locale)} {formatDate(project.updated_at)}</span>
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
  {#if !adminData}
    <ConfirmDialog
      bind:this={deleteDialog}
      titleId="reader-delete-project-title"
      title={t("Delete this project? This cannot be undone.", locale)}
      busy={deleting}
    >
      {#snippet children()}
        <p>{t("This cannot be undone.", locale)}</p>
        {#if deleteError}<p class="print-error" role="alert">
            {deleteError}
          </p>{/if}
      {/snippet}
      {#snippet actions()}
        <Button
          type="button"
          variant="secondary"
          icon="cancel"
          disabled={deleting}
          onclick={() => {
            deleteError = "";
            untrack(() => deleteDialog?.close());
          }}
        >
          {t("Cancel", locale)}
        </Button>
        <Button
          type="button"
          variant="danger"
          icon="action-delete"
          disabled={deleting}
          onclick={() => void removeProject()}
        >
          {t("Delete project", locale)}
        </Button>
      {/snippet}
    </ConfirmDialog>
  {/if}
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
    border-radius: var(--radius-sm);
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
    border: 1px solid var(--card-border);
    border-radius: var(--radius-sm);
    padding: clamp(1.4rem, 5vw, 2.4rem);
    background: var(--card-fill);
  }

  .state-mark {
    display: grid;
    width: 3rem;
    aspect-ratio: 1;
    place-items: center;
    border-radius: var(--radius-xs);
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

  /* One gap for every stack of cards: the page, the overview, and both columns. The
     .reader-columns wrapper used to have none, so the aside's first card butted against
     the steps card above it. */
  .reader {
    --stack-gap: 1.25rem;
    display: grid;
    gap: var(--stack-gap);
  }

  .text-size-reset {
    justify-self: start;
    min-height: 32px;
    padding: 0 0.4rem;
    border: 0;
    border-radius: var(--radius-xs);
    color: var(--accent-strong);
    background: transparent;
    font: inherit;
    font-size: 0.85rem;
    font-weight: 700;
    text-decoration: underline;
    cursor: pointer;
  }

  .text-size-reset:focus-visible {
    outline: 2px solid var(--focus);
  }

  .print-error {
    flex-basis: 100%;
    margin: 0;
    color: var(--danger);
    font-size: 0.9rem;
  }

  .reader-heading-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
  }

  .print-preparing-status {
    color: var(--ink-muted);
    font-size: 0.875rem;
  }

  .reader-overview {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    align-items: start;
    gap: var(--stack-gap);
    min-width: 0;
  }

  .reader-facts-card {
    min-width: 0;
    border: 1px solid var(--card-border);
    border-radius: var(--radius-sm);
    padding: clamp(1rem, 3vw, 1.4rem);
    background: var(--card-fill);
  }

  .reader-facts-card :global(.section-heading),
  .details-card :global(.section-heading) {
    margin-bottom: 0.9rem;
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

  .reader-subtitle {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.7rem;
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .reader-admin-context,
  .reader-admin-owner {
    display: inline-flex;
    min-width: 0;
    align-items: center;
    gap: 0.45rem;
  }

  .reader-admin-context {
    flex-wrap: wrap;
  }

  .reader-admin-owner > span:last-child {
    overflow-wrap: anywhere;
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

  .inline-error {
    margin: 0;
    color: var(--danger);
  }

  .pattern-section,
  .steps-section,
  .details-card {
    min-width: 0;
    border: 1px solid var(--card-border);
    border-radius: var(--radius-sm);
    padding: clamp(1rem, 3vw, 1.4rem);
    background: var(--card-fill);
  }

  .section-heading-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.8rem;
    margin-bottom: 0.9rem;
  }

  :global(.materials-card) .materials-body {
    padding: 0.25rem clamp(1rem, 3vw, 1.4rem) clamp(1rem, 3vw, 1.4rem);
  }

  .more-pill {
    display: inline-block;
    margin-left: 0.25rem;
    border-radius: 999px;
    padding: 0.05rem 0.5rem;
    background: color-mix(in srgb, var(--accent-strong) 12%, transparent);
    color: var(--accent-strong);
    font-size: 0.78rem;
    font-weight: 650;
    white-space: nowrap;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    padding: 0;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
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
    border-radius: var(--radius-xs);
    padding: 0.35rem 0.6rem;
    color: var(--accent-strong);
    background: var(--surface-raised);
    text-decoration: none;
    white-space: normal;
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

  :global(.gallery-image),
  :global(.step-image) {
    position: relative;
    display: block;
    min-width: 0;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0;
    background: var(--surface-raised);
    cursor: zoom-in;
  }

  :global(.gallery-image:active),
  :global(.step-image:active) {
    transform: none;
  }

  :global(.gallery-image) {
    min-height: 7rem;
    aspect-ratio: 1.3;
  }

  :global(.gallery-image) img,
  :global(.step-image) img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .print-reference-image {
    display: none !important;
  }

  :global(.gallery-image) img {
    position: absolute;
    inset: 0;
  }

  .reader-columns {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    align-items: start;
    gap: var(--stack-gap);
  }

  .pattern-column,
  .details-column {
    display: grid;
    min-width: 0;
    gap: var(--stack-gap);
  }

  .details-column {
    grid-template-columns: minmax(0, 1fr);
  }

  .reading-copy {
    max-width: 70ch;
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
    border-radius: var(--radius-xs);
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
    border-radius: var(--radius-xs);
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

  :global(.step-image) {
    width: 100%;
    aspect-ratio: 1.2;
  }

  .materials-body {
    display: grid;
    gap: 0.8rem;
    margin: 0;
  }

  .materials-body > div {
    display: grid;
    gap: 0.2rem;
    border-top: 1px solid var(--line);
    padding-top: 0.7rem;
  }

  .materials-body > div:first-child {
    border-top: 0;
    padding-top: 0;
  }

  .materials-body dt {
    color: var(--ink-muted);
    font-size: 0.78rem;
    font-weight: 650;
    text-transform: uppercase;
    letter-spacing: 0.045em;
  }

  .materials-body dd {
    display: grid;
    gap: 0.22rem;
    margin: 0;
    line-height: 1.45;
    overflow-wrap: anywhere;
  }

  .materials-body :global(.resource-card__link),
  .details-card :global(.resource-card__link) {
    display: grid;
    min-height: 0;
    align-items: center;
  }

  .yarn-notes {
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .materials-body a,
  .details-card a {
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
    border-radius: var(--radius-xs);
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
      --stack-gap: 1rem;
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

    .reader-facts-card {
      display: none;
    }

    .reader-topline,
    .print-preparing-status,
    :global(.page-header__actions),
    .lightbox {
      display: none !important;
    }

    .step-images {
      break-inside: avoid;
    }

    :global(.gallery-image),
    :global(.step-image) {
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

    :global(.gallery-image) img,
    :global(.step-image) img {
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

    :global(.gallery-image) .print-reference-image,
    :global(.step-image) .print-reference-image {
      display: block !important;
    }

    .print-error {
      display: block;
    }

    :global(.materials-card > summary) {
      display: none;
    }

    :global(.materials-card) {
      border: 0;
      border-radius: 0;
    }

    .reader-heading-actions,
    .reader-columns,
    .pattern-column,
    .details-column {
      display: block;
    }

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
    .materials-body > div {
      break-inside: avoid;
    }

    .rich-text,
    .reading-copy,
    .step-content {
      max-width: none;
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
    :global(.gallery-image),
    :global(.step-image) {
      transition: none;
    }
  }

  /* Pattern source links get the same tinted icon chips as the headings. */
  .source-link {
    display: inline-flex;
    align-items: center;
    gap: 0.55rem;
  }

  .source-link__icon {
    display: inline-grid;
    width: 1.8rem;
    height: 1.8rem;
    flex: none;
    place-items: center;
    border-radius: 999px;
    color: var(--tone-projects);
    background: color-mix(in srgb, var(--tone-projects) 16%, transparent);
  }

  .source-link__icon :global(svg) {
    width: 1rem;
    height: 1rem;
  }
</style>
