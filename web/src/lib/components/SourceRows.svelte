<script lang="ts">
  import type { Snippet } from "svelte";
  import SourceLink from "./SourceLink.svelte";

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
     nothing when there is no link. With an archive control, the page passes the archived copy to
     the control instead, which shows it with its action on the same line. -->
{#if href || archiveHref}
  <dl class="source-rows" style:--source-tone={`var(--tone-${tone})`}>
    <div class="source-row">
      <dt>{label}</dt>
      <dd>
        {#if href}
          <SourceLink {href} icon="notebook" label={openLabel} />
        {/if}
        {#if archiveHref}
          <SourceLink href={archiveHref} icon="history" label={archiveLabel} />
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
</style>
