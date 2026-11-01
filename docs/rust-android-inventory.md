# Android and API v1 inventory for the Rust port

Scope: observed contract of the Kotlin app and the original Python v1 API, current Rust route coverage, and remaining app/Rust integration gaps. The endpoint matrix below remains the contract reference; the Rust backend now implements versioned API routes under `rust/src/`. Remote Rust and Android validation is run separately from this inventory.

## Endpoint matrix

All paths below are relative to the configured server base URL. Except `GET /api/v1/meta` and `POST /api/v1/auth/token`, app requests use `Authorization: Bearer <PAT>` through [`AuthInterceptor.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/AuthInterceptor.kt). The API router declares `/api/v1` in [`routes/api/__init__.py`](../stricknani/routes/api/__init__.py); normal JSON routes scope each object to the token owner and return 404 for foreign or missing objects.

| Endpoint | Android usage and wire behavior |
|---|---|
| `GET /api/v1/meta` | Unauthenticated server identity `{version, build_id}`; `build_id` changes at process start. Used before onboarding to identify the server. [`MetaApi.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/MetaApi.kt), [`meta.py`](../stricknani/routes/api/meta.py) |
| `POST /api/v1/auth/token` | Unauthenticated JSON `{email,password,token_name?}` -> `{token}`. Mints a normal PAT; repeated bad attempts are rate-limited by IP and normalized email with `429`/`Retry-After`. PAT is returned once; only its hash is stored. [`PasswordTokenMinter.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/onboarding/PasswordTokenMinter.kt), [`auth.py`](../stricknani/routes/api/auth.py) |
| `GET /api/v1/categories`; `POST`/`PUT /{id}`/`DELETE /{id}` | Full current owner category list (`[{id,name}]`). Create is idempotent by name; rename rejects case-insensitive duplicates and rewrites matching project category strings; delete clears them. Mutations return category (create `201`, update `200`) or empty `204`. [`CategoriesApi.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/CategoriesApi.kt), [`categories.py`](../stricknani/routes/api/categories.py) |
| `GET /api/v1/projects?page=&category=&tag=&favorite=` | 1-based page of compact list items, ordered most recently updated; filters include category, substring tag, tri-state favorite. Response `{items,page,per_page,has_more}`. Android exposes page/filter parameters but normal offline display is cache-first. |
| `GET /api/v1/projects/{id}`; `POST /api/v1/projects`; `PUT`/`DELETE /{id}` | Detail/create/update return full project DTO (`POST 201`, `GET/PUT 200`); delete is empty `204`. Writes are whole-resource-style: nullable fields omitted by Kotlin serialization reach server defaults and can clear fields; `yarn` is derived from linked yarns. Project fields include tags, yarn IDs, ordered steps, image records, attachments, favorites and timestamps. Update supports optional `expected_updated_at`; mismatch returns `409` with current DTO in `detail`. Existing step IDs preserve step-image associations; omitted existing steps are removed. [`ProjectsApi.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/ProjectsApi.kt), [`ProjectDto.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/dto/ProjectDto.kt), [`projects.py`](../stricknani/routes/api/projects.py) |
| `POST`/`DELETE /api/v1/projects/{id}/favorite` | Idempotent set/unset; both return full project DTO (`200`), not a boolean. Android applies optimistic local update and rolls back on failure. |
| `GET /api/v1/yarns?page=&favorite=`; `GET /{id}`; `POST`/`PUT`/`DELETE /{id}` | Compact paginated list or full yarn detail/CRUD. Same status conventions and optional `expected_updated_at` conflict behavior as projects. DTO includes nullable numeric weight/length, project IDs, photo list, favorite and timestamps. [`YarnsApi.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/YarnsApi.kt), [`YarnDto.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/dto/YarnDto.kt), [`yarns.py`](../stricknani/routes/api/yarns.py) |
| `POST`/`DELETE /api/v1/yarns/{id}/favorite` | Idempotent favorite set/unset; full yarn DTO response. |
| `GET /api/v1/sync/projects?since=` and `/sync/yarns?since=` | `since` is the prior response's `server_time`, passed verbatim as ISO-8601. First sync omits it; subsequent delta includes entities with `updated_at > since` and owner-authored deletion tombstone IDs. Response has `updated`, `deleted_ids`, `server_time`, `full_resync_required`. Projects/yarns include full detail DTOs. [`SyncApi.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/SyncApi.kt), [`sync.py`](../stricknani/routes/api/sync.py), [`SyncDto.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/dto/SyncDto.kt) |
| `GET /api/v1/sync/projects|yarns?limit=1..50[&since=…]`, then `?cursor=…` | Optional bounded mode merges update and delete events into one page. Response adds `has_more` and `next_cursor`; all pages keep the first page's snapshot `server_time`. Cursor is opaque to clients and tied to entity kind, user, starting `since`, snapshot, keyset positions and page size. `since` and `cursor` cannot be sent together. Invalid, cross-user or cross-entity cursors return `400`. Omitting `limit` retains the original complete-response form. **Current Kotlin client does not opt into this mode:** its Retrofit methods send only `since`, and its DTOs omit `has_more`/`next_cursor`. |
| `GET /api/v1/sync/categories?since=` | Full category replacement every time; `since` is accepted but ignored; `deleted_ids` is empty. Categories have no reliable updated/deleted event history. Android maps response list and replaces cached table only when it differs. |
| `POST /projects/import` | Android's URL importer is outside `/api/v1` but uses the same Bearer PAT. Form URL-encoded `type=url`, `url`, `use_ai`; tolerant response DTO uses `name`/`title`, fields, `steps`, `image_urls`, and AI flags. Unknown response keys are ignored. Importer feeds result into offline project create. [`ProjectImportApi.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/ProjectImportApi.kt), [`ProjectImportDto.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/dto/ProjectImportDto.kt), [`test_api_v1.py`](../tests/test_api_v1.py) |
| `POST /api/v1/projects/{id}/images/title`; `POST /{id}/steps/{stepId}/images`; `POST /{id}/attachments`; `POST /api/v1/yarns/{id}/photos` | Multipart part must be named `file`; image routes may also send `alt_text`. Image and photo endpoints return image DTOs (`201`); attachments return attachment DTO (`201`). Images are validated from bytes/magic and decodability, size-capped, canonicalized to supported extensions, and thumbnailed. Attachments have the configured upload byte cap but preserve arbitrary content and client content type; unsafe-to-inline filename extensions are forced to download on media GET. [`ProjectsApi.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/ProjectsApi.kt), [`YarnsApi.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/YarnsApi.kt), [`files.py`](../stricknani/utils/files.py), [`attachments.py`](../stricknani/services/projects/attachments.py) |
| `DELETE /api/v1/projects/{id}/images/{imageId}`; `/attachments/{attachmentId}`; `DELETE /api/v1/yarns/{id}/photos/{photoId}` | Empty `204`; children are checked against the supplied parent as well as owner. Deleting a primary yarn photo promotes the oldest remaining photo. First uploaded yarn photo becomes primary. |
| `GET /media/{subdir}/{entityId}/{filename}` and `/media/thumbnails/{subdir}/{entityId}/{filename}` | Media URLs are server-relative paths in DTOs. They require either browser session or Bearer PAT and enforce object ownership; invalid/internal import paths are denied. Responses set `nosniff`, immutable caching and safe `Content-Disposition` (only image/PDF extensions inline). Android's media client attaches PAT but deliberately does not rewrite base URLs; `MediaUrlResolver` resolves paths beneath a server URL/path-prefix and rejects unsafe schemes, credentials and cross-origin URLs. [`media.py`](../stricknani/routes/media.py), [`MediaUrlResolver.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/media/MediaUrlResolver.kt), [`NetworkModule.kt`](../android/app/src/main/kotlin/blue/anika/wolle/di/NetworkModule.kt) |

