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

- [x] `stricknani/routes/api/` package, `/api/v1` prefix, `require_api_token`-authenticated
      (`stricknani/routes/api/__init__.py` aggregates `meta`/`categories`/`yarns`/`projects`)
- [x] Projects: list (paginated, filter by category/tag/favorite), detail (incl. steps, images,
      attachments, linked yarns), create, update, delete + favorite/unfavorite
      (`stricknani/routes/api/projects.py`). Reuses `services/projects/categories`,
      `services/projects/tags`, `services/projects/yarns.load_owned_yarns`,
      `services/audit.create_audit_log`; core field writes are new lean handlers rather than
      reusing the HTML routes' much larger create/update functions (those are entangled with
      URL-import/wayback-archiving concerns the app doesn't need - see file docstring reasoning
      in the commit)
- [x] Yarns: list/detail/create/update/delete + favorite/unfavorite + photo upload/delete
      (`stricknani/routes/api/yarns.py`). Reuses `services/yarn/presentation.resolve_yarn_preview`
- [x] Categories: list/create (`stricknani/routes/api/categories.py`, reuses
      `services/projects/categories.ensure_category`/`sync_project_categories`)
- [x] Image/attachment upload as multipart endpoints: project title image + step images (reusing
      `services/projects/images.upload_title_image`/`upload_step_image`) and attachments
      (reusing `services/projects/attachments.store_project_attachment`), plus yarn photo
      upload/delete
- [x] `GET /api/v1/meta` - server version/build id (unauthenticated by design, so the app can
      validate "is this a Stricknani server" during onboarding before a token exists - see SNA-6)
- [x] Tests: `tests/test_api_v1.py` (18 tests: meta/auth-required/invalid-token, category
      list/create, yarn CRUD+favorite+photos, project CRUD+steps+yarn-links+favorite,
      title/step image + attachment upload/delete, cross-user ownership 404s)

Status: **done** (2026-08-18) - verified via `nix develop -c uv run pytest -q` (246 passed),
`uv run ruff check .`, `uv run mypy .`. Two real bugs caught by the new tests and fixed before
landing: (1) serializing an ORM object's relationship after `db.commit()` under an async engine
raised `MissingGreenlet` (commit expires relationship attributes, and this session can't satisfy
a lazy-load) - fixed by re-fetching with eager `selectinload` after every commit that's followed
by serialization, in `yarns.py`'s create/update/favorite/unfavorite and the matching
`projects.py` favorite/unfavorite handlers; (2) project step replacement on update via a raw
`DELETE` query left old steps in place - fixed by mutating the `project.steps` relationship
collection directly so the `delete-orphan` cascade handles it.

## SNA-3: Delta-sync endpoints

- [x] `GET /api/v1/sync/projects?since=<iso8601>` -> `{updated: [...], deleted_ids: [...],
      server_time: <iso8601>, full_resync_required: bool}`; same shape for `/api/v1/sync/yarns`.
      `since` omitted/absent means "full sync" (all rows, no deletions to report)
- [x] `GET /api/v1/sync/categories` - same response shape, but always returns the full current
      list: categories have no `updated_at` and their deletions aren't recorded in `AuditLog`
      (only projects/yarns are audited), so there's no delta to compute; the list is small enough
      that this is cheap. `since` is accepted but unused, kept only for a consistent shape
- [x] Source `deleted_ids` from the existing `AuditLog` table (`entity_type`/`action="deleted"`/
      `created_at`, scoped to the requesting user) instead of adding a new tombstone table
