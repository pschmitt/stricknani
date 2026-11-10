# Android and Rust server API v2 parity inventory

Tasks T121.4 (contracts) and T121.5 (offline/account isolation, v1 retirement blockers).
Status as of `main` at `84ac5267` (Version 1.6.9 plus the T121 closure commits). This file
compares what the Android app calls with what `src/api_v2/*.rs` serves. It is an inventory, not a
design: it changes no route. The previous revision was written at `b9ffdbb`; every row below was
re-checked against the current Kotlin interfaces and Rust routers.

- Android side: `android/app/src/main/kotlin/blue/anika/wolle/data/api/` (`v2/*`,
  `ApiCapabilities.kt`, `AdminApi.kt`, `HistoryApi.kt`, v1 `*Api.kt`), `data/account/`,
  `data/oidc/`, `data/export/`, `data/imports/`, `data/onboarding/`, `data/timer/`,
  `sync/WriteReplayWorker.kt`.
- Server side: `src/api_v2/*.rs`.
- Executable contract: `tests/api_v2_client_contract.rs`. It drives the composed application with
  a Bearer PAT the way OkHttp does (no cookie, no `Origin`, no CSRF header) and decodes every
  response with a Rust copy of the Kotlin DTO rules (required keys, nullable keys, non-null lists
  with defaults). 15 tests; the six added for T121.4 are marked "(T121.4)" below.
- Related: `docs/web-v2-contract.md`, `android/docs/v2-migration.md`,
  `android/docs/rust-api-transition.md`.

Legend for "Status": **match** = both sides agree and a test pins it, **match, pinned elsewhere**
= pinned by another `tests/api_v2_*.rs` suite rather than the client contract, **server-only** =
served, no Android client, **v1 fallback** = Android still has a v1 code path for it, **gap** =
something to decide.

## Relevant commits

| Commit | Side | What it settles |
| --- | --- | --- |
| `3e1b8b2` | server | Bearer PAT (purpose `api`) accepted on resource, media, sync, import, OCR, archive and admin v2 routes without `Origin` or CSRF; `GET /api/v2/meta` with `user` |
| `2d06142` | server | `POST /api/v2/auth/token` returns `{data:{token}}` (401 `invalid_credentials`, 422, 429) |
| `b30bb29` and the T121.2 category commits | server | `GET /api/v2/sync/{projects,yarns,categories}`, cursor paging, tombstones |
| `72bfd10`, `ebf19b7` | client | `ApiCapabilities` probe; v2 for resources, favourites, media; v1 only for a server without v2 |
| T121.6 | server | media positions and the three exact-set reorder routes |
| T121.7, T121.8 | client, server | v2 sync, token login, categories sync; Bearer access to the account routes |

### `ApiCapabilities` fallback policy

One probe of `GET /api/v2/meta` with the Bearer token, cached per server URL and token:

| Probe result | Client decision |
| --- | --- |
| 200 with `data.user` | v2, user known |
| 200 with `user: null`, 404, 405, or a 401/403 without a v2 error body | v1 (cached) |
| 401 `session_expired`, 403 with a v2 body, 5xx, offline | the failure is thrown, nothing cached, next call re-probes |

Pinned by `meta_probe_reports_the_pat_owner_and_rejects_bad_bearers_without_cookie_fallback` and
`ApiCapabilitiesTest`.

## Cross-cutting contract

