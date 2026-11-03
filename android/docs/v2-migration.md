# Android: migrating from `/api/v1` to `/api/v2` (SNA-87)

Status: groundwork only. Nothing in the app calls v2 yet. Server facts below were read from
`rust/src/**` and `docs/web-v2-contract.md` in the `rust` branch (2026-11-03); the backend agent is
still changing them, so re-check each slice before switching it.

## 0. Server state today

- v1 (`/api/v1`, `rust/src/{projects,yarns,categories,sync,auth,media}.rs`) is Bearer-PAT only.
- v2 resource routes (`rust/src/api_v2/*.rs`) use `browser_user`/`mutation_user`: a browser session
  cookie, plus `Origin` and `X-CSRF-Token` on writes. A `Bearer` header is not looked at. The only v2
  routes that accept a PAT are `/api/v2/admin/*` (already used by `AdminApi`).
- v2 already exists for projects, yarns, categories, media upload/delete, favorites and deletes, but
  with cookie auth only. Missing for the app: PAT auth on those routes, sync, token login, a `me`
  endpoint (see section 6).
- All v2 responses send `Cache-Control: no-store`.

## 1. Cross-cutting differences

| Topic | v1 | v2 | App impact |
| --- | --- | --- | --- |
| Success envelope | bare object / `{items,...}` / bare array | `{ "data": ... }`; lists `{ "data": [...], "meta": {...} }` | New `V2Envelope<T>`, `V2ListEnvelope<T>` (`data/api/v2/V2Envelopes.kt`); mappers unwrap |
| Pagination | `{items, page, per_page, has_more}` | `{data, meta: {page, per_page, has_more}}`; same page size (50), 1-based | `V2ListEnvelope.toV1Page()` |
| Error body | `{ "detail": <string or entity> }` | `{ "error": { "code", "message", "fields"?, "current"? } }` | Everything that parses `detail` (`parseConflictDetail`) needs the v2 twin `parseV2Conflict`. `ApiErrorEnvelopeDto` already exists (admin) |
| Conflict (409) | `{detail: <current entity>}` | `{error:{code:"conflict", current:<entity>}}` | `WriteReplayWorker.decodeConflictBody`; note `current` is a v2 DTO (with `_html`) |
| Validation (422) | `{detail: "<text>"}` | `{error:{code:"validation_error", fields:{<field>:"..."}}}` | Only message text changes |
| Not found | 404 `{detail:"Project not found"}` | 404 `{error:{code:"not_found"}}` | `WriteReplayWorker` branches on status, unchanged |
| Auth failure | 401 `{detail}` | 401 `{error:{code:"session_expired"}}`; 403 `csrf_failed` for cookie writes | Today's v2 code `session_expired` means "bad credential" for a PAT too (the admin path already does this); do not tell the user "session expired" |
| IDs | JSON numbers | JSON numbers (same `i64`) | none; media ids are the same legacy public ids |
| Timestamps | `"2026-11-01T12:00:00.000000Z"` style strings (`created_at`, `updated_at`, `server_time`) | same strings, same struct (`ProjectResponse`) | none: `DateTimeUtils` already parses with/without offset. Echo `updated_at` back verbatim in `expected_updated_at` |
| Media/thumbnail URLs | `/media/projects/{id}/{file}`, `/media/thumbnails/projects/{id}/thumb_{stem}.jpg`; same for yarns | identical (same `crate::media` serializers) | none. `MediaUrlResolver`/`MediaClient` unchanged. Media serving stays on `/media/...` with Bearer (v1-era route, not versioned) |
| Markdown | raw Markdown only | raw plus server-sanitized `description_html`, `notes_html`, `other_materials_html`, step `description_html`, yarn `description_html`/`notes_html` | Ignored for now (the app renders Markdown itself, SNA-22/44). Possible later simplification |
| Tags | tokenized: v1 splits on comma, `#`, whitespace, case-insensitive dedupe | **each array item is one verbatim label** (`TagPolicy::PreserveLabels`) | Behaviour change: "winter gift" stays one tag in v2 but becomes two in v1. Offline-created tags with spaces will show differently after the switch. Tag filter (`?tag=`) matching rules changed with it; verify against `rust/tests/api_v2_projects.rs` |
| Favorite | `POST` / `DELETE .../favorite` | `PUT` / `DELETE .../favorite` | Different verb |
| Update with images | `image_urls` allowed on update (import path) | update rejects non-empty `image_urls` (422 `image_urls`) | The app never sends `image_urls` on update (it uses multipart), verify `ProjectWriteRequest.imageUrls` stays empty on `updateProject` |
| Nullability | `images`/`attachments` typed as arrays | arrays too (raw JSON values server-side, same shape) | none |

