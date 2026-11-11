<script lang="ts">
  // The status pill of a project: a check for done, a plain "In progress" otherwise. Android draws
  // the same pill (StatusBadge.kt); keep labels and the done-only rule on cards in step.
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";

  interface Props {
    status: "in_progress" | "done";
    locale: "en" | "de";
    class?: string;
  }

  let { status, locale, class: className = "" }: Props = $props();
</script>

<span
  class={`status-badge status-badge--${status} ${className}`}
  data-testid="project-status-badge"
  data-status={status}
>
  {#if status === "done"}<NavIcon name="check" />{/if}
  {status === "done" ? t("Done", locale) : t("Project in progress", locale)}
</span>

<style>
  .status-badge {
    display: inline-flex;
    max-width: 100%;
    align-items: center;
    gap: 0.25rem;
    border-radius: 999px;
    padding: 0.1rem 0.6rem;
    color: var(--ink-muted);
    background: var(--surface-raised);
    font-size: 0.78rem;
    font-weight: 650;
    line-height: 1.4;
    white-space: nowrap;
  }

  .status-badge--done {
    color: var(--on-primary-container);
    background: var(--primary-container);
  }

  .status-badge :global(svg) {
    width: 0.9em;
    height: 0.9em;
    flex: none;
  }
</style>
