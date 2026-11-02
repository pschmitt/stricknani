<script lang="ts">
  import { resolve } from "$app/paths";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import NavIcon from "#lib/components/NavIcon.svelte";
  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head><title>Stricknani · {t("Create", locale)}</title></svelte:head>

<section class="create-page" aria-labelledby="create-title">
  <p class="section-mark">{t("Your workspace", locale)}</p>
  <h1 id="create-title">{t("What would you like to add?", locale)}</h1>
  <p class="create-intro">
    {t("Choose a project or add yarn to your inventory.", locale)}
  </p>
  <div class="create-options">
    <a href={resolve("/projects/new")}>
      <span class="option-mark" aria-hidden="true"
        ><NavIcon name="projects" /></span
      >
      <span
        ><strong>{t("New project", locale)}</strong><small
          >{t("Plan the next thing you want to knit.", locale)}</small
        ></span
      >
    </a>
    <a href={resolve("/yarns/new")}>
      <span class="option-mark" aria-hidden="true"
        ><NavIcon name="yarns" /></span
      >
      <span
        ><strong>{t("New yarn", locale)}</strong><small
          >{t("Keep a note of a yarn you own.", locale)}</small
        ></span
      >
    </a>
  </div>
</section>

<style>
  .create-page {
    max-width: 46rem;
    border: 1px solid var(--line);
    border-radius: 1.1rem;
    padding: clamp(1.4rem, 4vw, 2.6rem);
    background: var(--surface);
  }

  h1 {
    margin: 0.45rem 0 0.65rem;
    font-size: clamp(2rem, 6vw, 3rem);
    font-weight: 680;
    letter-spacing: -0.06em;
    line-height: 1.08;
  }

  .create-intro {
    color: var(--ink-muted);
  }

  .create-options {
    display: grid;
    gap: 0.7rem;
    margin-top: 1.8rem;
  }

  .create-options a {
    display: flex;
    min-height: 5rem;
    align-items: center;
    gap: 0.9rem;
    border: 1px solid var(--line);
    border-radius: 0.8rem;
    padding: 0.8rem;
    text-decoration: none;
    transition:
      border-color 150ms ease,
      background-color 150ms ease;
  }

  .create-options a:hover {
    border-color: var(--accent);
    background: var(--surface-raised);
  }

  .option-mark {
    display: grid;
    width: 2.3rem;
    flex: 0 0 auto;
    aspect-ratio: 1;
    place-items: center;
    border-radius: 0.65rem;
    color: var(--accent-strong);
    background: var(--accent-wash);
  }

  .option-mark :global(svg) {
    width: 1.4rem;
    height: 1.4rem;
  }

  .create-options strong,
  .create-options small {
    display: block;
  }

  .create-options small {
    margin-top: 0.15rem;
    color: var(--ink-muted);
    font-size: 0.88rem;
  }
</style>