| Topic | Contract | Pinned by |
| --- | --- | --- |
| Auth | Strict `Authorization: Bearer <PAT>` of purpose `api`. Expired, revoked, unknown, browser-purpose, inactive-owner tokens, a non-Bearer scheme and a repeated header are 401 `session_expired`. An invalid Bearer never falls back to a cookie. A valid PAT needs no `Origin` or `X-CSRF-Token`. | `every_android_route_requires_a_valid_bearer_...`, `meta_probe_...` |
| Anonymous request | `GET` is 401 `session_expired`; a write with neither Bearer nor cookie is 403 `csrf_failed` (origin check first). The app only omits the header when its token is blank. | same |
| Success envelope | `{ "data": ... }` | all tests |
| List envelope | `{ "data": [...], "meta": { "page", "per_page", "has_more" } }`, 50 per page, 1-based; a page past the end is empty with `has_more:false` | `list_pages_are_fifty_wide_...` |
| Error envelope | `{ "error": { "code", "message", "fields"?, "current"? } }`; `fields` values are strings; never a v1 `detail` | `assert_error` everywhere |
| 409 conflict | `{ error: { code:"conflict", current: <full entity> } }` on stale `expected_updated_at`; a category rename collision is 409 with `fields.name` and no `current` | `stale_writes_return_409_...` |
| 404 | missing and foreign entities are the same 404 `not_found` | all |
| 422 | `validation_error` with `fields`; media order, alt text and timer bodies are `deny_unknown_fields` | `media_order_...`, `photo_descriptions_...`, `project_timer_...` (T121.4) |
| Delete | `204` empty; a repeat is 404 (the app treats a 404 delete as success) | `project_writes_...`, `media_upload_...`, `yarn_photos_...` |
| Media URLs | protected server-relative `/media/...` paths used with the same Bearer; failures are bare 401/403/404 (no envelope) | `media_upload_delete_and_protected_urls_...` |
| Caching | `Cache-Control` contains `no-store` | `meta_probe_...` |
| Tags | v2 keeps each array item as one label; v1 tokenised it | `project_writes_...` |

## Route inventory

"Android" names the Retrofit method or class.

### Capability probe, authentication, onboarding

| Route | Android | Status |
| --- | --- | --- |
| `GET /api/v2/meta` | `MetaV2Api` (`ApiCapabilities`, settings, error reporting); `OnboardingValidator`, `RegistrationClient`, `OidcLoginApi`, `ServerApiProbe` call it directly | **match**; `user.avatar_url` optional (T121.1 avatar follow-up) |
| `POST /api/v2/auth/token` | `PasswordTokenMinter`: v2 when the probe says so (reads `{data:{token}}`), else `POST /api/v1/auth/token` | **match**, **v1 fallback** |
| `POST /api/v2/auth/register` | `RegistrationClient` | **match, pinned elsewhere** (`api_v2_signup.rs`, `RegistrationClientTest`) |
| `POST /api/v2/auth/oidc/start`, `/exchange` | `OidcLoginApi` | **match, pinned elsewhere** (`api_v2_oidc_login.rs`) |
| `GET /api/v1/meta`, `GET /api/v1/categories` | `OnboardingValidator.checkIsStricknaniServer` / `checkTokenAuthorizes`, only when the v2 probe says "unsupported" | **v1 fallback** |

### Projects, yarns, categories

| Method and path | Android | Status |
| --- | --- | --- |
| `GET /api/v2/projects`, `GET /projects/{id}`, `POST`, `PUT`, `DELETE` | `ProjectsV2Api` | **match**; **v1 fallback** (`ProjectsApi`) |
| `PUT`/`DELETE /api/v2/projects/{id}/favorite` | `ProjectsV2Api`; v1 used `POST` | **match**, **v1 fallback**; `POST` is 405 on v2 |
| `GET /api/v2/projects/tags` | not used (editor suggests from the cache) | server-only |
| `POST .../images/title`, `.../steps/{id}/images`, `.../attachments`; `DELETE .../images/{id}`, `.../attachments/{id}` | `ProjectsV2Api` | **match**, **v1 fallback** |
| `PUT /api/v2/projects/{id}/images/order`, `.../steps/{stepId}/images/order` | `reorderTitleImages`, `reorderStepImages`; body `{ids}`, answer `{ids, updated_at}`; 422 for a partial/unknown/foreign-group list, 404 for a foreign step | **match** (T121.4: `media_order_routes_...`); v2 only, queued as `PROJECT_*_IMAGE_ORDER` |
| `PATCH /api/v2/projects/{id}/images/{imageId}` | `PhotoEditV2Api.updateProjectImageAlt`, body `{alt_text}`, answer full image; empty falls back to the file name | **match** (T121.4: `photo_descriptions_...`); v2 only, online only |
| `POST /api/v2/projects/{id}/images/{imageId}/edit` | `PhotoEditV2Api.editProjectImage` (rotate, crop) | **match, pinned elsewhere** (`api_v2_media.rs`) |
| `GET`/`PUT /api/v2/projects/{id}/timer` | `getProjectTimer`, `updateProjectTimer`; body `{action: start\|stop\|set_total, total_seconds?}`, answer `ProjectTimerDto` incl. `started_by` | **match** (T121.4: `project_timer_...`); v2 only |
| `PUT`/`DELETE /api/v2/projects/{id}/timer/reminder`, `GET /push/key`, `PUT`/`DELETE /push/subscription` | none (web push) | server-only |
| `GET`, `POST`, `PUT`, `DELETE /api/v2/yarns[/{id}]`, favourite `PUT`/`DELETE`, `POST .../photos`, `DELETE .../photos/{id}` | `YarnsV2Api` | **match**, **v1 fallback** (`YarnsApi`) |
| `PUT /api/v2/yarns/{id}/photos/order` | `YarnsV2Api.reorderPhotos` | **match** (T121.4); v2 only |
| `PATCH /api/v2/yarns/{id}/photos/{photoId}`, `POST .../photos/{id}/edit` | `PhotoEditV2Api` | **match** (T121.4 for `PATCH`); v2 only |
| `GET`, `POST`, `PUT`, `DELETE /api/v2/categories[/{id}]` | `CategoriesV2Api` | **match**, **v1 fallback** (`CategoriesApi`) |