DTO notes:
- v2 only **adds** `*_html` fields and renames nothing. Request bodies (`ProjectWriteRequest`,
  `YarnWriteRequest`, category requests) are byte-identical, including `expected_updated_at` and
  `yarn_ids`, so the v1 request DTOs are reused.
- `ImageDto`, `AttachmentDto`, `YarnPhotoDto`, `CategoryDto`, `ProjectListItemDto`,
  `YarnListItemDto` are reused unchanged for v2 payloads.
- New in `data/api/v2/`: `ProjectV2Dto`, `StepV2Dto`, `YarnV2Dto`, the envelopes, the three Retrofit
  interfaces (`ProjectsV2Api`, `YarnsV2Api`, `CategoriesV2Api`, provided in `NetworkModule`, unused)
  and `V2Mappers.kt` (`toV1()`, `toV1Page()`, `toRoomEntity(json)`).

## 2. Endpoint map

"Code" = app code affected when the call is switched.

### Projects

| v1 call (`ProjectsApi`) | v2 equivalent | Differences | Code |
| --- | --- | --- | --- |
| `GET api/v1/projects?page&category&tag&favorite` | `GET api/v2/projects` (+ new `q`) | envelope/meta; tag semantics | list/search calls are served from Room today; `ProjectsV2Api.listProjects` is only needed if the app adds online search |
| `GET .../projects/{id}` | `GET api/v2/projects/{id}` | `{data}`, `_html` fields | `ProjectRepository.refreshOne`, `adoptRemoteProject` |
| `POST .../projects` | `POST api/v2/projects` | 201 `{data}`; same body | `ProjectRepository.replayCreate` |
| `PUT .../projects/{id}` | `PUT api/v2/projects/{id}` | `{data}`; 409 error shape; `image_urls` rejected | `ProjectRepository.replayUpdate`, `WriteReplayWorker` conflict path |
| `DELETE .../projects/{id}` | `DELETE api/v2/projects/{id}` (204) | none (exists, cookie only) | `ProjectRepository.replayDelete` |
| `POST .../favorite` / `DELETE .../favorite` | `PUT` / `DELETE api/v2/projects/{id}/favorite` | PUT instead of POST; `{data: Project}` | `ProjectRepository.toggleFavorite` (online, optimistic) |
| `POST .../images/title` (multipart `file`, `alt_text`) | `POST api/v2/projects/{id}/images/title` | 201 `{data: Image}` | `replayTitleImageUpload` |
| `POST .../steps/{sid}/images` | `POST api/v2/projects/{id}/steps/{sid}/images` | same | `replayStepImageUpload` |
| `POST .../attachments` | `POST api/v2/projects/{id}/attachments` | same | `replayAttachmentUpload` |
| `DELETE .../attachments/{aid}` | same path on v2 (204) | exists | `replayAttachmentDelete` |
| `DELETE .../images/{iid}` | same path on v2 (204) | exists | `replayImageDelete` |
| `POST projects/import` (web route, form-encoded: `type=url`, `url`, `use_ai`) via `ProjectImportApi` | `POST api/v2/imports/preview` JSON `{type:"url", url, use_ai}` | Completely different response: `{data:{draft:{kind,sha256,sourceUrl,project:{name,description,category,yarn,yarns[],needles,otherMaterials,stitchSample,gaugeWidth,gaugeHeight,fiberContent,colorway,weightCategory,steps[],imageUrls[],link,brand,extras}, image},content_sha256,ai_requested,is_ai_enhanced,ai_fallback}}` (camelCase inside `draft`); error `import_unavailable`/`feature_disabled`/`rate_limited`; cookie only | `ProjectImportApi`, `ProjectImportDto`, `ApiProjectImporter`, `ProjectImportState`. Needs its own mapper (draft -> `ProjectImportResponseDto`); phase (e), low priority. Not mapped yet |

