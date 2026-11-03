<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { displayDomain, parseHttpUrl, stripNewlines } from "./url-field";

  let {
    id,
    value = $bindable(""),
    locale,
    placeholder,
    invalid = false,
    describedby,
    oninput,
    maxlength = 2048,
  }: {
    id: string;
    value?: string;
    locale: "en" | "de";
    placeholder?: string;
    invalid?: boolean;
    /** Ids of external helper or error text, space separated. */
    describedby?: string;
    oninput?: (value: string) => void;
    maxlength?: number;
  } = $props();

  const MAX_LINES = 4;
  let element = $state<HTMLTextAreaElement | null>(null);
  let focused = $state(false);

  const url = $derived(parseHttpUrl(value));
  const domain = $derived(displayDomain(url));
  const showDomain = $derived(!focused && domain !== "");
  const showClear = $derived(focused && value !== "");
  const domainId = $derived(`${id}-domain`);
  const described = $derived(
    [describedby, showDomain ? domainId : undefined]
      .filter(Boolean)
      .join(" ") || undefined,
  );

  function autosize(): void {
    const el = element;
    if (!el) return;
    el.style.height = "auto";
    const style = getComputedStyle(el);
    const lineHeight = parseFloat(style.lineHeight) || 22;
    const chrome =
      parseFloat(style.paddingTop) +
      parseFloat(style.paddingBottom) +
      parseFloat(style.borderTopWidth) +
      parseFloat(style.borderBottomWidth);
    const max = lineHeight * MAX_LINES + chrome;
    el.style.height = `${Math.min(el.scrollHeight, max)}px`;
    el.style.overflowY = el.scrollHeight > max ? "auto" : "hidden";
  }

  $effect(() => {
    void value;
    autosize();
  });

  function onInput(event: Event & { currentTarget: HTMLTextAreaElement }) {
    const clean = stripNewlines(event.currentTarget.value);
    if (clean !== event.currentTarget.value) event.currentTarget.value = clean;
    value = clean;
    oninput?.(clean);
  }

  function onKeydown(event: KeyboardEvent): void {
    // A URL is one line; Enter must neither add a newline nor submit.
    if (event.key === "Enter") event.preventDefault();
  }

  function clear(): void {
    value = "";
    oninput?.("");
    element?.focus();
  }
</script>

<svelte:window onresize={autosize} />

<div class="url-field">
  <div class="url-box">
    <textarea
      {id}
      bind:this={element}
      class="url-input"
      class:has-actions={url !== null || showClear}
      rows="1"
      {maxlength}
      {placeholder}
      inputmode="url"
      autocomplete="url"
      autocapitalize="off"
      spellcheck="false"
      aria-invalid={invalid ? "true" : undefined}
      aria-describedby={described}
      {value}
      oninput={onInput}
      onkeydown={onKeydown}
      onfocus={() => (focused = true)}
      onblur={() => (focused = false)}
    ></textarea>
    <div class="url-actions">
      {#if showClear}
        <!-- Keep focus in the field so the button survives the blur it would cause. -->
        <button
          type="button"
          class="url-action"
          aria-label={t("Clear link", locale)}
          onpointerdown={(event) => event.preventDefault()}
          onclick={clear}
        >
          <NavIcon name="close" />
        </button>
      {/if}
      {#if url}
        <a
          class="url-action"
          href={url.href}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={t("Open link", locale)}
        >
          <NavIcon name="external-link" />
        </a>
      {/if}
    </div>
  </div>
  <!-- Reserve the line while the field has focus, so blurring it (a click on Save
       starts with a blur) never shifts the controls below it out from under the
       pointer. -->
  {#if url}
    <small
      class="url-domain"
      class:url-domain--hidden={!showDomain}
      id={domainId}>{domain}</small
    >
  {/if}
</div>

<style>
  .url-field {
    display: grid;
    min-width: 0;
    gap: 0.25rem;
  }

  .url-box {
    position: relative;
    display: block;
  }

  .url-input {
    display: block;
    width: 100%;
    min-width: 0;
    min-height: var(--ui-input-height);
    resize: none;
    overflow-y: hidden;
    line-height: 1.4;
    word-break: break-all;
    overflow-wrap: anywhere;
  }

  .url-input.has-actions {
    padding-inline-end: 4.9rem;
  }

  .url-actions {
    position: absolute;
    inset-block-start: 0;
    inset-inline-end: 0.25rem;
    display: flex;
    min-height: var(--ui-input-height);
    align-items: center;
  }

  .url-action {
    display: inline-flex;
    width: 2.25rem;
    height: 2.25rem;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: 50%;
    padding: 0;
    color: var(--ink-muted);
    background: transparent;
    font-size: 1.15rem;
    cursor: pointer;
  }

  .url-action:hover {
    color: var(--ink);
    background: var(--surface);
  }

  .url-action:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 1px;
  }

  .url-domain {
    color: var(--ink-muted);
    font-size: 0.84rem;
    font-weight: 500;
  }

  .url-domain--hidden {
    visibility: hidden;
  }
</style>