`ProjectDto` is `ProjectV2Dto` plus `*_html` fields the client drops (`toV1()`); `images[]`,
`attachments[]` and `photos[]` are raw JSON on the server, so their shape is guaranteed only by
the tests.

### Sync

| Route | Android | Status |
| --- | --- | --- |
| `GET /api/v2/sync/projects`, `/yarns` | `SyncV2Api`, `runScopedV2Sync` (`scope` query for administrators, 409 `scope_mismatch` restarts as a full resync); `{data:{updated, deleted_ids, server_time, full_resync_required, has_more, next_cursor, scope?}}` | **match**, **v1 fallback** (`SyncApi`, bare v1 body) |
| `GET /api/v2/sync/categories` | `SyncV2Api.syncCategories`, `CategoryRepository.syncV2` | **match** (T121.4: `category_sync_pages_...`; deep suite `api_v2_category_sync.rs`), **v1 fallback** |

### Account, security, export

| Route | Android | Status |
| --- | --- | --- |
| `GET`, `PATCH /api/v2/account` | `AccountV2Api` (`use_gravatar`, `detail_card_order`) | **match** (T121.4: `account_token_routes_...`) |
| `GET`, `POST /api/v2/account/api-tokens`, `DELETE .../{id}`, `PATCH .../current` | `AccountV2Api`; create needs `current_password` (step-up) from a PAT, revoke answers 200 `{id, revoked, current_token}` | **match** (T121.4); details in `api_v2_account_bearer.rs` |
| `PUT /api/v2/account/password`, `POST`/`DELETE /account/avatar` | `AccountV2Api` | **match, pinned elsewhere** (`api_v2_account_bearer.rs`) |
| `POST /api/v2/account/delete` | `AccountDeletion` | **match, pinned elsewhere** (`api_v2_account_delete.rs`) |
| `GET`/`POST /api/v2/account/export`, ticket download, `POST /account/import` | `DataExportApi`, `DataRestoreApi` | **match, pinned elsewhere** (`api_v2_export.rs`, `api_v2_restore.rs`) |
| `/api/v2/account/identities*`, `/account/reauth*` | `OidcAccountApi` | **match, pinned elsewhere** (`api_v2_oidc_account.rs`) |
| `POST /api/v2/auth/login`, `/auth/logout`, `/auth/signup`, `GET /session` | none | server-only (browser, cookie and CSRF) |

### Imports, archive, sharing, history, admin

| Route | Android | Status |
| --- | --- | --- |
| `GET`, `POST /api/v2/imports/drafts`, `GET`, `DELETE .../{id}` | `ImportsV2Api` (`ImportDrafts`; skipped when the probe is not v2) | **match, pinned elsewhere** (`api_v2_imports.rs`) |
| `POST /api/v2/imports/preview-file`, `GET /imports/image` | `ImportsV2Api`, `ImportImagePicker` | **match, pinned elsewhere** |
| `POST /api/v2/imports/preview` | none (Android uses drafts) | server-only |
| `POST /api/v2/projects/preview`, `/tools/gauge`, `/utils/ocr` | none | server-only |
| `GET`, `POST /api/v2/{projects,yarns}/{id}/archive` | `ArchiveV2Api` | **match, pinned elsewhere** (`api_v2_archive.rs`) |
| shares, leave, transfer, `yarn-candidates`, `/people` | `SharingV2Api` | **match, pinned elsewhere** (`api_v2_sharing.rs`) |
| `GET /api/v2/{projects,yarns}/{id}/history[/{eventId}]`, `GET /activity` | `HistoryApi`, `ActivityV2Api` | **match, pinned elsewhere** (`api_v2_resource_history.rs`, `api_v2_activity.rs`) |
| `/api/v2/admin/audit`, `/admin/users*`, identities of other users | `AdminApi`, `OidcAccountApi` | **match, pinned elsewhere** (`api_v2_admin*.rs`, `api_v2_audit.rs`) |
| `/api/v2/admin/resources/...`, impersonation, `POST /telemetry/sentry` | none | server-only |

