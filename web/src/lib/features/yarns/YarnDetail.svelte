<script lang="ts">
  import StickyNote from "#lib/components/StickyNote.svelte";
  import ErrorPanel from "#lib/components/ErrorPanel.svelte";
  import { untrack } from "svelte";
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import ActionOverflow from "#lib/components/ActionOverflow.svelte";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import FavoriteButton from "#lib/components/FavoriteButton.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import ForeignOwnerBanner from "#lib/components/ForeignOwnerBanner.svelte";
  import { foreignOwner } from "#lib/owner.ts";
  import SectionHeading from "#lib/components/SectionHeading.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ShareMenuItem from "#lib/components/ShareMenuItem.svelte";
  import HistorySection from "#lib/features/history/HistorySection.svelte";
  import PhotoStrip from "#lib/components/PhotoStrip.svelte";
  import ArchiveControl from "#lib/features/media/ArchiveControl.svelte";
  import OcrControl from "#lib/features/media/OcrControl.svelte";
  import ProjectLightbox from "#lib/features/media/ProjectLightbox.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { msg, t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { textSize } from "#lib/text-size.svelte.ts";
  import {
    deleteYarn,
    getLinkedProject,
    getYarn,
    setYarnFavorite,
    type YarnDetail as Yarn,
    type LinkedProject,
  } from "./yarn-api";

  let { id }: { id: number } = $props();

  type LoadState = "loading" | "ready" | "not-found" | "error";

  let loadState = $state<LoadState>("loading");
  let yarn = $state<Yarn | null>(null);
  let linkedProjects = $state<LinkedProject[]>([]);
  let linkedProjectsLoading = $state(false);
  let linkedProjectsError = $state(false);
  let errorMessage = $state("");
  let actionError = $state("");
  let busyFavorite = $state(false);
  let deleteDialog = $state<ConfirmDialog | undefined>(undefined);
  let deleting = $state(false);
  let deleteError = $state("");
  let reload = $state(0);
  let routeGeneration = 0;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  let selectedPhotoId = $state<number | null>(null);
  const stripItems = $derived(
    (yarn?.photos ?? []).map((photo) => ({
      id: photo.id,
      url: photo.url,
      thumbnail_url: photo.thumbnail_url,
      alt: photo.alt_text || yarn?.name || "",
    })),
  );
  const lightboxImages = $derived(
    (yarn?.photos ?? []).map((photo) => ({
      id: photo.id,
      url: photo.url,
      thumbnail_url: photo.thumbnail_url,
      alt_text: photo.alt_text,
      image_type: "yarn",
      is_title_image: photo.is_primary,
      is_stitch_sample: false,
      step_id: null,
    })),
  );
  const canUseOcr = $derived(session.data?.capabilities.ocr === true);
  const canArchive = $derived(session.data?.capabilities.archive === true);
  const canReadArchiveState = $derived(
    session.data?.capabilities.archive_state === true,
  );
  const yarnId = $derived(id);
  const textSizeClass = $derived(textSize.cssClass);

  $effect(() => {
    const id = yarnId;
    const currentUserId = userId;
    reload;
    const generation = ++routeGeneration;
    busyFavorite = false;
    deleting = false;
    deleteError = "";
    untrack(() => deleteDialog?.close());
    actionError = "";
    const controller = new AbortController();
    yarn = null;
    linkedProjects = [];
    linkedProjectsError = false;
    if (!Number.isSafeInteger(id) || id <= 0) {
      loadState = "not-found";
      return () => controller.abort();
    }
    if (currentUserId === null) {
      loadState = "error";
      errorMessage = t("Sign in to view this yarn.", locale);
      return () => controller.abort();
    }
    loadState = "loading";
    errorMessage = "";
    void loadYarn(id, controller.signal);
    return () => {
      controller.abort();
      if (routeGeneration === generation) routeGeneration += 1;
    };
  });

  async function loadYarn(id: number, signal: AbortSignal) {
    try {
      const loaded = await getYarn(id, signal);
      if (signal.aborted) return;
      yarn = loaded;
      loadState = "ready";
      if (loaded.project_ids.length > 0) {
        linkedProjectsLoading = true;
        const projects = await Promise.all(
          loaded.project_ids.map(async (projectId) => {
            try {
              return await getLinkedProject(projectId, signal);
            } catch {
              return null;
            }
          }),
        );
        if (signal.aborted) return;
        linkedProjects = projects.filter(
          (project): project is LinkedProject => project !== null,
        );
        linkedProjectsError = linkedProjects.length < loaded.project_ids.length;
        linkedProjectsLoading = false;
      }
    } catch (error) {
      if (signal.aborted) return;
      linkedProjectsLoading = false;
      if (error instanceof ApiError && error.status === 404) {
        loadState = "not-found";
        return;
      }
      loadState = "error";
      errorMessage =
        error instanceof Error
          ? error.message
          : t("The yarn could not be loaded.", locale);
    }
  }

  function isCurrentResource(
    id: number,
    ownerId: number,
    generation: number,
  ): boolean {
    return (
      id === yarnId && ownerId === userId && generation === routeGeneration
    );
  }

  async function refreshYarn(id: number, ownerId: number) {
    const generation = routeGeneration;
    const loaded = await getYarn(id);
    if (!isCurrentResource(id, ownerId, generation)) return;
    yarn = loaded;
    linkedProjectsLoading = loaded.project_ids.length > 0;
    const projects = await Promise.all(
      loaded.project_ids.map(async (projectId) => {
        try {
          return await getLinkedProject(projectId);
        } catch {
          return null;
        }
      }),
    );
    if (!isCurrentResource(id, ownerId, generation)) return;
    linkedProjects = projects.filter(
      (project): project is LinkedProject => project !== null,
    );
    linkedProjectsError = linkedProjects.length < loaded.project_ids.length;
    linkedProjectsLoading = false;
  }

  async function refreshCurrentYarn(_marker: string) {
    const currentYarn = yarn;
    const currentOwnerId = userId;
    if (!currentYarn || currentOwnerId === null) return;
    await refreshYarn(currentYarn.id, currentOwnerId);
  }

  function openDeleteDialog() {
    if (!session.data?.user || !yarn || deleting) return;
    deleteError = "";
    deleteDialog?.showModal();
  }

  async function removeYarn() {
    const currentSession = session.data;
    const current = yarn;
    if (!currentSession?.user || !current || deleting) return;
    const id = current.id;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    deleting = true;
    deleteError = "";
    try {
      await deleteYarn(id, currentSession);
      if (!isCurrentResource(id, ownerId, generation)) return;
      publishFeedback({
        ownerId,
        kind: "success",
        message: t("Yarn deleted.", locale),
      });
      untrack(() => deleteDialog?.close());
      await goto(resolve("/yarns"));
    } catch (error) {
      if (!isCurrentResource(id, ownerId, generation)) return;
      deleteError =
        error instanceof Error
          ? error.message
          : t("The yarn could not be deleted.", locale);
    } finally {
      if (isCurrentResource(id, ownerId, generation)) deleting = false;
    }
  }

  async function toggleFavorite() {
    const currentSession = session.data;
    if (!yarn || !currentSession?.user || busyFavorite) return;
    const id = yarn.id;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    busyFavorite = true;
    actionError = "";
    try {
      const updated = await setYarnFavorite(
        id,
        !yarn.is_favorite,
        currentSession,
      );
      if (!isCurrentResource(id, ownerId, generation)) return;
      yarn = updated;
    } catch (error) {
      if (!isCurrentResource(id, ownerId, generation)) return;
      actionError =
        error instanceof Error
          ? error.message
          : t("The favorite could not be updated.", locale);
    } finally {
      if (isCurrentResource(id, ownerId, generation)) busyFavorite = false;
    }
  }

  function safeExternalHref(value: string | null): string | null {
    if (!value) return null;
    try {
      const url = new URL(value);
      return url.protocol === "https:" || url.protocol === "http:"
        ? url.href
        : null;
    } catch {
      return null;
    }
  }

  function formatDate(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.valueOf())
      ? ""
      : new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(date);
  }

  function displayNumber(value: number | null, unit: string): string | null {
    return value === null
      ? null
      : `${new Intl.NumberFormat(locale).format(value)} ${unit}`;
  }
