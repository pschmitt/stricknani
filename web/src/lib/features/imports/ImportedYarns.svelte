<script lang="ts">
  import { untrack } from "svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import ResourceCard from "#lib/components/ResourceCard.svelte";
  import { msg, t, type Locale } from "#lib/i18n.svelte.ts";
  import { getYarn, type YarnSummary } from "../yarns/yarn-api";
  import { importImageSrc } from "./import-api";
  import type { ImportYarnEditor, YarnChoice } from "./import-draft";
  import YarnStashPicker from "./YarnStashPicker.svelte";

  /**
   * The yarns a pattern lists, each with one choice: take a yarn from the
   * stash, create it as a new yarn, or leave it out. The choices are three
   * large icon cards (one radio group per yarn); Android shows the same.
   */
  let {
    yarns = $bindable(),
    locale,
    disabled,
    allowExternal,
  }: {
    yarns: ImportYarnEditor[];
    locale: Locale;
    disabled: boolean;
    allowExternal: boolean;
  } = $props();

  /** Stash yarns shown here (suggested matches and picked ones), by id. */
  let stash = $state<Record<number, YarnSummary>>({});

  const choices = [
    { value: "stash", label: msg("From my stash"), icon: "yarns" },
    { value: "new", label: msg("Create new"), icon: "add" },
    { value: "skip", label: msg("Leave out"), icon: "close" },
  ] as const;

  // Load the suggested stash yarns. A suggestion that cannot be loaded is
  // dropped, so the yarn falls back to "create new".
  $effect(() => {
    const wanted = new Set<number>();
    for (const yarn of yarns) {
      for (const id of yarn.stashCandidates) wanted.add(id);
      if (yarn.stashId !== null) wanted.add(yarn.stashId);
    }
    const missing = [...wanted].filter(
      (id) => untrack(() => stash[id]) === undefined,
    );
    if (!missing.length) return;
    const controller = new AbortController();
    void Promise.all(
      missing.map(async (id) => {
        try {
          stash[id] = await getYarn(id, controller.signal);
        } catch {
          if (controller.signal.aborted) return;
          for (const yarn of yarns) {
            yarn.stashCandidates = yarn.stashCandidates.filter(
              (candidate) => candidate !== id,
            );
            if (yarn.stashId === id) {
              yarn.stashId = null;
              if (yarn.suggestedId === id) yarn.suggestedId = null;
              if (yarn.choice === "stash") yarn.choice = "new";
            }
          }
        }
      }),
    );
    return () => controller.abort();
  });

  function setChoice(yarn: ImportYarnEditor, value: YarnChoice): void {
    yarn.choice = value;
  }

  /** The stash yarns the select offers: the matches plus a yarn picked by search. */
  function options(yarn: ImportYarnEditor): number[] {
    const ids = [...yarn.stashCandidates];
    if (yarn.stashId !== null && !ids.includes(yarn.stashId))
      ids.push(yarn.stashId);
    return ids.filter((id) => stash[id] !== undefined);
  }

  function summary(found: YarnSummary): string {
    return [found.brand, found.colorway].filter(Boolean).join(" · ");
  }
</script>

