<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { tick } from "svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import {
    listCategories,
    type Category,
  } from "#lib/features/categories/category-api.ts";
  import {
    listProjects,
    type ProjectSummary,
  } from "#lib/features/projects/project-api.ts";
  import { listYarns, type YarnSummary } from "#lib/features/yarns/yarn-api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { scopeFor } from "#lib/library-scope.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { setTheme, theme } from "#lib/theme.svelte.ts";
  import { buildStaticEntries, isDarkNow } from "#lib/palette/entries.ts";
  import { shouldRefocusSearch } from "#lib/palette/focus.ts";
  import type { PaletteEntry } from "#lib/palette/entries.ts";
  import {
    detectUrl,
    highlightParts,
    type PaletteGroupId,
    type Range,
  } from "#lib/palette/match.ts";
  import {
    palette,
    shortcutLabel,
    togglePalette,
  } from "#lib/palette/palette.svelte.ts";
  import {
    buildGroups,
    categoryEntry,
    projectEntry,
    yarnEntry,
    type PaletteRow,
  } from "#lib/palette/results.ts";

  const listId = $props.id();

  const locale = $derived(session.data?.locale ?? "en");
  const user = $derived(session.data?.user ?? null);

  let dialog = $state<HTMLDialogElement>();
  let input = $state<HTMLInputElement>();
  let query = $state("");
  let active = $state(0);
  let expanded = $state<PaletteGroupId[]>([]);
  let projects = $state<ProjectSummary[]>([]);
  let yarns = $state<YarnSummary[]>([]);
  let categories = $state<Category[]>([]);
  let searching = $state(false);
  let restoreFocusTo: HTMLElement | null = null;

  const url = $derived(detectUrl(query));

  const entries = $derived.by<PaletteEntry[]>(() => {
    const statics = buildStaticEntries({
      locale,
      isAdmin: user?.is_admin === true,
      resolvePath: (path) => resolve(path as "/"),
      url,
    });
    const found: PaletteEntry[] = url
      ? []
      : [
          ...projects.map((project) =>
            projectEntry(
              project,
              resolve("/projects/[id]", { id: String(project.id) }),
            ),
          ),
          ...yarns.map((yarn) =>
            yarnEntry(yarn, resolve("/yarns/[id]", { id: String(yarn.id) })),
          ),
          ...categories.map((category) =>
            categoryEntry(category, resolve("/categories")),
          ),
        ];
    // Actions first, then what was found, then pages and settings (the groups
    // sort themselves; this is the order inside a group).
    return [...statics, ...found];
  });
  const serverIds = $derived(
    new Set([
      ...projects.map((project) => `project:${project.id}`),
      ...yarns.map((yarn) => `yarn:${yarn.id}`),
    ]),
  );
  const groups = $derived(
    buildGroups({
      query,
      entries,
      serverIds,
      expanded: new Set(expanded),
      url,
    }),
  );
  const flat = $derived(groups.flatMap((group) => group.rows));
  const selected = $derived(Math.min(active, Math.max(0, flat.length - 1)));

  function groupTitle(id: PaletteGroupId, empty: boolean): string {
    switch (id) {
      case "actions":
        return t("Actions", locale);
      case "projects":
        return empty ? t("Recent projects", locale) : t("Projects", locale);
      case "yarns":
        return empty ? t("Recent yarns", locale) : t("Yarns", locale);
      case "categories":
        return t("Categories", locale);
      case "pages":
        return t("Pages", locale);
      default:
        return t("Settings", locale);
    }
  }

  // Open and close the native dialog from the shared state; it traps focus and
  // closes on Escape. Focus returns to whatever had it before.
  $effect(() => {
    const element = dialog;
    if (!element) return;
    if (palette.open && !element.open) {
      restoreFocusTo =
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null;
      query = "";
      active = 0;
      expanded = [];
      element.showModal();
      void tick().then(() => input?.focus());
    } else if (!palette.open && element.open) {
      element.close();
    }
  });

  function onDialogClose() {
    palette.open = false;
    const target = restoreFocusTo;
    restoreFocusTo = null;
    if (target?.isConnected) target.focus();
  }

  // Categories do not change while the palette is open: one request per opening.
  $effect(() => {
    if (!palette.open || !user) return;
    const controller = new AbortController();
    void listCategories(controller.signal)
      .then((list) => {
        if (!controller.signal.aborted) categories = list;
      })
      .catch(() => {});
    return () => controller.abort();
  });

  // Projects and yarns come from the same list APIs as the lists, scoped the same
  // way; typing is debounced and an older request never overwrites a newer one.
  $effect(() => {
    if (!palette.open || !user) return;
    const text = query.trim();
    if (detectUrl(text)) {
      projects = [];
      yarns = [];
      searching = false;
      return;
    }
    const scope = { scope: scopeFor(user) };
    const controller = new AbortController();
    const handle = setTimeout(
      () => {
        searching = true;
        const search = text ? new URLSearchParams({ q: text }).toString() : "";
        void Promise.allSettled([
          listProjects(search, controller.signal, scope),
          listYarns(search, controller.signal, scope),
        ]).then(([projectResult, yarnResult]) => {
          if (controller.signal.aborted) return;
          projects =
            projectResult.status === "fulfilled"
              ? projectResult.value.data
              : [];
          yarns =
            yarnResult.status === "fulfilled" ? yarnResult.value.data : [];
          searching = false;
        });
      },
      text ? 180 : 0,
    );
    return () => {
      clearTimeout(handle);
      controller.abort();
    };
  });

  function run(entry: PaletteEntry) {
    palette.open = false;
    if (entry.command === "toggle-theme") {
      setTheme(isDarkNow(theme.choice) ? "light" : "dark");
      return;
    }
    if (entry.href) void goto(entry.href);
  }

  function move(delta: number) {
    if (!flat.length) return;
    active = (selected + delta + flat.length) % flat.length;
    void tick().then(() =>
      document
        .getElementById(`${listId}-option-${active}`)
        ?.scrollIntoView({ block: "nearest" }),
    );
  }

  function onInputKeydown(event: KeyboardEvent) {
    if (event.isComposing) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      move(1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      move(-1);
    } else if (event.key === "Enter") {
      event.preventDefault();
      const row = flat[selected];
      if (row) run(row.entry);
    }
  }

  // SvelteKit blurs the active element when a navigation starts; one still
  // running when the palette opens (the redirect after signing in) would leave
  // the field without focus, and Enter would go to the page behind the dialog.
  function onInputFocusout(event: FocusEvent) {
    if (
      !dialog?.open ||
      !shouldRefocusSearch({
        open: palette.open,
        relatedTarget: event.relatedTarget,
        contains: (target) =>
          target instanceof Node && (dialog?.contains(target) ?? false),
      })
    )
      return;
    queueMicrotask(() => {
      if (palette.open && dialog?.open) input?.focus();
    });
  }

  function onWindowKeydown(event: KeyboardEvent) {
    if (!user) return;
    if (
      (event.ctrlKey || event.metaKey) &&
      !event.altKey &&
      !event.shiftKey &&
      event.key.toLowerCase() === "k"
    ) {
      event.preventDefault();
      togglePalette();
    }
  }

  function onBackdropClick(event: MouseEvent) {
    if (event.target === dialog) palette.open = false;
  }

  function showMore(id: PaletteGroupId) {
    if (!expanded.includes(id)) expanded = [...expanded, id];
  }

  function rangesFor(row: PaletteRow, field: number): Range[] | undefined {
    return row.ranges.get(field);
  }

  let indexOf = $derived.by(() => {
    const map = new Map<PaletteRow, number>();
    flat.forEach((row, index) => map.set(row, index));
    return map;
  });