## Differences the client has to know (v1 to v2)

| Area | v1 | v2 |
| --- | --- | --- |
| Envelope | bare body | `{data}`; lists add `meta` |
| Errors | FastAPI `{detail}` | `{error:{code,message,fields?,current?}}` |
| Favourite | `POST` toggles | `PUT` sets, `DELETE` clears, both idempotent |
| Pagination | `page` plus `size` style | fixed 50 per page, 1-based, `has_more` |
| Sync | bare body, `since` only | enveloped, `since` or `cursor`, `limit` 1..50, `has_more`, `scope` |
| Tags | tokenised | verbatim items |
| Update with images | `image_urls` accepted | non-empty `image_urls` is 422 `fields.image_urls` |
| Token login | `{token}`, 401/403 | `{data:{token}}`, 401 `invalid_credentials`, 422, 429 `rate_limited` |
| Bad Bearer | 401 `{detail}` | 401 `session_expired` (never cookie fallback) |
| Media order, alt text, timer, shares, history, archive, drafts, account | absent | v2 only |

## Open parity items

1. **v1 fallbacks still compiled in** for projects, yarns, categories, sync, token login and
   onboarding; see T121.5 below. Every v2 route Android calls has a pinned contract test now.
2. **Anonymous write is 403 `csrf_failed`, not 401** (origin check first). Harmless:
   `ApiCapabilities` treats 403 as undecided and the app only omits the header with a blank token.
3. **`/media/...` errors are not v2 envelopes** (bare 401/403/404); the app reads only the status.
4. **`GET /api/v2/meta` omits `user.avatar_url`**; the field is optional on the client.
5. **Behavioural**: a PAT's bad-token code is `session_expired`; the UI must not claim a browser
   session expired.

Not verified: `page=0`, negative or non-numeric `page`, and rate limits on the import and OCR
routes.

## T121.5: offline queue, account isolation and v1 retirement blockers

v1 stays available. Nothing here removes or changes a v1 route.

### Offline queue and account isolation: how it works and the evidence

The Android app is single-account per install. There is one Room database; queued writes live in
the `pending_mutations` table with no account column, and the media-upload bytes sit in
`filesDir/pending-uploads`. Isolation therefore rests on four mechanisms:

1. **Sign-out and account deletion wipe everything**: `SettingsViewModel.signOut` clears the
   credentials, the timers, `database.clearAllTables()` (cache, cursors, queue) and, since T121.5,
   `pending-uploads`. `accountDeleted` additionally clears the image cache.
2. **Re-sign-in keeps the queue only for the same account**: the OIDC path compares the new
   token's email with the saved one (`OidcFailure.AccountMismatch`); the password "sign in again"
   path now does the same (`isSameAccountForSignInAgain`, T121.5).
3. **Credentials key the capability cache** (`serverUrl` + token), so one account's probe, identity
   and admin flag never carry over.
4. **An expired session pauses replay** (`WriteReplayWorker.doWork` returns early while
   `sessionExpired`); a stale 401 cannot flag a new token (`markSessionExpired(tokenUsed)`).

Existing tests (JVM unless marked):

