<script lang="ts">
  import { resolve } from "$app/paths";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { importImageSrc, type ImportTarget } from "./import-api";
  import {
    deleteImportDraft,
    listImportDrafts,
    type ImportDraft,
  } from "./import-drafts-api";

  /**
   * "Waiting for review": the imports the user started and has not saved yet.
   * A running one shows a small spinner, a finished one is ready to review,
   * a failed one can be dismissed. Tapping opens the editor with the draft.
   */
  let { target = "all" }: { target?: ImportTarget | "all" } = $props();

  const POLL_MS = 4000;

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);

  let drafts = $state<ImportDraft[]>([]);
  let removing = $state<number[]>([]);

  const shown = $derived(
    drafts.filter((draft) => target === "all" || draft.target === target),
  );
  const anyRunning = $derived(
    drafts.some((draft) => draft.status === "running"),
  );

  // Load once per user, and look again while an import is still running.
  $effect(() => {
    const owner = userId;
    drafts = [];
    if (owner === null) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async (): Promise<void> => {
      try {
        const list = await listImportDrafts(controller.signal);
        if (controller.signal.aborted) return;
        drafts = list;
        if (list.some((draft) => draft.status === "running"))
          timer = setTimeout(() => void load(), POLL_MS);
      } catch {
        // The cards are a convenience: a failed load shows nothing.
      }
    };
    void load();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  });

  function path(draft: ImportDraft): string {
    const base = resolve(
      draft.target === "yarn" ? "/yarns/new" : "/projects/new",
    );
    return `${base}?draft=${draft.id}`;
  }

  function title(draft: ImportDraft): string {
    if (draft.title) return draft.title;
    if (draft.source_url) {
      try {
        return new URL(draft.source_url).hostname.replace(/^www\./, "");
      } catch {
        return draft.source_url;
      }
    }
    return draft.source_kind === "text"
      ? t("Pasted text", locale)
      : t("Import", locale);
  }

  function statusLabel(draft: ImportDraft): string {
    switch (draft.status) {
      case "running":
        return t("Importing…", locale);
      case "ready":
        return t("Ready to review", locale);
      case "failed":
        return t("Did not work", locale);
    }
  }

  async function dismiss(draft: ImportDraft): Promise<void> {
    const current = session.data;
    if (!current?.user || removing.includes(draft.id)) return;
    removing = [...removing, draft.id];
    try {
      await deleteImportDraft(draft.id, current);
      drafts = drafts.filter((entry) => entry.id !== draft.id);
    } catch {
      // It stays on the list; the next load shows the truth.
    } finally {
      removing = removing.filter((id) => id !== draft.id);
    }
  }
</script>

{#if shown.length}
  <section
    class="draft-cards"
    aria-labelledby="draft-cards-title"
    data-testid="import-drafts"
  >
    <h2 id="draft-cards-title">{t("Waiting for review", locale)}</h2>
    <ul>
      {#each shown as draft (draft.id)}
        <li class="draft-card draft-card--{draft.status}">
          <a class="draft-card__link" href={path(draft)}>
            <span class="draft-card__image" aria-hidden="true">
              {#if draft.image_url}
                <img
                  src={importImageSrc(draft.image_url)}
                  alt=""
                  loading="lazy"
                  decoding="async"
                />
              {:else}
                <NavIcon
                  name={draft.target === "yarn" ? "yarns" : "projects"}
                />
              {/if}
            </span>
            <span class="draft-card__copy">
              <span class="draft-card__title">{title(draft)}</span>
              <span class="draft-card__status">
                {#if draft.status === "running"}<span
                    class="draft-card__spinner"
                    aria-hidden="true"
                  ></span>{/if}
                {statusLabel(draft)}
              </span>
            </span>
          </a>
          <button
            type="button"
            class="draft-card__dismiss"
            aria-label={`${t("Dismiss", locale)}: ${title(draft)}`}
            disabled={removing.includes(draft.id)}
            onclick={() => void dismiss(draft)}
          >
            <NavIcon name="close" />
          </button>
        </li>
      {/each}
    </ul>
    {#if anyRunning}<p class="visually-hidden" role="status">
        {t("Importing…", locale)}
      </p>{/if}
  </section>
{/if}

<style>
  .draft-cards {
    display: grid;
    gap: 0.6rem;
    margin: 0 0 1.25rem;
  }

  h2 {
    margin: 0;
    font-size: 1.05rem;
  }

  ul {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(100%, 17rem), 1fr));
    gap: 0.6rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .draft-card {
    position: relative;
    min-width: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
  }

  .draft-card--ready {
    border-color: var(--accent);
    background: var(--accent-wash);
  }

  .draft-card--failed {
    border-color: var(--danger);
  }

  .draft-card__link {
    display: grid;
    grid-template-columns: 3.5rem minmax(0, 1fr);
    align-items: center;
    gap: 0.75rem;
    min-height: 4.5rem;
    padding: 0.5rem 3rem 0.5rem 0.5rem;
    color: var(--ink);
    text-decoration: none;
  }

  .draft-card__link:focus-visible {
    outline: 3px solid var(--accent);
    outline-offset: 2px;
    border-radius: var(--radius-sm);
  }

  .draft-card__image {
    display: grid;
    width: 3.5rem;
    height: 3.5rem;
    place-items: center;
    overflow: hidden;
    border-radius: var(--radius-xs);
    color: var(--accent-strong);
    background: var(--surface-raised);
  }

  .draft-card__image img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .draft-card__image :global(svg) {
    width: 1.6rem;
    height: 1.6rem;
  }

  .draft-card__copy {
    display: grid;
    gap: 0.15rem;
    min-width: 0;
  }

  .draft-card__title {
    overflow: hidden;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .draft-card__status {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .draft-card--failed .draft-card__status {
    color: var(--danger);
  }

  .draft-card__spinner {
    width: 0.9rem;
    height: 0.9rem;
    border: 2px solid var(--line);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: draft-spin 900ms linear infinite;
  }

  .draft-card__dismiss {
    position: absolute;
    top: 50%;
    right: 0.35rem;
    display: grid;
    width: 2.75rem;
    height: 2.75rem;
    place-items: center;
    transform: translateY(-50%);
    border: 0;
    border-radius: 50%;
    color: var(--ink-muted);
    background: transparent;
    cursor: pointer;
  }

  .draft-card__dismiss:hover {
    color: var(--ink);
    background: var(--surface-raised);
  }

  .draft-card__dismiss :global(svg) {
    width: 1.2rem;
    height: 1.2rem;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }

  @keyframes draft-spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .draft-card__spinner {
      animation: none;
      border-top-color: var(--line);
      border-right-color: var(--accent);
    }
  }
</style>