### Yarns

Same pattern as projects: `GET/POST/PUT/DELETE api/v2/yarns[/{id}]`, favorite `PUT`/`DELETE`,
`POST api/v2/yarns/{id}/photos` (201 `{data: YarnPhoto}`), `DELETE api/v2/yarns/{id}/photos/{pid}`
(204). No `category`/`tag` filters; `q` is new. 409 carries `error.current` = `YarnV2Dto`. Code:
`YarnRepository` (`replayCreate/Update/Delete`, `replayPhotoUpload/Delete`, favorite),
`WriteReplayWorker.replayYarn`.

### Categories

| v1 | v2 | Differences |
| --- | --- | --- |
| `GET api/v1/categories` -> `[{id,name}]` | `GET api/v2/categories` -> `{data:[{id,name}]}` | envelope |
| `POST` | `POST api/v2/categories` 201 `{data}` | creating an existing name (case-insensitive) returns the existing row with 201 (check v1 behaviour in `CategoryRepository` callers) |
| `PUT /{id}` | `PUT api/v2/categories/{id}` | rename collision: 409 `{error:{code:"conflict", fields:{name}}}` (v1: 409 `{detail}`) |
| `DELETE /{id}` | `DELETE api/v2/categories/{id}` 204 | none |

Code: `CategoryRepository`.

### Sync (`SyncApi`) - missing (Codex)

| v1 | v2 | Notes |
| --- | --- | --- |
| `GET api/v1/sync/projects?since=` -> `{updated:[Project], deleted_ids, server_time, full_resync_required, has_more, next_cursor}` | **missing (Codex)** | v1 also takes `limit` (1..50) and `cursor`; the app currently uses only `since` and `server_time` |
| `GET api/v1/sync/yarns` | **missing (Codex)** | same |
| `GET api/v1/sync/categories?since=` -> `{updated, deleted_ids, server_time}` | **missing (Codex)** | full replacement every time |

Requested v2 shape (to agree with Codex): keep the entity objects identical to the v2 detail DTOs;
put the rest in either `{data:{updated, deleted_ids, server_time, full_resync_required}}` or
`{data:[...], meta:{server_time, deleted_ids, has_more, next_cursor, full_resync_required}}`. Prefer
the first: one envelope, and `updated` contains v2 DTOs so `toV1()` applies. Either way the cursor
semantic (`server_time` echoed verbatim as `since`) must stay, since `SyncStateEntity.cursor` stores
it. Code: `ProjectRepository.sync`, `YarnRepository.sync`, `CategoryRepository.sync`, `SyncWorker`,
`SyncStateEntity`.

### Auth, meta