</script>

<svelte:window onkeydown={onWindowKeydown} />

{#snippet marked(text: string, ranges: Range[] | undefined)}
  {#each highlightParts(text, ranges) as part}{#if part.match}<mark
        >{part.text}</mark
      >{:else}{part.text}{/if}{/each}
{/snippet}

{#if user}
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
  <dialog
    bind:this={dialog}
    class="palette"
    aria-label={t("Search everything", locale)}
    onclose={onDialogClose}
    onclick={onBackdropClick}
  >
    <div class="palette__panel">
      <div class="palette__bar">
        <NavIcon name="search" />
        <input
          bind:this={input}
          bind:value={query}
          class="palette__input"
          type="text"
          role="combobox"
          aria-expanded="true"
          aria-controls={`${listId}-list`}
          aria-autocomplete="list"
          aria-activedescendant={flat.length
            ? `${listId}-option-${selected}`
            : undefined}
          aria-label={t("Search everything", locale)}
          placeholder={t("Search", locale)}
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          enterkeyhint="go"
          oninput={() => (active = 0)}
          onkeydown={onInputKeydown}
          onfocusout={onInputFocusout}
        />
        <kbd class="palette__esc">esc</kbd>
      </div>
      <div
        class="palette__list"
        id={`${listId}-list`}
        role="listbox"
        aria-label={t("Results", locale)}
        aria-busy={searching}
      >
        {#each groups as group (group.id)}
          <div class="palette__group" role="presentation">
            <span>{groupTitle(group.id, query.trim() === "")}</span>
            {#if group.total > group.rows.length}
              <button
                type="button"
                class="palette__more"
                tabindex="-1"
                onclick={() => showMore(group.id)}
                >{t("Show more", locale)} ({group.total - group.rows.length})
              </button>
            {/if}
          </div>
          {#each group.rows as row (row.entry.id)}
            {@const index = indexOf.get(row) ?? 0}
            <div
              class="palette__row"
              role="option"
              id={`${listId}-option-${index}`}
              aria-selected={index === selected}
              tabindex="-1"
              data-entry={row.entry.id}
              onmousemove={() => (active = index)}
              onclick={() => run(row.entry)}
              onkeydown={() => {}}
            >
              <span class="palette__icon" data-tone={row.entry.icon}>
                {#if row.entry.imageUrl}
                  <img src={row.entry.imageUrl} alt="" loading="lazy" />
                {:else}
                  <NavIcon name={row.entry.icon} />
                {/if}
              </span>
              <span class="palette__text">
                <span class="palette__title"
                  >{@render marked(row.entry.title, rangesFor(row, 0))}</span
                >
                {#if row.entry.sub}
                  <span class="palette__sub"
                    >{@render marked(row.entry.sub, rangesFor(row, 1))}</span
                  >
                {/if}
              </span>
            </div>
          {/each}
        {:else}
          <div class="palette__empty">
            <strong>{t("Nothing matches", locale)}</strong>
            <span>{t("Try fewer words, or a part of a name.", locale)}</span>
          </div>
        {/each}
      </div>
      <div class="palette__foot" aria-hidden="true">
        <span><kbd>↑</kbd><kbd>↓</kbd> {t("move", locale)}</span>
        <span><kbd>↵</kbd> {t("open", locale)}</span>
        <span class="palette__shortcut"><kbd>{shortcutLabel()}</kbd></span>
      </div>
    </div>
  </dialog>
{/if}

<style>
  .palette {
    box-sizing: border-box;
    width: 100%;
    max-width: none;
    height: 100%;
    max-height: none;
    margin: 0;
    padding: min(12vh, 7rem) 1rem 1rem;
    border: 0;
    background: transparent;
    color: var(--ink);
    overflow: hidden;
  }

  .palette:not([open]) {
    display: none;
  }

  .palette[open] {
    display: flex;
    align-items: flex-start;
    justify-content: center;
  }

  .palette::backdrop {
    background: rgb(15 10 20 / 55%);
  }

  .palette__panel {
    display: flex;
    width: 100%;
    max-width: 41rem;
    max-height: min(40rem, calc(100dvh - 2rem));
    flex-direction: column;
    overflow: hidden;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    background: var(--surface);
    box-shadow: 0 1.5rem 4rem rgb(0 0 0 / 35%);
    animation: palette-rise 0.16s ease-out;
  }

  @keyframes palette-rise {
    from {
      opacity: 0;
      transform: translateY(-6px) scale(0.98);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .palette__panel {
      animation: none;
    }
  }

  .palette__bar {
    display: flex;
    flex: none;
    align-items: center;
    gap: 0.7rem;
    padding: 0 1rem;
    border-bottom: 1px solid var(--line);
    color: var(--accent);
  }

  .palette__bar :global(svg) {
    width: 1.3rem;
    height: 1.3rem;
    flex: none;
  }

  .palette__input {
    min-width: 0;
    height: 3.5rem;
    flex: 1;
    border: 0;
    outline: 0;
    background: transparent;
    color: var(--ink);
    font: inherit;
    font-size: 1.05rem;
  }

  .palette__input::placeholder {
    color: var(--ink-muted);
  }

  kbd {
    padding: 0.05rem 0.4rem;
    border: 1px solid var(--line);
    border-bottom-width: 2px;
    border-radius: 0.4rem;
    background: var(--surface-raised);
    color: var(--ink-muted);
    font: inherit;
    font-size: 0.72rem;
    font-weight: 700;
    white-space: nowrap;
  }

  .palette__list {
    min-height: 0;
    flex: 1;
    overflow-y: auto;
    overscroll-behavior: contain;
    padding: 0.25rem 0 0.5rem;
  }

  .palette__group {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.7rem 1.1rem 0.25rem;
    color: var(--ink-muted);
    font-size: 0.72rem;
    font-weight: 800;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }

  .palette__more {
    min-height: 1.75rem;
    margin-left: auto;
    padding: 0 0.4rem;
    border: 0;
    background: transparent;
    color: var(--accent-strong);
    cursor: pointer;
    font: inherit;
    font-size: 0.78rem;
    font-weight: 700;
    letter-spacing: 0;
    text-transform: none;
  }

  .palette__more:hover {
    text-decoration: underline;
  }

  .palette__row {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin: 0 0.5rem;
    padding: 0.4rem 0.6rem;
    border: 1px solid transparent;
    border-radius: var(--radius-xs);
    cursor: pointer;
  }

  .palette__row[aria-selected="true"] {
    border-color: var(--accent);
    background: var(--accent-wash);
  }

  .palette__icon {
    display: grid;
    width: 2.25rem;
    height: 2.25rem;
    flex: none;
    place-items: center;
    overflow: hidden;
    border-radius: 0.65rem;
    background: var(--surface-raised);
    color: var(--accent-strong);
  }

  .palette__icon :global(svg) {
    width: 1.15rem;
    height: 1.15rem;
  }

  .palette__icon img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .palette__text {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
  }

  .palette__title,
  .palette__sub {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .palette__title {
    font-weight: 700;
  }

  .palette__sub {
    color: var(--ink-muted);
    font-size: 0.8rem;
  }

  mark {
    padding: 0 1px;
    border-radius: 3px;
    background: var(--warning-surface);
    color: inherit;
    font-weight: 800;
  }

  .palette__empty {
    display: grid;
    gap: 0.25rem;
    padding: 2rem 1rem;
    color: var(--ink-muted);
    text-align: center;
  }

  .palette__empty strong {
    color: var(--ink);
  }

  .palette__foot {
    display: flex;
    flex: none;
    align-items: center;
    gap: 1rem;
    padding: 0.5rem 1rem;
    border-top: 1px solid var(--line);
    color: var(--ink-muted);
    font-size: 0.75rem;
  }

  .palette__foot span {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
  }

  .palette__shortcut {
    margin-left: auto;
  }

  @media (hover: none) {
    .palette__foot,
    .palette__esc {
      display: none;
    }
  }

  @media (max-width: 560px) {
    .palette {
      padding-top: 0.5rem;
    }
  }
</style>
