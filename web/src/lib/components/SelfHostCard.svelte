<script lang="ts">
  import Button from "#lib/components/Button.svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { REPOSITORY_URL, SETUP_PROMPT } from "#lib/setup-prompt.ts";

  let { locale }: { locale: "en" | "de" } = $props();

  let copied = $state(false);
  let copyFailed = $state(false);
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function copyPrompt(): Promise<void> {
    clearTimeout(timer);
    copyFailed = false;
    try {
      await navigator.clipboard.writeText(SETUP_PROMPT);
      copied = true;
    } catch {
      copied = false;
      copyFailed = true;
    }
    timer = setTimeout(() => {
      copied = false;
      copyFailed = false;
    }, 2400);
  }
</script>

<aside class="selfhost" aria-labelledby="selfhost-title">
  <p class="selfhost__lead" id="selfhost-title">
    <span class="selfhost__yarn" aria-hidden="true"><Mascot size={40} /></span>
    <span>
      {t(
        "Registrations are closed here, but you can set up your own private Stricknani!",
        locale,
      )}
    </span>
  </p>
  <a
    class="selfhost__link"
    href={REPOSITORY_URL}
    target="_blank"
    rel="noopener noreferrer">{t("Stricknani on GitHub", locale)}</a
  >
  <details class="selfhost__agent">
    <summary>{t("Set it up with an AI coding agent", locale)}</summary>
    <p class="selfhost__hint">
      {t(
        "Paste this into Claude Code, Codex or opencode. The prompt is in English.",
        locale,
      )}
    </p>
    <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
    <pre class="selfhost__prompt" tabindex="0"><code>{SETUP_PROMPT}</code></pre>
    <div class="selfhost__copy">
      <Button variant="secondary" size="sm" icon="copy" onclick={copyPrompt}>
        {t("Copy prompt", locale)}
      </Button>
      <span class="selfhost__status" role="status">
        {#if copied}{t("Copied.", locale)}{:else if copyFailed}{t(
            "Copy failed. Select the text instead.",
            locale,
          )}{/if}
      </span>
    </div>
  </details>
</aside>

<style>
  .selfhost {
    margin-top: 1.1rem;
    padding: 1rem 1.15rem;
    border: 1.5px dashed color-mix(in srgb, var(--accent) 45%, transparent);
    border-radius: var(--radius-md, 16px);
    background: color-mix(in srgb, var(--accent) 6%, var(--surface));
    color: var(--ink);
    font-size: 0.92rem;
  }

  .selfhost__lead {
    display: flex;
    gap: 0.6rem;
    align-items: flex-start;
    margin: 0;
    font-weight: 650;
    line-height: 1.35;
  }

  .selfhost__yarn {
    flex: none;
    line-height: 0;
  }

  .selfhost__link {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    color: var(--accent-strong);
    font-weight: 650;
  }

  .selfhost__agent summary {
    display: flex;
    min-height: 44px;
    align-items: center;
    color: var(--accent-strong);
    cursor: pointer;
    font-weight: 650;
  }

  .selfhost__agent summary::after {
    width: 0.45rem;
    height: 0.45rem;
    margin: -0.2rem 0 0 0.6rem;
    border-right: 2px solid currentColor;
    border-bottom: 2px solid currentColor;
    content: "";
    transform: rotate(45deg);
    transition: transform 160ms ease;
  }

  .selfhost__agent[open] summary::after {
    margin-top: 0.15rem;
    transform: rotate(225deg);
  }

  @media (prefers-reduced-motion: reduce) {
    .selfhost__agent summary::after {
      transition: none;
    }
  }

  .selfhost__hint {
    margin: 0 0 0.6rem;
    color: var(--ink-muted);
  }

  .selfhost__prompt {
    max-height: 14rem;
    margin: 0;
    padding: 0.75rem 0.85rem;
    overflow: auto;
    border: 1px solid var(--card-border);
    border-radius: var(--radius-xs);
    background: var(--surface);
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: 0.78rem;
    line-height: 1.45;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  .selfhost__copy {
    display: flex;
    flex-wrap: wrap;
    gap: 0.6rem;
    align-items: center;
    margin-top: 0.65rem;
  }

  .selfhost__status {
    color: var(--ink-muted);
    font-size: 0.85rem;
  }
</style>
