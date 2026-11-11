<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { auditActionPresentation } from "./audit-action";

  let {
    action,
    locale,
    system = false,
    mediaKind = null,
  }: {
    action: string;
    locale: "en" | "de";
    system?: boolean;
    mediaKind?: "image" | "attachment" | "photo" | null;
  } = $props();
  const presentation = $derived(
    auditActionPresentation(action, { system, mediaKind }),
  );
  const label = $derived(
    presentation.known
      ? t(presentation.messageKey, locale)
      : presentation.label,
  );
</script>

<span
  class="audit-action-badge"
  class:action-badge--update={presentation.icon === "action-update"}
  class:action-badge--create={presentation.icon === "action-create" ||
    presentation.icon === "reset"}
  class:action-badge--upload={presentation.icon === "action-upload"}
  class:action-badge--delete={presentation.icon === "action-delete" ||
    presentation.icon === "action-remove" ||
    presentation.icon === "trash"}
  class:action-badge--unknown={!presentation.known}
>
  <NavIcon name={presentation.icon} />
  <span title={label}>{label}</span>
</span>

<style>
  .audit-action-badge {
    --action-color: var(--ink-muted);

    display: inline-flex;
    max-width: 100%;
    min-width: 0;
    align-items: center;
    gap: 0.4rem;
    border: 1px solid color-mix(in srgb, var(--action-color) 30%, var(--line));
    border-radius: 999px;
    padding: 0.22rem 0.62rem;
    color: var(--action-color);
    background: color-mix(in srgb, var(--action-color) 10%, var(--surface));
    font-size: 0.92rem;
    line-height: 1.35;
  }

  .audit-action-badge > :global(svg) {
    flex: 0 0 1.15rem;
    width: 1.15rem;
    height: 1.15rem;
  }

  .audit-action-badge > span {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .action-badge--create {
    --action-color: var(--success);
  }

  .action-badge--update {
    --action-color: var(--info);
  }

  .action-badge--upload {
    --action-color: var(--warning);
  }

  .action-badge--delete {
    --action-color: var(--danger);
  }

  .action-badge--unknown {
    --action-color: var(--ink-muted);
  }

  @supports not (background: color-mix(in srgb, red, white)) {
    .audit-action-badge {
      background: var(--surface);
    }
  }
</style>
