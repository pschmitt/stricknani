# Android compatibility with the Rust API and website

**Status:** SNA-74 records the compatibility inventory and plan; SNA-75 adds canonical website
links to the native parser and intent filters. Remote unit tests/debug assembly and the Rust E2E
and offline-replay CI suites pass on
[ad9878089](https://github.com/pschmitt/stricknani/commit/ad9878089d7ea60e97f01614ef8cb135051d5b7d).
[Android Build](https://github.com/pschmitt/stricknani/actions/runs/41546837801) and
[Android Lint](https://github.com/pschmitt/stricknani/actions/runs/41546833282) also pass on
[c3904b4](https://github.com/pschmitt/stricknani/commit/c3904b489ca0dddeeaa62cd60997f71d74a6f601),
which corrects the Kotlin layout caught by the full Nix formatter.
Rust APIs, authentication, Room storage and installed devices are unchanged.

## Decision for the current release

Keep Android on `/api/v1`. Its Retrofit interfaces, bearer PAT authentication, DTOs, offline queue,
and Room delta sync already match the Rust v1 contract; SNA-70 and SNA-71 provide focused evidence
against the Rust service. The new website's `/api/v2` is a same-origin browser API whose session
cookie and CSRF model is intentionally different. Do not change the Android base path to v2 or reuse
web cookies in the native app.

The immediate implementation is canonical website-link support, alongside documentation and
compatibility tracking. No API prefix change is needed to use the Rust backend. A future native v2
migration first needs an explicit mobile authentication contract and a sync decision.

## Compatibility matrix

| Area | Android contract today | Rust/web contract | Compatibility and migration concern |
| --- | --- | --- | --- |
| Server discovery and onboarding | `GET /api/v1/meta`, then bearer-authenticated `GET /api/v1/categories`; optional email/password onboarding exchanges credentials at `POST /api/v1/auth/token` for a PAT. Settings retain the server URL and PAT in encrypted preferences. | Rust mounts these v1 routes. V2 instead has `GET /api/v2/session` and `POST /api/v2/auth/login`; login establishes a browser session cookie and CSRF token. | V1 onboarding remains suitable for Android. V2 login is not a replacement for PAT onboarding: cookie storage, same-origin checks, CSRF, browser session lifetime, and password handling need a separate native design. Do not persist website cookies or silently mint/replace a PAT. |
| Projects | Retrofit uses `/api/v1/projects` list/detail/create/update/delete and favorite `POST`/`DELETE`; Kotlin DTOs model direct JSON objects and current project fields. Writes carry the existing optimistic concurrency field. | V2 project routes use the same resource names but return `{data: ...}` envelopes (paged lists also have `meta`), expose HTML-rendered companion fields, and use `PUT` for favorite. | Domain overlap does not imply wire compatibility. V2 needs separate envelope/list/error/write DTOs and method mapping; keep existing v1 DTOs stable. |
| Yarns | `/api/v1/yarns` list/detail/CRUD and `POST`/`DELETE` favorite, with direct DTO responses. | V2 yarn routes use browser-session auth, envelopes, paged-list metadata, and `PUT`/`DELETE` favorite. | Same shape and authentication risks as projects. Compare all write fields and conflict responses before a versioned client is introduced. |
| Categories | `/api/v1/categories` and full category replacement through `/api/v1/sync/categories`. | V2 has category CRUD with browser-session auth and `{data: ...}` responses; it has no Android-compatible sync endpoint. | A v2 client would need its own authenticated category refresh and explicit deletion semantics. Do not change Room replacement behavior based only on the v2 CRUD routes. |
| Delta sync | `/api/v1/sync/projects` and `/api/v1/sync/yarns` send the last `server_time` as `since`; responses include updated rows, `deleted_ids`, and server time. Categories are fetched as a full list. | Rust v1 retains these routes and supports optional bounded pages with `limit` and opaque `next_cursor`; default requests remain complete-response form. V2 has no matching delta-sync routes. | The current client does not request bounded pages. Kotlin DTOs model `full_resync_required` but repositories currently ignore it, and do not model `has_more`/`next_cursor`. If bounded pages are enabled, the client must drain every page from one snapshot before advancing the durable timestamp watermark; the opaque page cursor is separate state. Also define full-resync and pending-write behavior. |
| Updates and conflicts | Project/yarn writes carry `expected_updated_at`; queued replay handles a 409 by adopting the server's current DTO and preserves a visible conflict. | Rust v1 uses timestamp compare-and-set and returns a conflict with the current resource; this is not an HTTP ETag/`If-Match` contract. V2 also uses resource updates but has separate envelopes/errors. | Preserve compare-and-set and the current replay decisions in any adapter. Do not invent ETags or drop conflict payload handling. |
| Media | Multipart uploads/deletes use v1 project/yarn paths; media URL resolution preserves configured URL path prefixes and rejects cross-origin or unsafe URLs. Picked files are copied into app-private storage for queued replay. | Rust v1 uploads/deletes and registered-media reads authenticate bearer PATs. V2 upload/delete endpoints require browser session plus same-origin CSRF. The shared media reader accepts either bearer auth or browser session, then checks owner/resource association. | Current app media flows remain compatible with v1. A future v2 client must not assume a v2 upload URL or browser capability means the native client is authorized. Preserve owner checks, retryable local copies, deletion ordering, safe URL resolution, and attachment handling. |
| Offline storage and queued writes | Room is the source for observed data. A durable ordered mutation outbox replays project/yarn writes and media operations; temporary IDs are remapped after create; conflicts remain visible instead of being discarded. | V2 is a request/response browser API and exposes no equivalent outbox or delta stream. | Keep Room and the outbox as native behavior. Any API adapter must preserve operation ordering, conflict outcomes, idempotence/retry assumptions, and local media lifetime. A server/API migration alone must not require a Room schema change. |
| Base URL and path prefixes | A dynamic-base interceptor routes API calls to the configured self-hosted server URL; media resolution retains a configured prefix. | Rust serves v1 and v2 under the same host; the website uses `/app/...` SPA routes and same-origin API paths. | Keep requests relative to the configured base URL. Test a non-root deployment prefix before changing URL construction; never hardcode an origin or strip a configured prefix. |
| Deep links and website URLs | Android recognizes legacy `/projects/{id}` and `/yarn/{id}` paths and canonical `/app/projects/{id}` and `/app/yarns/{id}` paths on arbitrary self-hosted hosts. Only those explicit prefixes and positive 32-bit IDs are accepted; the parser has no configured-base-path context for arbitrary deployment prefixes. | SPA canonical paths are `/app/projects/{id}` and `/app/yarns/{id}`. Rust explicitly redirects legacy `/yarn/{id}` to the SPA when the web root is mounted; `/projects/{id}` continues through the legacy server-rendered router. | SNA-75 adds canonical paths while retaining legacy paths, host/scheme chooser behavior, and the configured single-server-per-device model. Parser tests cover canonical/legacy paths, query/fragment, edit suffixes, and invalid IDs/prefixes. Server redirect behavior remains a separate fixture-level integration check. |
| Native pattern import | Android has a dedicated v1 pattern-import client and uses the configured server URL/PAT. | Rust retains a PAT-authenticated v1 import endpoint; browser import preview is a separate v2 cookie/CSRF flow. | Keep the native importer on its explicit v1 contract until an equivalent native-authenticated flow is specified. |
| Capabilities | Android currently discovers server identity from v1 `/meta`; it does not use v2 feature flags as authorization. | V2 session advertises capabilities such as media, OCR, archive, import, and account actions. These indicate feature availability; endpoint authorization is separately enforced. | Capabilities are not grants and do not replace API auth. If surfaced in Android later, define a versioned native response and still handle server authorization/errors. |

## Evidence already available

- `android/app/src/main/kotlin/blue/anika/wolle/data/api/ProjectsApi.kt`, `YarnsApi.kt`,
  `CategoriesApi.kt`, and `SyncApi.kt` define the current Retrofit routes.
- `AuthInterceptor.kt`, `OnboardingValidator.kt`, and `PasswordTokenMinter.kt` show bearer PAT
  attachment, v1 validation, and optional PAT minting. `SettingsRepository.kt` owns credential
  persistence.
- `ProjectRepository.kt`, `YarnRepository.kt`, `CategoryRepository.kt`,
  `PendingMutationEntity.kt`, and `WriteReplayWorker.kt` define Room-first reads, cursor handling,
  queued writes, and replay behavior.
- `MediaUrlResolver.kt` and `PendingUploadStore.kt` define media URL safety and durable upload
  copies. Rust `media.rs` selects bearer authentication when an Authorization header is present;
  otherwise it checks a browser session. Rust v1's native pattern import remains bearer-authenticated;
  the website's import-preview route is a different v2 flow.
- `DeepLink.kt` and the manifest parse/declare both the legacy root paths and canonical `/app/...`
  SPA paths (SNA-75). Rust `lib.rs` mounts API v1 and v2 side by side; `web/src/lib/api.ts` is
  the website's v2 client.
- SNA-70's opt-in `RustBackendContractTest` covers the Android Retrofit DTOs, v1 categories,
  projects, yarns, favorites, sync, multipart uploads/deletes, and bearer-protected media reads
  against an isolated Rust fixture. SNA-71's opt-in instrumentation test covers Room repositories,
  `WriteReplayWorker`, queued writes, delta sync, deletion, and a project conflict against an
  isolated Rust fixture. Both are already recorded in `android/TODO.md`; do not rerun them as part
  of this documentation task.

These tests establish the exercised v1 surface, not v2 compatibility, deep-link redirect behavior,
all optional capabilities, or every self-hosted reverse-proxy configuration.

## Phased migration plan

1. **Keep the current contract stable.** Continue shipping the native client against Rust v1 while
   the website uses v2. Preserve PAT onboarding and the current Room/outbox behavior. No endpoint
   prefix change is needed for the website rollout.
2. **Freeze v1 compatibility.** Keep SNA-70 as the fixture-backed API contract test and SNA-71 as
   the offline replay integration test. Extend them when a v1 wire shape or server behavior changes;
   do not couple Android CI to a developer's live account or personal device.
3. **Choose a native auth contract before targeting v2.** Decide whether Rust will retain v1 for
   mobile or provide a documented mobile-native credential/session exchange. Browser cookies and
   browser CSRF are not a mobile auth design. Keep secrets in Android's existing encrypted
   credential store and avoid storing web-session cookies there.
4. **Introduce an explicit versioned client if needed.** Add distinct v2 envelopes, pagination,
   error, and write DTOs rather than mutating the v1 DTOs or changing only Retrofit's base path.
   Prove authentication and read/write behavior against the Rust fixture before switching any
   repository call.
5. **Specify sync before moving it.** Decide whether v1 delta sync remains the native contract. If
   Android ever uses bounded pages, drain them with opaque cursor handling and test snapshot
   consistency, tombstones, `full_resync_required`, local pending-write exclusions, and cursor
   commits. Do not persist a page cursor as the `since` timestamp.
6. **Migrate one vertical slice at a time.** Start with read-only categories or one resource list
   after auth and envelopes are settled; then detail, writes/conflicts, favorites, media, and only
   lastly sync. Keep v1 operational until each slice passes fixture contract tests.
7. **Preserve native media and link semantics.** Verify bearer media reads, multipart limits,
   same-origin/path-prefix URL resolution, queued file cleanup, and canonical plus legacy deep
   links. Treat the web UI's routes and feature flags as separate contracts from Android navigation
   and authorization.
8. **Gate rollout with isolated evidence.** Run Android unit/API contract tests and the offline
   instrumentation test against disposable seeded state; run lint/format/build through the
   established remote/CI gates. A Room migration is only in scope if a demonstrated data-model need
   cannot be handled by an API adapter, and must be reviewed separately with upgrade/rollback
   coverage.

## Follow-up boundaries

SNA-75 is the small application change justified by the canonical website paths; it changes only
native URL routing and does not change API authentication, server URLs, or account selection. The
parser accepts the two explicit app prefixes and legacy roots, not arbitrary reverse-proxy prefixes
that it cannot validate against the configured server base URL. A fixture-level server redirect
check can be added separately if link-sharing behavior needs end-to-end proof. If native v2 API
access is desired, first review a mobile authentication contract; do not prototype it by reusing the
browser login cookie.
