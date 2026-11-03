<script lang="ts">
  import { t } from "#lib/i18n.svelte.ts";
  let {
    title,
    description,
    locale,
  }: { title: string; description: string; locale: "en" | "de" } = $props();
</script>

<section class="preview-section" aria-labelledby="section-title">
  <p class="section-mark">Stricknani</p>
  <h1 id="section-title">{title}</h1>
  <p class="section-description">{description}</p>
  <div class="preview-state">
    <span class="preview-dot" aria-hidden="true"></span>
    <p>{t("This area is not connected yet.", locale)}</p>
  </div>
</section>

<style>
  .preview-section {
    max-width: 48rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    padding: clamp(1.4rem, 4vw, 2.6rem);
    background: var(--surface);
  }

  h1 {
    margin: 0.45rem 0 0.75rem;
    font-size: clamp(2rem, 6vw, 3.2rem);
    font-weight: 680;
    letter-spacing: -0.06em;
    line-height: 1.08;
  }

  .section-description {
    max-width: 39rem;
    margin: 0;
    color: var(--ink-muted);
    font-size: 1.05rem;
    line-height: 1.65;
  }

  .preview-state {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    margin-top: 2.2rem;
    border-top: 1px solid var(--line);
    padding-top: 1rem;
    color: var(--ink-muted);
    font-size: 0.92rem;
  }

  .preview-state p {
    margin: 0;
  }

  .preview-dot {
    width: 0.6rem;
    aspect-ratio: 1;
    border-radius: 50%;
    background: var(--accent);
  }
</style>