- [x] `full_resync_required` is always `False` for now rather than the originally-planned "since
      predates the oldest retained AuditLog entry" check - that check was implemented, then
      reverted after `tests/test_api_sync.py` caught it producing false positives for the entirely
      ordinary case of a `since` captured before a fresh account's very first `AuditLog` row
      (nothing to do with missing deletion coverage; `AuditLog` has no retention/pruning today so
      there's no actual gap to guard against yet). Field is kept in the response shape as a
      forward-compatible hook - see `sync.py`'s module docstring for what a correct future check
      would need (a recorded pruning cutoff, not "oldest row that happens to exist")
- [x] Tests: `tests/test_api_sync.py` (6 tests: initial full sync, delta returns only
      recently-updated rows, deletion reporting for both projects and yarns, the ancient-`since`
      false-positive regression case, category full-list sync)

Status: **done** (2026-08-18) - verified via `nix develop -c uv run pytest -q` against a fresh
(non-locally-migrated) `DATABASE_URL` to match CI's environment (255 passed), `uv run ruff check
.`, `uv run mypy .`. Also fixed a timezone-comparison bug caught along the way: `since` arrives
possibly-aware (client sends an offset), but `updated_at`/`created_at` are stored as naive UTC
(SQLite has no real datetime type and just compares the bound string) - `since` is now normalized
to naive UTC before use in any query.

## SNA-4: Bearer-auth support on the media route

- [x] New `get_current_user_any`/`require_auth_or_api_token` dependency in `stricknani/routes/
      auth.py`: tries the `Authorization: Bearer <token>` header first, falls back to the
      session cookie - shared by both the browser and the app rather than being media-specific
- [x] `stricknani/routes/media.py`'s `serve_media`/`serve_media_thumbnail` routes now depend on
      `require_auth_or_api_token` instead of `require_auth`, so the app can load project/yarn
      images (and thumbnails) straight from the JSON API responses' URLs, authenticated with
      the PAT
- [x] Tests: 3 new cases in `tests/test_media_authz.py` (Bearer-token owner access, Bearer-token
      cross-user 403, invalid Bearer token 401), added to the existing per-object authz fixture

Status: **done** (2026-08-18) - verified via `nix develop -c uv run pytest -q` (249 passed),
`uv run ruff check .`, `uv run mypy .`.

### Android app scaffolding

## SNA-5: Repo scaffold + Compose shell

- [x] `android/` Gradle project: single `:app` module, Kotlin + Jetpack Compose + Material 3
      (Expressive), Hilt, Room, Retrofit/OkHttp + kotlinx.serialization, Coil3, WorkManager +
      Hilt-Work, DataStore + `androidx.security` crypto - version catalog matching the sibling
      apps' current stack (`android/gradle/libs.versions.toml`, baselined off syncwich's). Only
      the Compose/Hilt shell actually uses its dependencies yet - Room/Retrofit/WorkManager are
      wired into the build but unused until SNA-6/SNA-7 add real code against them
- [x] Vendored the shared fleet conventions as a git submodule at `android/.just/android-app-ci`
      (same path convention as the sibling repos)
- [x] `android/justfile`: remote-build recipes against rofl-13/rofl-14 (never build Gradle
      locally) via the vendored `common.just`/`single-module.just`. Zenfone/Mi Pad/Pixel 5 device
      recipes are pulled in by the import but have no real target yet - **no physical test
      devices are assigned to this app** (deferred to SNA-12, unlike the sibling apps)
- [x] `android/AGENTS.md` (references the vendored shared doc + `android/TODO.md`'s architecture
      summary), `android/README.md`, `android/PRIVACY.md`, GPL-3.0 (already covered by repo-root
      `LICENSE`)
- [x] Added a pointer to `android/AGENTS.md`/`android/TODO.md` in the repo-root `AGENTS.md`
      Documentation Layout section
- [x] App icon/branding: adaptive launcher icon (background/foreground/monochrome layers) +
      splash screen (`core-splashscreen`) wired up and building, but the actual artwork is a
      placeholder (a simple generated yarn-ball glyph, warm berry `#8B3A4A`) - **real icon design
      is SNA-19**, filed separately; no `android/docs/branding/` yet since there's no real design
      to document there
- [x] CI: `.github/workflows/android-lint.yaml` (ktfmt + Android Lint) and `android-build.yaml`
      (unit tests + debug APK), both path-scoped to `android/**`. **Hand-written rather than
      delegating to the fleet's reusable `lint.yaml`/`build.yaml`**: those assume the Gradle
      project sits at the repo root, which isn't true in this monorepo (`android/` is a
      subdirectory alongside the Python web app) - only the cwd-agnostic
      `setup-jdk-gradle` composite action is reused, with `working-directory: android` in each
      step. Uploads a `ktfmt-diff-patch` artifact on failure (mirroring the reusable workflow),
      used live to fix a real formatting nit this session - but no auto-fix-commit/auto-PR yet
      (the reusable workflow's nicer failure UX - see its comments in `.just/android-app-ci` for
      what porting that would look like). **`release.yaml` is deferred to SNA-12** (needs signing
      -keystore infrastructure decisions - `android/justfile` has `enable_release_signing :=
      "false"` as a placeholder)
- [x] Material You theme (`ui/theme/{Color,Theme}.kt`): dynamic color on Android 12+, yarn/craft
      -inspired fallback palette (placeholder pending SNA-19) matching the launcher icon. Bottom
      navigation bar (`ui/navigation/WolleNavHost.kt`) with `PlaceholderScreen`s for the five
      Home / Projects / Yarn Stash / Search / Settings destinations, type-safe Navigation Compose
      routes (`ui/navigation/Route.kt`, kotlinx.serialization `@Serializable` route objects)

