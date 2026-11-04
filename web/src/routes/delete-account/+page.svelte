<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import SharedButton from "#lib/components/Button.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  const signedIn = $derived(session.data?.user != null);
  const issuesUrl = "https://github.com/pschmitt/stricknani/issues";
</script>

<svelte:head>
  <title>{t("Delete your account", locale)} · Stricknani</title>
</svelte:head>

<article class="delete-page" aria-labelledby="delete-title">
  <a
    class="inline-link back-link"
    href={signedIn ? resolve("/account") : resolve("/login")}
  >
    {signedIn ? t("Back to Account", locale) : t("Back to sign in", locale)}
  </a>

  <header class="delete-header">
    <p class="section-mark">{t("Your data, your call", locale)}</p>
    <h1 id="delete-title">{t("Delete your account", locale)}</h1>
    <p class="lead">
      {t(
        "Time to cast off? You can unravel your Stricknani account yourself, in about a minute. No email to write and no waiting for anyone.",
        locale,
      )}
    </p>
  </header>

  <section class="cta" aria-labelledby="how-title">
    <h2 id="how-title">{t("How to delete your account", locale)}</h2>
    <ol>
      <li>
        {t(
          "Open Account in the web app, or the Account screen in the Android app.",
          locale,
        )}
      </li>
      <li>
        {t(
          "Scroll down to Delete account and choose Delete my account.",
          locale,
        )}
      </li>
      <li>
        {t(
          "Confirm it is you with your password, or with your login provider if that is how you sign in.",
          locale,
        )}
      </li>
      <li>
        {t("Type DELETE or your email address, and confirm.", locale)}
      </li>
    </ol>
    {#if signedIn}
      <SharedButton
        variant="primary"
        onclick={() => void goto(resolve("/account"))}
        icon="account"
      >
        {t("Go to Account settings", locale)}
      </SharedButton>
    {:else}
      <p>
        {t(
          "You need to be signed in to delete your account, so that nobody else can do it for you.",
          locale,
        )}
        <a class="inline-link" href={resolve("/login")}
          >{t("Sign in to continue", locale)}</a
        >
      </p>
    {/if}
  </section>

  <section>
    <h2>{t("What gets deleted", locale)}</h2>
    <p>
      {t(
        "Everything that is yours goes in one go. It cannot be undone, so there is no trash to dig through afterwards.",
        locale,
      )}
    </p>
    <ul>
      <li>
        {t("Your projects, yarns, steps, notes, and categories.", locale)}
      </li>
      <li>
        {t(
          "Your photos and attachments, including the image files stored on the server.",
          locale,
        )}
      </li>
      <li>{t("Your favourites and your profile picture.", locale)}</li>
      <li>
        {t(
          "Every sign-in: browser sessions, the apps and devices you connected, and any linked login provider.",
          locale,
        )}
      </li>
      <li>
        {t(
          "The edit history of your projects and yarns, and your account itself, including your email address and password hash.",
          locale,
        )}
      </li>
    </ul>
  </section>

  <section>
    <h2>{t("What stays", locale)}</h2>
    <ul>
      <li>
        {t(
          "A bare note that an account was deleted, and when. It has no name, no email address, and nothing that points back to you.",
          locale,
        )}
      </li>
      <li>
        {t(
          "Copies in the operator's backups, until those backups are rotated out. How long that takes is up to the person who runs the server.",
          locale,
        )}
      </li>
      <li>
        {t(
          "On Android, deleting from the app also clears what the app stored on your phone. If you delete from the web instead, clear the app data or uninstall the app to remove the local copy.",
          locale,
        )}
      </li>
    </ul>
    <p>
      {t(
        "The last administrator of a server cannot delete their account, because nobody would be left to look after it. Make someone else an administrator first.",
        locale,
      )}
    </p>
  </section>

  <section>
    <h2>{t("Cannot get in?", locale)}</h2>
    <p>
      {t(
        "If you can no longer sign in, for example because you lost your password, ask the operator of the Stricknani server you use to delete the account for you. They run the server and can do it from the admin side.",
        locale,
      )}
    </p>
    <p>
      {t("Questions about the software itself are welcome on the", locale)}
      <a href={issuesUrl} target="_blank" rel="noopener noreferrer"
        >{t("GitHub issue tracker", locale)}</a
      >.
      {t("More about your data is in the", locale)}
      <a href={resolve("/privacy")}>{t("Privacy Policy", locale)}</a>.
    </p>
  </section>
</article>

<style>
  .delete-page {
    width: min(100%, 70ch);
    margin-inline: auto;
    color: var(--ink);
    font-size: 1.02rem;
    line-height: 1.65;
  }

  .back-link {
    margin-bottom: 0.5rem;
  }

  .delete-header {
    margin-bottom: 1.5rem;
    border-bottom: 1px solid var(--line);
    padding-bottom: 1.25rem;
  }

  h1,
  h2 {
    font-family: var(--font-display);
    font-variation-settings:
      "SOFT" 100,
      "WONK" 1;
    line-height: 1.2;
  }

  h1 {
    margin: 0.25rem 0 0.5rem;
    color: var(--accent-strong);
    font-size: clamp(1.8rem, 5vw, 2.5rem);
  }

  h2 {
    margin: 2rem 0 0.6rem;
    font-size: 1.35rem;
  }

  p,
  ul,
  ol {
    margin: 0.7rem 0 0;
  }

  ul,
  ol {
    padding-left: 1.3rem;
  }

  li + li {
    margin-top: 0.5rem;
  }

  .lead {
    margin: 0;
    font-size: 1.12rem;
  }

  .cta {
    margin-top: 1.5rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    padding: 0.2rem 1.1rem 1.1rem;
    background: var(--surface);
  }

  .cta h2 {
    margin-top: 1rem;
  }

  .cta :global(.ui-button) {
    margin-top: 0.9rem;
  }

  a {
    color: var(--accent-strong);
    font-weight: 650;
    overflow-wrap: anywhere;
  }
</style>
