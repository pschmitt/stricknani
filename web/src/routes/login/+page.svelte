<script lang="ts">
  import { resolve } from "$app/paths";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("Sign in", locale)}</title></svelte:head>

<section class="notice-panel" aria-labelledby="signed-in-title">
  <p class="section-mark">Stricknani</p>
  <h1 id="signed-in-title">{t("You are already signed in.", locale)}</h1>
  <p>{t("Open your project library to continue.", locale)}</p>
  <a class="inline-link" href={resolve("/projects")}
    >{t("Open projects", locale)}</a
  >
</section>
