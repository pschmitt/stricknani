<script lang="ts">
  export type NavIconName =
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
    | "favorite"
    | "refresh"
    | "previous"
    | "next"
    | "action-create"
    | "action-update"
    | "action-delete"
    | "action-upload"
    | "action-remove"
    | "action-default"
    | "clipboard"
    | "upload"
    | "download"
    | "link"
    | "preview"
    | "save"
    | "cancel"
    | "copy"
    | "check"
    | "close"
    | "sign-in"
    | "sign-out"
    | "external-link"
    | "move-up"
    | "move-down"
    | "expand"
    | "collapse"
    | "print"
    | "reset"
    | "search";

  let { name }: { name: NavIconName } = $props();
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
  {:else if name === "favorite"}
    <path
      d="M20.8 8.8c0 5.7-8.8 10.3-8.8 10.3S3.2 14.5 3.2 8.8a4.6 4.6 0 0 1 8.8-2.1 4.6 4.6 0 0 1 8.8 2.1Z"
    />
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
  {:else if name === "clipboard"}
    <rect x="6.5" y="5" width="11" height="15" rx="1.5" />
    <path d="M9.5 5V3.5h5V5M9 10h6M9 13.5h6M9 17h4" />
  {:else if name === "upload"}
    <path d="M5 15v4h14v-4M12 15V4m-4 4 4-4 4 4" />
  {:else if name === "download"}
    <path d="M5 14v5h14v-5M12 4v11m-4-4 4 4 4-4" />
  {:else if name === "link"}
    <path
      d="M10 13a5 5 0 0 0 7.1 0l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1M14 11a5 5 0 0 0-7.1 0l-2 2a5 5 0 0 0 7.1 7.1l1.1-1.1"
    />
  {:else if name === "preview"}
    <path d="M2.5 12s3.4-6 9.5-6 9.5 6 9.5 6-3.4 6-9.5 6-9.5-6-9.5-6Z" />
    <circle cx="12" cy="12" r="2.5" />
  {:else if name === "save"}
    <path d="M4.5 4.5h12l3 3v12h-15zM8 4.5v5h8v-5M8 19.5v-7h8v7" />
  {:else if name === "cancel"}
    <circle cx="12" cy="12" r="8.5" />
    <path d="m9 9 6 6m0-6-6 6" />
  {:else if name === "copy"}
    <rect x="8" y="8" width="12" height="12" rx="1.5" />
    <path
      d="M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8"
    />
  {:else if name === "check"}
    <path d="m4.5 12.5 5 5 10-11" />
  {:else if name === "close"}
    <path d="m6 6 12 12M18 6 6 18" />
  {:else if name === "sign-in"}
    <path d="M11 4.5h8v15h-8M3 12h11m-4-4 4 4-4 4" />
  {:else if name === "sign-out"}
    <path d="M13 4.5H5v15h8M21 12H10m4-4-4 4 4 4" />
  {:else if name === "external-link"}
    <path d="M13 5h6v6M19 5l-9 9" />
    <path d="M17 13v5.5a1 1 0 0 1-1 1H5.5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1H11" />
  {:else if name === "move-up"}
    <path d="M12 20V4m-6 6 6-6 6 6" />
  {:else if name === "move-down"}
    <path d="M12 4v16m6-6-6 6-6-6" />
  {:else if name === "expand"}
    <path d="m5 9 7 7 7-7" />
  {:else if name === "collapse"}
    <path d="m5 15 7-7 7 7" />
  {:else if name === "print"}
    <path
      d="M7 8V4.5h10V8M7 17H5a1.5 1.5 0 0 1-1.5-1.5v-5A1.5 1.5 0 0 1 5 9h14a1.5 1.5 0 0 1 1.5 1.5v5A1.5 1.5 0 0 1 19 17h-2"
    />
    <path d="M7 14h10v5.5H7zM17 11.5h.01" />
  {:else if name === "reset"}
    <path d="M4 9V4m0 0h5M4 4l3.2 3.2A8 8 0 1 1 4 13" />
  {:else if name === "search"}
    <circle cx="10.8" cy="10.8" r="6.8" />
    <path d="m16 16 4.5 4.5" />
  {:else}
    <path d="M5 4.5h14v15H5zM8 8h8M8 12h8M8 16h5" />
  {/if}
</svg>

<style>
  svg {
    display: block;
    width: 1em;
    height: 1em;
    flex: 0 0 1em;
  }
</style>