</script>

<svelte:head>
  <title>Stricknani · {yarn?.name ?? t("Yarns", locale)}</title>
  <meta name="description" content={yarn?.brand ?? t("Yarn details", locale)} />
</svelte:head>

{#if loadState === "loading"}
  <main class="detail-loading" aria-busy="true" aria-live="polite">
    <div class="loading-mark" aria-hidden="true"></div>
    <p>{t("Loading yarn details…", locale)}</p>
  </main>
{:else if loadState === "not-found"}
  <div class="page-container page-container--standard">
    <ErrorPanel
      kind="yarn-missing"
      seed={String(id)}
      backHref={resolve("/yarns")}
      backLabel={msg("Back to yarns")}
    />
  </div>
{:else if loadState === "error"}
  <section
    class="detail-error page-container page-container--standard"
    aria-labelledby="yarn-error-title"
  >
    <h1 id="yarn-error-title">{errorMessage}</h1>
    <div class="error-actions">
      <Button
        class="quiet-button"
        variant="secondary"
        icon="reset"
        type="button"
        onclick={() => (reload += 1)}
      >
        {t("Try again", locale)}
      </Button>
      <a
        class="ui-button ui-button--primary ui-button--md primary-action"
        href={resolve("/yarns")}>{t("Back to yarns", locale)}</a
      >
    </div>
  </section>
{:else if yarn}
  {@const currentYarn = yarn}
  <main
    class="yarn-detail page-container page-container--wide"
    aria-labelledby="yarn-title"
  >
    <PageHeader title={currentYarn.name} titleId="yarn-title" stackedActions>
      {#snippet context()}
        <p class="identity">
          {[currentYarn.brand, currentYarn.colorway]
            .filter(Boolean)
            .join(" · ")}
        </p>
      {/snippet}
      {#snippet actions()}
        <div class="heading-actions">
          <FavoriteButton
            favorite={currentYarn.is_favorite}
            busy={busyFavorite}
            {locale}
            onclick={() => void toggleFavorite()}
          />
          <a
            class="primary-action ui-button ui-button--primary ui-button--md"
            href={`${resolve("/yarns")}/${currentYarn.id}/edit`}
            ><ActionLabel icon="action-update"
              >{t("Edit yarn", locale)}</ActionLabel
            ></a
          >
          <ActionOverflow label={t("More actions", locale)}>
            {#snippet children(close)}
              <div class="action-overflow__section">
                <p class="action-overflow__heading" id="yarn-text-size-label">
                  {t("Text size", locale)}
                </p>
                <div
                  class="action-overflow__segmented text-size-controls"
                  role="group"
                  aria-labelledby="yarn-text-size-label"
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
                aria-label={t("Yarn actions", locale)}
              >
                <ShareMenuItem
                  title={currentYarn.name}
                  ownerId={userId}
                  {locale}
                  {close}
                />
                <button
                  class="action-overflow__item print-button"
                  role="menuitem"
                  type="button"
                  onclick={() => {
                    close();
                    window.print();
                  }}
                >
                  <NavIcon name="print" />
                  <span>{t("Print yarn", locale)}</span>
                </button>
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
                  <span>{t("Delete yarn", locale)}</span>
                </button>
              </div>
            {/snippet}
          </ActionOverflow>
        </div>
      {/snippet}
    </PageHeader>

    {#if foreignOwner(currentYarn.owner, userId)}
      {@const foreign = foreignOwner(currentYarn.owner, userId)}
      {#if foreign}
        <ForeignOwnerBanner kind="yarn" owner={foreign} {locale} />
      {/if}
    {/if}

    {#if actionError}<div class="notice-error" role="alert">
        {actionError}
      </div>{/if}

    {#if stripItems.length}
      <div class="photo-strip-wrap">
        <PhotoStrip
          items={stripItems}
          label={t("Photos", locale)}
          openLabel={t("Yarn photo", locale)}
          {locale}
          onopen={(item) => (selectedPhotoId = item.id)}
        >
          {#snippet tile(item)}
            <OcrControl
              src={item.url}
              label={t("Recognized text from yarn photo", locale)}
              ownerId={userId}
              {locale}
              enabled={canUseOcr}
            />
          {/snippet}
        </PhotoStrip>
      </div>
    {/if}

    <div class="detail-layout">
      <div class="detail-main">
        {#if yarn.description_html}
          <section class="detail-section">
            <SectionHeading icon="notebook" tone="yarns"
              >{t("Description", locale)}</SectionHeading
            >
            <div class="rich-text {textSizeClass}">
              {@html yarn.description_html}
            </div>
          </section>
        {/if}
        {#if yarn.notes_html}
          <StickyNote
            title={t("Notes", locale)}
            headingId="yarn-notes-heading"
            closeLabel={t("Close notes", locale)}
            moveHint={t("Long-press and drag to move", locale)}
            movedLabels={{
              left: t("Notes moved to the left", locale),
              right: t("Notes moved to the right", locale),
              up: t("Notes moved up", locale),
              down: t("Notes moved down", locale),
            }}
          >
            <div class="rich-text {textSizeClass}">{@html yarn.notes_html}</div>
          </StickyNote>
        {:else}
          <StickyNote
            title={t("Notes", locale)}
            headingId="yarn-notes-heading"
            emptyLabel={t("Add a note", locale)}
            emptyHref={`${resolve("/yarns")}/${yarn.id}/edit#yarn-notes`}
          />
        {/if}
        {#if yarn.project_ids.length > 0}
          <section class="detail-section" aria-labelledby="used-in-title">
            <SectionHeading id="used-in-title" icon="projects" tone="yarns"
              >{t("Used in projects", locale)}</SectionHeading
            >
            {#if linkedProjectsLoading}
              <p class="muted" aria-live="polite">
                {t("Loading linked projects…", locale)}
              </p>
            {:else if linkedProjects.length > 0}
              <ul class="project-list">
                {#each linkedProjects as project (project.id)}
                  <li>
                    <ResourceCard
                      kind="project"
                      href={`${resolve("/projects")}/${project.id}`}
                      imageUrl={project.preview_url}
                      name={project.name}
                      subtitle={project.category}
                    />
                  </li>
                {/each}
              </ul>
            {:else}
              <p class="muted">
                {linkedProjectsError
                  ? t("Linked projects could not be loaded.", locale)
                  : t("No projects use this yarn yet.", locale)}
              </p>
            {/if}
          </section>
        {:else}
          <section class="detail-section">
            <SectionHeading icon="projects" tone="yarns"
              >{t("Used in projects", locale)}</SectionHeading
            >
            <p class="muted">{t("No projects use this yarn yet.", locale)}</p>
          </section>
        {/if}
      </div>

      <aside class="spec-panel" aria-label={t("Yarn specifications", locale)}>
        <SectionHeading icon="info" tone="yarns"
          >{t("At a glance", locale)}</SectionHeading
        >
        <dl class={textSizeClass}>
          {#if yarn.brand}<div>
              <dt>{t("Brand", locale)}</dt>
              <dd>{yarn.brand}</dd>
            </div>{/if}
          {#if yarn.colorway}<div>
              <dt>{t("Colorway", locale)}</dt>
              <dd>{yarn.colorway}</dd>
            </div>{/if}
          {#if yarn.dye_lot}<div>
              <dt>{t("Dye lot", locale)}</dt>
              <dd>{yarn.dye_lot}</dd>
            </div>{/if}
          {#if yarn.fiber_content}<div>
              <dt>{t("Fiber content", locale)}</dt>
              <dd>{yarn.fiber_content}</dd>
            </div>{/if}
          {#if yarn.weight_category}<div>
              <dt>{t("Yarn weight", locale)}</dt>
              <dd>{yarn.weight_category}</dd>
            </div>{/if}
          {#if yarn.recommended_needles}<div>
              <dt>{t("Recommended needles", locale)}</dt>
              <dd>{yarn.recommended_needles}</dd>
            </div>{/if}
          {#if yarn.weight_grams !== null}<div>
              <dt>{t("Skein weight", locale)}</dt>
              <dd>{displayNumber(yarn.weight_grams, t("g", locale))}</dd>
            </div>{/if}
          {#if yarn.length_meters !== null}<div>
              <dt>{t("Skein length", locale)}</dt>
              <dd>{displayNumber(yarn.length_meters, t("m", locale))}</dd>
            </div>{/if}
          <div>
            <dt>{t("Updated", locale)}</dt>
            <dd>
              <time datetime={yarn.updated_at}
                >{formatDate(yarn.updated_at)}</time
              >
            </dd>
          </div>
        </dl>
        {#if safeExternalHref(yarn.link)}
          <a
            class="source-link"
            href={safeExternalHref(yarn.link) ?? undefined}
            target="_blank"
            rel="noopener noreferrer">{t("Yarn source", locale)}</a
          >
        {/if}
        {#if safeExternalHref(yarn.link_archive)}
          <a
            class="source-link"
            href={safeExternalHref(yarn.link_archive) ?? undefined}
            target="_blank"
            rel="noopener noreferrer">{t("Archived source", locale)}</a
          >
        {/if}
        {#if safeExternalHref(yarn.link) && (canArchive || canReadArchiveState)}
          <ArchiveControl
            entity="yarns"
            id={yarn.id}
            sourceLink={yarn.link ?? ""}
            detailHasArchive={yarn.link_archive !== null}
            ownerId={userId}
            {locale}
            enabled={canArchive}
            readStateEnabled={canReadArchiveState}
            onArchived={refreshCurrentYarn}
          />
        {/if}
      </aside>
    </div>

    <HistorySection
      kind="yarns"
      id={currentYarn.id}
      {locale}
      currentEmail={session.data?.user?.email ?? null}
    />
  </main>
  <ProjectLightbox
    images={lightboxImages}
    selectedId={selectedPhotoId}
    title={currentYarn.name}
    {locale}
    onClose={() => (selectedPhotoId = null)}
    onSelect={(image) => (selectedPhotoId = image.id)}
  />
  <ConfirmDialog
    bind:this={deleteDialog}
    titleId="delete-yarn-title"
    title={t("Delete yarn?", locale)}
    busy={deleting}
  >
    {#snippet children()}
      <p>
        {t("Delete", locale)} <strong>{currentYarn.name}</strong>? {t(
          "This cannot be undone.",
          locale,
        )}
      </p>
      {#if deleteError}<p class="notice-error" role="alert">
          {deleteError}
        </p>{/if}
    {/snippet}
    {#snippet actions()}
      <Button
        variant="secondary"
        icon="cancel"
        type="button"
        disabled={deleting}
        onclick={() => {
          deleteError = "";
          untrack(() => deleteDialog?.close());
        }}
      >
        {t("Keep yarn", locale)}
      </Button>
      <Button
        variant="danger"
        icon="action-delete"
        type="button"
        disabled={deleting}
        onclick={() => void removeYarn()}
      >
        {deleting ? t("Deleting…", locale) : t("Delete yarn", locale)}
      </Button>
    {/snippet}
  </ConfirmDialog>
{/if}

<style>
  .yarn-detail,
  .detail-error {
    --stack-gap: 1.25rem;
    display: grid;
    gap: var(--stack-gap);
  }

  .identity,
  .muted {
    margin: 0;
    color: var(--ink-muted);
  }

  .heading-actions,
  .error-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
  }

  .detail-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(15rem, 19rem);
    align-items: start;
    gap: var(--stack-gap);
  }

  .detail-main {
    display: grid;
    gap: var(--stack-gap);
    min-width: 0;
  }

  /* The placed sticky note is only a wrapper for its fixed-position button. Taken out of
     the grid flow it no longer adds a second gap between the cards around it. */
  .detail-main > :global(.notes--placed) {
    position: absolute;
  }

  .detail-section,
  .spec-panel {
    min-width: 0;
    border: 1px solid var(--card-border);
    border-radius: var(--radius-sm);
    padding: clamp(1rem, 3vw, 1.4rem);
    background: var(--card-fill);
  }

  .detail-section :global(.section-heading),
  .spec-panel :global(.section-heading) {
    margin-bottom: 0.9rem;
  }

  .photo-strip-wrap {
    margin-bottom: 1rem;
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

  .rich-text {
    max-width: 68ch;
    overflow-wrap: anywhere;
    line-height: 1.75;
  }

  .rich-text :global(:first-child) {
    margin-top: 0;
  }

  .rich-text :global(:last-child) {
    margin-bottom: 0;
  }

  .spec-panel {
    position: sticky;
    top: 1rem;
  }

  dl {
    display: grid;
    gap: 0;
    margin: 0;
  }

  dl div {
    display: grid;
    grid-template-columns: minmax(6.5rem, 0.85fr) minmax(0, 1.15fr);
    gap: 0.6rem;
    border-bottom: 1px solid var(--line);
    padding: 0.65rem 0;
  }

  dt {
    color: var(--ink-muted);
    font-size: 0.87em;
  }

  dd {
    margin: 0;
    overflow-wrap: anywhere;
    font-weight: 570;
    text-align: right;
  }

  .source-link {
    display: flex;
    min-height: 44px;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 620;
    text-decoration-thickness: 0.08em;
    text-underline-offset: 0.18em;
  }

  .project-list {
    display: grid;
    gap: 0.5rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .project-list li {
    min-width: 0;
    border-bottom: 1px solid var(--line);
    padding: 0.45rem 0;
  }

  .notice-error,
  .detail-error {
    border: 1px solid var(--card-border);
    border-radius: var(--radius-sm);
    padding: 1rem;
    color: var(--danger);
    background: var(--card-fill);
  }

  .detail-loading {
    display: grid;
    min-height: 14rem;
    align-content: center;
    justify-items: center;
    gap: 0.8rem;
    color: var(--ink-muted);
  }

  .loading-mark {
    width: 2rem;
    height: 2rem;
    border: 3px solid var(--line);
    border-right-color: var(--accent);
    border-radius: 50%;
    animation: spin 900ms linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(1turn);
    }
  }

  .detail-loading p {
    margin: 0;
  }

  @media (prefers-reduced-motion: reduce) {
    .loading-mark {
      animation: none;
    }
  }

  @media (max-width: 760px) {
    .heading-actions {
      width: 100%;
    }

    .heading-actions > * {
      flex: 1;
    }

    .heading-actions > :global(.favorite-button),
    .heading-actions > :global(.action-overflow) {
      flex: 0 0 44px;
    }

    .detail-layout {
      grid-template-columns: 1fr;
    }

    .spec-panel {
      position: static;
    }
  }

  @media print {
    :global(.desktop-rail),
    :global(.app-header),
    :global(.mobile-navigation),
    :global(.skip-link),
    .heading-actions {
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

    .spec-panel {
      position: static;
    }

    .detail-layout {
      grid-template-columns: 1fr;
    }

    .detail-section,
    .spec-panel {
      break-inside: avoid;
    }

    .rich-text {
      max-width: none;
      color: #111;
    }
  }

  @media (max-width: 480px) {
    .heading-actions {
      display: grid;
      grid-template-columns: 44px minmax(0, 1fr) 44px;
    }

    .heading-actions > * {
      min-width: 0;
    }
  }
</style>
