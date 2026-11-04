<script lang="ts">
  import { resolve } from "$app/paths";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";

  const locale = $derived(session.data?.locale ?? "en");
  const signedIn = $derived(session.data?.user != null);
  const issuesUrl = "https://github.com/pschmitt/stricknani/issues";
</script>

<svelte:head>
  <title>{t("Privacy Policy", locale)} · Stricknani</title>
</svelte:head>

<article class="privacy" aria-labelledby="privacy-title">
  <a
    class="inline-link back-link"
    href={signedIn ? resolve("/more") : resolve("/login")}
  >
    {signedIn ? t("Back to More", locale) : t("Back to sign in", locale)}
  </a>

  <header class="privacy-header">
    <p class="section-mark">{t("Privacy", locale)}</p>
    <h1 id="privacy-title">{t("Privacy Policy", locale)}</h1>
    <p class="effective">{t("Effective date: 4 November 2026", locale)}</p>
  </header>

  <section>
    <h2>{t("About this policy", locale)}</h2>
    <p>
      {t(
        "This policy explains how Stricknani handles information when you use a Stricknani web instance or the companion Android app.",
        locale,
      )}
    </p>
    <p>
      {t(
        "Stricknani is self-hosted software. The operator of the instance you use is responsible for its deployment, configuration, backups, and legal obligations. This page describes the default behavior of the software and is not a promise about every deployment.",
        locale,
      )}
    </p>
  </section>

  <section>
    <h2>{t("Who is responsible for your data?", locale)}</h2>
    <p>
      {t(
        "For a self-hosted installation, the person or organization operating that installation is the controller of the data stored there. The Stricknani project does not receive data from independent self-hosted instances.",
        locale,
      )}
    </p>
    <p>
      {t(
        "If you use an instance operated by somebody else, contact that operator about access, deletion, retention, or other privacy requests. Questions about the Stricknani software can be reported through the",
        locale,
      )}
      <a href={issuesUrl} target="_blank" rel="noopener noreferrer"
        >{t("GitHub issue tracker", locale)}</a
      >.
    </p>
  </section>

  <section>
    <h2>{t("Information handled by the web app", locale)}</h2>
    <ul>
      <li>
        {t(
          "Account information: your email address, a password hash, and account state such as your role and whether the account is active.",
          locale,
        )}
      </li>
      <li>
        {t(
          "Knitting content: projects, yarn entries, notes, instructions, categories, steps, attachments, profile images, and other information you choose to store.",
          locale,
        )}
      </li>
      <li>
        {t(
          "Authentication information: a session cookie for browser login and personal access tokens for API or Android access. Token values are shown only when they are created; the server stores a hash for later verification.",
          locale,
        )}
      </li>
      <li>
        {t(
          "Single sign-on: if the operator enables sign-in through an OpenID Connect provider such as Authelia and you link it to your account, the server stores the provider's identifier for you (issuer and subject) and the email address the provider confirmed, so it can recognise you. The provider handles the sign-in itself under its own terms. You can unlink it in your account settings.",
          locale,
        )}
      </li>
      <li>
        {t(
          "Operational information: application and import logs may contain timestamps, routes, status information, error details, and identifiers needed to operate and troubleshoot the instance. The operator chooses log destinations and retention.",
          locale,
        )}
      </li>
    </ul>
  </section>

  <section>
    <h2>{t("Why this information is used", locale)}</h2>
    <p>
      {t(
        "The web app uses this information to authenticate you, provide project and yarn management, synchronize API clients, serve your authorized media, process imports, protect the service, and diagnose failures. It does not sell your information or use it for advertising.",
        locale,
      )}
    </p>
  </section>

  <section>
    <h2>{t("Android app and offline data", locale)}</h2>
    <p>
      {t(
        "The Android app connects only to the Stricknani server URL that you configure. It stores that URL and your personal access token locally, encrypted at rest. If you sign in with your email and password, the password is sent only to your server to create that token and is never stored on the device.",
        locale,
      )}
    </p>
    <p>
      {t(
        "Projects, yarn entries, and their photos are cached on the device so the app can work offline. Offline edits may be queued locally and are sent to your configured server when synchronization resumes. Removing the app or clearing its application data removes the local cache according to Android behavior.",
        locale,
      )}
    </p>
    <p>
      {t(
        "The Android app has no analytics, advertising, or tracking service. Data sent during synchronization goes to the server you configured. Error reports are optional and described under Error reporting below.",
        locale,
      )}
    </p>
    <p>
      {t(
        "The Android app does not use Android cloud backup. You can export a backup of the server connection and app settings, including your personal access token, to a file or folder that you choose, either manually or on a schedule. A backup can be protected with a password; without one, the file is readable by anyone who can open it.",
        locale,
      )}
    </p>
    <p>
      {t(
        "The app uses the camera only when you choose to scan a setup QR code. The camera image is processed on the device.",
        locale,
      )}
    </p>
    <p>
      {t(
        "On Android 13 and later, the app may ask for permission to show a notification when a background sync finds changes. The notification is created on the device, and syncing does not depend on it.",
        locale,
      )}
    </p>
    <p>
      {t(
        "If the app crashes, it saves a diagnostic report on the device (time, app version, device model, Android version, and a stack trace, with access tokens redacted) and shows it at the next launch. The report stays on the device unless you copy and share it yourself or error reporting is on.",
        locale,
      )}
    </p>
    <p>
      {t(
        "Builds installed from GitHub rather than Google Play check GitHub Releases for a newer version. That request does not include your access token and is subject to GitHub's privacy policy.",
        locale,
      )}
    </p>
  </section>

  <section>
    <h2>{t("Optional external services", locale)}</h2>
    <p>
      {t(
        "A deployment may enable integrations that send limited information outside the Stricknani instance. For example, an AI provider may receive material submitted for AI-assisted pattern import, and an archive service may receive a URL when you request an archive snapshot.",
        locale,
      )}
    </p>
    <p>
      {t(
        "Link imports: when you import a pattern or yarn from a link, the server fetches that web page and its pictures for you, so the website sees the server's address. For Ravelry links the server may also query the Ravelry API. If AI assistance is enabled and you choose it, the page text is sent to the configured AI provider, which can be an OpenAI-compatible service or a gateway run by the operator under the operator's own provider account.",
        locale,
      )}
    </p>
    <p>
      {t(
        "Profile pictures: if you have not uploaded one, the server may look up your Gravatar. It sends Gravatar a SHA-256 hash of your email address (never the address itself), receives the image, and keeps a copy for up to 24 hours. Your browser and the Android app never contact Gravatar, so only the server's address is visible to it. Gravatar processes the request under its own privacy policy. You can turn this off in your account settings, and the instance operator can disable it for the whole instance.",
        locale,
      )}
    </p>
    <p>
      {t(
        "These integrations are optional and controlled by the instance operator. Their providers process information under their own terms and privacy policies; review those policies before enabling or using the related feature.",
        locale,
      )}
    </p>
    <p>
      {t(
        "The web interface itself uses locally served assets and does not require an analytics or advertising network. It loads nothing from third-party servers.",
        locale,
      )}
    </p>
  </section>

  <section>
    <h2>{t("Error reporting", locale)}</h2>
    <p>
      {t(
        "Error reporting is optional and configured by the instance operator. If the operator has not set it up, nothing is reported and no setting appears.",
        locale,
      )}
    </p>
    <p>
      {t(
        "When it is on, the server, the web app, and the Android app report errors: the error message, a stack trace, the app version, and your browser or operating system version. They do not report account contents such as projects, yarns, photos, or notes, and they remove access tokens, cookies, email addresses, and IP addresses before sending. The only account detail attached to a report from the server is your numeric account ID.",
        locale,
      )}
    </p>
    <p>
      {t(
        "Reports go to the operator's own Sentry error tracker, through your Stricknani server. Your browser and the Android app never contact the error tracker directly.",
        locale,
      )}
    </p>
    <p>
      {t(
        "You can turn it off at any time with Send error reports in your account settings (Preferences) or in the Android app settings. The server then drops errors that occur while handling your requests. Errors that have no connection to a signed-in user, such as a server start-up failure, are governed by the operator's setting.",
        locale,
      )}
    </p>
    <p>
      {t(
        "The Android app behaves the same way whether you install it from Google Play or from GitHub: it sends nothing unless the server offers error reporting and you have not turned Send error reports off in the app settings.",
        locale,
      )}
    </p>
    <p>
      {t(
        "Error reports are sent only over an encrypted HTTPS connection. If your server address starts with http:// (a server on a home network, for example), the Android app does not send error reports, and the web app does not either when its page is served over plain HTTP, except on the same device (localhost) for development.",
        locale,
      )}
    </p>
  </section>

  <section>
    <h2>{t("Storage, retention, and deletion", locale)}</h2>
    <p>
      {t(
        "The instance stores account data and content in its configured database and media storage. There is no universal retention period: the operator determines retention, backups, log rotation, and whether deleted data remains in backups for a time.",
        locale,
      )}
    </p>
    <p>
      {t(
        "You can manage your content and revoke personal access tokens through the available features. You can also delete your account yourself, in the web app or the Android app, under Account. You confirm it is you with your password or your login provider, and the server then permanently deletes your projects, yarns, photos, attachments, favourites, profile picture, sessions, connected apps, linked logins, and the edit history of your content, together with the account itself.",
        locale,
      )}
      <a href={resolve("/delete-account")}
        >{t("How to delete your account", locale)}</a
      >.
    </p>
    <p>
      {t(
        "After a deletion the server keeps only an anonymous record that an account was deleted, with no name or email address. Copies may remain in the operator's backups until they are rotated out, according to the operator's retention period, and in logs or external services that already received information. To request an export of your data, or if you cannot sign in, contact the instance operator.",
        locale,
      )}
    </p>
  </section>

  <section>
    <h2>{t("Security", locale)}</h2>
    <p>
      {t(
        "Stricknani uses password hashing, access checks, CSRF protection, upload validation, and security response headers. Operators should use HTTPS, protect application secrets, restrict access to the database and media volume, keep the software and host updated, and configure backups securely.",
        locale,
      )}
    </p>
    <p>
      {t(
        "No software can guarantee absolute security. The security and confidentiality of a self-hosted installation also depend on the operator, hosting environment, network, integrations, and devices used to access it.",
        locale,
      )}
    </p>
  </section>

  <section>
    <h2>{t("Your choices and contact", locale)}</h2>
    <p>
      {t(
        "You may choose what to enter, upload, synchronize, or send to optional integrations. You may stop using the Android app, clear its local data, revoke API tokens, and contact the relevant instance operator about your rights under applicable law.",
        locale,
      )}
    </p>
    <p>
      {t(
        "For software questions or privacy concerns that relate to the project, use the",
        locale,
      )}
      <a href={issuesUrl} target="_blank" rel="noopener noreferrer"
        >{t("GitHub issue tracker", locale)}</a
      >.
    </p>
  </section>

  <section>
    <h2>{t("Changes to this policy", locale)}</h2>
    <p>
      {t(
        "This policy may be updated when Stricknani behavior or integrations change. The current version is published at this URL. Check the effective date above for the latest revision.",
        locale,
      )}
    </p>
  </section>
</article>

<style>
  .privacy {
    width: min(100%, 70ch);
    margin-inline: auto;
    color: var(--ink);
    font-size: 1.02rem;
    line-height: 1.65;
  }

  .back-link {
    margin-bottom: 0.5rem;
  }

  .privacy-header {
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
  ul {
    margin: 0.7rem 0 0;
  }

  ul {
    padding-left: 1.3rem;
  }

  li + li {
    margin-top: 0.5rem;
  }

  .effective {
    margin: 0;
    color: var(--ink-muted);
    font-size: 0.92rem;
  }

  section a {
    color: var(--accent-strong);
    font-weight: 650;
    overflow-wrap: anywhere;
  }
</style>
