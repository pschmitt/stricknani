<script lang="ts">
  import SharedButton from "#lib/components/Button.svelte";
  import { resolve } from "$app/paths";
  import { page } from "$app/state";
  import { onDestroy } from "svelte";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import ProjectReader from "#lib/features/projects/ProjectReader.svelte";
  import type {
    ProjectDetail,
    ProjectYarn,
  } from "#lib/features/projects/project-api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    getAdminResource,
    type AdminResourceData,
    type AdminResourceKind,
  } from "./admin-resource-api";

  type LoadState = "loading" | "ready" | "error";
  type RelatedResource = { id: number; resource: AdminResourceData | null };

  let { kind, id }: { kind: string; id: number } = $props();
  let resource = $state<AdminResourceData | null>(null);
  let relatedResources = $state<RelatedResource[]>([]);
  let relatedLoadedFor = $state<string | null>(null);
  let loadState = $state<LoadState>("loading");
  let loadError = $state("");
  let retry = $state(0);
  let loadGeneration = 0;
  let ownerGeneration = 0;
  let relatedGeneration = 0;
  let currentOwner: number | null | undefined;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const queryEventId = $derived(page.url.searchParams.get("event"));
  const projectReaderData = $derived.by(() => {
    const current = resource;
    const ownerId = userId;
    if (
      loadState !== "ready" ||
      current?.kind !== "project" ||
      current.kind !== kind ||
      current.id !== id ||
      ownerId === null ||
      relatedLoadedFor !== relatedKey(current, ownerId)
    )
      return null;

    const projectResource = current.resource;
    const project: ProjectDetail = {
      ...projectResource,
      preview_url:
        projectResource.images.find((image) => image.is_title_image)
          ?.thumbnail_url ??
        projectResource.images[0]?.thumbnail_url ??
        null,
    };
    const yarns: ProjectYarn[] = relatedResources.flatMap((related) => {
      const yarnResource = related.resource;
      if (yarnResource?.kind !== "yarn") return [];
      const yarn = yarnResource.resource;
      return [
        {
          id: yarn.id,
          name: yarn.name,
          brand: yarn.brand,
          colorway: yarn.colorway,
          fiber_content: yarn.fiber_content,
          weight_category: yarn.weight_category,
          recommended_needles: yarn.recommended_needles,
          weight_grams: yarn.weight_grams,
          length_meters: yarn.length_meters,
          notes_html: yarn.notes_html,
          link: yarn.link,
          preview_url:
            yarn.photos.find((photo) => photo.is_primary)?.thumbnail_url ??
            yarn.photos[0]?.thumbnail_url ??
            null,
        },
      ];
    });

    return {
      project,
      yarns,
      owner: current.owner,
      yarnWarning: relatedResources.some(
        (related) => related.resource === null,
      ),
    };
  });

  $effect(() => {
    const requestKind = kind;
    const requestId = id;
    const ownerId = userId;
    const isAdmin = session.data?.user?.is_admin === true;
    retry;
    const request = ++loadGeneration;
    if (currentOwner !== ownerId) {
      currentOwner = ownerId;
      ownerGeneration += 1;
      resource = null;
    }
    const ownerRequest = ownerGeneration;
    const controller = new AbortController();
    loadError = "";
    if (
      ownerId === null ||
      !isAdmin ||
      !Number.isSafeInteger(requestId) ||
      requestId <= 0 ||
      !isResourceKind(requestKind)
    ) {
      loadState = "error";
      loadError = t("Administrator access is required.", locale);
      return () => controller.abort();
    }
    loadState = "loading";
    void getAdminResource(requestKind, requestId, controller.signal)
      .then((result) => {
        if (
          controller.signal.aborted ||
          request !== loadGeneration ||
          ownerRequest !== ownerGeneration ||
          userId !== ownerId
        )
          return;
        resource = result;
        loadState = "ready";
      })
      .catch((error: unknown) => {
        if (
          controller.signal.aborted ||
          request !== loadGeneration ||
          ownerRequest !== ownerGeneration ||
          userId !== ownerId
        )
          return;
        resource = null;
        loadState = "error";
        loadError =
          error instanceof ApiError && error.status === 403
            ? t("Administrator access is required.", locale)
            : error instanceof ApiError && error.status === 404
              ? t("Resource not found.", locale)
              : error instanceof Error
                ? error.message
                : t("Resource details could not be loaded.", locale);
      });
    return () => controller.abort();
  });

  $effect(() => {
    const current = resource;
    const ownerId = userId;
    const request = ++relatedGeneration;
    const controller = new AbortController();
    relatedResources = [];
    relatedLoadedFor = null;
    if (!current || ownerId === null) return () => controller.abort();

    const relatedKind = current.kind === "project" ? "yarn" : "project";
    const ids =
      current.kind === "project"
        ? current.resource.yarn_ids
        : current.kind === "yarn"
          ? current.resource.project_ids
          : [];
    relatedResources = ids.map((id) => ({ id, resource: null }));
    const key = relatedKey(current, ownerId);
    if (ids.length === 0) {
      relatedLoadedFor = key;
      return () => controller.abort();
    }
    void loadRelatedResources(relatedKind, ids, controller.signal).then(
      (results) => {
        if (
          controller.signal.aborted ||
          request !== relatedGeneration ||
          userId !== ownerId
        )
          return;
        relatedResources = results;
        relatedLoadedFor = key;
      },
    );
    return () => controller.abort();
  });

  onDestroy(() => {
    loadGeneration += 1;
    ownerGeneration += 1;
  });

  function isResourceKind(value: string): value is AdminResourceKind {
    return value === "project" || value === "yarn" || value === "category";
  }

  function relatedKey(value: AdminResourceData, ownerId: number): string {
    return `${ownerId}:${value.kind}:${value.id}`;
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

  function eventHref(): string | null {
    const eventId = Number(queryEventId);
    return Number.isSafeInteger(eventId) && eventId > 0
      ? `/app/admin/audit/${eventId}`
      : null;
  }

  function kindLabel(value: AdminResourceKind): string {
    switch (value) {
      case "project":
        return t("Project", locale);
      case "yarn":
        return t("Yarn", locale);
      case "category":
        return t("Category", locale);
    }
  }

  function formatDate(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.valueOf())) return "";
    return new Intl.DateTimeFormat(locale, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(date);
  }

  function formatBytes(value: number): string {
    if (value < 1024) return `${value} B`;
    const units = ["KB", "MB", "GB"];
    let amount = value;
    let unit = -1;
    do {
      amount /= 1024;
      unit += 1;
    } while (amount >= 1024 && unit < units.length - 1);
    return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(amount)} ${units[unit]}`;
  }

  function updatedAt(value: AdminResourceData): string | null {
    return value.kind === "category" ? null : value.resource.updated_at;
  }

  function previewUrl(value: AdminResourceData): string | null {
    if (value.kind === "project") {
      return (
        value.resource.images.find((image) => image.is_title_image)
          ?.thumbnail_url ??
        value.resource.images[0]?.thumbnail_url ??
        null
      );
    }
    if (value.kind === "yarn") {
      return (
        value.resource.photos.find((photo) => photo.is_primary)
          ?.thumbnail_url ??
        value.resource.photos[0]?.thumbnail_url ??
        null
      );
    }
    return null;
  }

  function relatedSubtitle(value: AdminResourceData): string | null {
    if (value.kind === "project") return value.resource.category;
    if (value.kind === "yarn") {
      return (
        [value.resource.brand, value.resource.colorway]
          .filter(Boolean)
          .join(" · ") || null
      );
    }
    return null;
  }

  async function loadRelatedResources(
    kind: "project" | "yarn",
    ids: number[],
    signal: AbortSignal,
  ): Promise<RelatedResource[]> {
    const results: RelatedResource[] = ids.map((id) => ({
      id,
      resource: null,
    }));
    let nextIndex = 0;
    const worker = async () => {
      while (!signal.aborted) {
        const index = nextIndex;
        nextIndex += 1;
        if (index >= ids.length) return;
        const id = ids[index];
        const result = results[index];
        if (id === undefined || result === undefined) return;
        try {
          result.resource = await getAdminResource(kind, id, signal);
        } catch {
          result.resource = null;
        }
      }
    };
    await Promise.all(
      Array.from({ length: Math.min(4, ids.length) }, () => worker()),
    );
    return results;
  }
</script>

<svelte:head>
  {#if !projectReaderData}
    <title>
      Stricknani · {resource?.name ?? t("Resource details", locale)}
    </title>
  {/if}
</svelte:head>

{#if projectReaderData}
  <ProjectReader {id} adminData={projectReaderData} />
{:else}
  <section
    class="resource-page page-container page-container--standard"
    aria-labelledby="resource-title"
  >
    <PageHeader
      title={resource?.name ?? t("Resource details", locale)}
      titleId="resource-title"
    >
      {#snippet context()}
        <div class="resource-heading-context">
          {#if resource}
            <span class="resource-heading-preview" aria-hidden="true">
              <ResourceCard
                kind={resource.kind}
                name=""
                href={null}
                imageUrl={previewUrl(resource)}
                variant="compact"
                titleTag="span"
              />
            </span>
          {/if}
          <p>{t("Read-only view", locale)}</p>
        </div>
      {/snippet}
    </PageHeader>

    {#if loadState === "loading"}
      <p class="notice-panel" aria-live="polite">
        {t("Loading resource details…", locale)}
      </p>
    {:else if loadState === "error"}
      <section
        class="notice-panel"
        role="alert"
        aria-labelledby="resource-error-title"
      >
        <h2 id="resource-error-title">{loadError}</h2>
        <SharedButton
          variant="secondary"
          type="button"
          onclick={() => (retry += 1)}
          icon="refresh"
        >
          {t("Try again", locale)}
        </SharedButton>
      </section>
    {:else if resource}
      <div class="resource-content">
        <div class="resource-meta">
          <span class="resource-kind">{kindLabel(resource.kind)}</span>
          {#if updatedAt(resource)}
            <span
              >{t("Updated", locale)} {formatDate(updatedAt(resource)!)}</span
            >
          {/if}
        </div>

        {#if resource.owner}
          <section class="owner-strip" aria-label={t("Resource owner", locale)}>
            <UserAvatar
              avatarUrl={resource.owner.avatar_url}
              name={resource.owner.email}
              size="large"
            />
            <div>
              <p class="field-label">{t("Owner", locale)}</p>
              <p class="owner-name">{resource.owner.email}</p>
            </div>
          </section>
        {/if}

        {#if eventHref()}
          <a
            class="ui-button ui-button--secondary ui-button--md history-link"
            href={eventHref() ?? undefined}
          >
            <ActionLabel icon="audit"
              >{t("Show recorded changes", locale)}</ActionLabel
            >
          </a>
        {/if}

        {#if resource.kind === "project"}
          {@const project = resource.resource}
          <section
            class="resource-section"
            aria-labelledby="project-details-title"
          >
            <h2 id="project-details-title">{t("Project details", locale)}</h2>
            <dl class="field-grid">
              <div>
                <dt>{t("Category", locale)}</dt>
                <dd>{project.category ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Yarn", locale)}</dt>
                <dd>{project.yarn ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Needles", locale)}</dt>
                <dd>{project.needles ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Stitch sample", locale)}</dt>
                <dd>{project.stitch_sample ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Created", locale)}</dt>
                <dd>{formatDate(project.created_at)}</dd>
              </div>
              <div>
                <dt>{t("Updated", locale)}</dt>
                <dd>{formatDate(project.updated_at)}</dd>
              </div>
            </dl>
            {#if project.yarn_ids.length}
              <div class="linked-resources">
                <h3>{t("Linked yarns", locale)}</h3>
                {#each relatedResources as related (related.id)}
                  {@const yarn = related.resource}
                  <ResourceCard
                    kind="yarn"
                    name={yarn?.name ?? `${t("Yarn", locale)} #${related.id}`}
                    href={yarn
                      ? resolve("/admin/resources/[kind]/[id]", {
                          kind: "yarn",
                          id: String(related.id),
                        })
                      : null}
                    imageUrl={yarn ? previewUrl(yarn) : null}
                    subtitle={yarn ? relatedSubtitle(yarn) : null}
                  />
                {/each}
              </div>
            {/if}
            {#if project.tags.length}
              <div class="tag-list" aria-label={t("Tags", locale)}>
                {#each project.tags as tag (tag)}<span>{tag}</span>{/each}
              </div>
            {/if}
            {#if project.description_html}
              <section class="text-section">
                <h3>{t("Description", locale)}</h3>
                <div class="rich-text">{@html project.description_html}</div>
              </section>
            {/if}
            {#if project.notes_html}
              <section class="text-section">
                <h3>{t("Notes", locale)}</h3>
                <div class="rich-text">{@html project.notes_html}</div>
              </section>
            {/if}
            {#if project.other_materials_html}
              <section class="text-section">
                <h3>{t("Other materials", locale)}</h3>
                <div class="rich-text">
                  {@html project.other_materials_html}
                </div>
              </section>
            {/if}
            {#if safeExternalUrl(project.link)}
              <p>
                <a
                  href={safeExternalUrl(project.link)}
                  target="_blank"
                  rel="noopener noreferrer">{t("Original source", locale)}</a
                >
              </p>
            {/if}
            {#if safeExternalUrl(project.link_archive)}
              <p>
                <a
                  href={safeExternalUrl(project.link_archive)}
                  target="_blank"
                  rel="noopener noreferrer">{t("Archived source", locale)}</a
                >
              </p>
            {/if}
          </section>

          {#if project.steps.length}
            <section class="resource-section" aria-labelledby="steps-title">
              <h2 id="steps-title">{t("Steps", locale)}</h2>
              <ol class="step-list">
                {#each project.steps as step (step.id)}
                  <li>
                    <h3>{step.step_number}. {step.title}</h3>
                    {#if step.description_html}<div class="rich-text">
                        {@html step.description_html}
                      </div>{/if}
                  </li>
                {/each}
              </ol>
            </section>
          {/if}

          {#if project.images.length}
            <section class="resource-section" aria-labelledby="images-title">
              <h2 id="images-title">{t("Images", locale)}</h2>
              <ul class="media-grid">
                {#each project.images as image (image.id)}
                  {@const imageStep = project.steps.find(
                    (step) => step.id === image.step_id,
                  )}
                  <li>
                    <a
                      href={image.url}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      <img
                        src={image.thumbnail_url}
                        alt={image.alt_text}
                        loading="lazy"
                        decoding="async"
                      />
                      <span>
                        {image.alt_text || t("Open full image", locale)}
                        {#if image.step_id}
                          · {t("Step", locale)}
                          {imageStep?.step_number ?? image.step_id}
                        {/if}
                      </span>
                    </a>
                  </li>
                {/each}
              </ul>
            </section>
          {/if}

          {#if project.attachments.length}
            <section
              class="resource-section"
              aria-labelledby="attachments-title"
            >
              <h2 id="attachments-title">{t("Attachments", locale)}</h2>
              <ul class="attachment-list">
                {#each project.attachments as attachment (attachment.id)}
                  <li>
                    <a
                      href={attachment.url}
                      download={attachment.original_filename}
                    >
                      <ActionLabel icon="download"
                        >{attachment.original_filename}</ActionLabel
                      >
                    </a>
                    <span
                      >{attachment.content_type} · {formatBytes(
                        attachment.size_bytes,
                      )}</span
                    >
                  </li>
                {/each}
              </ul>
            </section>
          {/if}
        {:else if resource.kind === "yarn"}
          {@const yarn = resource.resource}
          <section
            class="resource-section"
            aria-labelledby="yarn-details-title"
          >
            <h2 id="yarn-details-title">{t("Yarn details", locale)}</h2>
            <dl class="field-grid">
              <div>
                <dt>{t("Brand", locale)}</dt>
                <dd>{yarn.brand ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Colorway", locale)}</dt>
                <dd>{yarn.colorway ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Dye lot", locale)}</dt>
                <dd>{yarn.dye_lot ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Fiber content", locale)}</dt>
                <dd>{yarn.fiber_content ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Weight category", locale)}</dt>
                <dd>{yarn.weight_category ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Recommended needles", locale)}</dt>
                <dd>{yarn.recommended_needles ?? t("Not provided", locale)}</dd>
              </div>
              <div>
                <dt>{t("Weight", locale)}</dt>
                <dd>
                  {yarn.weight_grams ??
                    t("Not provided", locale)}{yarn.weight_grams !== null
                    ? ` ${t("grams", locale)}`
                    : ""}
                </dd>
              </div>
              <div>
                <dt>{t("Length", locale)}</dt>
                <dd>
                  {yarn.length_meters ??
                    t("Not provided", locale)}{yarn.length_meters !== null
                    ? ` ${t("meters", locale)}`
                    : ""}
                </dd>
              </div>
              <div>
                <dt>{t("Created", locale)}</dt>
                <dd>{formatDate(yarn.created_at)}</dd>
              </div>
              <div>
                <dt>{t("Updated", locale)}</dt>
                <dd>{formatDate(yarn.updated_at)}</dd>
              </div>
            </dl>
            {#if yarn.project_ids.length}
              <div class="linked-resources">
                <h3>{t("Linked projects", locale)}</h3>
                {#each relatedResources as related (related.id)}
                  {@const project = related.resource}
                  <ResourceCard
                    kind="project"
                    name={project?.name ??
                      `${t("Project", locale)} #${related.id}`}
                    href={project
                      ? resolve("/admin/resources/[kind]/[id]", {
                          kind: "project",
                          id: String(related.id),
                        })
                      : null}
                    imageUrl={project ? previewUrl(project) : null}
                    subtitle={project ? relatedSubtitle(project) : null}
                  />
                {/each}
              </div>
            {/if}
            {#if yarn.description_html}
              <section class="text-section">
                <h3>{t("Description", locale)}</h3>
                <div class="rich-text">{@html yarn.description_html}</div>
              </section>
            {/if}
            {#if yarn.notes_html}
              <section class="text-section">
                <h3>{t("Notes", locale)}</h3>
                <div class="rich-text">{@html yarn.notes_html}</div>
              </section>
            {/if}
            {#if safeExternalUrl(yarn.link)}
              <p>
                <a
                  href={safeExternalUrl(yarn.link)}
                  target="_blank"
                  rel="noopener noreferrer">{t("Original source", locale)}</a
                >
              </p>
            {/if}
            {#if safeExternalUrl(yarn.link_archive)}
              <p>
                <a
                  href={safeExternalUrl(yarn.link_archive)}
                  target="_blank"
                  rel="noopener noreferrer">{t("Archived source", locale)}</a
                >
              </p>
            {/if}
          </section>
          {#if yarn.photos.length}
            <section class="resource-section" aria-labelledby="photos-title">
              <h2 id="photos-title">{t("Photos", locale)}</h2>
              <ul class="media-grid">
                {#each yarn.photos as photo (photo.id)}
                  <li>
                    <a
                      href={photo.url}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      <img
                        src={photo.thumbnail_url}
                        alt={photo.alt_text}
                        loading="lazy"
                        decoding="async"
                      />
                      <span
                        >{photo.alt_text || t("Open full image", locale)}</span
                      >
                    </a>
                  </li>
                {/each}
              </ul>
            </section>
          {/if}
        {:else}
          <section
            class="resource-section"
            aria-labelledby="category-details-title"
          >
            <h2 id="category-details-title">{t("Category details", locale)}</h2>
            <dl class="field-grid">
              <div>
                <dt>{t("Category name", locale)}</dt>
                <dd>{resource.resource.name}</dd>
              </div>
            </dl>
          </section>
        {/if}
      </div>
    {/if}
  </section>
{/if}

<style>
  .resource-page {
    display: grid;
    gap: 1.25rem;
    padding-bottom: 3rem;
  }

  .resource-heading-context {
    display: flex;
    align-items: center;
    gap: 0.65rem;
  }

  .resource-heading-preview {
    width: 3.25rem;
    flex: 0 0 3.25rem;
  }

  .resource-heading-preview :global(.resource-card__link) {
    display: block;
  }

  .resource-heading-preview :global(.resource-card__copy) {
    display: none;
  }

  .resource-heading-context p {
    margin: 0;
  }
  .resource-content {
    display: grid;
    gap: 1rem;
  }
  .resource-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    color: var(--muted);
    font-size: 0.9rem;
  }
  .resource-kind {
    color: var(--accent-strong);
    font-weight: 700;
  }
  .owner-strip {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.9rem 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
  }
  .owner-strip p {
    margin: 0;
  }
  .owner-name {
    font-weight: 650;
    overflow-wrap: anywhere;
  }
  .field-label,
  .field-grid dt {
    color: var(--muted);
    font-size: 0.85rem;
  }
  .field-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr));
    gap: 0.75rem 1rem;
    margin: 0;
  }
  .field-grid div {
    min-width: 0;
  }
  .field-grid dt {
    margin-bottom: 0.15rem;
  }
  .field-grid dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .resource-section {
    display: grid;
    gap: 0.85rem;
    padding: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
  }
  .resource-section h2,
  .resource-section h3 {
    margin: 0;
  }
  .resource-section h2 {
    font-size: 1.15rem;
  }
  .resource-section h3 {
    font-size: 1rem;
  }
  .text-section {
    display: grid;
    gap: 0.35rem;
  }
  .rich-text {
    overflow-wrap: anywhere;
    line-height: 1.6;
  }
  .rich-text :global(:first-child) {
    margin-top: 0;
  }
  .rich-text :global(:last-child) {
    margin-bottom: 0;
  }
  .tag-list,
  .linked-resources {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }
  .tag-list span {
    padding: 0.25rem 0.6rem;
    border-radius: 999px;
    background: var(--accent-wash);
    color: var(--accent-strong);
  }
  .linked-resources {
    align-items: center;
  }
  .linked-resources :global(.resource-card) {
    flex: 1 1 16rem;
    max-width: 100%;
  }
  .linked-resources h3 {
    flex-basis: 100%;
  }
  .step-list {
    display: grid;
    gap: 0.75rem;
    padding-left: 1.5rem;
    margin: 0;
  }
  .step-list li {
    padding-left: 0.25rem;
  }
  .step-list h3 {
    margin-bottom: 0.4rem;
  }
  .media-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr));
    gap: 0.75rem;
    padding: 0;
    margin: 0;
    list-style: none;
  }
  .media-grid a {
    display: grid;
    gap: 0.4rem;
    color: inherit;
    text-decoration: none;
  }
  .media-grid img {
    width: 100%;
    aspect-ratio: 1;
    object-fit: cover;
    border-radius: var(--radius-xs);
    background: var(--surface-raised);
  }
  .media-grid span {
    font-size: 0.9rem;
    overflow-wrap: anywhere;
  }
  .attachment-list {
    display: grid;
    gap: 0.6rem;
    padding: 0;
    margin: 0;
    list-style: none;
  }
  .attachment-list li {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.4rem 1rem;
  }
  .attachment-list a {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    overflow-wrap: anywhere;
  }
  .attachment-list li > span {
    color: var(--muted);
    font-size: 0.85rem;
  }
  .history-link {
    display: inline-flex;
    align-items: center;
    justify-self: start;
    text-decoration: none;
  }
  .resource-page a:focus-visible {
    outline: 3px solid var(--accent-strong);
    outline-offset: 3px;
  }
</style>
