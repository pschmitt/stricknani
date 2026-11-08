<script lang="ts">
  import type { Snippet } from "svelte";
  import NavIcon from "./NavIcon.svelte";

  type Tone = "projects" | "yarns";

  let {
    tone = "projects",
    label,
    href,
    archiveHref,
    openLabel,
    archiveLabel,
    children,
  }: {
    tone?: Tone;
    label: string;
    href: string | null;
    archiveHref: string | null;
    openLabel: string;
    archiveLabel: string;
    children?: Snippet;
  } = $props();
</script>

<!-- The source row of the details card on project and yarn pages: original link, archived copy
     and the archive control (passed as children), so both pages cannot drift apart. Renders
     nothing when there is no link. -->
{#if href || archiveHref}
  <dl class="source-rows" style:--source-tone={`var(--tone-${tone})`}>
    <div class="source-row">
      <dt>{label}</dt>
      <dd>
        {#if href}
          <a
            class="source-link"
            {href}
            target="_blank"
            rel="noopener noreferrer"
          >
            <span class="source-link__icon" aria-hidden="true"
              ><NavIcon name="notebook" /></span
            >
            {openLabel} ↗
          </a>
        {/if}
        {#if archiveHref}
          <a
            class="source-link"
            href={archiveHref}
            target="_blank"
            rel="noopener noreferrer"
          >
            <span class="source-link__icon" aria-hidden="true"
              ><NavIcon name="history" /></span
            >
            {archiveLabel} ↗
          </a>
        {/if}
        {@render children?.()}
      </dd>
    </div>
  </dl>
{/if}

<style>
  .source-rows {
    display: grid;
    min-width: 0;
    margin: 0;
  }

  .source-row {
    display: grid;
    min-width: 0;
    gap: 0.2rem;
    border-top: 1px solid var(--line);
    margin-top: 0.65rem;
    padding-top: 0.65rem;
  }

  .source-row dt {
    color: var(--ink-muted);
    font-size: 0.78rem;
    font-weight: 650;
  }

  .source-row dd {
    display: grid;
    min-width: 0;
    gap: 0.2rem;
    margin: 0;
  }

  .source-link {
    display: inline-flex;
    min-height: 44px;
    width: fit-content;
    align-items: center;
    gap: 0.55rem;
    color: var(--accent-strong);
    font-weight: 640;
    overflow-wrap: anywhere;
  }

  .source-link__icon {
    display: inline-grid;
    width: 1.8rem;
    height: 1.8rem;
    flex: none;
    place-items: center;
    border-radius: 999px;
    color: var(--source-tone);
    background: color-mix(in srgb, var(--source-tone) 16%, transparent);
  }

  .source-link__icon :global(svg) {
    width: 1rem;
    height: 1rem;
  }
</style>
