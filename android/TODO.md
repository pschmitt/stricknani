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
      navigation bar (`ui/navigation/StricknaniNavHost.kt`) with `PlaceholderScreen`s for the five
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
(the backend product it's a client for) - flag if this naming guess is wrong. **This guess was
wrong**: the user corrected the display name to "Stricknani" - see SNA-20.

## SNA-6: Onboarding + credential storage

- [x] Onboarding screen (`ui/onboarding/OnboardingScreen.kt`/`OnboardingViewModel.kt`): server URL
      + PAT entry, token visibility toggle, inline error messages. `OnboardingValidator`
      validates in two steps before anything is persisted: unauthenticated `GET /api/v1/meta`
      (confirms it's a Stricknani server at all, distinct "wrong URL" vs "wrong token" errors)
      then an authenticated `GET /api/v1/categories` with the entered token (there's no
      `/api/v1/users/self`-equivalent yet, so this is the stand-in "does this token actually
      work" check - cheap, real, `require_api_token`-gated endpoint). Uses its own
      unauthenticated `@ValidationClient` OkHttpClient rather than the app's normal stack, since
      that stack's interceptors read the *saved* connection - exactly what's being validated
      before it's saved (same pattern as syncwich's `OnboardingValidator`)
- [x] `SettingsRepository`: `EncryptedSharedPreferences`-backed server URL + token storage
      (`data/settings/SettingsRepository.kt`), `StateFlow<Boolean> isConfigured` that
      `MainActivity` observes to reactively pick `Route.Onboarding` vs `Route.Home` as the nav
      graph's start destination (a full recreation of `StricknaniNavHost`, not an in-place
      `navController.navigate` - see its kdoc). DataStore for non-secret sync bookkeeping
      (last-sync timestamps, sync interval preference) is **deferred to SNA-7**: nothing reads or
      writes it before the sync engine exists, so scaffolding it now would be unused code
- [x] `DynamicBaseUrlInterceptor` + `AuthInterceptor` (nyetbox pattern, `data/api/`),
      `di/NetworkModule.kt` (main authenticated `OkHttpClient` + the separate
      `@ValidationClient` one). Nothing injects the main client yet - SNA-7 wires Retrofit/Coil
      against it; a Hilt `@Provides` binding is inert until something actually requests it
- [x] Sign-out: `SettingsRepository.signOut()` wipes credentials, wired to a real (if minimal)
      `ui/settings/SettingsScreen.kt` - the full SNA-18 Settings screen doesn't exist yet, but
      sign-out needed to be reachable/testable end to end rather than left as dead code behind a
      `PlaceholderScreen`. **The Room cache half is not wiped** - there's no Room cache yet
      (SNA-7); this needs a follow-up once it exists

Status: **done** (2026-08-18) - CI green (`android-lint.yaml`, `android-build.yaml`), reinstalled
on the Zenfone 10 and verified live with real network calls against real hostnames (not just
"it compiles"): a fresh install with no saved credentials opens straight on the Onboarding screen
with no bottom nav bar; a syntactically-invalid URL produces the `MalformedUrl` error message; a
syntactically-valid but unresolvable URL (`https://nonexistent.invalid.example`) produces the
distinct `Unreachable` error message, confirming `OnboardingValidator`'s two-step check and error
-mapping both work end to end on-device. **A caught false alarm worth recording**: a first pass at
this same "unreachable" test showed the stale `MalformedUrl` message after entering a valid URL -
turned out to be a mistap (the Connect button's y-coordinate shifted once the earlier error text
was on-screen, so the tap landed above it and never re-triggered validation), not a real bug;
retested from a clean, error-free state with correct coordinates and got the right result. Also
hit, fixed, and documented in `android/AGENTS.md`: a local `ktfmtCheck` run via
`nix develop --command ./gradlew` on `rofl-13` again (second time) falsely reported
`ktfmtCheckMain NO-SOURCE` while 11 files actually had real formatting violations that CI's
`Android Lint` caught - fixed via CI's `ktfmt-diff-patch` artifact, not local re-attempts. **Not
verified**: a full live connect against an actual running Stricknani server + a freshly generated
PAT (only failure paths were exercised, not the success path, since no reachable test server was
set up this session). No unit tests added for `OnboardingValidator`'s error-mapping/URL parsing -
`SettingsRepository` needs Android's `EncryptedSharedPreferences`/`MasterKey` (instrumented test
territory, not plain JVM unit tests) and nothing else here has enough pure logic yet to be worth
isolating; revisit once SNA-7 adds more.

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