| Concern | Test |
| --- | --- |
| Queued writes: coalescing, temp-id rebasing, timestamp carried | `data/repository/QueuedUpdateRequestTest`, `ProjectMediaOrderRebaseTest`, `MediaOrderingTest`, `data/uploads/ProjectAttachmentMutationTest` |
| Queued writes survive sync and tombstones until replayed | `data/repository/SharedSyncTest` (`queued local writes win over a delta ...`, `a queued local write keeps its row out of the changed ids`), `EveryoneScopeTest` (`a demoted administrator's foreign rows are purged except queued writes`) |
| Retry versus conflict classification, 409 `current` | `sync/WriteReplayWorkerTest` (`WriteReplayWorkerV2ConflictTest`), `ui/common/SyncIssuePresentationTest`, `SyncFailureReasonTest`, `data/errorreporting/ErrorReportingTest` |
| Interrupted sync repeats from the old cursor, same delta twice is idempotent | `SharedSyncTest` (`an interrupted run is simply repeated ...`, `replaying the same delta twice ...`), `data/api/v2/SyncV2Test`, `ScopedSyncTest` |
| Per-scope cursors, fresh database after sign-out | `EveryoneScopeTest` (`each scope has its own cursor`, `a fresh database after sign-out starts from the defaults`) |
| Capability cache per account, sign-out drops identity | `data/api/ApiCapabilitiesTest` (`the answer is kept per server url and token`, `a failure after a good probe for other credentials does not leak the old answer`, `signing out drops the cached v2 identity`) |
| Stale 401 of an old token, expiry detection | `data/api/SessionExpiryTest`, `data/settings/AuthInfoTest` |
| OIDC re-sign-in refuses another account | `data/oidc/OidcCoordinatorTest` (`AccountMismatch`) |
| Timers and reminders per account key | `data/timer/RunningTimerControllerTest` (`the stop action does nothing for another account ...`), `ProjectTimerReminderPolicyTest` |
| Account deletion wipes local data | `data/account/AccountDeletionRepositoryTest` |
| `clearAllTables` removes shared rows, cursors, queue, filter (instrumented) | `androidTest/.../data/db/AppDatabaseMigrationTest` |
| Replay against a real Rust backend (instrumented) | `androidTest/.../sync/RustBackendReplayIntegrationTest`, `RustBackendContractTest` (JVM) |

Gaps found and closed in this task:

- **Password "sign in again" accepted any account.** `SignInAgainViewModel` kept the cache and the
  queue and replayed them under whatever email was typed, so account A's queued creates and
  edits could land under account B (the OIDC path already refused). Fixed with
  `isSameAccountForSignInAgain`; new JVM test `ui/signin/SignInAgainAccountTest` (same account
  case-insensitively, another account refused, unknown saved email from a QR setup accepted).
  Residual: a QR-configured session has no saved email, so a different account cannot be
  detected there until the saved email is learned from `GET /api/v2/meta` (`user.email`).
- **Plain sign-out left `pending-uploads` on disk** (only account deletion removed it). The rows
  were gone, so nothing replayed, but the previous account's picked photos and PDFs stayed in app
  storage. `signOut` now deletes the directory.

Remaining, not changed: `clearAllTables` and the file wipe are not unit-testable on the JVM (Room
and `Context`); the instrumented `AppDatabaseMigrationTest` is the evidence and was not run in
this task (no device).

### Every remaining v1 caller and what blocks retirement

**Android (`android/app/src/main/kotlin/blue/anika/wolle/`)**

| File | v1 routes | Used when | Blocker |
| --- | --- | --- | --- |
| `data/api/ProjectsApi.kt`, `YarnsApi.kt`, `CategoriesApi.kt` | `/api/v1/projects[...]` (CRUD, favourite `POST`, media), `/yarns[...]`, `/categories[...]` | `ApiCapabilities` says v1: a server without v2, or a 200 meta without `user` | Retiring v1 requires a minimum-server-version decision: an installed app talking to an older server needs these. Repositories (`ProjectRepository`, `YarnRepository`, `CategoryRepository`) branch on `capabilities.useV2()`. |
| `data/api/SyncApi.kt` | `/api/v1/sync/{projects,yarns,categories}` | v1 fallback of the three repositories' `syncDetailed` | same; v1 and v2 share the stored `since` cursor (`server_time`, verbatim), so switching generation needs no resync |
| `data/api/MetaApi.kt` | `/api/v1/meta` | provided in `NetworkModule` but no remaining caller (dead code, safe to delete) | none |
| `data/onboarding/OnboardingValidator.kt` | `/api/v1/meta`, `/api/v1/categories` | the v2 probe says "unsupported" | old-server support |
| `data/onboarding/PasswordTokenMinter.kt` | `/api/v1/auth/token` | probe says v1 | old-server support |
| `data/api/SessionExpiryInterceptor.kt` | recognises a `/api/v1/` 401 as a rejected token | v1 fallback | goes with the fallback |
| `androidTest/.../e2e/StricknaniE2eTest.kt` | reads fixture state through `/api/v1/yarns/{id}`, `/projects/{id}` | the instrumented journey | switch the verifier to v2 reads |
| `docs`, `android/docs/*` | mention v1 | history | docs only |

