<script lang="ts">
  import type { AuditClientKind } from "./audit-client";

  let { kind }: { kind: AuditClientKind } = $props();
</script>

<!-- Hand-drawn strokes in the style of NavIcon: globe, android head, terminal, cog, question mark. -->
<svg
  aria-hidden="true"
  focusable="false"
  viewBox="0 0 24 24"
  fill="none"
  stroke="currentColor"
  stroke-width="1.8"
  stroke-linecap="round"
  stroke-linejoin="round"
>
  {#if kind === "web"}
    <circle cx="12" cy="12" r="9" />
    <path
      d="M3 12h18M12 3c2.6 2.4 3.9 5.4 3.9 9S14.6 18.6 12 21c-2.6-2.4-3.9-5.4-3.9-9S9.4 5.4 12 3Z"
    />
  {:else if kind === "android"}
    <path d="M5 17a7 7 0 0 1 14 0v1H5ZM8 5l1.6 2.4M16 5l-1.6 2.4" />
    <path d="M9.5 13h.01M14.5 13h.01" />
  {:else if kind === "api"}
    <rect x="3" y="5" width="18" height="14" rx="2" />
    <path d="m7 10 3 2.5L7 15M12.5 15H17" />
  {:else if kind === "system"}
    <circle cx="12" cy="12" r="3" />
    <path
      d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"
    />
  {:else}
    <circle cx="12" cy="12" r="9" />
    <path d="M9.5 9.5a2.6 2.6 0 1 1 3.7 2.3c-.8.4-1.2 1-1.2 1.7M12 17h.01" />
  {/if}
</svg>

<style>
  svg {
    width: 1em;
    height: 1em;
    flex: none;
  }
</style>
