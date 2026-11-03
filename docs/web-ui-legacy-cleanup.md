# Legacy browser UI cleanup boundary

Reviewed against source `2c94f380b6201a75882c4f1a4a9903c4a61f38a9` and deployed
runtime `52221ad266d5c38124f7ca442add952055223e7b` on 2026-11-02.

The new browser application is served below `/app/`. `WEB_ROOT` changes the
root landing to `/app/`, but the Rust server still composes the legacy HTML
routers and their related services. This note separates browser-only code from
contracts that still have concrete callers.

The conditional cleanup review is complete. Actual asset removal is not safe
yet: the mounted HTML routes, Android callers and root-worker consumers below
remain. This is a decision to retain their contracts, not evidence that those
consumers have been retired. No routes, assets, data or production services
changed during this review.

## Keep Android-facing contracts

- Keep `/api/v1/**` and media authorization/response behavior stable for the
  native Android client. Its Retrofit interfaces call these endpoints directly
  (`android/app/src/main/kotlin/blue/anika/wolle/data/api/ProjectsApi.kt` and
  `YarnsApi.kt`).
- Keep `POST /projects/import`. Android's
  [ProjectImportApi](../android/app/src/main/kotlin/blue/anika/wolle/data/api/ProjectImportApi.kt)
  explicitly calls this bearer-authenticated JSON endpoint outside `/api/v1`.
  [import_service.rs](../rust/src/import_service.rs) mounts it independently
  of the `/imports/**` HTML forms. A blanket removal of unversioned routes
  would break a current native-client workflow.
- Keep persisted media paths and migrated filename/thumbnail aliases. Android's
  [MediaUrlResolver](../android/app/src/main/kotlin/blue/anika/wolle/data/media/MediaUrlResolver.kt)
  consumes these URLs; [media_aliases.rs](../rust/tests/media_aliases.rs) checks
  live authorization and owner-scoped resolution. These are data contracts,
  rather than obsolete frontend assets.
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
references with the pages that consume them. The Rust static mount serves `rust/static`
(moved there from the removed Python `stricknani/static` tree, trimmed to the
files these pages use: the three stylesheets, `rust-utilities.js`, the icons,
the favicon and the Space Grotesk fonts). Remove files from it together with
the pages that reference them.

The new [frontend router](../rust/src/frontend.rs) independently serves the
generated `/app` shell/catalog/immutable assets, and its
[session client](../web/src/lib/api.ts) calls `/api/v2/session` and
`/api/v2/auth/**`. Svelte styles and feature pages are bundled from `web/`;
the new UI does not load those old HTML templates and styles.
The [Rust package assembly](../nix/rust-package.nix) already excludes the old
Python frontend JavaScript, HTMX and TipTap directories, while retaining files
required by the mounted Rust views and workers.

The [frontend](../rust/tests/frontend.rs), [PWA](../rust/tests/pwa.rs),
[Android import](../rust/tests/import_service.rs) and media-alias tests identify
boundaries for future removal. This review inspected their coverage; it does
not claim an additional test run.

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
