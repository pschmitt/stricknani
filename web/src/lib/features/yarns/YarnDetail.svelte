<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ArchiveControl from "#lib/features/media/ArchiveControl.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import YarnPhotoManager from "./YarnPhotoManager.svelte";
  import {
    deleteYarn,
    getLinkedProject,
    getYarn,
    setYarnFavorite,
    type YarnDetail as Yarn,
    type LinkedProject,
  } from "./yarn-api";

  let { id }: { id: number } = $props();

  type LoadState = "loading" | "ready" | "error";

  let loadState = $state<LoadState>("loading");
  let yarn = $state<Yarn | null>(null);
  let linkedProjects = $state<LinkedProject[]>([]);
  let linkedProjectsLoading = $state(false);
  let linkedProjectsError = $state(false);
  let errorMessage = $state("");
  let actionError = $state("");
  let busyFavorite = $state(false);
  let deleting = $state(false);
  let deleteDialog = $state<HTMLDialogElement | null>(null);
  let reload = $state(0);
  let routeGeneration = 0;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const canUseOcr = $derived(session.data?.capabilities.ocr === true);
  const canArchive = $derived(session.data?.capabilities.archive === true);
  const canReadArchiveState = $derived(
    session.data?.capabilities.archive_state === true,
  );
  const yarnId = $derived(id);

  $effect(() => {
    const id = yarnId;
    const currentUserId = userId;
    reload;
    const generation = ++routeGeneration;
    busyFavorite = false;
    deleting = false;
    actionError = "";
    const controller = new AbortController();
    yarn = null;
    linkedProjects = [];
    linkedProjectsError = false;
    if (!Number.isSafeInteger(id) || id <= 0) {
      loadState = "error";
      errorMessage = t("This yarn could not be found.", locale);
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
      loadState = "error";
      errorMessage =
        error instanceof ApiError && error.status === 404
          ? t("This yarn is no longer available.", locale)
          : error instanceof Error
            ? error.message
            : t("The yarn could not be loaded.", locale);
      linkedProjectsLoading = false;
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

  async function removeYarn() {
    const currentSession = session.data;
    if (!yarn || !currentSession?.user || deleting) return;
    const id = yarn.id;
    const ownerId = currentSession.user.id;
    const generation = routeGeneration;
    deleting = true;
    actionError = "";
    try {
      await deleteYarn(id, currentSession);
      if (!isCurrentResource(id, ownerId, generation)) return;
      deleteDialog?.close();
      await goto(resolve("/yarns"));
    } catch (error) {
      if (!isCurrentResource(id, ownerId, generation)) return;
      actionError =
        error instanceof Error
          ? error.message
          : t("The yarn could not be deleted.", locale);
    } finally {
      if (isCurrentResource(id, ownerId, generation)) deleting = false;
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
{:else if loadState === "error"}
  <section
    class="detail-error page-container page-container--standard"
    aria-labelledby="yarn-error-title"
  >
    <h1 id="yarn-error-title">{errorMessage}</h1>
    <div class="error-actions">
      <button class="quiet-button" type="button" onclick={() => (reload += 1)}
        ><ActionLabel icon="reset">{t("Try again", locale)}</ActionLabel
        ></button
      >
      <a class="primary-action" href={resolve("/yarns")}
        >{t("Back to yarns", locale)}</a
      >
    </div>
  </section>
{:else if yarn}
  {@const currentYarn = yarn}
  <main
    class="yarn-detail page-container page-container--wide"
    aria-labelledby="yarn-title"
  >
    <PageHeader title={currentYarn.name} titleId="yarn-title">
      {#snippet context()}
        <p class="identity">
          {[currentYarn.brand, currentYarn.colorway]
            .filter(Boolean)
            .join(" · ")}
        </p>
      {/snippet}
      {#snippet actions()}
        <div class="heading-actions">
          <button
            class="favorite-button"
            type="button"
            aria-pressed={currentYarn.is_favorite}
            disabled={busyFavorite}
            onclick={() => void toggleFavorite()}
          >
            <span aria-hidden="true">{currentYarn.is_favorite ? "★" : "☆"}</span
            >
            {currentYarn.is_favorite
              ? t("Favorited", locale)
              : t("Add to favorites", locale)}
          </button>
          <a
            class="primary-action"
            href={`${resolve("/yarns")}/${currentYarn.id}/edit`}
            ><ActionLabel icon="action-update"
              >{t("Edit yarn", locale)}</ActionLabel
            ></a
          >
          <button
            class="quiet-button destructive"
            type="button"
            onclick={() => deleteDialog?.showModal()}
            ><ActionLabel icon="action-delete"
              >{t("Delete", locale)}</ActionLabel
            ></button
          >
        </div>
      {/snippet}
    </PageHeader>

    {#if actionError}<div class="notice-error" role="alert">
        {actionError}
      </div>{/if}

    <div class="detail-layout">
      <div class="detail-main">
        <section
          class="detail-section photos-section"
          aria-label={t("Photos", locale)}
        >
          <YarnPhotoManager
            yarnId={yarn.id}
            photos={yarn.photos}
            {locale}
            ownerId={userId}
            ocrEnabled={canUseOcr}
            onResourceUpdated={refreshYarn}
          />
        </section>

        {#if yarn.description_html}
          <section class="detail-section">
            <h2>{t("Description", locale)}</h2>
            <div class="rich-text">{@html yarn.description_html}</div>
          </section>
        {/if}
        {#if yarn.notes_html}
          <section class="detail-section">
            <h2>{t("Notes", locale)}</h2>
            <div class="rich-text">{@html yarn.notes_html}</div>
          </section>
        {/if}
        {#if yarn.project_ids.length > 0}
          <section class="detail-section" aria-labelledby="used-in-title">
            <h2 id="used-in-title">{t("Used in projects", locale)}</h2>
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
            <h2>{t("Used in projects", locale)}</h2>
            <p class="muted">{t("No projects use this yarn yet.", locale)}</p>
          </section>
        {/if}
      </div>

      <aside class="spec-panel" aria-label={t("Yarn specifications", locale)}>
        <h2>{t("At a glance", locale)}</h2>
        <dl>
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
  </main>
{/if}

<dialog
  bind:this={deleteDialog}
  class="confirm-dialog"
  aria-labelledby="delete-yarn-title"
>
  <h2 id="delete-yarn-title">{t("Delete yarn?", locale)}</h2>
  {#if yarn}<p>
      {t("Delete", locale)} <strong>{yarn.name}</strong>? {t(
        "This cannot be undone.",
        locale,
      )}
    </p>{/if}
  {#if actionError}<p class="error-text" role="alert">{actionError}</p>{/if}
  <div class="dialog-actions">
    <button
      class="quiet-button"
      type="button"
      disabled={deleting}
      onclick={() => deleteDialog?.close()}
      ><ActionLabel icon="cancel">{t("Keep yarn", locale)}</ActionLabel></button
    >
    <button
      class="danger-button"
      type="button"
      disabled={deleting}
      onclick={() => void removeYarn()}
      ><ActionLabel icon="action-delete"
        >{deleting
          ? t("Deleting…", locale)
          : t("Delete yarn", locale)}</ActionLabel
      ></button
    >
  </div>
</dialog>

<style>
  .yarn-detail,
  .detail-error {
    display: grid;
    gap: 1.2rem;
  }

  .identity,
  .muted {
    margin: 0;
    color: var(--ink-muted);
  }

  .heading-actions,
  .error-actions,
  .dialog-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
  }

  .primary-action,
  .quiet-button,
  .favorite-button,
  .danger-button {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    border: 1px solid var(--line);
    border-radius: 0.7rem;
    padding: 0.55rem 0.8rem;
    color: var(--accent-strong);
    background: var(--surface);
    font: inherit;
    font-weight: 620;
    text-decoration: none;
    cursor: pointer;
  }

  .primary-action {
    border-color: var(--accent-strong);
    color: var(--on-accent);
    background: var(--accent-strong);
  }

  .favorite-button span {
    font-size: 1.2rem;
  }

  .destructive {
    color: var(--danger);
  }

  .detail-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(15rem, 19rem);
    align-items: start;
    gap: 1rem;
  }

  .detail-main {
    display: grid;
    gap: 1rem;
    min-width: 0;
  }

  .detail-section,
  .spec-panel {
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: 0.95rem;
    padding: clamp(1rem, 3vw, 1.4rem);
    background: var(--surface);
  }

  .detail-section h2,
  .spec-panel h2 {
    margin: 0 0 0.8rem;
    font-size: 1.1rem;
  }

  .photos-section {
    padding: 1rem;
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
    font-size: 0.87rem;
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
    border: 1px solid var(--line);
    border-radius: 0.9rem;
    padding: 1rem;
    color: var(--danger);
    background: var(--surface);
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

  .confirm-dialog {
    max-width: min(92vw, 32rem);
    border: 1px solid var(--line);
    border-radius: 1rem;
    padding: 1.2rem;
    color: var(--ink);
    background: var(--surface);
  }

  .confirm-dialog::backdrop {
    background: rgb(15 28 27 / 72%);
  }

  .confirm-dialog h2 {
    margin-top: 0;
  }

  .confirm-dialog p {
    color: var(--ink-muted);
  }

  .danger-button {
    border-color: var(--danger);
    color: var(--on-danger);
    background: var(--danger);
  }

  .error-text {
    color: var(--danger) !important;
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

    .detail-layout {
      grid-template-columns: 1fr;
    }

    .spec-panel {
      position: static;
    }
  }

  @media (max-width: 480px) {
    .heading-actions {
      display: grid;
      grid-template-columns: 1fr 1fr;
    }

    .favorite-button {
      grid-column: 1 / -1;
    }

    .heading-actions > * {
      min-width: 0;
    }

    .dialog-actions {
      align-items: stretch;
      flex-direction: column-reverse;
    }
  }
</style>