Status: **done** (2026-08-18) - fully verified: CI green (`android-lint.yaml` ktfmt + Android
Lint, `android-build.yaml` unit tests + debug APK assembly) against `blue.anika.wolle.debug`, and
`just build-fetch debug` on `rofl-13.brkn.lol` + `just {zenfone,mipad,px5}-install` confirmed the
app actually installs and runs on all three fleet devices (Zenfone 10, Mi Pad 4, Pixel 5) -
`pm list packages`, `dumpsys activity activities` (`ResumedActivity`, no crash in logcat) on each,
plus a live screenshot from the Zenfone showing the bottom nav and dynamic Material You dark theme
rendering correctly. Caught and fixed two real issues along the way: (1) a
latent `worktree_suffix` bug in the justfile - it compared an absolute `git rev-parse --git-dir`
against a cwd-relative `--git-common-dir`, so it falsely treated every normal checkout as a linked
worktree whenever `just` runs from `android/` (a subdirectory) rather than the repo root, unlike
the sibling apps whose Gradle project *is* the repo root; (2) a ktfmt formatting nit (KDoc comment
line-wrapping) on two files, fixed via the `ktfmt-diff-patch` CI artifact after a manual
`ktfmtCheck`/`ktfmtFormat` rerun on rofl-13 proved unreliable (reported `NO-SOURCE` for the main
sourceSet for reasons that didn't reproduce CI's environment - CI itself was used as the oracle
instead of guessing). Package name corrected to `blue.anika.wolle` (was a placeholder
`dev.pschmitt.stricknani`) per user correction; app is branded "Wolle" (German for "wool/yarn",
matching the package) rather than reusing the web app's "Stricknani" name, mirroring how syncwich
(app name) differs from Mealie
(the backend product it's a client for) - flag if this naming guess is wrong.

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

## SNA-15: Backup/restore support

- [ ] Local encrypted export of the Room cache (projects, yarns, categories, settings) plus
      cached media, matching syncwich's "optional encrypted backups with password and schedule
      support" and nyetbox's backup/restore feature
- [ ] Restore flow: pick a backup file, decrypt with the password, repopulate Room (subject to a
      confirmation prompt since it can overwrite local state - reconcile against a subsequent
      `/api/v1/sync/*` pull rather than treating the backup as more authoritative than the server)
- [ ] Optional scheduled/automatic backups (WorkManager), configurable in Settings
- [ ] Decide storage target: on-device (SAF file picker) at minimum; consider matching syncwich's
      scope before deciding whether cloud/remote destinations are in scope

Status: not started

## SNA-16: Configurable navbar

- [ ] Let the user choose which destinations appear in the bottom nav / nav rail and in what
      order, matching nyetbox's configurable navbar - persisted in DataStore, editable from
      Settings
- [ ] Sensible default order (Home / Projects / Yarn Stash / Search / Settings, per SNA-5) so this
      is a customization on top of a good default, not a required setup step

Status: not started

## SNA-17: Deep links ("open with" a project/yarn URL)

- [ ] Android App Links so a stricknani project/yarn URL (from the web app, a share, a browser)
      opens directly in the app instead of the browser, matching syncwich's
      `docs/deep-links.md`/`assetlinks.json` pattern - server-side, this needs a per-server
      `.well-known/assetlinks.json` (the server URL is user-configured, not hardcoded, so this
      can't be baked into a single static manifest the way a single-tenant app's can)
- [ ] Handle both `https://<server>/projects/{id}` and `/yarn/{id}` style URLs, resolving to the
      matching local Room entity when cached, falling back to an online fetch/onboarding-required
      state when not yet synced or the app isn't set up for that server
- [ ] "Share" / "Open in app" surfaces from project/yarn detail screens, consistent with the
      existing web app's share links

Status: not started

## SNA-18: Settings screen with an About section

- [ ] Dedicated Settings screen (nav destination placeholder already listed in SNA-5): server
      URL/account (sign-out), theme (light/dark/auto), sync interval/policy, navbar
      customization (SNA-16), backup/restore (SNA-15) - the UI surface over `SettingsRepository`
      from SNA-6, not a data-layer task itself
- [ ] About section: app version/build (+ server version/build from `/api/v1/meta`), GPL-3.0
      license, link to the GitHub repo, changelog/`android/TODO.md`-derived release notes -
      matches the sibling apps' About screens

Status: not started

## SNA-19: Redesign the app icon, shared consistently between web and Android

- [ ] Design a new primary app icon/brand mark for Stricknani (current `stricknani/static/
      favicon.svg` is a minimal placeholder) - used as the Android adaptive launcher icon
      (foreground/background/monochrome layers, per SNA-5) and the web app's favicon/PWA icons
      (`stricknani/static/favicon.svg`, `manifest.webmanifest` icons), so both surfaces present
      the same mark instead of independently designed ones
- [ ] Single source-of-truth asset (e.g. an SVG in one place, likely under the web app's static
      dir or a new top-level `branding/`) that both `android/` (launcher icon generation) and
      `stricknani/static/` derive from, to keep them from drifting apart later

Status: not started

<!-- vim: set ft=markdown et ts=2 sw=2 : -->
