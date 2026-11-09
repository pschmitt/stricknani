<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { onMount } from "svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import {
    importPrefillQuery,
    prefillUrlFromSearch,
  } from "#lib/features/imports/shared-link.ts";

  // There is one way to add things: the editor. Old links to this page (and
  // the share target) land there, with the link kept.
  const locale = $derived(session.data?.locale ?? "en");
  const editor = resolve("/projects/new");

  onMount(() => {
    const link = prefillUrlFromSearch(window.location.search);
    const start = new URLSearchParams(window.location.search).get("start");
    const query = link
      ? `${importPrefillQuery(link)}${start === "1" ? "&start=1" : ""}`
      : "";
    void goto(`${editor}${query}`, { replaceState: true });
  });
</script>

<svelte:head><title>Stricknani</title></svelte:head>

<p class="page-container page-container--compact">
  <a href={editor}>{t("Continue", locale)}</a>
</p>
