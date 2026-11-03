# Android and Rust server API v2 parity inventory

Task T121.4 (contracts). Status as of the `rust` branch at `b9ffdbb`. This file compares
what the Android app calls (or is prepared to call) with what `rust/src/api_v2/*.rs`
serves. It is an inventory, not a design: it changes no server or client code.

- Android side: `android/app/src/main/kotlin/blue/anika/wolle/data/api/` (`v2/*`,
  `ApiCapabilities.kt`, `AdminApi.kt`, `HistoryApi.kt`, `SyncApi.kt`,
  `ProjectImportApi.kt`), `sync/WriteReplayWorker.kt`, and the onboarding classes.
- Server side: `rust/src/api_v2/*.rs`.
- Executable contract: `rust/tests/api_v2_client_contract.rs`. It drives the composed
  application with a Bearer PAT the way OkHttp does (no cookie, no `Origin`, no CSRF
  header) and decodes every response with a Rust copy of the Kotlin DTO rules
  (required keys, nullable keys, non-null lists with defaults).
- Related, owned elsewhere (read-only here): `docs/web-v2-contract.md`,
  `android/docs/v2-migration.md`.

Legend for the "Status" column: **match** = both sides agree (the contract test pins
it), **client-only** = the Android code calls it but the server has no such route,
**server-only** = the server serves it but Android has no client, **mismatch** = both
exist but differ in a way the client must handle.

## Relevant commits

| Commit | Side | What it settles |
| --- | --- | --- |
| `3e1b8b2` api v2: support native PATs and current-user metadata | server | Bearer PAT (purpose `api`) accepted on the resource, media, sync, import, OCR, archive and admin v2 routes without `Origin` or CSRF; `GET /api/v2/meta` with `user` |
| `2d06142` api v2: add native password token login | server | `POST /api/v2/auth/token` returns `{data:{token}}`; 401 `invalid_credentials`, 422 `validation_error`, 429 `rate_limited` |
| `b30bb29` api v2: add bounded project and yarn delta sync | server | `GET /api/v2/sync/projects` and `/sync/yarns`, cursor paging, tombstones; categories pending |
| `72bfd10` android: route resource calls to /api/v2 when the server supports it (SNA-87 phase a) | client | `ApiCapabilities` probe, v2 for projects, yarns, categories, favourites and media, v1 kept for sync, token login, onboarding and URL import |
| `ebf19b7` android: only a server without v2 falls back to v1 (SNA-87) | client | Fallback policy, see below |

### `ApiCapabilities` fallback policy (`72bfd10`, `ebf19b7`)

One probe of `GET /api/v2/meta` with the Bearer token, cached per server URL and
token:

| Probe result | Client decision | Server behaviour that supports it |
| --- | --- | --- |
| 200 with `data.user` | v2, user known | PAT owner returned |
| 200 with `user: null` | v1 (cached) | anonymous request, or a request without a Bearer |
| 404 or 405 | v1 (cached) | a server that has no v2 |
| 401 `session_expired`, 403, 5xx, offline | the probe's own failure is thrown, nothing cached, next call re-probes | invalid, expired, revoked, inactive-owner or wrong-purpose Bearer is always 401 `session_expired`, even next to a valid session cookie |

Pinned by `meta_probe_reports_the_pat_owner_and_rejects_bad_bearers_without_cookie_fallback`.

## Cross-cutting contract