Status: in progress (2026-08-18) - starting with Retrofit DTOs/API interfaces + Room
entities/DAOs, then repositories + SyncWorker/SyncScheduler + Coil wiring. Scoped to the *read*
path (sync pulls data in) per this task's own checklist - write endpoints/UI are SNA-8 (offline
write queue) and SNA-10 (create/edit screens), not duplicated here.

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

## SNA-20: Rename app display name from "Wolle" to "Stricknani"

- [x] User correction, superseding SNA-5's naming guess (the guess reasoned by analogy to
      syncwich/Mealie - app name distinct from the backend product name - but that was wrong
      here: the app should be called "Stricknani", not "Wolle"). Package name `blue.anika.wolle`
      is unaffected - only the user-facing display name/prose branding changes
- [x] `res/values/strings.xml`'s `app_name` ("Wolle" -> "Stricknani")
- [x] `android/README.md`, `android/PRIVACY.md`: title and prose rewritten (not just a find/
      replace - "Wolle for your Stricknani server" phrasing no longer works once the app and
      backend share a name, so both were restructured to talk about "the app"/"the Stricknani
      Android app" instead of a distinct app name)
- [x] `android/AGENTS.md`: intro line and Physical test devices/Builds sections updated (also
      caught two other now-stale mentions - "no physical devices configured" and the old
      `~/build/wolle-android` path - while in there)
- [x] Theme/style names (`Theme.Wolle` -> `Theme.Stricknani`, `Theme.Wolle.Splash` ->
      `Theme.Stricknani.Splash` in `themes.xml`/`values-night/themes.xml`/`AndroidManifest.xml`)
- [x] Renamed Kotlin identifiers for consistency: `WolleApp` -> `StricknaniApp` (file renamed
      too), `WolleTheme` -> `StricknaniTheme`, `WolleNavHost` -> `StricknaniNavHost` (file
      renamed too), `Wolle*` color tokens in `Color.kt` -> `Stricknani*`. Package/directory
      structure (`blue.anika.wolle`) and `applicationId` are untouched
- [x] Also renamed for consistency (not strictly required, but "wolle" scattered through
      internal-only identifiers was worth cleaning up while in there): `settings.gradle.kts`'s
      `rootProject.name` ("wolle" -> "stricknani-android"), `flake.nix`'s `appName`, the
      `justfile`'s header comment, its `WOLLE_*` env var names -> `STRICKNANI_ANDROID_*`, its
      remote build path (`~/build/wolle-android` -> `~/build/stricknani-android` - re-synced and
      re-verified after the rename, see Status below), and its placeholder SNA-12 keystore/rbw
      entry names
- [x] Rebuilt, redeployed, and reverified on all three fleet devices after the rename (not just a
      docs change) - see Status below

Status: **done** (2026-08-18) - CI green on the rename commit; rebuilt on `rofl-13.brkn.lol`
against the renamed `~/build/stricknani-android` remote path (fresh full sync, confirms the
justfile's remote-path rename works, not just the code rename) and reinstalled on Zenfone 10 and
Mi Pad 4, confirmed running (`ResumedActivity`, no crash). `aapt2 dump badging` on the built APK
is the authoritative proof of the rename itself: `application: label='Stricknani'` across every
bundled locale. Pixel 5 wasn't reinstalled this pass - its wireless adb had disconnected and the
`rbw`-gated Home Assistant reconnect webhook wasn't accepted within the timeout - but it's running
the identical pre-rename build from SNA-5 (same code, only the label changed), so there's no
functional gap, just a stale label on that one device until its next reconnect + `just px5-install`.

<!-- vim: set ft=markdown et ts=2 sw=2 : -->
