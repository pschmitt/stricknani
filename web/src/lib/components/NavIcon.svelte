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
    | "audit"
    | "user-add"
    | "user-enable"
    | "user-disable"
    | "key"
    | "filter"
    | "refresh"
    | "previous"
    | "next"
    | "action-create"
    | "action-update"
    | "action-delete"
    | "action-upload"
    | "action-remove"
    | "action-default";

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
  {:else if name === "audit"}
    <path d="M5 5.5h14M5 12h14M5 18.5h14" />
    <circle cx="3.5" cy="5.5" r=".5" />
    <circle cx="3.5" cy="12" r=".5" />
    <circle cx="3.5" cy="18.5" r=".5" />
  {:else if name === "user-add"}
    <circle cx="9" cy="8" r="3" />
    <path
      d="M3.5 20c.4-3.1 2.4-4.8 5.5-4.8 1.7 0 3.1.5 4.1 1.4M18 13v7M14.5 16.5h7"
    />
  {:else if name === "user-enable"}
    <circle cx="9" cy="8" r="3" />
    <path
      d="M3.5 20c.4-3.1 2.4-4.8 5.5-4.8 1.7 0 3.1.5 4.1 1.4m1.4 2.1 1.8 1.8 4.2-4.2"
    />
  {:else if name === "user-disable"}
    <circle cx="9" cy="8" r="3" />
    <path
      d="M3.5 20c.4-3.1 2.4-4.8 5.5-4.8 1.7 0 3.1.5 4.1 1.4M15 16l6 6m0-6-6 6"
    />
  {:else if name === "key"}
    <circle cx="8" cy="15.5" r="4" />
    <path d="m11 12.5 8-8 2 2-2 2 1.5 1.5-2 2-1.5-1.5-3.5 3.5" />
  {:else if name === "filter"}
    <path d="M4 5h16l-6.2 7.2v5.2l-3.6 1.8v-7z" />
  {:else if name === "refresh"}
    <path
      d="M20 11a8 8 0 0 0-14.2-4L4 9m0-5v5h5M4 13a8 8 0 0 0 14.2 4L20 15m0 5v-5h-5"
    />
  {:else if name === "previous"}
    <path d="m15 5-7 7 7 7" />
  {:else if name === "next"}
    <path d="m9 5 7 7-7 7" />
  {:else if name === "action-create"}
    <path d="M5 5.5h10l4 4v9H5zM15 5.5v4h4M8 13h8M8 16h5" />
  {:else if name === "action-update"}
    <path d="m4.5 16.5-.8 4 4-.8 11-11-3.2-3.2zM13.8 6.2l3.2 3.2" />
  {:else if name === "action-delete"}
    <path d="M4.5 7h15M9 7V4.5h6V7m2 0-.7 12H7.7L7 7m3 3v6m4-6v6" />
  {:else if name === "action-upload"}
    <path d="M5 14v5h14v-5M12 16V4m-4 4 4-4 4 4" />
  {:else if name === "action-remove"}
    <path d="M5 12h14" />
  {:else}
    <path d="M5 4.5h14v15H5zM8 8h8M8 12h8M8 16h5" />
  {/if}
</svg>
