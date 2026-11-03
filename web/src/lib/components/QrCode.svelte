<script lang="ts">
  import { qrSvg } from "#lib/qr-code.ts";

  let {
    value,
    label,
  }: {
    value: string;
    /** Accessible name; the code itself is not readable by assistive technology. */
    label: string;
  } = $props();

  const code = $derived(qrSvg(value));
</script>

<svg
  class="qr-code"
  role="img"
  aria-label={label}
  viewBox={`0 0 ${code.size} ${code.size}`}
  shape-rendering="crispEdges"
>
  <rect width={code.size} height={code.size} class="qr-code__background" />
  <path d={code.path} class="qr-code__modules" />
</svg>

<style>
  .qr-code {
    display: block;
    width: 100%;
    height: auto;
    border-radius: var(--radius-xs);
  }

  .qr-code__background {
    fill: var(--surface);
  }

  .qr-code__modules {
    fill: var(--ink);
  }
</style>