## Sync contract details and porting pitfall

The client checkpoint and the bounded-pagination cursor are different things. Android persists only the ISO timestamp `server_time` per entity type in Room, and submits that value as `since` on the next run ([`ProjectRepository.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/repository/ProjectRepository.kt), [`YarnRepository.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/repository/YarnRepository.kt)). The server samples one UTC `server_time` snapshot, returns updates newer than `since`, and tombstones from audit rows newer than `since`; the legacy query uses strict `>` comparisons. This timestamp is the durable next-sync watermark, not a page token.

For optional `limit` pages, the server holds the original snapshot and walks a deterministic merged keyset order: update `(updated_at,id)` events and delete `(created_at,audit_id)` events, updates winning exact timestamp ties. `next_cursor` carries each stream's last consumed position plus snapshot and other bindings. The client must process every page before advancing its durable `since` watermark to the response `server_time`; advancing after page one would permanently skip the rest. A future Rust design may use a monotonic internal event sequence, but v1 still needs to accept/return timestamp watermarks and the optional opaque cursor separately. Do not expose an internal sequence as `server_time`, reinterpret `since` as an opaque token, or generate a fresh snapshot per page. Timestamp parsing/comparison must normalize offset-aware and naive UTC forms consistently; the current Python code normalizes incoming timestamps for naive-UTC SQLite columns.

