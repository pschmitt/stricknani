<script lang="ts">
  import { resolve } from "$app/paths";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
</script>

<svelte:head>
  <title>Stricknani · {t("Your knitting workspace", locale)}</title>
</svelte:head>

<section class="welcome" aria-labelledby="welcome-title">
  <div class="welcome-copy">
    <p class="section-mark">{t("Your workspace", locale)}</p>
    <h1 id="welcome-title">{t("Your knitting, in one place.", locale)}</h1>
    <p class="welcome-description">
      {t(
        "Keep your projects, notes and yarn details close while you knit.",
        locale,
      )}
    </p>
    <a class="primary-link" href={resolve("/projects")}
      >{t("Open projects", locale)}</a
    >
  </div>
  <div class="stitch-sample" aria-hidden="true">
    <div class="stitch-row">
      <span></span><span></span><span></span><span></span><span></span>
    </div>
    <div class="stitch-row offset">
      <span></span><span></span><span></span><span></span><span></span>
    </div>
    <div class="stitch-row">
      <span></span><span></span><span></span><span></span><span></span>
    </div>
    <div class="stitch-row offset">
      <span></span><span></span><span></span><span></span><span></span>
    </div>
    <div class="stitch-row">
      <span></span><span></span><span></span><span></span><span></span>
    </div>
  </div>
</section>

<section class="preview-note" aria-labelledby="preview-title">
  <div class="note-mark" aria-hidden="true">i</div>
  <div>
    <h2 id="preview-title">{t("Preview foundation", locale)}</h2>
    <p>
      {t(
        "Sign-in, session handling and navigation are connected. Project and yarn workflows are being built next.",
        locale,
      )}
    </p>
  </div>
</section>

<style>
  .welcome {
    position: relative;
    display: grid;
    min-height: min(27rem, 52vh);
    grid-template-columns: minmax(0, 1.2fr) minmax(13rem, 0.8fr);
    align-items: center;
    gap: 2rem;
    border: 1px solid var(--line);
    border-radius: 1.1rem;
    padding: clamp(1.5rem, 5vw, 3.5rem);
    overflow: hidden;
    background: var(--surface);
  }

  .welcome-copy {
    position: relative;
    z-index: 1;
    max-width: 39rem;
  }

  .section-mark {
    margin: 0 0 0.9rem;
    color: var(--accent);
    font-size: 0.92rem;
    font-weight: 650;
  }

  h1 {
    max-width: 10ch;
    margin: 0;
    font-size: clamp(2.2rem, 6vw, 4.1rem);
    font-weight: 680;
    letter-spacing: -0.06em;
    line-height: 1.04;
  }

  .welcome-description {
    max-width: 34rem;
    margin: 1.2rem 0 0;
    color: var(--ink-muted);
    font-size: clamp(1rem, 2vw, 1.12rem);
    line-height: 1.65;
  }

  .primary-link {
    display: inline-flex;
    min-height: 2.9rem;
    align-items: center;
    justify-content: center;
    margin-top: 1.65rem;
    border-radius: 0.7rem;
    padding: 0.65rem 1.1rem;
    color: #fff;
    background: #116d64;
    font-weight: 650;
    text-decoration: none;
    transition:
      background-color 150ms ease,
      transform 150ms ease;
  }

  .primary-link:hover {
    background: #0b574f;
  }

  .primary-link:active {
    transform: translateY(1px);
  }

  .stitch-sample {
    display: grid;
    gap: 0.7rem;
    align-self: center;
    justify-self: center;
    width: min(100%, 17rem);
    transform: rotate(-7deg);
  }

  .stitch-row {
    display: flex;
    justify-content: center;
    gap: 0.45rem;
  }

  .stitch-row span {
    width: clamp(1.3rem, 4vw, 2rem);
    aspect-ratio: 1.4;
    border: 2px solid var(--accent);
    border-radius: 50%;
    background: var(--accent-wash);
  }

  .stitch-row.offset {
    transform: translateX(0.65rem);
  }

  .preview-note {
    display: flex;
    max-width: 52rem;
    align-items: flex-start;
    gap: 0.9rem;
    margin-top: 1.4rem;
    border-left: 3px solid var(--accent);
    padding: 0.25rem 0.9rem;
    color: var(--ink-muted);
  }

  .note-mark {
    display: grid;
    width: 1.5rem;
    flex: 0 0 auto;
    aspect-ratio: 1;
    place-items: center;
    border-radius: 50%;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 0.86rem;
    font-weight: 700;
  }

  .preview-note h2 {
    margin: 0;
    color: var(--ink);
    font-size: 0.98rem;
    font-weight: 650;
  }

  .preview-note p {
    margin: 0.2rem 0 0;
    font-size: 0.9rem;
  }

  @media (max-width: 620px) {
    .welcome {
      min-height: auto;
      grid-template-columns: minmax(0, 1fr);
      gap: 1rem;
      padding: 1.35rem;
    }

    h1 {
      max-width: 13ch;
      font-size: clamp(2rem, 9vw, 2.8rem);
    }

    .stitch-sample {
      width: 11rem;
      gap: 0.45rem;
      margin: 0.5rem auto 0;
      transform: rotate(-7deg) scale(0.85);
    }

    .preview-note {
      margin-top: 1.1rem;
    }
  }
</style>