| v1 | v2 | Differences | Code |
| --- | --- | --- | --- |
| `GET api/v1/meta` -> `{version, build_id, is_admin}` (unauthenticated; `is_admin` if a PAT is sent) | `GET api/v2/session` exists but is cookie-based, returns `{data:{user,csrf_token,capabilities,locale}}`, `user:null` when anonymous. **`me`/meta with Bearer: missing (Codex)**: needs `user {id,email,is_admin}` | v2 session is not usable with a PAT | `OnboardingValidator.checkIsStricknaniServer`, `MetaApi`, `AdminRepository.probeAdmin` |
| `GET api/v1/categories` with Bearer (token check in onboarding) | any v2 Bearer route, ideally the `me` endpoint (200 = server supports v2 for this token, 401 = invalid token) | | `OnboardingValidator.checkTokenAuthorizes` |
| `POST api/v1/auth/token` `{email,password,token_name}` -> `{token}`; 401/403 bad credentials | **missing (Codex)**: v2 token login. `/api/v2/auth/login` makes a browser cookie session (needs a CSRF dance), not a PAT. `POST /api/v2/account/api-tokens` makes a PAT but needs a session | the one onboarding call that still needs v1 until Codex adds it | `PasswordTokenMinter` (raw OkHttp), `OnboardingError.InvalidCredentials` |

Both onboarding paths are raw OkHttp against `@ValidationClient` (no interceptors), so they are
switched separately from the Retrofit calls.

### Favorites and deletes

All exist on v2 for projects/yarns/media (see tables), cookie-only. Remaining work is only the auth.
The delete/favorite repository calls are one-liners; switch them with phase (d) even though the
routes exist, because they are only valid once Bearer works. Categories delete exists too.

## 3. Switch-over order

Server slices decide the order. Each phase lands behind `ApiCapabilities` (section 5) so an old
server keeps using v1.

1. Phase (a) - projects, yarns, categories, media reads and writes. Needs: Bearer on v2 resource
   routes. App work: inject `*V2Api` next to the v1 API into `ProjectRepository`, `YarnRepository`,
   `CategoryRepository`; pick by `capabilities.resources`; map with `toV1()`; replace
   `parseConflictDetail` with a dispatcher that handles both shapes; update favorite verb. Sync stays
   on v1 meanwhile (the cached entities are the same shape after `toV1()`, so v1 sync and v2 writes
   mix safely). Queued offline writes already in the outbox are plain `ProjectWriteRequest` JSON,
   so they replay on either version.
2. Phase (b) - sync. Needs v2 sync endpoints. App work: `SyncV2Api`, an envelope DTO, switch the
   three repositories' `sync()`.
3. Phase (c) - auth/token + meta/me. Needs v2 token login and `me`. App work: `OnboardingValidator`
   and `PasswordTokenMinter` on v2 with v1 fallback; `ApiCapabilities` probe; replace
   `AdminRepository.probeAdmin` with `user.is_admin` (the TODO in SNA-86 already anticipates this).
   Technically the `me` probe is needed to enable phase (a)/(b), so ship the probe and its v1 fallback
   with phase (a), and the token-minting switch here.
4. Phase (d) - favorites and deletes: only the call switch (they exist server-side). Can land with
   (a). Listed separately because it is the app's smallest unit and can ship even before sync.
5. Phase (e) - cleanups: URL import via `/imports/preview`, drop v1 DTOs (`ProjectPageDto`,
   `*SyncResponseDto`), remove `/api/v1` paths once the minimum supported server is v2-capable.

## 4. Room / offline storage

Options:
1. Migrate stored `detailJson` to v2 JSON. Needs a Room migration rewriting every row (it is
   a big JSON blob per row), and the stored model would then contain `_html` fields and break older
   app versions after a downgrade. High cost, no benefit.
2. Map v2 -> the existing v1-shaped domain and store that (recommended).
3. Bump the Room schema to v2-shaped columns. Requires migrating `detailJson` and every reader;
   no user benefit.

Recommendation: option 2. The v2 payload is a strict superset of v1 (added `*_html`, no renames, same
ids, same timestamps, same media URLs), so `toV1()` is lossless for everything the app shows. Reasons:
- zero Room schema change and zero data migration, so no risk to users' offline data;
- v1 and v2 cached rows are interchangeable, so a mixed fleet (some entities refreshed over v1, some
  over v2, or a fallback to v1 after a server rollback) works;