No Android code path calls v1 for: media order, alt text, timer, account, history, activity,
sharing, archive, drafts, admin, OIDC. Those exist on v2 only.

**Web (`web/`)**

| Caller | Routes | Blocker |
| --- | --- | --- |
| `web/src` | none (the SvelteKit app uses v2 only) | none |
| `web/e2e/lib/seed.ts` and `seed.test.ts`, `summarize-e2e.test.ts` | `POST /api/v1/auth/token`, `/categories`, `/yarns`, `/projects`, `.../photos`, `.../images/title`, `GET /yarns/{id}`, `/projects/{id}`, lists | the Playwright fixture seeds and verifies through v1; port to v2 (token via `/api/v2/auth/token`, v2 writes and reads) |

**Server-internal and legacy**

| Where | v1 use | Blocker |
| --- | --- | --- |
| `src/lib.rs` | nests `/api/v1` = `auth`, `categories`, `yarns`, `projects`, `sync`, `/meta` | the routers themselves; removal deletes `src/{auth,categories,yarns,projects,sync}.rs` v1 handlers and their `tests/{categories,yarns,projects,sync,relations,security,pwa,...}.rs` suites |
| `src/media.rs` | `/api/v1/projects/.../images/title`, `/steps/.../images`, `/attachments`, `/yarns/.../photos`, deletes | v1 media routes; shared helpers with v2 `media.rs` |
| `src/archive_service.rs` | `POST /api/v1/projects/{id}/archive`, `/yarns/{id}/archive` | v2 `archive.rs` exists; v1 routes have no known caller besides tests (`tests/archive_service.rs`) |
| `src/import_service.rs` | `POST /projects/import` (`android_import`, legacy Android URL import) and the form routes `/imports/preview`, `/imports/preview-file`, `/imports/create-project`, `/imports/create-yarn`; the last two call the router in-process at `/api/v1/projects` and `/api/v1/yarns` (`api_call`) | the in-process calls depend on v1 create handlers; `/projects/import` is no longer called by the current app (it uses drafts), but older installed APKs still use it. Inventory installed clients first |
| `src/ocr_service.rs` | legacy `POST /utils/ocr` (not under `/api/v1`) built on the `core` router | v2 `POST /api/v2/utils/ocr` exists; confirm no installed client uses the legacy route |
| `src/legacy_redirects.rs` | no v1; `GET`-only redirects of old page paths to `/app/` | independent of v1 retirement |
| `tests/*` | `image_import`, `ai`, `audit`, `importer`, `import_service`, `media`, `media_ids`, `security`, `pwa`, `api_v2.rs`, `api_v2_token.rs`, `api_v2_sync.rs`, `api_v2_sharing.rs`, `api_v2_admin_*`, `api_v2_category_sync.rs` mention `/api/v1` | several v2 suites use v1 only as the oracle for an old behaviour or to seed data; each needs a v2 equivalent before the v1 handlers go |
| `src/ai.rs` | `openrouter.ai/api/v1` | unrelated (external provider) |

### Blockers, in order

1. **Installed-client inventory.** Android builds that probe v2 never fall back, but any build
   before the capability probe (`72bfd10`) and any app talking to a server that has not been
   upgraded uses v1 for everything. Decide a minimum app version and server version, and watch the
   `/api/v1` request count on the server (no such metric exists today; add one before retiring).
2. **Android fallback code** in the three repositories, `SyncApi`, `OnboardingValidator`,
   `PasswordTokenMinter` and `SessionExpiryInterceptor` is the only thing that keeps an old
   server working; remove it in one app release together with the minimum-server requirement.
3. **Legacy `POST /projects/import`** for already-installed APKs (and its in-process v1 calls).
4. **Web e2e seed** (`web/e2e/lib/seed.ts`) and the Android instrumented e2e verifier read and
   write through v1.
5. **Rust test suites** that exercise the v1 handlers (the list in the table above) must be
   ported or deleted with them; shared helpers in `src/media.rs` and `src/archive_service.rs`
   must be split from the v1 routes first.
6. **Behavioural differences to communicate** (the v1 to v2 table above), notably tag handling
   and the favourite verb, for any third-party v1 consumer (PAT scripts).
