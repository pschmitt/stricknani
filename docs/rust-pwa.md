# Rust candidate PWA behavior

The Rust candidate exposes `/sw.js`, `/register-pwa.js`, `/manifest.webmanifest`, and `/offline`
through `pwa::router()`. The service worker is served from the origin root with
`Service-Worker-Allowed: /`; pages opt in by loading the local registration script. The offline
page contains English and German text, has no inline JavaScript, does not personalize on cookies,
and is marked `Cache-Control: no-store` for ordinary HTTP caches. Its typed label pairs use the Rust
gettext catalog with source-defined German fallbacks, while retaining both languages on screen.

The service worker precaches only an explicit public set: the generic offline page, PWA manifest,
registration script, app CSS, favicon, and icons. It fetches those URLs without cookies and caches
only successful, non-redirected same-origin responses whose final URL is exactly the requested
URL. Other GET requests and every non-GET request stay network-only. Navigations are never cached;
the offline page is returned only when a navigation fetch fails at the network layer. An HTTP
response such as a login redirect, 401, or server error is returned unchanged.

The worker uses the versioned `stricknani-pwa-v2` cache. Bump the version when changing cache
policy or public-shell assets. Activation deletes older caches owned by this worker and removes
cache entries outside the current public allowlist. It also clears the previous Python worker's
`stricknani-static-*` and `stricknani-runtime-*` caches, which could contain previously viewed
authenticated HTML.

This is a resource and cache policy, not an OS sandbox. The worker's explicit Cache Storage code
does not place pages, API responses, or media in a cache; ordinary browser HTTP caching remains
governed by server response headers. The server's global browser security middleware still
supplies CSP and other security headers when `pwa::router()` is merged into the app.
`rust/tests/pwa.rs` verifies the
router's public asset headers and scope, manifest, bilingual cookie-independent fallback, and
absence of application/API/media routes from this standalone router. The JavaScript policy is
kept explicit in `rust/assets/service-worker.js`; tests do not duplicate it as a second Rust-side
allowlist.
