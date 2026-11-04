<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { onMount } from "svelte";
  import Button from "#lib/components/Button.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { importAvailability } from "#lib/features/imports/import-availability.ts";
  import {
    importPrefillQuery,
    sharedUrlFromSearch,
  } from "#lib/features/imports/shared-link.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  const availability = $derived(importAvailability(session.data?.capabilities));

  // The Web Share Target request is a plain GET. Read it in the browser: the
  // page is prerendered, where the query string does not exist.
  let ready = $state(false);
  let link = $state<string | null>(null);

  onMount(() => {
    link = sharedUrlFromSearch(window.location.search);
    ready = true;
  });

  const host = $derived.by(() => {
    if (!link) return "";
    try {
      return new URL(link).hostname.replace(/^www\./, "");
    } catch {
      return link;
    }
  });

  // Choosing only opens the import page with the link filled in; nothing is
  // imported until the user confirms there.
  function choose(kind: "project" | "yarn"): void {
    if (!link) return;
    const path =
      kind === "project" ? resolve("/imports") : resolve("/imports/yarn");
    void goto(`${path}${importPrefillQuery(link)}`, { replaceState: true });
  }
</script>

<svelte:head
  ><title>Stricknani · {t("Import a link", locale)}</title></svelte:head
>

<section
  class="share-page page-container page-container--compact"
  aria-labelledby="share-title"
  data-testid="share-page"
>
  {#if ready && link && !availability.url && session.data}
    <PageHeader title={t("Link import is off", locale)} titleId="share-title" />
    <p>{t("Link imports are turned off on this server.", locale)}</p>
    <p class="share-link" data-testid="share-link">{link}</p>
  {:else if ready && link}
    <PageHeader
      title={t("Import this link as a project or as a yarn?", locale)}
      titleId="share-title"
    />
    <p class="share-host" data-testid="share-host">{host}</p>
    <p class="share-link" data-testid="share-link">{link}</p>
    <div class="share-actions">
      <Button
        variant="primary"
        icon="projects"
        data-testid="share-project"
        onclick={() => choose("project")}
      >
        {t("Project", locale)}
      </Button>
      <Button
        variant="secondary"
        icon="yarns"
        data-testid="share-yarn"
        onclick={() => choose("yarn")}
      >
        {t("Yarn", locale)}
      </Button>
    </div>
  {:else if ready}
    <PageHeader title={t("No link found", locale)} titleId="share-title" />
    <p>
      <a href={resolve("/create")}>{t("Create", locale)}</a>
    </p>
  {/if}
</section>

<style>
  .share-page {
    display: grid;
    gap: 0.75rem;
    align-content: start;
  }

  .share-host {
    margin: 0;
    font-weight: 650;
  }

  .share-link {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
    overflow-wrap: anywhere;
  }

  .share-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    margin-top: 0.5rem;
  }
</style>
