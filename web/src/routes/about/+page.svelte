<script lang="ts">
  import { onMount } from "svelte";
  import { resolve } from "$app/paths";
  import { androidAction } from "#lib/app-links.ts";
  import { appVersion } from "#lib/app-version.ts";
  import {
    appLicence,
    credits,
    displayVersion,
    emptyServerBuild,
    fetchServerBuild,
    issuesUrl,
    shortBuildId,
    sourceUrl,
    sponsorUrl,
    versionReport,
    type ServerBuild,
  } from "#lib/about.ts";
  import Button from "#lib/components/Button.svelte";
  import Mascot from "#lib/components/Mascot.svelte";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  const signedIn = $derived(session.data?.user != null);
  const androidApp = androidAction();

  let server = $state<ServerBuild>(emptyServerBuild);
  let copyStatus = $state<"idle" | "copied" | "blocked">("idle");
  let copyTimer: ReturnType<typeof setTimeout> | undefined;

  const version = $derived(displayVersion(server));
  const buildId = $derived(shortBuildId(server.buildId));

  onMount(() => {
    const controller = new AbortController();
    fetchServerBuild(controller.signal).then(
      (build) => (server = build),
      () => {},
    );
    return () => {
      controller.abort();
      clearTimeout(copyTimer);
    };
  });

  async function copyVersion() {
    try {
      await navigator.clipboard.writeText(versionReport(server));
      copyStatus = "copied";
    } catch {
      copyStatus = "blocked";
    }
    clearTimeout(copyTimer);
    copyTimer = setTimeout(() => (copyStatus = "idle"), 4000);
  }

  interface LinkRow {
    icon: NavIconName;
    tone: "code" | "heart" | "app" | "privacy" | "bug";
    label: string;
    description: string;
    href: string;
    external: boolean;
  }

  const links: LinkRow[] = $derived([
    {
      icon: "code",
      tone: "code",
      label: t("Source code on GitHub", locale),
      description: t("Read the code, follow along or contribute.", locale),
      href: sourceUrl,
      external: true,
    },
    {
      icon: "heart",
      tone: "heart",
      label: t("Sponsor on GitHub", locale),
      description: t("Like Stricknani? Support its development.", locale),
      href: sponsorUrl,
      external: true,
    },
    {
      icon: "download",
      tone: "app",
      label: t("Get the Android app", locale),
      description: t(
        "Knit offline with the Stricknani app for Android.",
        locale,
      ),
      href: androidApp.url,
      external: true,
    },
    {
      icon: "help",
      tone: "privacy",
      label: t("Privacy Policy", locale),
      description: t("How Stricknani handles your data.", locale),
      href: resolve("/privacy"),
      external: false,
    },
    {
      icon: "flag",
      tone: "bug",
      label: t("Report a problem", locale),
      description: t("Found a bug or have an idea? Tell us on GitHub.", locale),
      href: issuesUrl,
      external: true,
    },
  ]);
</script>

<svelte:head>
  <title>{t("About", locale)} · Stricknani</title>
</svelte:head>

