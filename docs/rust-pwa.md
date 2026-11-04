# Rust PWA behavior

The installable app lives under `/app/`. The Rust server serves its worker, manifest, offline
fallback and icons from the generated web build (`WEB_ROOT`) through `pwa::router_with_web_root()`:
`/app/sw.js` (scope `/app/`), `/app/manifest.webmanifest`, `/app/offline.html`, `/app/offline.css`,
`/app/offline-theme.js` and `/app/icons/*.png`. The worker policy lives in
`web/static/service-worker.js`; `tests/pwa.rs` verifies the routes, headers and scope.

## Retired root worker

The removed server-rendered UI registered a worker at `/sw.js` with scope `/`. Browsers that still
hold that registration re-fetch `/sw.js` on their update checks, so the route stays and serves a
retirement worker: it deletes every cache and unregisters itself, so no stale legacy page is ever
served. `/register-pwa.js`, `/manifest.webmanifest` and `/offline` no longer exist.

The server's global security middleware supplies CSP and the other security headers for all of
these routes.