Kotlin's project and yarn sync DTOs already decode `full_resync_required` (with a default of false). This is distinct from the `build_id` returned by the metadata endpoint; a process restart alone is not evidence that cached data must be discarded. Preserve the sync field and verify the client reset path with a real backend fixture rather than introducing a new build-identity reset contract. See [`ProjectDto.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/dto/ProjectDto.kt) and [`YarnDto.kt`](../android/app/src/main/kotlin/blue/anika/wolle/data/api/dto/YarnDto.kt).

## Auth, data and security semantics to retain

- PAT is a bearer credential, stored encrypted on Android; the onboarding validator separately makes unauthenticated `meta` then authenticated `categories` requests. The token-mint endpoint is the sole password exchange, rate-limited and unauthenticated. Never log request authorization or token-mint body/response in Rust tracing.
- API and media authorization are per owner. In particular, media GETs are protected rather than public static files; missing and unauthorized object behavior should not create an object-existence oracle. Attachments are not necessarily images: preserve download behavior for active content/extensions and avoid trusting multipart MIME alone.
- Kotlin stores API timestamps as raw strings; avoid serializing a timezone-naive timestamp without a stable UTC interpretation. `ProjectWriteRequest`/`YarnWriteRequest` carry the observed `updated_at` in `expected_updated_at` for compare-and-set, and replay logic treats `404`/`409` as terminal conflict resolution, not transient retries ([`WriteReplayWorker.kt`](../android/app/src/main/kotlin/blue/anika/wolle/sync/WriteReplayWorker.kt)). Keep `409.detail` as the current full DTO or update the client and tests in lockstep.
- Project create can carry importer image URL lists; server fetch/attachment behavior can have SSRF and partial-import implications. Ordinary update's step `image_urls` are not the same as uploaded image child records. URL import's unauthenticated external fetch behavior and any follow redirects require the Rust SSRF policy to be tested independently.
- JSON `null`/missing behavior matters because app serialization sets `explicitNulls = false`; API DTOs also have defaults for arrays and optional response fields. Decode should tolerate additive server response fields like the Kotlin JSON configuration does, while server request validation should retain documented fields, defaults, and conflict/status semantics.

## Existing evidence and gaps

Backend evidence is strong for Python behavior: [`test_api_v1.py`](../tests/test_api_v1.py) covers bearer auth, meta, import PAT use, category CRUD, yarn/project CRUD, favorites, optimistic concurrency, image upload/delete, and owner isolation. [`test_api_sync.py`](../tests/test_api_sync.py) covers initial/delta sync, bounded pagination, merged deletes, invalid cursor/limits, tombstones, and categories. [`test_api_auth_token.py`](../tests/test_api_auth_token.py) covers mint success/failure/rate limiting; [`test_api_tokens.py`](../tests/test_api_tokens.py) covers one-time token display/revocation. These validate Python only.

Android has broad local behavior tests (write replay, pending uploads/deletes, repositories, DTO photo ordering, cache/offline flows) and Compose journeys. MockWebServer is only used for update-checking, and the existing E2E harness in [`android-e2e.yaml`](../.github/workflows/android-e2e.yaml) still builds and seeds the Python Docker image. Those checks do not establish Rust v1 compatibility. Existing E2E covers onboarding, project/yarn detail/edit/delete, photos, attachment flows and offline/cache journeys against Python; see [`StricknaniE2eTest.kt`](../android/app/src/androidTest/kotlin/blue/anika/wolle/e2e/StricknaniE2eTest.kt). The opt-in replay instrumentation below adds direct Rust coverage for the outbox path, while broader Compose journeys and remaining endpoints still need a Rust fixture lane.

## Rust backend offline replay instrumentation

[`RustBackendReplayIntegrationTest.kt`](../android/app/src/androidTest/kotlin/blue/anika/wolle/sync/RustBackendReplayIntegrationTest.kt)
is an opt-in instrumentation test that uses the installed app's Hilt graph, Room
repositories, `WriteReplayWorker`, and real Retrofit clients. It first points the app
at an unreachable local URL, creates and edits project/yarn rows through the repositories,
then restores the disposable Rust URL and runs the worker. It checks temp-ID replacement,
server and Room state, normal delta sync, queued project/yarn deletion, and 409 conflict
adoption/non-retry behavior. A unique name prefix scopes server cleanup; local cache rows,
outbox rows, sync cursors, the temporary fixture file, and the saved app credentials are
restored or removed in `finally`.

