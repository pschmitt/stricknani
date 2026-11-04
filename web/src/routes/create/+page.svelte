<script lang="ts">
  import { resolve } from "$app/paths";
  import NavIcon, { type NavIconName } from "#lib/components/NavIcon.svelte";
  import KnittingBasket from "#lib/components/KnittingBasket.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { importAvailability } from "#lib/features/imports/import-availability.ts";
  const locale = $derived(session.data?.locale ?? "en");

  type CreateOption = {
    href: string;
    icon: NavIconName;
    tone: "projects" | "yarns" | "create";
    title: string;
    description: string;
    /** Why this option cannot be used on this server, if so. */
    disabledReason?: string;
    /** A softer limit: the option works, one part of it does not. */
    note?: string;
  };
  const availability = $derived(importAvailability(session.data?.capabilities));

  const options: CreateOption[] = $derived([
    {
      href: resolve("/projects/new"),
      icon: "projects",
      tone: "projects",
      title: t("New project", locale),
      description: t("Plan the next thing you want to knit.", locale),
    },
    {
      href: resolve("/yarns/new"),
      icon: "yarns",
      tone: "yarns",
      title: t("New yarn", locale),
      description: t("Keep a note of a yarn you own.", locale),
    },
    {
      href: resolve("/imports"),
      icon: "imports",
      tone: "create",
      title: t("Import a project", locale),
      description: t(
        "Create a project draft from text, a file, or a pattern link.",
        locale,
      ),
      note: availability.ai
        ? undefined
        : t("AI import is turned off on this server.", locale),
    },
    {
      href: resolve("/imports/yarn"),
      icon: "imports",
      tone: "yarns",
      title: t("Import a yarn", locale),
      description: t("Fill in a yarn from a shop or Ravelry link.", locale),
      disabledReason: availability.url
        ? undefined
        : t("Link imports are turned off on this server.", locale),
      note: availability.ai
        ? undefined
        : t("AI import is turned off on this server.", locale),
    },
  ]);
</script>

<svelte:head><title>Stricknani · {t("Create", locale)}</title></svelte:head>

<section
  class="create-page page-container page-container--compact"
  aria-labelledby="create-title"
>
  <header class="create-hero">
    <KnittingBasket size={132} />
    <div class="create-hero__copy">
      <h1 id="create-title">{t("What would you like to add?", locale)}</h1>
      <p>
        {t(
          "Start something new, or bring in a pattern you already have.",
          locale,
        )}
      </p>
    </div>
  </header>
  <div class="create-options">
    {#each options as option (option.href)}
      {#if option.disabledReason}
        <div
          class="create-card create-card--disabled"
          role="link"
          aria-disabled="true"
          style:--card-tone={`var(--tone-${option.tone})`}
          style:--card-wash={`var(--tone-${option.tone}-wash)`}
        >
          <span class="create-card__icon" aria-hidden="true"
            ><NavIcon name={option.icon} /></span
          >
          <span class="create-card__copy">
            <span class="create-card__title">{option.title}</span>
            <span class="create-card__description">{option.description}</span>
            <span class="create-card__note">{option.disabledReason}</span>
          </span>
        </div>
      {:else}
        <a
          class="create-card"
          href={option.href}
          style:--card-tone={`var(--tone-${option.tone})`}
          style:--card-wash={`var(--tone-${option.tone}-wash)`}
        >
          <span class="create-card__icon" aria-hidden="true"
            ><NavIcon name={option.icon} /></span
          >
          <span class="create-card__copy">
            <span class="create-card__title">{option.title}</span>
            <span class="create-card__description">{option.description}</span>
            {#if option.note}<span class="create-card__note">{option.note}</span
              >{/if}
          </span>
          <span class="create-card__go" aria-hidden="true"
            ><NavIcon name="next" /></span
          >
        </a>
      {/if}
    {/each}
  </div>
</section>

<style>
  .create-hero {
    display: flex;
    align-items: center;
    gap: 1.25rem;
    margin: 0.5rem 0 1.25rem;
  }

  .create-hero__copy {
    display: grid;
    gap: 0.25rem;
    min-width: 0;
  }

  .create-hero h1 {
    margin: 0;
    font-family: var(--font-display);
    font-size: clamp(1.6rem, 4vw, 2.3rem);
    line-height: 1.1;
  }

  .create-hero p {
    margin: 0;
    color: var(--ink-muted);
    font-size: 1.05rem;
  }

  .create-options {
    display: grid;
    gap: 0.9rem;
    margin-top: 1rem;
  }

  /* Same family as the yarn stash card on Home: a tinted card per kind, the
     icon in a large chip, vertically centred, and a "go" chevron. */
  .create-card {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 1.1rem;
    min-height: 6.5rem;
    border-radius: var(--radius-lg, 1.5rem);
    padding: 1.1rem 1.25rem;
    color: var(--ink);
    background: var(--card-wash);
    text-decoration: none;
    transition: transform 220ms cubic-bezier(0.3, 1.6, 0.5, 1);
  }

  .create-card--disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .create-card--disabled:hover {
    transform: none;
  }

  .create-card__note {
    color: var(--ink-muted);
    font-size: 0.85rem;
  }

  .create-card:not(.create-card--disabled):hover {
    transform: translateY(-2px);
  }

  .create-card:active {
    transform: scale(0.98);
  }

  .create-card:focus-visible {
    outline: 3px solid var(--card-tone);
    outline-offset: 3px;
  }

  .create-card__icon,
  .create-card__go {
    display: grid;
    place-items: center;
    border-radius: 999px;
    color: var(--card-tone);
    background: color-mix(in srgb, var(--card-tone) 16%, transparent);
  }

  .create-card__icon {
    width: 4rem;
    height: 4rem;
  }

  .create-card__icon :global(svg) {
    width: 2.1rem;
    height: 2.1rem;
  }

  .create-card__go {
    width: 2.25rem;
    height: 2.25rem;
  }

  .create-card__go :global(svg) {
    width: 1.15rem;
    height: 1.15rem;
  }

  .create-card__copy {
    display: grid;
    gap: 0.2rem;
    min-width: 0;
  }

  .create-card__title {
    font-family: var(--font-display);
    font-size: 1.25rem;
    font-weight: 650;
    line-height: 1.2;
  }

  .create-card__description {
    color: var(--ink-muted);
    font-size: 0.95rem;
    line-height: 1.4;
  }

  @media (max-width: 30rem) {
    .create-hero {
      gap: 0.9rem;
    }

    .create-card {
      gap: 0.9rem;
      padding: 1rem;
    }

    .create-card__icon {
      width: 3.25rem;
      height: 3.25rem;
    }

    .create-card__icon :global(svg) {
      width: 1.75rem;
      height: 1.75rem;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .create-card {
      transition: none;
    }

    .create-card:hover,
    .create-card:active {
      transform: none;
    }
  }
</style>