| Topic | Contract | Pinned by |
| --- | --- | --- |
| Auth | Strict `Authorization: Bearer <PAT>` of purpose `api`. Expired, revoked, unknown, browser-purpose and inactive-owner tokens, a non-Bearer scheme and a repeated `Authorization` header are 401 `session_expired`. An invalid Bearer never falls back to a session cookie. A valid PAT needs no `Origin` or `X-CSRF-Token`. | `every_android_route_requires_a_valid_bearer_and_never_falls_back_to_the_cookie`, `meta_probe_...` |
| Anonymous request | `GET` is 401 `session_expired`. A write with neither Bearer nor cookie is 403 `csrf_failed` (the browser origin check runs first). The app only omits the header when its token is blank (`AuthInterceptor`). | same |
| Success envelope | `{ "data": ... }` | all tests |
| List envelope | `{ "data": [...], "meta": { "page", "per_page", "has_more" } }`, page size 50, 1-based, omitted `page` is 1, a page past the end is an empty list with `has_more:false` | `list_pages_are_fifty_wide_...` |
| Error envelope | `{ "error": { "code", "message", "fields"?, "current"? } }`. `fields` values are strings. Never a v1 `detail`. | `assert_error` in every test |
| 409 conflict | `{ "error": { "code": "conflict", "message", "current": <full entity> } }` on project and yarn update when `expected_updated_at` is stale. `current` carries the same keys as the detail DTO (`_html` included). Category rename conflict has `fields.name` and no `current`. | `stale_writes_return_409_...` |
| Timestamps | `updated_at` is echoed verbatim as `expected_updated_at`; a malformed value is 422 `validation_error` with `fields.expected_updated_at` | same |
| 404 | Missing and foreign entities are both 404 `not_found` (no `current`) | `project_writes_decode_...` |
| Delete | `204` with an empty body; a repeat is 404 `not_found` (the app treats a 404 delete as success: `replayDelete`, `replayAttachmentDelete`, `replayImageDelete`, `replayPhotoDelete`) | `project_writes_...`, `media_upload_...`, `yarn_photos_...` |
| Media URLs | `url` and `thumbnail_url` are protected server-relative `/media/...` paths. The app uses them as returned with the same Bearer. Anonymous or invalid Bearer is a bare 401, another owner's file is a bare 403, a deleted file is a bare 404 (no v2 envelope: the app only reads the status). | `media_upload_delete_and_protected_urls_work_with_a_pat` |
| Caching | `Cache-Control` contains `no-store` | `meta_probe_...` |
| Tags | v2 keeps each array item as one label (`"winter gift"` stays one tag); v1 tokenised it. The app's editor splits on commas only. | `project_writes_decode_...` |

## Route inventory

"Android" names the Retrofit method or class. "Server" is the handler module in
`rust/src/api_v2/`.

### Capability probe, authentication

| Route | Android | Server | Status | Notes |
| --- | --- | --- | --- | --- |
| `GET /api/v2/meta` | `MetaV2Api.getMeta` -> `MetaV2Dto` | `meta.rs` | **match**, one optional gap | `{data:{version, build_id, is_admin, user:{id,email,is_admin}\|null}}`. Android also reads `user.avatar_url` (optional); the server does not emit it here (see gap 3). |
| `POST /api/v2/auth/token` | none; `PasswordTokenMinter` still calls `POST /api/v1/auth/token` and reads `{token}` | `native_auth.rs` | **server-only** | v2 body is `{email, password, token_name?}` and the answer is wrapped: `{data:{token}}`. 401 `invalid_credentials`, 422 `validation_error` (`fields`), 429 `rate_limited` + `Retry-After`. The app maps 401 and 403 to "invalid credentials" and everything else to `ServerError`. Pinned by `native_token_login_mints_a_pat_...`. |
| `GET /api/v1/meta` (onboarding), `GET /api/v1/categories` (token check) | `OnboardingValidator` | v1 router | client on v1 | The v2 equivalent is the `meta` probe above (200 for anyone, 401 for a bad Bearer). |

### Projects (`ProjectsV2Api`, `projects.rs`, `media.rs`)

| Method and path | Android request | Server request | Response | Status |
| --- | --- | --- | --- | --- |
| `GET /api/v2/projects` | `page`, `category`, `tag`, `favorite`, `q` | same query keys | `{data:[ProjectListItem], meta}` | **match** |
| `GET /api/v2/projects/{id}` | none | none | `{data: ProjectDto}` | **match** |
| `POST /api/v2/projects` | `ProjectWriteRequest` (default-valued keys omitted by Kotlin) | `ProjectWriteRequest`, unknown keys ignored | `201 {data: ProjectDto}` | **match** |
| `PUT /api/v2/projects/{id}` | same plus `expected_updated_at`, step `id` | same | `200 {data}`; 409 with `error.current`; non-empty `image_urls` (or step `image_urls`) is 422 `fields.image_urls` | **match** (the app never sends `image_urls` on update) |
| `DELETE /api/v2/projects/{id}` | none | none | `204`; repeat `404` | **match** |
| `PUT /api/v2/projects/{id}/favorite` | none | none | `200 {data: ProjectDto}`, idempotent | **match** |
| `DELETE /api/v2/projects/{id}/favorite` | none | none | `200 {data: ProjectDto}`, idempotent | **match** |
| `POST /api/v2/projects/{id}/favorite` | not used (v1 verb) | no route | `405` | **match** (the v1 verb is deliberately gone) |
| `POST /api/v2/projects/{id}/images/title` | multipart `file`, optional `alt_text` | same parts | `201 {data: Image}` | **match** |
| `POST /api/v2/projects/{id}/steps/{stepId}/images` | multipart `file`, optional `alt_text` | same | `201 {data: Image}` with `step_id` | **match** |
| `POST /api/v2/projects/{id}/attachments` | multipart `file` | same | `201 {data: Attachment}` | **match** |
| `DELETE /api/v2/projects/{id}/images/{imageId}` | none | none | `204`; repeat `404` | **match** |
| `DELETE /api/v2/projects/{id}/attachments/{attachmentId}` | none | none | `204`; repeat `404` | **match** |

