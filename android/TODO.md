# TODO

Running backlog/changelog for the Stricknani Android app. Every user-visible feature or fix gets
a stable `SNA-N` identifier and an explicit state; IDs are never reused. Convention matches the
sibling Android apps (syncwich, nyetbox, jollyfin, augh) - see `android/AGENTS.md` once it exists,
and `.just/android-app-ci/AGENTS-shared.md` for the fleet-wide shared conventions it will vendor.

This file tracks the **Android app** only. The web app's own backlog stays in the repo-root
`TODO.md` (`T-N` prefix) - a handful of entries here (backend API work) have a matching `T-N`
counterpart there once created, since that work lands in `stricknani/`, not `android/`.

## Status legend

- `not started` / `in progress` / `mostly done` / `**done**`, each with a date and how it was
  verified (mirrors the fleet convention - see e.g. syncwich's `TODO.md`).

## Architecture summary

- Native Kotlin/Jetpack Compose Android app living at `android/` in this repo (not a separate
  repo, unlike the sibling apps) - package `blue.anika.wolle`, single `:app` Gradle module,
  GPL-3.0 (matches repo `LICENSE`).
- Offline-first, same hard requirement as syncwich/nyetbox: every read is a Room `Flow` first; a
  network call is only ever a best-effort background refresh that upserts into Room.
- Auth: a new Stricknani-side long-lived Personal Access Token (PAT), generated from the web UI
  (Settings -> API Tokens), not username/password - same reasoning as syncwich's Mealie token
  (a browser session JWT is short-lived and a bad fit for an app that may go days offline).
  Server URL + token stored in `EncryptedSharedPreferences`, dynamic-base-URL + auth OkHttp
  interceptors, same shape as nyetbox's `DynamicBaseUrlInterceptor`/`AuthInterceptor`.
- Sync: delta/incremental (`?since=<cursor>`) sync endpoints on the backend instead of full
  refetch every time, sourcing deletions from the existing `AuditLog` table (already records
  project/yarn deletes) as a tombstone feed. WorkManager periodic sync + manual pull-to-refresh +
  best-effort sync on launch.
- Room schema follows the syncwich pattern: real columns for filter/sort/list fields (name,
  category, tags, favorite, thumbnail path, `updatedAt`), full detail (steps, images, notes,
  materials) stored as a JSON column decoded with kotlinx.serialization at read time - not
  deep-normalized into many join tables.
- Material 3 Expressive: dynamic color on Android 12+, hand-picked yarn/craft-inspired fallback
  palette, `material-icons-extended` with icon+label everywhere (nyetbox's UI convention).

## Now

## Next

### Backend (`stricknani/`) - JSON API for the app

## SNA-1: `ApiToken` model + Settings UI for Personal Access Tokens

- [x] `ApiToken` SQLAlchemy model (`user_id` FK, `name`, `token_hash`, `created_at`,
      `last_used_at`, `expires_at` nullable) + Alembic migration
      (`stricknani/alembic/versions/9bbac92505be_add_api_tokens_table.py`)
- [x] Token generation: random secret (`sna_` prefix) shown once at creation, only its SHA-256
      hash persisted (`generate_api_token`/`hash_api_token` in `stricknani/utils/auth.py`) -
      raw value is never stored
- [x] Settings page at `/user/api-tokens` (`stricknani/routes/user.py`,
      `stricknani/templates/user/api_tokens.html`): list/create/revoke tokens, linked from the
      navbar user menu. Simplified from the original "default name suggestion" idea to a plain
      required-empty-ok name field defaulting to "Stricknani Android" server-side
- [x] `require_api_token`/`get_current_user_from_api_token` FastAPI dependencies
      (`stricknani/routes/auth.py`): `Authorization: Bearer <token>` -> hash lookup -> `User`,
      independent of the cookie-session `get_current_user`/`require_auth` flow
- [x] Exempt Bearer-authenticated requests from the CSRF dependency in `main.py`
      (`csrf_validation_dependency` now returns early when the `Authorization` header is a
      `Bearer` token - CSRF only makes sense for cookie-authenticated browser requests)
- [x] Tests: `tests/test_api_tokens.py` (token generation/hashing, `get_user_from_api_token`
      resolution/expiry/inactive-user/last-used-bump, route-level create/list/revoke + ownership
      check, CSRF-exemption behavior). Full suite green (237 passed), ruff/mypy/i18n-check clean

Status: **done** (2026-08-18) - verified via `nix develop -c uv run pytest -q` (237 passed),
`uv run ruff check .`, `uv run mypy .`, and `just i18n-check`. Package name for the Android app
itself corrected to `blue.anika.wolle` (was a placeholder `dev.pschmitt.stricknani`) - see
Architecture summary and SNA-5/SNA-6 below.

## SNA-2: Versioned JSON API for projects/yarns/categories (`/api/v1/`)

- [ ] `stricknani/routes/api/` package, `/api/v1` prefix, `require_api_token`-authenticated
- [ ] Projects: list (paginated, filter by category/tag/favorite), detail (incl. steps, images,
      attachments, linked yarns), create, update, delete - reuse `services/projects/*` business
      logic rather than duplicating it
- [ ] Yarns: list/detail/create/update/delete - reuse `services/yarn/*`
- [ ] Categories: list/create
- [ ] Image/attachment upload as multipart endpoints mirroring the existing form routes
- [ ] `GET /api/v1/meta` - server version/build id, so the app can detect it needs a full resync
      after a breaking server upgrade (mirrors how the web app's own service worker versions its
      caches via `SW_BUILD_ID`)

Status: not started

## SNA-3: Delta-sync endpoints

- [ ] `GET /api/v1/sync/projects?since=<iso8601>` -> `{updated: [...], deleted_ids: [...],
      server_time: <iso8601>}`; same shape for `/api/v1/sync/yarns` and `/api/v1/sync/categories`
- [ ] Source `deleted_ids` from the existing `AuditLog` table (`entity_type`/`action="delete"`/
      `created_at`) instead of adding a new tombstone table
- [ ] Confirm `AuditLog` retention is long enough that a client that hasn't synced in a while
      doesn't miss deletions (or force a full resync if the requested `since` predates the oldest
      retained audit entry)

Status: not started

## SNA-4: Bearer-auth support on the media route

- [ ] `stricknani/routes/media.py`'s ownership-checked serving route currently resolves the user
      via cookie session only - teach it to also accept `Authorization: Bearer <token>` so the app
      can load project/yarn images authenticated with the PAT

Status: not started

### Android app scaffolding

## SNA-5: Repo scaffold + Compose shell

- [ ] `android/` Gradle project: single `:app` module, Kotlin + Jetpack Compose + Material 3
      (Expressive), Hilt, Room, Retrofit/OkHttp + kotlinx.serialization, Coil3, WorkManager +
      Hilt-Work, DataStore + `androidx.security` crypto - version catalog matching the sibling
      apps' current stack (see `gradle/libs.versions.toml` in syncwich/nyetbox for a baseline)
- [ ] Vendor the shared fleet conventions as a git submodule at
      `android/.just/android-app-ci` (same path convention as the sibling repos)
- [ ] `android/justfile`: remote-build recipes against rofl-13/rofl-14 (never build Gradle
      locally), Zenfone 10 / Mi Pad 4 / Pixel 5 device recipes, matching the sibling apps exactly
- [ ] `android/AGENTS.md` (references the vendored shared doc + this file's architecture summary),
      `android/README.md`, `android/PRIVACY.md`, GPL-3.0 (already covered by repo-root `LICENSE`)
- [ ] Add a short pointer to `android/AGENTS.md`/`android/TODO.md` in the repo-root `AGENTS.md`
      Documentation Layout section
- [ ] App icon/branding (`android/docs/branding/`), adaptive launcher icon + monochrome variant,
      splash screen (`core-splashscreen`)
- [ ] CI: `lint.yaml` (ktfmt + Android Lint, path-scoped to `android/` so it doesn't run on
      Python-only changes and vice versa), `build.yaml`, `release.yaml` modeled on the sibling
      apps' workflows
- [ ] Material You theme: dynamic color on Android 12+, yarn/craft-inspired fallback palette
      matching the launcher icon; bottom-nav or nav-rail Compose shell with placeholder
      Home / Projects / Yarn Stash / Search / Settings destinations, type-safe Navigation Compose
      routes

Status: not started

## SNA-6: Onboarding + credential storage

- [ ] Onboarding screen: server URL + PAT entry, validated against `/api/v1/meta` (or a
      `/api/v1/users/self`-equivalent once it exists)
- [ ] `SettingsRepository`: `EncryptedSharedPreferences`-backed server URL + token storage, plus
      DataStore for non-secret sync bookkeeping (last-sync timestamps, sync interval preference)
- [ ] `DynamicBaseUrlInterceptor` + `AuthInterceptor` (nyetbox pattern), `di/NetworkModule.kt`
- [ ] Sign-out wipes both credentials and the Room cache

Status: not started

## SNA-7: Offline data layer + sync engine

- [ ] Room schema: `ProjectEntity`/`YarnEntity`/`CategoryEntity` with flat filter/sort columns +
      a `detailJson` blob column for full nested detail, decoded at read time
- [ ] Cache-first repositories (`ProjectRepository`, `YarnRepository`, `CategoryRepository`) -
      every read is a Room `Flow`; a failed `refresh()` never clears cached data
- [ ] `SyncWorker` (WorkManager periodic, `NetworkType.CONNECTED`) + `SyncScheduler`, driven by
      the `/api/v1/sync/*` delta endpoints (SNA-3) - upsert `updated`, delete `deleted_ids`, never
      a naive full refetch
- [ ] Manual pull-to-refresh + best-effort sync on launch; sync failure surfaces a subtle
      staleness indicator, never blocks or clears already-cached data
- [ ] Coil3 image loading sharing the authenticated OkHttp client (so image requests carry the
      Bearer token), disk-cached, not duplicated into Room

Status: not started

## SNA-8: Offline write queue

- [ ] Local "pending mutation" outbox table (create/update/delete ops, client-generated temp ids
      for offline-created rows)
- [ ] `WriteReplayWorker`: flushes the outbox when connectivity returns, reconciles temp ids with
      server-assigned ids from the response
- [ ] A replay failure (e.g. the project was deleted server-side in the meantime) surfaces a
      conflict banner rather than silently dropping the local edit

Status: not started

### Android app screens

## SNA-9: Core browsing screens

- [ ] Home/dashboard: sync status, favorites, recently viewed
- [ ] Projects list: search, category/tag filter chips, favorite toggle
- [ ] Project detail: steps, images, gauge/needles/materials, linked yarns, notes
- [ ] Yarn stash list + detail: filter by brand/colorway/weight, photos
- [ ] Category management
- [ ] Global search across projects and yarns

Status: not started

## SNA-10: Create/edit flows

- [ ] Project create/edit form (fields mirroring the web app), step reordering, image
      picker/upload
- [ ] Yarn create/edit form, photo picker/upload
- [ ] All writes go through the offline queue (SNA-8), so they work with zero connectivity

Status: not started

## SNA-11: Gauge calculator

- [ ] Port `stricknani/utils/gauge.py`'s calculation logic natively into Kotlin (pure math, no
      network call needed) rather than adding a backend endpoint for it - keeps this screen fully
      offline-capable with zero dependency on server reachability

Status: not started

## SNA-12: Fleet release parity

- [ ] Obtainium + GitHub Releases distribution, `declaroid` entry in `android/README.md`
- [ ] Physical-device deploy recipes verified on Zenfone 10 / Mi Pad 4 / Pixel 5
- [ ] Optional Play Store publishing workflow (mirror syncwich's `play-store.yaml`)

Status: not started

## Stretch / later

## SNA-13: QR-code onboarding

- [ ] Generate a setup QR from the web Settings page (server URL + a freshly created PAT) that
      the app can scan during onboarding instead of manual entry - matches nyetbox's
      `nix run .#nyetbox-setup` / jollyfin's QR device-provisioning UX

Status: not started

## SNA-14: Sync-completion notifications

- [ ] Optional local notification when a background sync finds changes, or when an async backend
      job (e.g. link archiving) completes for a project/yarn

Status: not started

<!-- vim: set ft=markdown et ts=2 sw=2 : -->
