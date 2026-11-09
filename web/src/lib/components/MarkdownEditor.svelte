<script lang="ts">
  // One editor for every Markdown field: a visual (WYSIWYG) editor with a small toolbar, or the
  // plain Markdown source ("Raw"). It holds and emits the same Markdown string a textarea would,
  // so dirty tracking, drafts and validation keep working. The visual editor (ProseMirror) is
  // loaded on demand; until then, and whenever the text uses something it cannot show without
  // changing it (a table), the Raw textarea is used.
  import { onMount, untrack } from "svelte";
  import { focusOnHash } from "./focus-on-hash.ts";
  import NavIcon, { type NavIconName } from "./NavIcon.svelte";
  import SegmentedControl from "./SegmentedControl.svelte";
  import { msg, t, type Locale } from "#lib/i18n.svelte.ts";
  import { editorMode } from "#lib/markdown/editor-mode.svelte.ts";
  import type {
    ActiveFormats,
    EditorCommand,
    EditorHandle,
  } from "#lib/markdown/editor-core.ts";

  type Core = typeof import("#lib/markdown/editor-core.ts");
  type Model = typeof import("#lib/markdown/markdown-model.ts");

  let {
    id,
    label = "",
    labelledBy,
    describedBy,
    value = $bindable(""),
    rows = 4,
    disabled = false,
    placeholder = "",
    maxlength,
    lines = false,
    sticky = false,
    locale,
    onInput,
    onfocus,
  }: {
    id: string;
    /** Accessible name; use `labelledBy` instead when a visible label exists. */
    label?: string;
    /** Id of the visible label element. */
    labelledBy?: string;
    describedBy?: string;
    value?: string;
    rows?: number;
    disabled?: boolean;
    placeholder?: string;
    maxlength?: number;
    /** Every line break of the text stays a line break (list-like fields). */
    lines?: boolean;
    /** Blend into the paper of a sticky note. */
    sticky?: boolean;
    locale?: Locale;
    /** Called with the new text on every edit, for parents that do not bind `value`. */
    onInput?: (value: string) => void;
    onfocus?: () => void;
  } = $props();

  let core = $state<Core | null>(null);
  let model = $state<Model | null>(null);
  // The text uses something the visual editor cannot show faithfully.
  let unsupported = $state(false);
  let handle: EditorHandle | null = null;
  let host = $state<HTMLDivElement | null>(null);
  let formats = $state<ActiveFormats | null>(null);
  let linkOpen = $state(false);
  let linkValue = $state("");
  let linkHadHref = $state(false);
  let linkInput = $state<HTMLInputElement | null>(null);
  let rawArea = $state<HTMLTextAreaElement | null>(null);
  let lastEmitted = "";

  const shown = $derived(
    editorMode.value === "visual" && core && model && !unsupported
      ? "visual"
      : "raw",
  );
  const editLabel = $derived(label || undefined);

  onMount(() => {
    editorMode.load();
    let cancelled = false;
    void Promise.all([
      import("#lib/markdown/editor-core.ts"),
      import("#lib/markdown/markdown-model.ts"),
    ])
      .then(([loadedCore, loadedModel]) => {
        if (cancelled) return;
        unsupported = !loadedModel.canEditVisually(value, lines);
        model = loadedModel;
        core = loadedCore;
      })
      .catch(() => {
        // The editor chunk could not load (offline): the Raw textarea stays usable.
      });
    return () => {
      cancelled = true;
    };
  });

  // Create and destroy the visual editor with its host element.
  $effect(() => {
    if (shown !== "visual" || !core || !host) return;
    const created = core.createMarkdownEditor({
      parent: host,
      markdown: untrack(() => value),
      lines,
      editable: !untrack(() => disabled),
      placeholder,
      id,
      label: label || t("Text", locale),
      labelledBy,
      describedBy,
      onChange: (markdown) => {
        lastEmitted = markdown;
        value = markdown;
        onInput?.(markdown);
      },
      onFormats: (next) => {
        formats = next;
      },
      onLinkShortcut: openLink,
      onFocusChange: (focused) => {
        if (focused) onfocus?.();
      },
    });
    lastEmitted = untrack(() => value);
    handle = created;
    return () => {
      created.destroy();
      handle = null;
      formats = null;
      linkOpen = false;
    };
  });

  // Draft reloads and other outside changes of the text.
  $effect(() => {
    const next = value;
    if (next === lastEmitted) return;
    lastEmitted = next;
    if (model) unsupported = !model.canEditVisually(next, lines);
    handle?.setMarkdown(next);
  });

  $effect(() => {
    handle?.setEditable(!disabled);
  });

  function setMode(next: "visual" | "raw") {
    if (next === "visual" && model) {
      unsupported = !model.canEditVisually(value, lines);
      if (unsupported) return;
    }
    editorMode.set(next);
    queueMicrotask(() => {
      if (next === "visual") handle?.focus();
      else rawArea?.focus();
    });
  }

  function run(command: EditorCommand) {
    handle?.run(command);
  }

  function openLink() {
    linkValue = handle?.linkHref() ?? "";
    linkHadHref = linkValue !== "";
    linkOpen = true;
    queueMicrotask(() => linkInput?.focus());
  }

  function normalizeHref(raw: string): string {
    const trimmed = raw.trim();
    if (/^(https?:|mailto:|tel:|\/|#)/i.test(trimmed)) return trimmed;
    return `https://${trimmed}`;
  }

  function applyLink() {
    const trimmed = linkValue.trim();
    handle?.setLink(trimmed ? normalizeHref(trimmed) : null);
    linkOpen = false;
  }

  function removeLink() {
    handle?.setLink(null);
    linkOpen = false;
  }

  function closeLink() {
    linkOpen = false;
    handle?.focus();
  }

  function onLinkKey(event: KeyboardEvent) {
    if (event.key === "Enter") {
      event.preventDefault();
      applyLink();
    } else if (event.key === "Escape") {
      event.preventDefault();
      closeLink();
    }
  }

  function onRawInput(event: Event & { currentTarget: HTMLTextAreaElement }) {
    lastEmitted = event.currentTarget.value;
    onInput?.(event.currentTarget.value);
  }

  const tools: {
    command: EditorCommand | "link";
    icon: NavIconName;
    name: string;
    key: keyof ActiveFormats;
  }[] = [
    { command: "bold", icon: "format-bold", name: msg("Bold"), key: "bold" },
    {
      command: "italic",
      icon: "format-italic",
      name: msg("Italic"),
      key: "italic",
    },
    {
      command: "heading",
      icon: "format-heading",
      name: msg("Heading"),
      key: "heading",
    },
    {
      command: "bullet",
      icon: "list-bullets",
      name: msg("Bulleted list"),
      key: "bullet",
    },
    {
      command: "ordered",
      icon: "list-numbers",
      name: msg("Numbered list"),
      key: "ordered",
    },
    { command: "link", icon: "link", name: msg("Link"), key: "link" },
  ];
</script>

<div
  class="md-editor"
  class:md-editor--sticky={sticky}
  class:md-editor--disabled={disabled}
  data-ready={core ? "true" : "false"}
  style:--md-rows={rows}
>
  <div class="md-editor__bar">
    {#if shown === "visual"}
      <div
        class="md-editor__tools"
        role="toolbar"
        aria-label={t("Formatting", locale)}
      >
        {#each tools as tool (tool.command)}
          <button
            type="button"
            class="md-editor__tool"
            class:md-editor__tool--on={formats?.[tool.key]}
            aria-label={t(tool.name, locale)}
            title={t(tool.name, locale)}
            aria-pressed={formats?.[tool.key] ?? false}
            {disabled}
            onmousedown={(event) => event.preventDefault()}
            onclick={() =>
              tool.command === "link" ? openLink() : run(tool.command)}
          >
            <NavIcon name={tool.icon} />
          </button>
        {/each}
      </div>
    {/if}
    <div class="md-editor__mode">
      <SegmentedControl
        size="compact"
        label={t("Editor mode", locale)}
        value={shown}
        {disabled}
        options={[
          {
            value: "visual",
            label: t("Visual", locale),
            disabled: unsupported || !core,
            title: unsupported
              ? t(
                  "This text uses formatting the visual editor cannot show.",
                  locale,
                )
              : undefined,
          },
          { value: "raw", label: t("Raw", locale) },
        ]}
        onchange={setMode}
      />
    </div>
  </div>

  {#if shown === "visual" && linkOpen}
    <div class="md-editor__link">
      <input
        bind:this={linkInput}
        bind:value={linkValue}
        type="text"
        inputmode="url"
        autocomplete="off"
        aria-label={t("Link address", locale)}
        placeholder="https://"
        onkeydown={onLinkKey}
      />
      <button type="button" class="md-editor__link-ok" onclick={applyLink}
        >{t("Apply", locale)}</button
      >
      {#if linkHadHref}
        <button type="button" onclick={removeLink}
          >{t("Remove link", locale)}</button
        >
      {/if}
      <button type="button" onclick={closeLink}>{t("Cancel", locale)}</button>
    </div>
  {/if}

  {#if shown === "visual"}
    <div class="md-editor__surface" bind:this={host}></div>
  {:else}
    <textarea
      {id}
      use:focusOnHash
      bind:this={rawArea}
      bind:value
      {rows}
      {maxlength}
      {disabled}
      {placeholder}
      {onfocus}
      aria-label={editLabel}
      aria-labelledby={labelledBy}
      aria-describedby={describedBy}
      class="md-editor__raw"
      oninput={onRawInput}></textarea>
    {#if unsupported}
      <small class="md-editor__note"
        >{t(
          "This text uses formatting the visual editor cannot show, so it is edited as Markdown.",
          locale,
        )}</small
      >
    {/if}
  {/if}
</div>

<style>
  .md-editor {
    /* The reader's size and leading (app.css), which follow the text-size setting. */
    --md-size: var(--reading-font-size);
    --md-line-height: var(--reading-line-height);
    display: grid;
    min-width: 0;
    gap: 0.4rem;
    color: var(--ink);
  }

  .md-editor__bar {
    display: flex;
    min-width: 0;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
  }

  .md-editor__tools {
    display: flex;
    min-width: 0;
    flex: 1 1 auto;
    gap: 0.15rem;
    overflow-x: auto;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0.2rem;
    background: var(--surface-raised);
    scrollbar-width: none;
  }

  .md-editor__tools::-webkit-scrollbar {
    display: none;
  }

  .md-editor__tool {
    display: inline-flex;
    width: 2.25rem;
    height: 2.25rem;
    flex: none;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: 999px;
    padding: 0;
    color: var(--ink-muted);
    background: transparent;
    cursor: pointer;
    transition:
      background-color 120ms ease,
      color 120ms ease;
  }

  .md-editor__tool :global(svg) {
    width: var(--ui-action-icon-size-md, 20px);
    height: var(--ui-action-icon-size-md, 20px);
  }

  .md-editor__tool:hover:not(:disabled) {
    color: var(--ink);
    background: var(--surface-highest);
  }

  .md-editor__tool--on,
  .md-editor__tool--on:hover:not(:disabled) {
    color: var(--accent-strong);
    background: var(--accent-wash);
    box-shadow: inset 0 0 0 1px var(--accent-strong);
  }

  .md-editor__mode {
    display: inline-flex;
    flex: none;
    margin-inline-start: auto;
  }

  .md-editor__tool:focus-visible,
  .md-editor__link button:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 1px;
  }

  .md-editor button:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }

  @media (pointer: coarse) {
    .md-editor__tool {
      width: 2.75rem;
      height: 2.75rem;
    }
  }

  .md-editor__link {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.4rem;
  }

  .md-editor__link input {
    min-width: 12rem;
    flex: 1 1 14rem;
  }

  .md-editor__link button {
    min-height: 2.5rem;
    border: 1px solid var(--line);
    border-radius: 999px;
    padding-inline: 0.9rem;
    background: var(--surface-raised);
    font-weight: 700;
    cursor: pointer;
  }

  .md-editor__link .md-editor__link-ok {
    border-color: var(--accent-strong);
    color: var(--accent-strong);
  }

  .md-editor__note {
    color: var(--ink-muted);
  }

  /* The text itself: the size and leading of the reader, so editing looks like reading. */
  .md-editor__raw,
  .md-editor__surface :global(.md-editor__content) {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    border: 1px solid var(--control-border);
    border-radius: var(--ui-input-radius, 12px);
    padding: 0.6rem 0.8rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
    font-size: var(--md-size);
    line-height: var(--md-line-height);
    overflow-wrap: anywhere;
  }

  .md-editor__raw {
    min-height: calc(var(--md-rows, 4) * 1lh + 1.2rem);
    resize: vertical;
    transition: box-shadow 120ms ease;
  }

  .md-editor__surface :global(.md-editor__content) {
    min-height: calc(var(--md-rows, 4) * var(--md-line-height) * 1em + 1.2rem);
    white-space: pre-wrap;
    word-wrap: break-word;
    outline: none;
    transition: box-shadow 120ms ease;
  }

  .md-editor__raw:focus-visible,
  .md-editor__surface :global(.md-editor__content:focus) {
    outline: none;
    border-color: var(--focus);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--focus) 28%, transparent);
  }

  .md-editor__surface
    :global(.md-editor__content--empty > :first-child::before) {
    content: attr(data-placeholder);
    float: left;
    height: 0;
    color: var(--ink-muted);
    pointer-events: none;
  }

  .md-editor__surface :global(.md-editor__content p),
  .md-editor__surface :global(.md-editor__content ul),
  .md-editor__surface :global(.md-editor__content ol),
  .md-editor__surface :global(.md-editor__content blockquote) {
    margin: 0 0 0.7em;
  }

  .md-editor__surface :global(.md-editor__content > :last-child) {
    margin-bottom: 0;
  }

  .md-editor__surface :global(.md-editor__content ul),
  .md-editor__surface :global(.md-editor__content ol) {
    padding-inline-start: 1.5em;
  }

  .md-editor__surface :global(.md-editor__content li > p) {
    margin: 0;
  }

  .md-editor__surface :global(.md-editor__content h1),
  .md-editor__surface :global(.md-editor__content h2),
  .md-editor__surface :global(.md-editor__content h3),
  .md-editor__surface :global(.md-editor__content h4) {
    margin: 0.6em 0 0.3em;
    line-height: 1.3;
  }

  .md-editor__surface :global(.md-editor__content blockquote) {
    border-inline-start: 3px solid var(--line);
    padding-inline-start: 0.8em;
    color: var(--ink-muted);
  }

  .md-editor__surface :global(.md-editor__content a) {
    color: var(--accent-strong);
    text-decoration: underline;
  }

  .md-editor__surface :global(.md-editor__content img) {
    max-width: 100%;
    height: auto;
  }

  .md-editor__surface :global(.md-editor__content code) {
    border-radius: 6px;
    padding: 0.05em 0.3em;
    background: var(--surface-highest);
  }

  /* On a sticky note the editor is part of the paper: no box, ruled lines, the note's ink. */
  .md-editor--sticky {
    --md-line-height: 1.6;
  }

  .md-editor--sticky .md-editor__raw,
  .md-editor--sticky .md-editor__surface :global(.md-editor__content) {
    border: 0;
    padding: 0.25rem 0.4rem;
    color: var(--sticky-ink);
    background: repeating-linear-gradient(
        to bottom,
        transparent 0 calc(var(--md-line-height) * 1em - 1px),
        color-mix(in srgb, var(--sticky-ink) 16%, transparent)
          calc(var(--md-line-height) * 1em - 1px)
          calc(var(--md-line-height) * 1em)
      )
      0 0.25rem / 100% calc(var(--md-line-height) * 1em) local;
  }

  .md-editor--sticky .md-editor__raw:focus-visible,
  .md-editor--sticky .md-editor__surface :global(.md-editor__content:focus) {
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--sticky-ink) 45%, transparent);
  }

  .md-editor--sticky .md-editor__tools {
    border-color: color-mix(in srgb, var(--sticky-ink) 22%, transparent);
    background: color-mix(in srgb, var(--sticky-ink) 7%, transparent);
  }

  .md-editor--sticky .md-editor__tool {
    color: color-mix(in srgb, var(--sticky-ink) 75%, transparent);
  }

  .md-editor--sticky .md-editor__surface :global(a),
  .md-editor--sticky .md-editor__note {
    color: var(--sticky-ink);
  }

  @media (prefers-reduced-motion: reduce) {
    .md-editor__tool,
    .md-editor__raw,
    .md-editor__surface :global(.md-editor__content) {
      transition: none;
    }
  }
</style>