`ProjectDto` (server) is `ProjectV2Dto` (client): the v1 fields plus `description_html`,
`notes_html`, `other_materials_html` and `steps[].description_html`. The client drops the
`_html` fields (`toV1()`). `images[]`, `attachments[]` and the yarn `photos[]` are raw JSON
on the server, so their shape is only guaranteed by tests: `ImageDto` needs `id`, `url`,
`thumbnail_url`, `alt_text`, `image_type`, `is_title_image`, `is_stitch_sample` as non-null.

### Yarns (`YarnsV2Api`, `yarns.rs`, `media.rs`)

| Method and path | Notes | Status |
| --- | --- | --- |
| `GET /api/v2/yarns` | query `page`, `favorite`, `q` (no `category` or `tag`); `{data:[YarnListItem], meta}` | **match** |
| `GET`, `POST`, `PUT`, `DELETE /api/v2/yarns[/{id}]` | same rules as projects; 409 `error.current` is a `YarnV2Dto`; negative `weight_grams` or `length_meters` is 422 | **match** |
| `PUT`, `DELETE /api/v2/yarns/{id}/favorite` | idempotent, `200 {data: YarnDto}`; `POST` is `405` | **match** |
| `POST /api/v2/yarns/{id}/photos` | multipart `file`; `201 {data: Photo}`; the first photo is `is_primary` | **match** |
| `DELETE /api/v2/yarns/{id}/photos/{photoId}` | `204`; deleting the primary promotes the next photo; repeat `404` | **match** |

### Categories (`CategoriesV2Api`, `categories.rs`)

| Method and path | Notes | Status |
| --- | --- | --- |
| `GET /api/v2/categories` | `{data:[{id,name}]}` (not paged) | **match** |
| `POST /api/v2/categories` | `201 {data}`; creating an existing name is idempotent | **match** |
| `PUT /api/v2/categories/{id}` | rename collision is 409 `conflict` with `fields.name`, no `current` | **match** |
| `DELETE /api/v2/categories/{id}` | `204` | **match** |

### Sync

| Route | Android | Server | Status |
| --- | --- | --- | --- |
| `GET /api/v2/sync/projects`, `GET /api/v2/sync/yarns` | none; `SyncApi` calls `/api/v1/sync/*` and decodes the bare v1 body | `sync.rs`: `{data:{updated:[full DTO], deleted_ids, server_time, full_resync_required (always false), has_more, next_cursor}}`; query `since` or `cursor` (not both), `limit` 1..50 | **server-only** (phase b) |
| `GET /api/v2/sync/categories` | none; `SyncApi.syncCategories` on v1 | no route | gap 2 |

The server's `updated[]` entries are byte-identical to the detail DTO, so `toV1()` applies
unchanged. `server_time` is passed back verbatim as `since`. Pinned by
`sync_reports_native_writes_with_the_detail_shape_and_tombstones_deletes`.

### Administrator (`AdminApi`, `admin.rs`, `audit.rs`)

| Method and path | Status | Notes |
| --- | --- | --- |
| `GET /api/v2/admin/audit?page&entity&action` and `GET /api/v2/admin/audit/{id}` | **match** | `AuditPageDto` and `AuditDetailEnvelopeDto`; a PAT of a non-admin is 403 `admin_required` |
| `GET /api/v2/admin/users?page`, `POST /api/v2/admin/users`, `PATCH /api/v2/admin/users/{id}/active`, `POST /api/v2/admin/users/{id}/reset-password` | **match** | covered in depth by `api_v2_admin_pat.rs` and `api_v2_admin.rs` |

### History

| Route | Android | Server | Status |
| --- | --- | --- | --- |
| `GET /api/v2/projects/{id}/history[?page]`, `GET /api/v2/projects/{id}/history/{eventId}`, same for `/yarns/{id}/...` | `HistoryApi`, `HistoryRepository` | `resource_history.rs` (`a85e4b5`): PAT or cookie read, owner-scoped, 20 events per page, `page` of 0 or above 10000 is 400 `invalid_query`, foreign or mismatched event is 404 `not_found` | **match** (served since `a85e4b5`; its contract is in `api_v2_resource_history.rs`, the Bearer requirement is pinned here) |
| `GET /api/v2/activity[?limit&before]` | not yet (Home "Recent activity" to come) | `activity.rs`: PAT or cookie read, owner-scoped feed of project and yarn events, coalesced server side; `limit` 1-50 (default 10), `before` is an event id, bad query is 400 `invalid_query`; contract in `api_v2_activity.rs` and `web-v2-contract.md` | **server ready** |