<article class="about" aria-labelledby="about-title">
  {#if !signedIn}
    <a class="inline-link back-link" href={resolve("/login")}>
      {t("Back to sign in", locale)}
    </a>
  {/if}

  <header class="hero">
    <Mascot size={120} bob />
    <h1 id="about-title">Stricknani</h1>
    <p class="tagline">
      {t("Offline-first knitting projects and yarn stash.", locale)}
    </p>
  </header>

  <section class="card" aria-labelledby="version-title">
    <header class="card__header">
      <span class="card__icon card__icon--version" aria-hidden="true"
        ><NavIcon name="info" /></span
      >
      <h2 id="version-title">{t("Version", locale)}</h2>
    </header>
    <dl class="facts">
      <div class="fact">
        <dt>{t("App version", locale)}</dt>
        <dd>{version}</dd>
      </div>
      {#if buildId}
        <div class="fact">
          <dt>{t("Build", locale)}</dt>
          <dd>{buildId}</dd>
        </div>
      {/if}
      <div class="fact">
        <dt>{t("Web app", locale)}</dt>
        <dd>{appVersion}</dd>
      </div>
    </dl>
    <div class="copy-row">
      <Button
        variant="secondary"
        icon="copy"
        onclick={() => void copyVersion()}
      >
        {t("Copy", locale)}
      </Button>
      <p class="copy-status" role="status">
        {#if copyStatus === "copied"}
          {t("Copied. Paste it into your bug report.", locale)}
        {:else if copyStatus === "blocked"}
          {t(
            "Copy was blocked. Select the version and copy it manually.",
            locale,
          )}
        {/if}
      </p>
    </div>
  </section>

  <section class="card" aria-labelledby="links-title">
    <h2 id="links-title" class="visually-hidden">
      {t("Links", locale)}
    </h2>
    <ul class="rows">
      {#each links as link (link.href)}
        <li>
          <a
            class="link-row"
            href={link.href}
            target={link.external ? "_blank" : undefined}
            rel={link.external ? "noopener noreferrer" : undefined}
          >
            <span class="chip chip--{link.tone}" aria-hidden="true"
              ><NavIcon name={link.icon} /></span
            >
            <span class="link-row__text">
              <span class="link-row__label">{link.label}</span>
              <span class="link-row__hint">{link.description}</span>
            </span>
            {#if link.external}
              <span class="link-row__go" aria-hidden="true"
                ><NavIcon name="external-link" /></span
              >
              <span class="visually-hidden"
                >{t("(opens in a new tab)", locale)}</span
              >
            {:else}
              <span class="link-row__go" aria-hidden="true"
                ><NavIcon name="next" /></span
              >
            {/if}
          </a>
        </li>
      {/each}
    </ul>
  </section>

  <section class="card" aria-labelledby="credits-title">
    <header class="card__header">
      <span class="card__icon card__icon--credits" aria-hidden="true"
        ><NavIcon name="heart" /></span
      >
      <h2 id="credits-title">{t("Credits and licences", locale)}</h2>
    </header>
    <p class="card__intro">
      {t("Stricknani is free software, licensed under the", locale)}
      <a href={appLicence.url} target="_blank" rel="noopener noreferrer"
        >{appLicence.name}</a
      >.
    </p>
    <ul class="credits">
      {#each credits as credit (credit.name)}
        <li>
          <a href={credit.url} target="_blank" rel="noopener noreferrer"
            >{credit.name}</a
          >
          <span class="credit-detail"
            >{t(credit.detail, locale)}, {credit.licence}</span
          >
        </li>
      {/each}
    </ul>
  </section>

  <p class="footer-line">{t("Made with wool and love.", locale)}</p>
</article>

<style>
  .about {
    display: grid;
    width: 100%;
    max-width: 44rem;
    margin-inline: auto;
    gap: 1rem;
  }

  .back-link {
    justify-self: start;
  }

  .hero {
    display: grid;
    justify-items: center;
    gap: 0.35rem;
    padding-block: 1rem 0.75rem;
    text-align: center;
  }

  .hero h1 {
    margin: 0.5rem 0 0;
    font-family: var(--font-display);
    font-size: clamp(2.25rem, 8vw, 3.25rem);
    font-weight: 700;
    letter-spacing: -0.03em;
    line-height: 1.05;
  }

  .tagline {
    max-width: 28ch;
    margin: 0;
    color: var(--ink-muted);
    font-size: 1.1rem;
    line-height: 1.45;
    text-wrap: balance;
  }

  .card {
    display: grid;
    min-width: 0;
    gap: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    padding: 1rem;
    background: var(--surface);
  }

  .card__header {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.75rem;
  }

  h2 {
    margin: 0;
    font-size: 1.25rem;
    font-weight: 660;
    letter-spacing: -0.025em;
  }

  .card__icon,
  .chip {
    display: inline-flex;
    width: 2.5rem;
    height: 2.5rem;
    flex: none;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    font-size: 1.35rem;
  }

  .card__icon--version,
  .chip--code {
    color: var(--tone-categories);
    background: var(--tone-categories-wash);
  }

  .card__icon--credits,
  .chip--heart {
    color: var(--tone-home);
    background: var(--tone-home-wash);
  }

  .chip--app {
    color: var(--tone-gauge);
    background: var(--tone-gauge-wash);
  }

  .chip--privacy {
    color: var(--tone-audit);
    background: var(--tone-audit-wash);
  }

  .chip--bug {
    color: var(--tone-more);
    background: var(--tone-more-wash);
  }

  .chip :global(svg),
  .card__icon :global(svg) {
    width: 1.2em;
    height: 1.2em;
  }

  .facts {
    display: grid;
    margin: 0;
  }

  .fact {
    display: flex;
    min-width: 0;
    justify-content: space-between;
    gap: 1rem;
    border-top: 1px solid var(--line);
    padding-block: 0.65rem;
  }

  .fact:first-child {
    border-top: 0;
    padding-top: 0;
  }

  .fact dt {
    color: var(--ink-muted);
  }

  .fact dd {
    margin: 0;
    font-variant-numeric: tabular-nums;
    font-weight: 700;
    overflow-wrap: anywhere;
    user-select: all;
  }

  .copy-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.75rem;
  }

  .copy-status {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .rows {
    display: grid;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .rows li + li {
    border-top: 1px solid var(--line);
  }

  .link-row {
    display: flex;
    min-height: 44px;
    align-items: center;
    gap: 0.85rem;
    margin-inline: -0.5rem;
    border-radius: var(--radius-sm);
    padding: 0.7rem 0.5rem;
    color: var(--ink);
    text-decoration: none;
  }

  .link-row:hover {
    background: var(--surface-raised);
  }

  .link-row__text {
    display: grid;
    min-width: 0;
    flex: 1;
    gap: 0.15rem;
    line-height: 1.4;
  }

  .link-row__label {
    font-weight: 700;
  }

  .link-row__hint {
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .link-row__go {
    display: inline-flex;
    flex: none;
    color: var(--ink-muted);
  }

  .link-row__go :global(svg) {
    width: 1.1rem;
    height: 1.1rem;
  }

  .card__intro {
    margin: -0.25rem 0 0;
    color: var(--ink-muted);
    line-height: 1.5;
  }

  .credits {
    display: grid;
    margin: 0;
    padding: 0;
    gap: 0.5rem;
    list-style: none;
  }

  .credits li {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    gap: 0.15rem 1rem;
  }

  .credits a {
    font-weight: 700;
  }

  .credit-detail {
    color: var(--ink-muted);
  }

  .footer-line {
    margin: 0.5rem 0 1rem;
    color: var(--ink-muted);
    font-family: var(--font-display);
    font-size: 1.05rem;
    text-align: center;
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
</style>
