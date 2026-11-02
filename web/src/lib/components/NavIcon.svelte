<script lang="ts">
  type IconName =
    | "projects"
    | "yarns"
    | "create"
    | "more"
    | "imports"
    | "categories"
    | "gauge"
    | "account"
    | "admin"
    | "audit";

  let { name }: { name: IconName } = $props();
</script>

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
  {#if name === "projects"}
    <path d="M4.5 5.5h10l5 5v8h-15z" />
    <path d="M14.5 5.5v5h5M8 14h8M8 17h6" />
  {:else if name === "yarns"}
    <path d="M6 5.5c2-2 10-2 12 0s2 11 0 13-10 2-12 0-2-11 0-13Z" />
    <path d="M7 8c3 1 7 7 10 8M17 8c-3 1-7 7-10 8M8 12h8" />
  {:else if name === "create"}
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 8v8M8 12h8" />
  {:else if name === "more"}
    <circle cx="5" cy="12" r="1" />
    <circle cx="12" cy="12" r="1" />
    <circle cx="19" cy="12" r="1" />
  {:else if name === "imports"}
    <path d="M5 14v4.5h14V14M12 3.5v10M8.5 10l3.5 3.5 3.5-3.5" />
  {:else if name === "categories"}
    <path d="M4.5 5.5h9l6 6-8 8-7-7z" />
    <circle cx="9" cy="10" r="1" />
  {:else if name === "gauge"}
    <path d="M4 19.5 19.5 4M7 4.5l2 2M4.5 7l2 2M12 16l2 2M15 13l2 2" />
    <path d="M14.5 4.5h5v5" />
  {:else if name === "account"}
    <circle cx="12" cy="8" r="3.25" />
    <path d="M5.5 20c.6-3.2 3-5 6.5-5s5.9 1.8 6.5 5" />
  {:else if name === "admin"}
    <path d="M12 3.5 19 6v5.2c0 4.1-2.8 7.3-7 9.3-4.2-2-7-5.2-7-9.3V6z" />
    <path d="m9 12 2 2 4-4" />
  {:else}
    <path d="M5 5.5h14M5 12h14M5 18.5h14" />
    <circle cx="3.5" cy="5.5" r=".5" />
    <circle cx="3.5" cy="12" r=".5" />
    <circle cx="3.5" cy="18.5" r=".5" />
  {/if}
</svg>