The instrumentation process cannot read the host's `STRICKNANI_RUST_TEST_*` environment
directly. The runner should create a temporary JSON fixture from those three variables,
write it to the app's private `files/rust-replay-fixture.json` over `adb` stdin, and pass
only the test class as an instrumentation argument. For example, this Bash snippet keeps
the values out of shell arguments and output:

```bash
set -eu
fixture_file="$(mktemp)"
trap 'rm -f "$fixture_file"' EXIT
chmod 600 "$fixture_file"
python3 - > "$fixture_file" <<'PY'
import json
import os

print(json.dumps({
    "url": os.environ["STRICKNANI_RUST_TEST_URL"],
    "email": os.environ["STRICKNANI_RUST_TEST_EMAIL"],
    "password": os.environ["STRICKNANI_RUST_TEST_PASSWORD"],
}))
PY
adb -s "$DEVICE_SERIAL" shell run-as blue.anika.wolle.debug sh -c 'cat > files/rust-replay-fixture.json' < "$fixture_file"
adb -s "$DEVICE_SERIAL" shell am instrument -w -e class blue.anika.wolle.sync.RustBackendReplayIntegrationTest blue.anika.wolle.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The URL must be reachable from the emulator, for example through `10.0.2.2` with a
forwarded host port or `adb reverse`. The test logs in through the Rust browser session
flow and logs out after cleanup; this revokes the temporary session credential. Project
and yarn deletes also create durable sync tombstones, and revoked token rows are retained
by the backend schema, so the Rust database used by this test must be a disposable isolated
fixture. No test fixture file or credential belongs in source control.

## Concrete integration test plan

1. **Rust route contract tests (no Android emulator required):** drive the real Rust router with disposable storage and two users. Assert every matrix route's method/path, bearer requirement, status code, DTO field names/null/default behavior, pagination/filtering, idempotent favorite semantics, category rename/delete effects, and owner isolation. Assert no response/log includes raw PAT/password.
2. **Sync compatibility tests:** seed tied timestamps, more than one page of updates plus tombstones, and create/update/delete during pagination. Check bounded pages are unique and complete, share one `server_time`, preserve cursor binding (user/entity), reject `since+cursor`, invalid/oversized limits and malformed/oversized cursors, and that repeating `since=server_time` neither misses equal-time rows nor replays changes forever. Test offset-aware timestamps and exact timestamp precision against server output. Test categories as complete replacement. Verify the full-resync flag handling with the Kotlin client; do not infer it from a process build ID.
3. **Media tests:** upload each supported image type and a malformed/forged MIME body; verify byte cap, canonical extension, response URLs, thumbnails, and served bytes. Test attachment PDF/image/unknown and active-content extensions for correct headers/download behavior. With two users test source and thumbnail GET ownership, guessed IDs, parent/child mismatch on delete, path traversal/internal directory denial, and authentication after token revocation. Include server subpath base URLs because Android intentionally resolves media paths differently from Retrofit API paths.
4. **Kotlin wire tests:** use MockWebServer around each Retrofit interface plus onboarding clients. Pin methods, content types, exact query/form/multipart field names, optional-field omission, timestamp pass-through, auth header routing (not on meta/token mint, present on API and media), 201/204 handling, additive unknown response fields, `409.detail` decoding, and media URL resolution. In particular test bounded sync DTOs (`has_more`, `next_cursor`) before enabling pagination in the client.
5. **Broader app-to-Rust E2E:** parameterize the disposable fixture in `.github/workflows/android-e2e.yaml` and its seed helper so the same smoke/full emulator journeys can run against the Rust binary/container. The new replay instrumentation covers project/yarn queueing, temp IDs, sync, deletion and conflict adoption; extend the Rust lane with onboarding, favorites, project step/title images, yarn photos, attachment upload/delete, URL import behavior or a deterministic local import fixture, restart/re-sync, and cross-user denial. Keep external URL/AI calls stubbed or disabled. Run against a fresh disposable DB/media root, never production state.
6. **Parity checkpoint:** run the existing Python API tests as contract-source evidence and the Kotlin E2E suite against Rust; report any deliberate differences. Do not count Python-only tests or UI-only JVM tests as Android/Rust integration evidence.