- the outbox payloads (`ProjectWriteRequest`/`YarnWriteRequest`) are already version-agnostic;
- the UI keeps its Markdown rendering unchanged.
The `toRoomEntity` mappers produce byte-identical rows to the v1 path
(`V2DtoTest.projectMapsToTheSameRoomRowAsV1`). Revisit only if the app wants to consume
`*_html`; then add nullable columns or a separate cache table with a normal Room migration, not a
rewrite of `detailJson`.

One thing to watch: tag semantics (section 1). Cached rows are unaffected; only newly written tags
differ. Nothing to migrate.

## 5. `ApiCapabilities` design

Detect per server+token, not per app build: the user can point the app at an old server.

```kotlin
enum class ApiGeneration { V1, V2 }

data class ApiCapabilities(
    val resources: ApiGeneration = V1,   // phase (a): v2 projects/yarns/categories/media via Bearer
    val sync: ApiGeneration = V1,        // phase (b)
    val auth: ApiGeneration = V1,        // phase (c): v2 token login
)

@Singleton
class ApiCapabilitiesProvider @Inject constructor(
    private val metaV2Api: MetaV2Api,     // GET api/v2/me (name TBD by Codex)
    settings: SettingsRepository,
) {
    val capabilities: StateFlow<ApiCapabilities>
    suspend fun probe()                   // call once per app start, token change and after a 401/404 on v2
}
```

- Probe: `GET /api/v2/me` with the Bearer token. 200 with `user` -> `resources = V2` (and the body
  gives `is_admin`). 401/403 -> token invalid: keep V1 and let the existing auth-failure UI run. 404
  / 405 / non-JSON -> old server, `V1`. Network error -> keep the last cached value (persist the
  last result with the server URL + token hash in `AppPreferencesRepository`) so offline start keeps
  the right generation; never default to V2 when unknown.
- Sync and token-login are probed separately if the server announces them (for example a
  `capabilities` object in the `me` body, or a 404 from `/api/v2/sync/projects?since=`), because
  Codex ships them in different slices. Until the server advertises them, flags stay V1.
- Repositories take `ApiCapabilitiesProvider` and branch in one place each (a tiny
  `V1/V2` strategy per repository). A v2 call that returns 401 with `session_expired` and
  was chosen because of a stale cached probe re-probes once and falls back.
- Onboarding: the probe replaces `checkIsStricknaniServer` + `checkTokenAuthorizes` when v2 is
  available. v1 `meta` stays as the "is this Stricknani" check for old servers.
- Test: provider with a fake `MetaV2Api` returning 200/401/404/IOException.

## 6. Missing v2 endpoints for the app (exact list)

1. Bearer PAT authentication on all v2 resource routes used here: `/api/v2/{projects,yarns,categories}`
   (+ `/{id}`, `/favorite`) and the media upload/delete routes, with no CSRF/Origin requirement for
   PAT requests (as on `/api/v2/admin/*`). Present as routes, cookie-only today.
2. `GET /api/v2/sync/projects`, `/sync/yarns`, `/sync/categories` (`since`, `limit`, `cursor`),
   returning v2 DTOs.
3. Token login: `POST /api/v2/auth/token` (email, password, token_name) -> PAT, with the current
   v1 behaviour (rate limits; 401 on bad credentials).
4. `GET /api/v2/me` (or meta) reachable with a Bearer PAT: `user {id, email, is_admin}`, plus the
   build/version and, ideally, capability flags; the anonymous variant for "is this a Stricknani
   server" if v1 `meta` is to be dropped.
5. Optional: Bearer support on `POST /api/v2/imports/preview` for URL import (today it is cookie-only
   and returns an incompatible draft shape).

Present but cookie-only (needs only item 1): projects/yarns create/update/delete, favorites,
categories, project image/attachment and yarn photo upload/delete.