{#each yarns as yarn, index (index)}
  {@const cardId = `import-yarn-${index}`}
  {@const picks = options(yarn)}
  <fieldset class="imported-yarn" data-testid="imported-yarn">
    <legend>{yarn.name || t("Imported yarn", locale)}</legend>
    {#if yarn.amount}<p class="supporting">
        {t("Amount", locale)}: {yarn.amount}
      </p>{/if}
    <div
      class="choice-cards"
      role="radiogroup"
      aria-label={`${t("What to do with this yarn", locale)}: ${yarn.name || t("Imported yarn", locale)}`}
    >
      {#each choices as option (option.value)}
        <label
          class="choice-card"
          class:choice-card--on={yarn.choice === option.value}
        >
          <input
            type="radio"
            name={`${cardId}-choice`}
            value={option.value}
            checked={yarn.choice === option.value}
            {disabled}
            onchange={() => setChoice(yarn, option.value)}
          />
          <span class="choice-card__icon" aria-hidden="true"
            ><NavIcon name={option.icon} /></span
          >
          <span class="choice-card__label">{t(option.label, locale)}</span>
        </label>
      {/each}
    </div>

    {#if yarn.choice === "stash"}
      {#if picks.length > 1}
        <label class="field"
          ><span>{t("Which yarn from my stash?", locale)}</span><select
            {disabled}
            value={yarn.stashId === null ? "" : String(yarn.stashId)}
            onchange={(event) => {
              const value = event.currentTarget.value;
              yarn.stashId = value === "" ? null : Number(value);
            }}
          >
            {#if yarn.stashId === null}<option value="" selected
                >{t("Pick a yarn from your stash.", locale)}</option
              >{/if}
            {#each picks as id (id)}
              <option value={String(id)}
                >{stash[id].name}{summary(stash[id])
                  ? ` · ${summary(stash[id])}`
                  : ""}</option
              >
            {/each}
          </select></label
        >
      {/if}
      {#if yarn.stashId !== null && stash[yarn.stashId]}
        <div class="picked">
          <ResourceCard
            kind="yarn"
            href={null}
            imageUrl={stash[yarn.stashId].preview_url}
            name={stash[yarn.stashId].name}
            subtitle={summary(stash[yarn.stashId])}
            badge={yarn.stashId === yarn.suggestedId
              ? t("Suggested match", locale)
              : null}
          />
        </div>
      {:else if yarn.stashId === null && picks.length <= 1}
        <p class="supporting">{t("Pick a yarn from your stash.", locale)}</p>
      {/if}
      <YarnStashPicker
        {locale}
        inputId={`${cardId}-search`}
        label={yarn.stashId === null
          ? t("Find a yarn in your stash", locale)
          : t("Pick a different yarn", locale)}
        exclude={yarn.stashId === null ? [] : [yarn.stashId]}
        {disabled}
        onPick={(picked) => {
          stash[picked.id] = picked;
          yarn.stashId = picked.id;
        }}
      />
    {:else if yarn.choice === "new"}
      <p class="supporting">
        {t("This yarn is created with the project and linked to it.", locale)}
      </p>
      {#if yarn.imageUrl && allowExternal}
        <img
          class="yarn-photo"
          src={importImageSrc(yarn.imageUrl)}
          alt=""
          loading="lazy"
        />
      {/if}
      <label class="field"
        ><span>{t("Yarn name", locale)}</span><input
          bind:value={yarn.name}
          {disabled}
        /></label
      >
      <div class="form-grid">
        <label class="field"
          ><span>{t("Brand", locale)}</span><input
            bind:value={yarn.brand}
            {disabled}
          /></label
        >
        <label class="field"
          ><span>{t("Colorway", locale)}</span><input
            bind:value={yarn.colorway}
            {disabled}
          /></label
        >
        <label class="field"
          ><span>{t("Fiber content", locale)}</span><input
            bind:value={yarn.fiberContent}
            {disabled}
          /></label
        >
        <label class="field"
          ><span>{t("Weight category", locale)}</span><input
            bind:value={yarn.weightCategory}
            {disabled}
          /></label
        >
        <label class="field"
          ><span>{t("Weight in grams", locale)}</span><input
            type="number"
            min="0"
            step="1"
            bind:value={yarn.weightGrams}
            {disabled}
          /></label
        >
        <label class="field"
          ><span>{t("Length in meters", locale)}</span><input
            type="number"
            min="0"
            step="1"
            bind:value={yarn.lengthMeters}
            {disabled}
          /></label
        >
      </div>
    {:else}
      <p class="supporting">
        {t("This yarn will not be added to the project.", locale)}
      </p>
    {/if}
  </fieldset>
{/each}

<style>
  .imported-yarn {
    min-width: 0;
    margin: 0.8rem 0;
    padding: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
  }

  .imported-yarn legend {
    padding: 0 0.4rem;
    color: var(--accent-strong);
    font-weight: 720;
  }

  .supporting {
    margin: 0.2rem 0 0.6rem;
    color: var(--ink-muted);
  }

  .choice-cards {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 0.6rem;
    margin: 0.4rem 0 0.8rem;
  }

  /* The radio is the card: it stays focusable and keeps the arrow keys. */
  .choice-card {
    position: relative;
    display: grid;
    min-height: 5.25rem;
    align-content: center;
    justify-items: center;
    gap: 0.35rem;
    padding: 0.7rem 0.4rem;
    border: 2px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--surface);
    color: var(--ink);
    text-align: center;
    cursor: pointer;
    transition:
      background 140ms ease,
      border-color 140ms ease;
  }

  .choice-card input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    opacity: 0;
    cursor: pointer;
  }

  .choice-card:has(input:focus-visible) {
    outline: 3px solid var(--accent);
    outline-offset: 2px;
  }

  .choice-card:has(input:disabled) {
    cursor: not-allowed;
    opacity: 0.6;
  }

  .choice-card--on {
    border-color: var(--accent);
    background: var(--accent-wash);
    color: var(--accent-strong);
  }

  .choice-card__icon {
    display: grid;
    place-items: center;
  }

  .choice-card__icon :global(svg) {
    width: 1.75rem;
    height: 1.75rem;
  }

  .choice-card__label {
    font-size: 0.9rem;
    font-weight: 650;
    line-height: 1.2;
  }

  .field {
    display: grid;
    gap: 0.4rem;
    margin: 0.7rem 0;
    font-weight: 620;
  }

  .field input,
  .field select {
    width: 100%;
    min-width: 0;
  }

  .form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 1rem;
  }

  .picked {
    display: grid;
    gap: 0.4rem;
    margin: 0.4rem 0 0.8rem;
  }

  .yarn-photo {
    width: 4.5rem;
    height: 4.5rem;
    border-radius: var(--radius-xs);
    object-fit: cover;
  }

  @media (max-width: 26rem) {
    .choice-cards {
      grid-template-columns: repeat(auto-fit, minmax(6.5rem, 1fr));
    }

    .form-grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .choice-card {
      transition: none;
    }
  }
</style>
