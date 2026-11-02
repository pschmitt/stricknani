# Legacy browser UI cleanup boundary

The new browser application is served below `/app/`. `WEB_ROOT` changes the
root landing to `/app/`, but the Rust server still composes the legacy HTML
routers and their related services. This note separates browser-only code from
contracts that still have concrete callers.

## Keep Android-facing contracts

- Keep `/api/v1/**` and media authorization/response behavior stable for the
  native Android client. Its Retrofit interfaces call these endpoints directly
  (`android/app/src/main/kotlin/blue/anika/wolle/data/api/ProjectsApi.kt` and
  `YarnsApi.kt`).
- Keep the Android share-link paths reachable. The app manifest accepts
  `/projects/…` and `/yarn/…`; `DeepLinkParser` consumes numeric project and
  singular yarn IDs, and the detail view models generate those two URL forms.
  Browser GET aliases may redirect into `/app/projects/{id}` and
  `/app/yarns/{id}`; they need not preserve the old page design.
- The current Rust HTML router has `/projects/{id}` and plural
  `/yarns/{id}` routes. With `WEB_ROOT` configured, the frontend now also
  redirects GET requests for the Android singular `/yarn/{id}` link to
  `/app/yarns/{id}`, preserving its query string. Without `WEB_ROOT`, that
  alias remains absent.

## Legacy Rust HTML consumers

`rust/src/lib.rs` composes `web::router` (or `router_with_landing` when
`WEB_ROOT` is set), account, audit, signup, import, archive, OCR, and gauge
routes. Only `/` changes to the new UI landing; existing paths still render
legacy pages or process their form submissions.

The templates are compiled, live route dependencies rather than unused
reference files:

- `rust/src/web.rs` renders `rust/templates/web.html` for login, project, yarn,
  and category pages and mutations.
- `rust/src/web_accounts.rs`, `web_audit.rs`, and `web_signup.rs` render
  `accounts.html`, `audit.html`, and `signup.html`.
- `rust/src/import_service.rs` renders `import.html` and currently redirects
  successful creates to `/projects/{id}` or `/yarns/{id}`.
- `rust/src/archive_service.rs` builds browser URLs under `/projects/{id}` and
  `/yarns/{id}`.
- `rust/src/lib.rs` renders the standalone `/gauge/` page; `rust/src/pwa.rs`
  renders the legacy `/offline` page.

The legacy page templates reference `/static/css/app.css`, `material.css`, and
`rust.css`, plus `/static/rust-utilities.js` where used. Migrate or remove those
references with the pages that consume them. The Rust static mount exposes the
shared `stricknani/static` tree, which also belongs to the Python reference app;
do not remove that shared tree as part of Rust UI cleanup without auditing the
Python templates separately.

## Root-scope PWA retirement

The legacy templates link `/manifest.webmanifest` and load
`/register-pwa.js`. `rust/src/pwa.rs` serves those resources, `/sw.js`, and
`/offline`. The registration script registers `/sw.js` with scope `/`. The
root worker caches only allowlisted public shell resources and uses `/offline`
as its network-failure navigation fallback; it does not cache authenticated
pages, APIs, or media.

The new app has a separate `/app/sw.js` worker and `/app/` scope.
`web/src/lib/components/PwaUpdate.svelte` registers that worker. Keep the app
worker and its explicit public-asset allowlist when retiring the old shell.

Do not remove the root worker endpoints in the same change that drops the old
templates. Installed browsers can retain a root-scope worker after the server
stops serving the old registration script. A cleanup release should first stop
legacy pages from registering it and provide a bounded worker-retirement path
that unregisters the old worker and clears only its legacy caches. Remove the
root worker, manifest, offline route, and their tests after that transition is
verified.

## Cleanup sequence

After the new UI journeys replace the legacy browser features and acceptance
passes:

1. Convert old shared browser-link paths to fixed local redirects into `/app/`.
   Keep the Android URL patterns and never accept an arbitrary redirect target.
2. Update Rust import/archive links and form/action tests to use the new browser
   paths or API v2 before deleting their old HTML handlers.
3. Remove the replaced Rust page handlers, compiled Askama templates, and
   template-only CSS/helpers once no route or internal redirect references them.
4. Retire the root-scope PWA in a separate staged transition, then remove its
   handlers/assets/tests while retaining the app-scoped PWA.
5. Keep Android v1 API/media routes and the Python reference application/assets
   outside this browser cleanup. Production cutover is a separate deployment
   decision.