### Server routes Android does not use

| Route | Auth | Notes |
| --- | --- | --- |
| `GET /api/v2/session`, `POST /api/v2/auth/login`, `POST /api/v2/auth/logout`, `POST /api/v2/auth/signup` | cookie and CSRF | browser only |
| `GET`, `POST`, `PUT`, `DELETE /api/v2/account...` (profile, API tokens, password, avatar) | cookie and CSRF only (`browser_user`, `mutation_user`), a PAT is refused | the app cannot manage its own tokens, password or avatar over v2 |
| `POST /api/v2/projects/preview`, `POST /api/v2/tools/gauge`, `POST /api/v2/utils/ocr` | PAT accepted (`resource_mutation_user`) | no Android client |
| `GET`, `POST /api/v2/projects/{id}/archive`, `/yarns/{id}/archive` | PAT accepted | no Android client |
| `POST /api/v2/imports/preview`, `POST /api/v2/imports/preview-file` | PAT accepted | Android imports through `POST /projects/import` instead (gap 5) |
| `GET /api/v2/admin/resources/...`, `GET /api/v2/admin/users/{id}/avatar/{filename}` | admin | web admin identity views |

## Gaps

1. **Owner history: resolved.** `HistoryApi` was client-only when this inventory started; the
   server gained the routes in `a85e4b5` with the 20-per-page shape the client expects. Old
   servers still answer 404 or 405 and the app hides the section.
2. **No `GET /api/v2/sync/categories`.** The client's `SyncApi.syncCategories` expects (on v1)
   `{updated:[{id,name}], deleted_ids, server_time}`; the server documents it as pending a
   storage migration. The app keeps all sync on v1 until this and a `SyncV2Api` exist.
3. **`GET /api/v2/meta` omits `user.avatar_url`.** `MetaV2UserDto.avatarUrl` is optional and
   tolerated, but the app wants the `/media/thumbnails/users/{id}/thumb_<hash>.jpg` path that
   `GET /api/v2/session` already returns. (Concord `T121.1.avatar` is working on it; the
   contract test accepts either absent or a string.)
4. **Sync envelope is new for the client.** v2 wraps the body in `{data:{...}}` and adds
   `has_more` and `next_cursor`. `ProjectSyncResponseDto` and `YarnSyncResponseDto` decode the
   bare v1 body, so a `SyncV2Api` and `{data}` DTO are required, not just a path change.
5. **URL import mismatch.** Android posts form-encoded `type`, `url`, `use_ai` to the web route
   `POST /projects/import` and decodes `ProjectImportResponseDto`. The v2 equivalent is
   `POST /api/v2/imports/preview` with JSON `{type:"url", url, use_ai}` returning
   `{data:{draft:{...}}}` (different shape, `import_unavailable` and `rate_limited` errors).
   The server accepts a PAT there; the client has no mapper.
6. **Token login and onboarding still on v1.** `PasswordTokenMinter` reads `{token}` from
   `/api/v1/auth/token`; v2 answers `{data:{token}}` and uses 422 and 429 where v1 used other
   statuses. The `OnboardingValidator` calls v1 `meta` and `categories`. Switching them needs
   the unwrapping and the extra status mapping.
7. **Anonymous write is 403, not 401.** A write with no `Authorization` header and no cookie is
   403 `csrf_failed` (the origin check precedes the session check). The app omits the header
   only when its token is blank, and `ApiCapabilities` treats 403 as "undecided", so this is
   harmless, but it differs from the 401 `session_expired` the app expects for bad tokens.
8. **`/media/...` errors are not v2 envelopes.** Anonymous, invalid-Bearer and foreign-owner
   requests are bare 401 or 403 (a foreign API entity is 404). The app reads only the status.
9. **Behavioural, not structural.** v2 keeps tags verbatim (v1 tokenised them), update rejects
   `image_urls`, and a PAT's bad-token code is `session_expired` (not "token invalid"), so the
   UI must not say "session expired" for a PAT.

Not verified and not covered by a test: `page=0`, negative or non-numeric `page` handling,
and v2 behaviour under rate limits for the import and OCR routes.
