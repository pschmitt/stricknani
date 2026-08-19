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

- [x] Room schema: `ProjectEntity`/`YarnEntity`/`CategoryEntity` with flat filter/sort columns +
      a `detailJson` blob column for full nested detail, decoded at read time
- [x] Cache-first repositories (`ProjectRepository`, `YarnRepository`, `CategoryRepository`) -
      every read is a Room `Flow`; a failed `refresh()` never clears cached data
- [x] `SyncWorker` (WorkManager periodic, `NetworkType.CONNECTED`) + `SyncScheduler`, driven by
      the `/api/v1/sync/*` delta endpoints (SNA-3) - upsert `updated`, delete `deleted_ids`, never
      a naive full refetch
- [x] Best-effort sync on launch (`StricknaniApp.onCreate` + right after onboarding succeeds, both
      via `SyncScheduler.syncNow()`); a failed sync step is caught per-step in `SyncWorker` and
      never clears already-cached data. Pull-to-refresh itself and a staleness indicator are UI
      surface that doesn't exist yet - deferred to SNA-9, not duplicated here
- [x] Coil3 image loading via a dedicated `@MediaClient` OkHttp client (`AuthInterceptor` only, no
      `DynamicBaseUrlInterceptor` - Stricknani's media URLs are already relative, so double-
      prefixing would break subpath-hosted instances), wired as `StricknaniApp`'s
      `SingletonImageLoader.Factory`, disk-cached (256 MiB cap), not duplicated into Room

Status: done (2026-08-18). Scoped to the *read* path per this task's own checklist - write
endpoints/UI are SNA-8 (offline write queue) and SNA-10 (create/edit screens), not duplicated here.

DTOs (`data/api/dto/*.kt`) mirror `stricknani/routes/api/schemas.py` field-for-field, re-verified
against the live source during this task (not just recalled from memory) - every response/request
schema, `@GET`/`@POST` path and query param cross-checked against `routes/api/{meta,categories,
yarns,projects,sync}.py`. Timestamp fields are plain `String` (not `kotlinx.datetime.Instant`),
parsed defensively by `data/util/DateTimeUtils.kt` at the repository/mapper layer - handles both
the naive-UTC form the backend's `created_at`/`updated_at` actually serialize as and the offset-
aware form `server_time` uses, rather than assuming a single strict ISO8601 shape.

Room: one table per entity (`projects`/`yarns`/`categories`) combining flat sort/filter columns
with a `detailJson` blob of the full synced DTO (same pattern as syncwich's `RecipeDetailEntity`),
plus a `sync_state` table persisting each entity type's last `server_time` cursor (replayed
verbatim as the next `since`, never reformatted client-side) so it commits atomically with the
rows it gates. `YarnResponse`/`ProjectResponse` have no server-computed `preview_url` (only their
`*ListItemResponse` counterparts do) - the repository computes an equivalent client-side from the
primary/title image's thumbnail. `fallbackToDestructiveMigration(dropAllTables = true)`: every
table here is a rebuildable server cache, not user data, so a future schema bump just wipes and
resyncs. `SettingsViewModel.signOut()` now also clears the whole database, so a sign-out doesn't
leak the previous account's cached rows into a fresh sign-in.

WorkManager's default ContentProvider auto-init was already pre-disabled in the manifest back in
SNA-5/6 (anticipating this task) - `di/WorkModule.kt` provides `WorkManager.getInstance(context)`
explicitly instead, which needed one fix during this task: the first remote build failed with a
Dagger `MissingBinding` on `WorkManager` (`SyncScheduler` needed it, nothing provided it) - added
`WorkModule` (mirrors syncwich's) and the remote debug build went green on the retry.

Verified: `just build debug rofl-13.brkn.lol` (remote, per this repo's build convention) compiles
clean - `BUILD SUCCESSFUL`, Hilt/KSP/Room codegen all resolve, no unresolved references. **Not
verified**: an actual live sync against a running Stricknani server (no reachable test server was
set up this session, same gap noted in SNA-6), and there is no UI yet that displays synced data
(Home/Projects/Yarns/Search are still `PlaceholderScreen`s from SNA-5) - both are exercised
end-to-end once SNA-9 replaces those placeholders with real screens reading from these
repositories. Local `ktfmtCheck` was not run given its documented unreliable-NO-SOURCE caveat
(see this file's SNA-5/SNA-6 notes and `android/AGENTS.md`) - pushing and letting CI be the
authoritative formatting/lint check instead. CI's `Android Lint` job did catch real ktfmt
violations on the first push (as expected per that caveat); fixed by applying the run's
`ktfmt-diff-patch` artifact, re-verified with another remote debug build, and CI is green on the
follow-up push.

## SNA-8: Offline write queue

- [x] Local "pending mutation" outbox table (`PendingMutationEntity`/`PendingMutationDao`,
      `pending_mutations` table, DB version bumped to 2): create/update/delete ops, client
      -generated negative temp ids for offline-created rows (`ProjectDao`/`YarnDao.minId()` mints
      the next one)
- [x] `WriteReplayWorker`: flushes the outbox when connectivity returns (periodic, every 15
      minutes, plus an immediate one-off chained before every post-write sync via
      `SyncScheduler.replayThenSyncNow()`), replays strictly in insertion order, reconciles temp
      ids with server-assigned ids via `PendingMutationDao.reassignLocalId` once a queued create
      actually lands
- [x] A replay failure leaves the mutation queued with `lastErrorMessage` recorded and the worker
      returns `Result.retry()` when any mutation failed; the Home and Settings screens surface
      queued sync issues and offer retry/dismiss actions rather than hiding a stuck local edit

Status: **mostly done** (2026-08-19) - verified with the new conflict/sync-issue unit tests and
remote `just check rofl-13.brkn.lol` (ktfmt, unit tests, and Android Lint). A live replay against a
running Stricknani server remains unverified because no disposable test server was available.

### Android app screens

## SNA-9: Core browsing screens

- [x] Home/dashboard: favorites (projects + yarns) and recently-updated projects, pull-to-refresh
- [x] Projects list: search, category/tag filter chips (+ inline "add category" dialog), favorite
      toggle, pull-to-refresh
- [x] Project detail: steps, images, needles/stitch sample/other materials, linked yarns
      (tappable, resolved from `yarn_ids` against the local yarn cache), notes
- [x] Yarn stash list + detail: search by name/brand/colorway, favorites-only filter, photos
- [x] Category management: create from the Projects screen's filter row and rename/delete from
      the Categories screen, with explicit delete confirmation and project reassignment handled by
      the authenticated JSON API
- [x] Global search across projects and yarns - client-side over the Room cache, so it works fully
      offline with zero network call

Status: **done** (2026-08-19) - tag filter chips are derived from the offline Room project cache,
and category rename/delete are available in the Android Categories screen and authenticated JSON
API. Focused Android and API tests cover filtering, rename propagation, deletion clearing project
categories, and duplicate-name rejection; remote `just check rofl-13.brkn.lol` is green.

Favorite toggling (`ProjectsApi`/`YarnsApi` `favorite`/`unfavorite`, wired into
`ProjectRepository`/`YarnRepository`) is a direct online call with local optimistic-update +
rollback-on-failure, not routed through the still-nonexistent SNA-8 write queue - reasonable for a
single idempotent boolean field; full create/update/delete writes still wait for SNA-8/SNA-10.

Media: `data/media/MediaUrlResolver.kt` turns a DTO's relative `/media/...` path into an absolute
URL using the currently configured server, consumed by every screen via `viewModel.previewUrl()`/
`resolveMediaUrl()` and rendered with Coil3's `AsyncImage` against the `@MediaClient` image loader
wired in SNA-7.

Navigation: added `Route.ProjectDetail(projectId)`/`Route.YarnDetail(yarnId)` (type-safe nav args
via `SavedStateHandle.toRoute<>()`, same pattern as syncwich); `StricknaniNavHost` now routes all
five bottom-nav destinations to real screens plus the two detail routes - no `PlaceholderScreen`
usages remain anywhere in the nav graph. Removed the now-unused `placeholder_home_*`/
`placeholder_projects_*`/`placeholder_yarns_*`/`placeholder_search_*` string resources
(`placeholder_settings_*` stays - `SettingsScreen` is still sign-out-only pending SNA-18).

Verified: `just build debug rofl-13.brkn.lol` - `BUILD SUCCESSFUL` (fixed one real compile error
along the way: a missing `androidx.compose.foundation.layout.height` import in
`YarnsListScreen.kt`, caught by the remote build, not local static checks). Installed and launched
on the Zenfone 10 (`just deploy-zenfone debug`) - app starts with no crash, `logcat` shows
`SyncWorker` completing successfully (`Worker result SUCCESS` x2, from the periodic + startup
enqueue in `StricknaniApp.onCreate`), and the Onboarding screen renders correctly. **Not
verified**: actually rendering synced Home/Projects/Yarns/Search data on-device - the device's
saved credentials from earlier SNA-6 testing are no longer present (onboarding showed instead of
the main shell), and per SNA-7's own "not verified" note, no reachable Stricknani test server was
set up this session either, so re-onboarding wasn't possible. Onboarding's own network-gated
validation means the main shell (and therefore these new screens) cannot be reached at all without
a real server + token - closing this gap needs a reachable test instance in a future session.
CI (`Android Lint`) caught real ktfmt violations on the first push, as expected per the documented
caveat; fixed via the run's `ktfmt-diff-patch` artifact, re-verified with another remote debug
build, and CI is green on the follow-up push.

## SNA-10: Create/edit flows

- [x] Project create/edit form (`ui/projects/ProjectEditorScreen.kt`/`ProjectEditorViewModel.kt`):
      name/category/needles/stitch sample/other materials/tags/link/description/notes text
      fields, category suggestion chips (reuses `CategoryRepository`), linked-yarn multi-select
      via `FilterChip`s, step reordering, queued title/step image uploads, and project attachment
      picker uploads/deletions
- [x] Yarn create/edit form (`ui/yarns/YarnEditorScreen.kt`/`YarnEditorViewModel.kt`): same text
      -field shape as the project form, mirroring `YarnWriteRequest`, plus queued multi-photo
      uploads
- [x] All writes go through the offline queue (SNA-8) via
      `ProjectRepository.createProject`/`updateProject`/`deleteProject` (and the yarn
      equivalents) - zero-connectivity create/edit/delete shows up in Room immediately and
      replays once connectivity returns
- [x] Delete wired end-to-end too (confirmation dialog in both editor screens' top bar), even
      though it wasn't explicitly in this task's original checklist - the write-queue delete
      path already existed from SNA-8's `deleteProject`/`deleteYarn`/`replayDelete`, so exposing
      it in the UI was a small addition that makes the offline queue fully exercisable rather than
      leaving one of its three operations UI-dead
- [x] Navigation: `Route.ProjectEditor(projectId: Int? = null)`/`Route.YarnEditor(yarnId: Int? =
      null)` (`null` = create, a value = edit) wired into `StricknaniNavHost` - a "New
      project"/"New yarn" `ExtendedFloatingActionButton` on the respective list screens, an edit
      icon in the respective detail screens' `TopAppBar`

Status: **mostly done** (2026-08-19) - verified via remote `just check rofl-13.brkn.lol` (ktfmt,
unit tests, and Android Lint), plus focused step-reordering and project-attachment mutation tests
and API regression tests that confirm step IDs and attached images survive an update reorder. An
actual live create/edit/delete/upload round-trip remains unverified because no reachable
disposable Stricknani server was available.

## SNA-11: Gauge calculator

- [x] Port `stricknani/utils/gauge.py`'s calculation logic natively into Kotlin
      (`data/util/GaugeCalculator.kt`, pure math, no network call needed) rather than adding a
      backend endpoint for it - keeps this screen fully offline-capable with zero dependency on
      server reachability
- [x] `ui/gauge/GaugeCalculatorScreen.kt`: no ViewModel - the screen holds its own `remember`/
      `rememberSaveable` state directly since there's nothing to inject (no repository, no
      network, no persistence), unlike every other screen in the app. Mirrors
      `templates/gauge/calculator.html`'s three field groups (pattern gauge / your gauge / pattern
      counts) and result card; the Calculate button is disabled until every required field is a
      positive integer, matching the backend's `Form(gt=0)` validation but rejected client-side
      before submission is even possible rather than surfaced as a 4xx after the fact
- [x] Reachable from `HomeScreen`'s new `TopAppBar` (a calculator icon action) via
      `Route.Gauge` - not a bottom-nav destination, since the five-item bottom nav is fixed
      (SNA-5/9) and this is an occasional-use tool, not core browsing
- [x] Unit tests (`GaugeCalculatorTest.kt`, JUnit4 - this repo's **first** Android unit test,
      finally isolable pure logic per the reasoning in SNA-6/7/8/10's notes): 4 cases ported
      verbatim from `tests/test_gauge.py`'s test vectors

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (all 4 new tests pass, `BUILD SUCCESSFUL`), then `just deploy-all debug`
+ relaunch on Zenfone 10, Mi Pad 4, and Pixel 5 (`ResumedActivity`, no crash in logcat on any of
the three). One real correctness bug caught and fixed before landing: the initial port used
`kotlin.math.roundToInt()` (round-half-up), but Python's builtin `round()` - what the backend
actually uses - is round-half-to-even, and gauge ratios land exactly on `.5` often enough in
practice (e.g. 121 cast-on stitches at a 19/22 gauge ratio = exactly 104.5) that the two would have
silently disagreed with the web app on that boundary; the port test caught it (expected 104 per
`tests/test_gauge.py`, got 105) and it's now fixed via `Math.rint()`. **Not verified**: the
calculator screen's actual on-device rendering/interaction - reaching it requires being past
Onboarding, and no reachable Stricknani test server has been set up in any session so far (same
recurring gap as SNA-6/7/8/9/10); only confirmed the app still launches without crashing after this
change, not that the new screen looks/behaves correctly once opened.

## SNA-12: Fleet release parity

- [x] Generated a real release-signing keystore (RSA 4096, `keytool`, 30yr validity, alias
      `stricknani-ci`) - PKCS12 forces a single store/key password, unlike the older JKS format
      the sibling apps' comments assume, so `CI_KEYSTORE_PASSWORD` and `CI_KEY_PASSWORD` are the
      same value here. User asked (2026-08-18) to also save it to Bitwarden, matching the fleet's
      existing pattern - stored as the "Stricknani Android CI Signing Keystore" rbw entry (a
      `secure_note`, two attachments: `stricknani-android-ci.jks` +
      `stricknani-android-ci-keystore.env`), verified with a round-trip download/diff before
      registering `CI_KEYSTORE_BASE64`/`CI_KEYSTORE_PASSWORD`/`CI_KEY_ALIAS`/`CI_KEY_PASSWORD` as
      GitHub Actions repo secrets and flipping `android/justfile`'s `enable_release_signing` to
      `"true"` (the justfile's `rbw_keystore_entry`/attachment-name variables already matched this
      exact naming before today - only the keystore/secrets themselves were missing). All local
      copies of the keystore/passwords were `shred -u`'d after upload; only the rbw entry and the
      GitHub secrets hold it now.
- [x] `.github/workflows/release.yaml`: hand-written (not delegated to
      `pschmitt/android-app-ci`'s reusable release workflow) for the same repo-root-vs-`android/`
      subdirectory reason as `android-build.yaml`/`android-lint.yaml`, but mirrors that reusable
      workflow's actual logic closely - signed release + debug APK builds, a rolling `latest`
      prerelease on every `main` push and a real permanent release on a semver tag push,
      `SHA256SUMS`, SLSA build provenance attestation, embedded-version-name and
      embedded-`GIT_REVISION` verification, stale-asset vacuuming on the `latest` tag. Deliberately
      has **no** `paths:` filter (unlike the other two workflows) - GitHub ANDs `paths` with `tags`
      on the same `push` trigger, and path-diff filtering is unreliable for a brand-new tag ref, so
      a real version-tag release could silently no-op; an occasional unneeded rebuild on a
      backend-only commit to `main` is a far smaller cost than that.
- [x] Obtainium badge + `declaroid` YAML snippet added to `android/README.md`'s new
      "Installation" section, matching jollyfin's exact wording/structure - the Obtainium deep
      link's `additionalSettings` filters to `.*-release\.apk$` (excludes the `latest` tag's debug
      APKs) with `autoApkFilterByArch: true` so it auto-picks the right ABI split.
- [x] Physical-device deploy recipes: already re-verified repeatedly this session (`just
      deploy-all` on the Zenfone 10 across SNA-19/21/22/23/24/25/26/27's device-testing passes;
      Mi Pad 4 mostly works but needs occasional wireless-adb reconnects; Pixel 5 has been
      unreachable over wireless adb for this entire session - a Home Assistant/Tasker webhook gap
      outside this app's control, not a deploy-recipe defect).
- [x] The user decided to pursue Play Store publishing; repository scaffolding and a dry-run-safe,
      explicitly gated workflow now live in SNA-42. The external Play Console account/listing,
      screenshots, privacy URL, content-rating/data-safety forms, and service-account credential
      remain intentionally unverified until that setup is completed.

Status: **mostly done** (2026-08-18) - the release workflow's first-ever run (triggered by its own
landing commit, since it has no `paths:` filter) succeeded outright: all 9 CI checks green
including `Release`, and `gh release view latest` confirms a real signed prerelease with all 8 APKs
(4 ABIs x debug/release) plus `SHA256SUMS`. Play Store publication remains a separate SNA-42
follow-up and is disabled by default.

## Stretch / later

## SNA-13: QR-code onboarding

- [x] Researched nyetbox's/jollyfin's QR setup patterns first (2026-08-18): both are *local,
      offline* tools (a Nix/`qrencode` CLI for nyetbox against third-party NetBox; an in-app
      device-to-device config export for jollyfin, no server involved at all) - neither actually
      has a web-side generation component, since neither app's own backend is first-party the way
      Stricknani's is. So this is a genuinely new capability, not a port: server-side QR rendering
      on the web Settings page, built from nyetbox's proven wire format
      (`stricknani://setup?p=<base64url-JSON>`, unencrypted like nyetbox's - not jollyfin's
      password-protected variant, since this is shown once on an already-authenticated page and
      scanned immediately, not carried around long-term) and its CameraX+ZXing scanning stack.
- [x] **Web (backend)**: `stricknani/utils/qr_setup.py` builds the setup URI and renders it as a
      `data:image/png` QR via the new `qrcode[pil]` dependency (`pyproject.toml`, `nix/package.nix`,
      `uv.lock`); `POST /user/api-tokens/qr-setup` (`stricknani/routes/user.py`) mints a token named
      "QR setup" and renders it on the existing API Tokens page (`templates/user/api_tokens.html`),
      reusing `web/middleware.py`'s `_is_secure_request` to build the right `scheme://host` even
      behind a TLS-terminating reverse proxy. New template strings extracted/translated into German
      (`just i18n-update`, `i18n-check` passes).
- [x] **Also added** (adjacent, same "get a token onto a new device without visiting the web UI
      first" goal, user-requested 2026-08-18): `POST /api/v1/auth/token`
      (`stricknani/routes/api/auth.py`) trades an email/password for a freshly minted PAT in one
      request - simpler than syncwich's Mealie-backed two-step JWT-then-mint-token dance, since
      Stricknani's own backend controls both ends. Reuses `authenticate_user`/rate-limiting exactly
      like `/auth/login`. Needs its own CSRF exemption (`main.py`'s `_CSRF_EXEMPT_PATHS`, by exact
      path) since - unlike every other `/api/v1` route - it has no Bearer token yet to exempt via
      the normal header check. 5 new tests (`tests/test_api_auth_token.py`): success, custom token
      name, wrong password, unknown email, rate-limited after repeated failures.
- [x] **Android**: `data/onboarding/QrConfigCodec.kt` (decode-only, generation is server-side) and
      `data/onboarding/PasswordTokenMinter.kt` (plain `OkHttpClient` + manual JSON, matching
      `OnboardingValidator`'s existing pattern rather than introducing Retrofit for one endpoint).
      `scanner/BarcodeAnalyzer.kt` + `ui/onboarding/QrScannerDialog.kt`: CameraX + ZXing (same
      versions as nyetbox/jollyfin's scanners), deliberately much simpler than nyetbox's full
      `ScannerScreen` (no lens switching/zoom/torch/tap-to-focus - a one-shot setup-code scan
      doesn't need any of that), shown as a plain `Dialog` like `ImageViewerDialog` rather than a
      nav route. `OnboardingScreen` now has a 3-way mode switch (Manual / QR code / Sign in);
      `OnboardingViewModel` gained `signInWithPassword`/`connectFromScannedText`, both funneling
      into a shared `persistAndSucceed` (extracted from `connect`, matching syncwich's
      `persistAndSucceed` split - minted/QR-scanned tokens don't need `connect`'s own validation
      round-trip the same way, though QR still goes through it since a scanned code could be stale
      by the time it's actually scanned, unlike a token minted in the same request). Also
      registered `stricknani://setup` as a manifest intent-filter, so a phone's own default camera
      app's QR auto-detection can hand the payload back to Stricknani too, not just the in-app
      scanner. `QrConfigCodecTest` includes a cross-check against a payload built exactly the way
      the Python backend encodes it, not just this class's own round-trip.

Status: **done** (2026-08-19) - verified via `nix develop -c uv run pytest -q` (261 passed)
+ `ruff format`/`ruff check` clean + `just i18n-check` passing on the backend, and
`just gradle rofl-13.brkn.lol ":app:assembleDebug" ":app:testDebugUnitTest" ":app:lintDebug"`
(`BUILD SUCCESSFUL`, lint clean) on Android. Confirmed for real on the Zenfone 10: signed out to
reach the new onboarding screen, all three mode chips render and fit on screen (fixed a real bug
here - the initial "Server URL + token"/"Scan QR code"/"Sign in" labels overflowed off the right
edge on this phone's width; shortened to "Manual"/"QR code"/"Sign in" and added a horizontal-scroll
fallback), the QR scanner dialog correctly requests camera permission and binds a live CameraX
preview with the dimmed-viewfinder overlay (status bar's green camera indicator confirms real
binding, not just a UI mock). The password sign-in flow is now **confirmed working end-to-end**:
the initial live test correctly reached production and got a real HTTP 404 because the backend
work hadn't been deployed yet (rofl-10 was still pinned to a pre-SNA-13 `stricknani` rev in
`nixos-config.git`'s `flake.lock` - a stale pin, not a bad deploy attempt). Re-ran
`nix flake lock --update-input stricknani` + `just deploy rofl-10` and confirmed via
`curl https://wolle.anika.blue/openapi.json` that `/api/v1/auth/token` and
`/user/api-tokens/qr-setup` are now live; then signed in on the Zenfone with the dedicated
`ai@anika.blue` test account against production and landed on Home with a real synced project
list - the full round trip (password → minted token → persisted connection → sync) works.

## SNA-14: Sync-completion notifications

- [x] Optional local notification when a background sync finds changes (new `SyncNotifier`:
      channel creation + posting, gated on the `POST_NOTIFICATIONS` runtime permission -
      Android 13+, silently no-ops if denied/not yet granted). **Scoped down** from the original
      "or when an async backend job (e.g. link archiving) completes" - that half needs a
      server-side way to detect a specific async job's completion, which doesn't exist yet; only
      the background-sync-found-changes half is tractable today
- [x] Only the *periodic* background sync notifies - `SyncWorker` reads a
      `KEY_NOTIFY_ON_CHANGE` input-data flag that `SyncScheduler.schedulePeriodic()` sets (and
      `syncNow()`/`replayThenSyncNow()` don't), since a manual pull-to-refresh or the on-launch
      sync means the user is already looking at the fresh data - notifying then would just be
      noise
- [x] Only project/yarn sync results count as "changes" - categories are excluded since they have
      no real delta (`CategoryRepository.sync()` always replaces the whole list; "changed" there
      is meaningless noise, not a signal). `ProjectRepository.sync()`/`YarnRepository.sync()` now
      return `Boolean` (did this pull anything) instead of `Unit`
- [x] `POST_NOTIFICATIONS` requested once from `HomeScreen` (`RequestNotificationPermissionEffect`,
      `ui/common/`) rather than at cold start - the prompt appears once the user has actually
      reached the app's main shell (post-onboarding), not before they've seen any value in it

Status: **mostly done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol
":app:assembleDebug" ":app:testDebugUnitTest" ":app:lintDebug"` (`BUILD SUCCESSFUL`, lint clean -
including the `POST_NOTIFICATIONS`-gated `NotificationManagerCompat.notify()` call, which lint
would otherwise flag as a missing-permission call), then `just deploy-all debug` + relaunch on
Zenfone 10, Mi Pad 4, and Pixel 5 (`ResumedActivity`, no crash in logcat on any of the three).
**Not verified**: an actual notification firing on-device - that needs a periodic `SyncWorker` run
that both finds real changes and has connectivity to a real Stricknani server, none of which is
reachable this session (same recurring gap as every sync-touching ticket since SNA-6/7). No unit
tests added for `SyncNotifier` itself (Android `NotificationManager`/permission-check-bound, same
"not enough isolable pure logic" reasoning as SNA-6/7/8/10/18) - the pure boolean-return change to
`ProjectRepository.sync()`/`YarnRepository.sync()` doesn't have new pure logic worth isolating
either (it's a one-line derivation already exercised implicitly by the repositories' existing
behavior).

## SNA-15: Backup/restore support

- [x] User confirmed (2026-08-18, asked because the ticket as written matches syncwich's
      always-authoritative-local-data model, which doesn't quite fit here) to build full syncwich
      parity - same PBKDF2+AES-GCM crypto, zip container, SAF UX, WorkManager scheduling - but
      **deliberately excluding the Room cache and cached media** from what gets backed up, unlike
      syncwich. `DatabaseModule.provideAppDatabase`'s own kdoc already frames every `AppDatabase`
      table as a disposable server-side mirror (`fallbackToDestructiveMigration` on every schema
      bump, no hand-written migrations) - backing that up would just risk restoring stale rows that
      then fight the next sync. The credentials + settings are the genuinely non-reproducible part
      (losing the API token means re-onboarding from scratch), so that's the whole backup payload.
      This also directly satisfies the ticket's own restore requirement ("reconcile against a
      subsequent sync pull rather than treating the backup as more authoritative than the server")
      - there's nothing backed up that *could* conflict with the server in the first place.
- [x] `data/backup/BackupCrypto.kt`: PBKDF2WithHmacSHA256 (210k iterations, 256-bit key) -> AES/GCM
      /NoPadding, magic `"STB1"` + plain/encrypted flag + salt/iv framing - same scheme as syncwich/
      nyetbox's own `BackupCrypto`, just this app's own magic bytes. Unit-tested
      (`BackupCryptoTest`: round-trip, wrong password, tampered ciphertext, garbage input) since
      it's pure JVM logic with no Android framework binding, matching the `GaugeCalculatorTest`/
      `NavbarCustomizationTest` precedent.
- [x] `data/backup/BackupManager.kt`: builds/reads a zip (`manifest.json`, `credentials.json`,
      `settings.json`) via `java.util.zip` (no new dependency, matching syncwich), passed through
      `BackupCrypto`. `import()` doesn't touch Room - the caller (`SettingsViewModel.importBackup`)
      triggers `SyncScheduler.syncNow()` right after a successful restore instead.
- [x] `data/backup/{BackupScheduler,BackupWorker}.kt`: `BackupWorker` (`@HiltWorker`, matching
      `SyncWorker`/`WriteReplayWorker`'s pattern) writes into the SAF folder tree the user picked,
      using platform `DocumentsContract` directly (no `androidx.documentfile` dependency needed for
      a single create-and-write). `BackupScheduler` enqueues/cancels it via
      `PeriodicWorkRequestBuilder` keyed by `BackupFrequency` (Daily/Weekly/Monthly).
      `AppPreferencesRepository` gained the folder-URI/enabled/frequency prefs (plain DataStore,
      matching its existing non-secret settings); the optional backup password is a deliberate
      deviation from syncwich - stored via `SettingsRepository`'s existing
      `EncryptedSharedPreferences` instead of a plain DataStore string, since this app already has
      Keystore-backed storage available and a backup password is exactly the secret it exists for.
- [x] `ui/settings/BackupSettingsScreen.kt` (new `SettingsCategory.Backup` entry): manual "Export
      now" (`ActivityResultContracts.CreateDocument`, optional password via a dialog before the
      write) / "Choose file" restore (`OpenDocument`, a password dialog only appears if
      `BackupManager.import` throws `BackupPasswordRequiredException`), and a "Scheduled backups"
      card (enable switch that launches `OpenDocumentTree` + calls
      `takePersistableUriPermission` - otherwise the grant is transient and `BackupWorker` loses
      folder access the next time the process restarts - frequency chips, folder/password rows).
      All state lives on the existing shared `SettingsViewModel` (not a dedicated
      `BackupSettingsViewModel` like syncwich's), matching this app's one-shared-ViewModel-per-hub
      convention from SNA-21.

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest" ":app:lintDebug"` (`BUILD SUCCESSFUL`, lint clean, `BackupCryptoTest`
green), then confirmed for real on the Zenfone 10: the Backup category card and screen render
correctly; "Export now" launches the `CreateDocument` picker with a sensible suggested filename
(and, incidentally, confirmed sibling apps syncwich/nyetbox/jollyfin really do already have
matching `*-backup*.bin`/`*_backup.bin` files sitting in Downloads, exactly the reference pattern
this was built from); "Choose file" launches the `OpenDocument` picker; the scheduled-backup
password dialog round-tripped for real through `SettingsRepository`'s `EncryptedSharedPreferences`
("Backup password set" / "Backup password removed" snackbars, "Backup password" row correctly
flipping between "Set"/"Not set (unencrypted)"). Didn't complete an actual end-to-end
export-a-file-then-restore-it pass - the on-device system file picker's rename-on-save text field
didn't respond reliably to `adb shell input text`/`keyevent` (a driving-the-picker-via-adb
limitation, not an app bug), and completing it risked overwriting an unrelated file already sitting
in the shared test device's Downloads folder, so I backed out of the picker rather than force it.
Mi Pad 4 wireless adb dropped again this pass (same recurring gap); Pixel 5 still unreachable.

## SNA-16: Configurable navbar

- [x] Let the user choose which destinations appear in the bottom nav and in what order
      (`ui/settings/SettingsScreen.kt`'s new "Navigation" section: a checkbox + up/down arrows
      per destination), matching nyetbox's configurable navbar in spirit - simplified to
      up/down-arrow reordering rather than drag-and-drop, to avoid a new dependency for a
      5-item list. Persisted via a new `AppPreferencesRepository.navbarItems` (DataStore,
      `NavbarItemPreference(id, visible)` list serialized as JSON) - reused rather than adding a
      second DataStore-backed repository just for this
- [x] Sensible default order (Home / Projects / Yarn Stash / Search / Settings, per SNA-5, in
      `TopLevelDestination`'s declared order) so this is a customization on top of a good
      default, not a required setup step - `NavbarCustomization.sanitize(null)` (nothing saved
      yet) returns exactly that
- [x] `TopLevelDestination.SETTINGS` can never be hidden (its checkbox is disabled) - it's the
      only way back to this customization UI, so hiding it would lock the user out of un-hiding
      anything. Enforced twice: the checkbox is disabled in the UI, and
      `NavbarCustomization.sanitize` forces it visible again even if a corrupted/hand-edited
      DataStore value claims otherwise
- [x] Forward/backward compatible against future destination changes: an unknown saved id is
      dropped (a destination removed in a later release), and a destination missing from a saved
      preference is appended as visible (one added in a later release) - `NavBarViewModel` backs
      the bottom bar itself, reading through the same sanitizer
- [x] Unit tests (`NavbarCustomizationTest.kt`, JUnit4 - second Android unit test after SNA-11's):
      5 cases covering the default-fallback, unknown-id-dropped, settings-forced-visible,
      missing-destination-appended, and visible-filtering behaviors

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`, all tests pass), then confirmed for real on the
Zenfone 10 against the `ai@anika.blue` test account (SNA-25): the Navigation section renders all
five destinations with correct checkboxes/icons/labels and working up/down reorder arrows. One
real compile error caught before landing: `Modifier.padding(horizontal = ..., bottom = ...)` isn't
a valid overload (`padding` only accepts `horizontal`+`vertical` together, or all four sides
individually) - fixed by spelling out `start`/`end`/`bottom` explicitly. Redesign pending as part
of SNA-21 (Settings screen overhaul).

## SNA-17: Deep links ("open with" a project/yarn URL)

- [x] Researched syncwich's/nyetbox's actual App Links implementations before starting (both
      sibling repos, via a research pass) rather than assume the ticket's own premise held up:
      **neither app actually solves the "one APK, arbitrary self-hosted domain, verified/no-dialog
      App Links" problem.** syncwich only gets `autoVerify`+no-dialog behavior because it targets
      one hardcoded domain (`nom.brkn.lol`) baked into its own manifest - its
      `docs/deep-links.md` explicitly says supporting a different self-hosted domain needs a
      manifest edit and a new release build, which doesn't fit Stricknani's actually-multi-tenant,
      one-APK-many-servers model at all. nyetbox's `docs/app-links.md` is the real precedent: it
      explicitly rejects verified App Links for exactly this reason and ships a wildcard-host,
      non-`autoVerify` intent filter instead, relying on Android's normal link-handling (the user
      can promote the app via Settings -> Apps -> Stricknani -> "Open by default", or Android's
      "Open with" chooser where that appears) - so that's the pattern this follows.
- [x] `AndroidManifest.xml`: two new `android:host="*"`, non-`autoVerify` intent-filters (https and
      http, matching `usesCleartextTraffic`) with `android:pathPattern="/projects/.*"` and
      `"/yarn/.*"`, exactly matching the web app's own `stricknani/routes/{projects,yarn}.py`
      route prefixes (confirmed by reading those routers directly rather than guessing).
- [x] `ui/navigation/DeepLink.kt` (`DeepLinkParser.parse`): parses only the URL's *path*, not its
      host - like nyetbox's `NetBoxUrlParser`, a Stricknani install is single-server-per-device
      (one `SettingsRepository.serverUrl`), so whichever server is currently configured is
      necessarily the right target for any matching path; there's no "link points to a server I'm
      not connected to" case to handle. Unit-tested (`DeepLinkParserTest`, pure logic, matches the
      `BackupCryptoTest`/`GaugeCalculatorTest` precedent).
- [x] `MainActivity.kt` (`pendingDeepLink` state, `onCreate`/`onNewIntent` - the manifest's
      existing `android:launchMode="singleTask"` means a warm relaunch hits `onNewIntent`, not a
      fresh `onCreate`) + `StricknaniNavHost.kt` (`pendingDeepLinkRoute`/`onDeepLinkConsumed`
      params, consumed via a `LaunchedEffect` alongside the `NavController` it already owns):
      deferred (not dropped) until `SettingsRepository.isConfigured` is true, matching nyetbox's/
      syncwich's onboarding-gating pattern, then a single `navController.navigate(route)`.
- [x] Share action: `ProjectDetailViewModel`/`YarnDetailViewModel` gained `shareUrl()` (reuses
      `MediaUrlResolver.resolve("/projects/$id")`/`"/yarn/$id"` - it already does exactly "prepend
      the configured server's base URL", no new code needed there), wired to a new Share icon in
      both detail screens' `TopAppBar` via a small `Context.shareUrl()` extension
      (`ui/common/ShareUrl.kt`, plain `ACTION_SEND` + `createChooser`).

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest" ":app:lintDebug"` (`BUILD SUCCESSFUL`, lint clean, `DeepLinkParserTest`
green), then confirmed for real on the Zenfone 10 against the `ai@anika.blue` account. Checked
Android's actual platform behavior directly rather than assume: `adb shell dumpsys package
domain-preferred-apps` and the `android.settings.APP_OPEN_BY_DEFAULT_SETTINGS` screen both confirm
Stricknani is correctly registered as a link-capable app for these paths ("Open supported links"
already on, "0 verified links" - exactly the expected unverified-wildcard-host state, not a bug).
A plain `am start -a VIEW -d https://wolle.anika.blue/projects/6` (no explicit component) opened
Chrome, not the chooser - expected: modern Android silently prefers the assigned system default
browser for *unverified* web-scheme links rather than offering non-browser apps in a chooser,
which is exactly nyetbox's own documented limitation, not a defect in this implementation.
Targeting `MainActivity` explicitly (equivalent to what a real "Open with" chooser selection would
deliver) proved the app's own handling is correct end-to-end: a project link cold-started the app
straight into "SNA-22 Markdown Test" (id 6), and a yarn link sent to the already-running app (
`onNewIntent`, confirmed via adb's "delivered to currently running top-most instance" message)
correctly routed to the Yarn detail screen's existing "Yarn not found" state for a nonexistent id -
proving both the cold-start and warm-relaunch paths, and that an invalid id degrades gracefully
instead of crashing. The Share icon was also verified for real: tapping it on the same test project
opened the system share sheet pre-filled with the exact expected URL
(`https://wolle.anika.blue/projects/6`) - backed out without selecting a real contact, since this
is a shared test device with the owner's actual contacts in that sheet. Mi Pad 4 still needs
re-onboarding (unrelated, noted under SNA-22); Pixel 5 still unreachable over wireless adb.

## SNA-18: Settings screen with an About section

- [x] Dedicated Settings screen (`ui/settings/SettingsScreen.kt`/`SettingsViewModel.kt`): Account
      (server URL display + sign-out, unchanged from SNA-6), Appearance (light/dark/"follow
      system" radio group, new `ThemeMode` persisted via a new `AppPreferencesRepository` -
      DataStore `Preferences`, not `EncryptedSharedPreferences`, since nothing here needs
      Keystore encryption), Sync (last-synced timestamp derived from `SyncStateDao`'s per-entity
      cursors + a static description of the current fixed policy). Navbar customization
      (SNA-16) and backup/restore (SNA-15) are **not** shown - there's nothing to surface until
      those tickets exist; the screen doesn't invent placeholder UI for features that don't work
      yet
- [x] `MainActivity` now reads `AppPreferencesRepository.themeMode` and passes the resolved
      `darkTheme: Boolean` into `StricknaniTheme` (was hardcoded to `isSystemInDarkTheme()`)
- [x] About section: app version/build (`BuildConfig.VERSION_NAME`/`VERSION_CODE`/
      `GIT_REVISION`), server version/build (`MetaApi.getMeta()`, fetched once on screen open,
      falls back to "Unavailable" rather than blocking the screen if the server isn't reachable),
      GPL-3.0 license label, a "View source on GitHub" link (`LocalUriHandler`) -
      `android/TODO.md`-derived release notes were scoped out (no changelog-rendering UI exists
      anywhere else in the app either, and this file isn't bundled into the APK)
- [x] Removed the now-dead `placeholder_settings_title`/`placeholder_settings_subtitle` string
      resources (matches the SNA-9 precedent of deleting placeholder strings once a real screen
      replaces them)

Status: **done** (2026-08-18) - a reachable production Stricknani server
(`https://wolle.anika.blue`, rofl-10) became available partway through this session (see SNA-25),
closing the recurring "no test server" gap from every prior UI ticket. Verified for real on the
Zenfone 10 against a dedicated non-admin test account (`ai@anika.blue`, SNA-25): the Account
section shows the real connected server URL and a working sign-out (with confirmation dialog);
the About section correctly fetches and displays live server version/build via `/api/v1/meta`.
**Still pending its own redesign** - see SNA-21 (user feedback: match syncwich's card-based,
multi-screen Settings UX instead of one flat list). No unit tests added for
`SettingsViewModel`/`AppPreferencesRepository` - both are DataStore/Room-`Flow`-bound with the
same "not enough isolable pure logic" reasoning as SNA-6/7/8/10.

## SNA-19: Redesign the app icon, shared consistently between web and Android

- [x] Turns out `stricknani/static/favicon.svg` was *not* actually a placeholder by the time this
      was picked up - it already carries a real brand mark (a blue->purple yarn-ball gradient
      circle, three crossed yarn-strand curves, two knitting needles poking past the edge) that
      matches `stricknani/static/icons/icon-{192,512}.png`. The Android side was the actual
      placeholder (`ic_launcher_foreground.xml`'s own comment said as much: "pending the real icon
      design (SNA-19)", a plain circle + 3 plain arcs, maroon `icon_background` unrelated to the
      web palette). So the real scope here was: stop the two surfaces drifting apart, not invent a
      new mark from scratch.
- [x] Added `branding/icon.svg` at the repo root as the single source of truth (SNA-19's
      "single source-of-truth asset" ask) - identical mark to `stricknani/static/favicon.svg`,
      which now has a comment pointing back at it. If the mark changes, regenerate both derivatives
      from this file.
- [x] Rebuilt the Android adaptive icon from that same mark instead of the placeholder:
      `ic_launcher_foreground.xml` translates the source SVG's 64x64 path data by a flat
      `<group android:translateX="22" android:translateY="22">` into the 108x108 adaptive icon
      canvas (no rescaling needed - the mark's own farthest point, a needle tip at radius ~30.5,
      already sits inside the ~66dp/33 safe-zone radius once recentered), with the yarn-ball
      circle expressed as a two-arc `pathData` circle using an `aapt:attr`-embedded linear
      gradient (`#2563EB` -> `#7C3AED`, matching the web mark's gradient exactly, positioned via
      the translated bounding box). `ic_launcher_monochrome.xml` (also used as `SyncNotifier`'s
      small notification icon) mirrors the same geometry as a single-alpha silhouette, per
      Android's themed-icon convention. `colors.xml`'s `icon_background` changed from the old
      unrelated maroon `#8B3A4A` to `#2563EB` - the exact same hex as
      `manifest.webmanifest`'s `theme_color`, so the launcher background and the web app's browser
      chrome color are now literally the same value. Regenerated the legacy flattened
      `mipmap/ic_launcher.png` (used pre-API26 and as the splash screen's
      `windowSplashScreenAnimatedIcon`) straight from `branding/icon.svg` via
      `rsvg-convert` (`nix run nixpkgs#librsvg` - note its default app *is* `rsvg-convert`, so
      don't repeat the binary name after `--` or it's parsed as a second input file and fails with
      a confusing "Multiple SVG files" error).

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"`
(`BUILD SUCCESSFUL` - confirms the `aapt:attr`/gradient vector syntax is valid), then confirmed for
real on the Zenfone 10: the app drawer now shows the blue-purple yarn-ball mark (searched
"stricknani" to find it since it isn't pinned to the home screen), and the splash screen shows the
same mark centered on a matching solid blue background with no visible seam between the launcher
icon and window background colors. Also deployed to the Mi Pad 4 (`just deploy-all`) but didn't
screenshot-verify there this pass (same onboarding-screen gap noted under SNA-22 - unrelated to
this change, the icon itself doesn't require a server connection to render). Pixel 5 still
unreachable over wireless adb this session.

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

## SNA-21: Redesign the Settings screen to match syncwich's (separate screens + cards)

- [x] Surveyed syncwich's actual `SettingsScreen.kt`/`SettingsCategory.kt` pattern first: a hub
      screen (`SettingsGroupCard`/`SettingsSingleItemCard` of category rows) navigating to a
      dedicated full screen per category (its own `Scaffold`/`TopAppBar`/back arrow), one shared
      `SettingsViewModel` instance passed down from a `SettingsCategoryScreen` dispatcher - not
      guessed
- [x] Replaced SNA-18's single flat `LazyColumn` with exactly that shape: `SettingsCategory`
      enum (Account/Appearance/Navigation/Sync/About - fewer categories than syncwich's, matching
      what this app actually has), a new `Route.SettingsCategoryRoute(categoryName: String)` nav
      route (the category passed as its `.name` string rather than the enum directly, to avoid
      any Navigation Compose enum-as-route-arg edge cases), `SettingsCategoryScreen` dispatching
      to five new dedicated screens (`AccountSettingsScreen`/`AppearanceSettingsScreen`
      /`NavigationSettingsScreen`/`SyncSettingsScreen`/`AboutSettingsScreen`), and shared
      `SettingsGroupCard`/`SettingsSingleItemCard`/`SettingsListItem` building blocks
      (`SettingsComponents.kt`) ported from syncwich's
- [x] `SettingsScreen` (the hub) no longer takes a `SettingsViewModel` itself - it only needs the
      list of categories to render as rows, no live data

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest" ":app:lintDebug"` (`BUILD SUCCESSFUL`, lint clean), then confirmed for
real on the Zenfone 10 and Mi Pad 4 against the live `ai@anika.blue` account: the hub renders all
five category cards correctly, and both Appearance and Navigation sub-screens were opened and
render/behave identically to their SNA-16/SNA-18 counterparts, just inside the new card-based
per-category layout. Pixel 5 not reinstalled this pass - wireless adb was disconnected and didn't
reconnect within the Home Assistant/Tasker webhook's timeout (same recurring gap as SNA-20/SNA-27).

## SNA-22: Render Markdown properly (descriptions, notes, steps)

- [x] Project/yarn description, notes, and step description fields are currently rendered as
      plain `Text` (no Markdown parsing) even though the web app likely stores/expects Markdown
      in these fields. User feedback (2026-08-18): needs real Markdown rendering, **including
      inline images embedded in the Markdown itself** (`ie also the ones in md descriptions,
      steps etc`) - not just the dedicated title/step image galleries that already work
- [x] Needs a Markdown-rendering approach that supports inline images resolved through
      `MediaUrlResolver`/the `@MediaClient` Coil loader (same auth/base-URL handling as every
      other image in the app), not just plain text formatting
- [x] Added `com.mikepenz:multiplatform-markdown-renderer` 0.43.0 (+ `-m3` for Material3 styling,
      `-coil3` for image support) to `gradle/libs.versions.toml`/`app/build.gradle.kts`. Replaced
      the plain `Text(value, ...)` calls for `ProjectDetailScreen`'s description/step
      description/notes and `YarnDetailScreen`'s notes with `com.mikepenz.markdown.m3.Markdown(
      content = value, imageTransformer = Coil3ImageTransformerImpl, ...)`. `Coil3ImageTransformerImpl`
      resolves inline `![]()` images through whatever `SingletonImageLoader` is installed
      app-wide (`StricknaniApp.kt`'s authenticated `@MediaClient` OkHttp client) with zero extra
      wiring - confirmed this is exactly why the `-coil3` artifact (not `-coil` for Coil2) was
      the right pick.

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`), then confirmed for real on the Zenfone 10: created
a project (`stricknani-cli project add --owner-email ai@anika.blue ...`) whose notes field is a
Markdown document with an H1 header, **bold**, _italic_, a link, a bullet list, and an inline
`![]()` image hosted on an external URL. All of it rendered correctly - heading size/weight, bold,
italics, underlined link styling, bullet glyphs, and the inline image loaded and displayed inline
below the list, proving the Coil3 image transformer picks up the app's existing image loader with
no special-case wiring. Also deployed the APK to the Mi Pad 4 (`just deploy-all`), but couldn't
visually re-verify there this pass - the device is showing the onboarding screen (no stored
server URL/PAT), unrelated to this change. Re-onboarding needs a fresh PAT for `ai@anika.blue`;
the `/auth/login` CSRF flow that worked for the Zenfone's initial onboarding returned a 403 this
time (double-submit-cookie CSRF likely needs the token as a header, not just a form field - not
investigated further since it's orthogonal to SNA-22). Pixel 5 still unreachable over wireless adb
this session (same recurring Home Assistant/Tasker reconnect gap as SNA-20/SNA-21/SNA-27 - not
fixable without physical access to the phone).

## SNA-23: Full-screen image viewer (pinch-zoom gallery)

- [x] Ported nyetbox's `ImageViewerDialog.kt` pattern (the more general/reusable of the two
      sibling implementations - syncwich's `RecipeImageViewer` is recipe-specific, not a shared
      component) to `ui/common/ImageViewerDialog.kt`, trimmed to just `imageUrls: List<String>` +
      `initialIndex` (dropped nyetbox's PDF/metadata-link/edit-action fields - not needed here).
      No new Gradle dependency - `HorizontalPager` is core Compose Foundation; zoom/pan/dismiss are
      hand-rolled `pointerInput`/`awaitEachGesture` gesture detection, same as nyetbox's
- [x] Shown as a plain `Dialog`, not a nav route: `HorizontalPager` swipe between images,
      pinch-to-zoom + pan (double-tap toggles 1x/2.5x), vertical drag-to-dismiss, explicit close
      button, chevron prev/next buttons when there's more than one image
- [x] Wired into `ProjectDetailScreen`/`YarnDetailScreen`: added `.clickable` to the existing image
      thumbnails (previously did nothing), `viewerIndex: Int?` state holds which index is open. Uses
      `itemsIndexed` (not `items` + `.indexOf`) for the index and builds the URL list with `map`
      (not `mapNotNull`) so indices stay aligned with `viewerIndex` even if a URL somehow fails to
      resolve

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest" ":app:lintDebug"` (`BUILD SUCCESSFUL` after a one-off corrupted remote
KSP incremental cache needed `just clean` first - unrelated to this change, see `android/AGENTS.md`
if it recurs), then confirmed for real on the Zenfone 10 and Mi Pad 4: created a real test project
via the API with an uploaded title image (`ai@anika.blue` account), tapped its thumbnail on-device,
the full-screen viewer opened correctly (image fit to width, close button, no crash), and
double-tap-to-zoom didn't crash (the test image is a flat color, so the zoom itself isn't visually
distinguishable, but the gesture path is exercised).

## SNA-25: Production backend deploy + dedicated AI-testing account

- [x] Production (`rofl-10`, `https://wolle.anika.blue`) was 71 commits behind `main` - missing
      the entire versioned JSON API (SNA-1-4) plus ~60 commits of security hardening (rate
      limiting, password policy, CSRF/CSP tightening, revocable sessions, PWA offline mode,
      pagination). Updated the `stricknani` flake input pin in `nixos-config` and deployed via
      `just deploy rofl-10` - this is what unblocked every "not verified: no reachable test
      server" caveat above. One deploy hiccup: the first `nixos-rebuild switch` restarted
      `stricknani.service` per its log, but the running process was still on the pre-switch Nix
      store path (stale binary) until a manual `systemctl restart stricknani.service` - root cause
      not fully understood, worth watching on the next deploy
- [x] Added a dedicated non-admin `ai@anika.blue` account (`nixos-config`'s
      `services/stricknani.nix`: a new `stricknani-ensure-ai-user` oneshot systemd service running
      `stricknani-cli user create`, idempotent, gated on a new sops secret) so AI-assisted Android
      testing never touches the real users' (`philipp@schmitt.co`/`anika.bergmann@mailbox.org`)
      project/yarn data - user-requested (2026-08-18) explicitly for this reason
- [x] Generated a Personal Access Token for `ai@anika.blue` (via the web UI's login + CSRF flow,
      scripted with `curl`) and used it to onboard the Zenfone 10 for real - confirmed Home/
      Settings/Navigation all render correctly against live data with zero crashes

Status: **done** (2026-08-18) - this is infra/testing-account setup, not an app feature; see
SNA-16/SNA-18 above for what it unblocked.

## SNA-26: Rounded search bar styling (match syncwich)

- [x] Found syncwich's actual pattern: a shared `ui/common/SearchField.kt` composable - a `TextField`
      (not `OutlinedTextField`) with `shape = RoundedCornerShape(28.dp)` (fully pill-shaped),
      filled `surfaceContainerHighest` background, transparent focus/unfocus indicators (no
      underline), and a clear button shown once there's text - explicitly said to mirror nyetbox's
      `ModernSearchField` too, so this is the fleet-wide convention, not syncwich-specific
- [x] Ported it verbatim to `ui/common/SearchField.kt` and swapped it into the three search entry
      points that used the default `OutlinedTextField` shape before:
      `ProjectsListScreen`/`YarnsListScreen`/`SearchScreen`. The `AddCategoryDialog`'s text field
      (`ProjectsListScreen`) is untouched - it's a dialog input, not a search bar

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest" ":app:lintDebug"` (`BUILD SUCCESSFUL`, lint clean), then `just
deploy-zenfone debug` + `just mipad-install` and confirmed for real on both against the live
`ai@anika.blue` account: the Projects search field renders as a fully rounded pill, no crash on
either device.

## SNA-24: Verify/harden offline image caching

- [x] User feedback (2026-08-18): "make sure we cache the img assets properly" - the backend
      already sends `Cache-Control: public, max-age=31536000, immutable` on `/media/*` responses
      (`stricknani/routes/media.py`), and SNA-7 wired a dedicated 256 MiB Coil3 disk cache
      (`@MediaClient`), so this may already work - **not yet verified on a real device with real
      image data** (no project/yarn with a photo existed in the dedicated `ai@anika.blue` test
      account as of this note). Needs: add a real image to a test project/yarn, view it once
      online, then confirm it still renders with the device in airplane mode

Status: **done** (2026-08-18) - created a real test project via `POST /api/v1/projects` and
uploaded a real title image via `POST /api/v1/projects/{id}/images/title` (both against the live
`ai@anika.blue` account), synced it to the Zenfone 10, then disabled WiFi (`adb shell svc wifi
disable` - a real network-down state, not just a UI toggle) and relaunched the app: **both the
list thumbnail and the full-size detail-screen image rendered correctly with zero network**,
alongside the expected "Couldn't sync - showing cached data." banner (SNA-7's failed-sync-keeps-
cached-data behavior, also confirmed working for real here). No code changes were needed - the
existing SNA-7 Coil3 disk cache + backend `Cache-Control` headers already worked correctly; this
ticket was pure verification.

## SNA-27: Fix dead space above screen headers

- [x] User feedback (2026-08-18): "there is a lot of dead space above the header... always refer
      to the other apps, esp syncwich and nyetbox". Two distinct real bugs found (not one):
      1. `StricknaniNavHost`'s outer `Scaffold` didn't zero its `contentWindowInsets`, so every
         destination's own `Scaffold`/`TopAppBar` was (architecturally) at risk of double-applying
         the status-bar inset on top of the outer one - fixed by setting
         `contentWindowInsets = WindowInsets(0, 0, 0, 0)`, matching syncwich's
         `SyncwichNavHost` exactly (including its explanatory comment)
      2. The actual visible bug, on `OnboardingScreen`: its root `Column` used
         `verticalArrangement = Arrangement.Center`, vertically centering the whole form and
         leaving a large empty gap above the title - syncwich's equivalent screen anchors to the
         top (`Alignment.TopCenter` via its `CenteredContent` wrapper, `Arrangement.spacedBy`
         inside). Fixed by removing the `Center` arrangement (defaults to `Top`)
- [x] Confirmed both fixes visually on the Zenfone 10, before/after screenshots, against the live
      `ai@anika.blue` account: Onboarding's title now starts immediately below the status bar
      (previously ~28% down the screen); Home's title sits directly under the status bar with no
      gap beyond the `TopAppBar`'s normal height

Status: **done** (2026-08-18) - a debugging detour is worth recording here: two rebuild+redeploy
cycles in a row showed **zero visual change** after real, correct source edits, because a bare
`just build` (used directly instead of `just build-fetch`/`just deploy-zenfone`) never copies the
built APK back to local `./dist/` - so `just zenfone-install "./dist/..."` kept reinstalling a
stale APK with `adb install -r` reporting "Success" regardless. Resolved with a canary text
marker to unambiguously prove code changes were/weren't reaching the device, then traced to the
missing `fetch` step. Documented in `android/AGENTS.md`'s Builds section so it isn't repeated.
Verified for real (not just build-success) on Zenfone 10 and Mi Pad 4, `ResumedActivity`/no crash
on both; Pixel 5's wireless adb was disconnected at deploy time (unrelated, recurring issue - see
SNA-20's notes on the same gap) so it's still running the pre-fix build until its next reconnect.

## SNA-28: Small UI feedback batch (Settings header, yarn icon, Gauge in navbar, Home sync card, "Used in" thumbnails)

- [x] Settings hub screen (`SettingsScreen.kt`) had no `TopAppBar`/page title at all - a real
      regression from the SNA-21 redesign (the old flat-list screen it replaced didn't need one
      since it also had no header, but every *other* top-level destination has some visual anchor:
      Home's `TopAppBar`, Projects'/Yarns' search field). syncwich's own Settings hub has one too.
      Fixed by wrapping it in a `Scaffold`/`TopAppBar(title = { Text("Settings") })`, matching
      Home's pattern exactly (`Modifier.padding(innerPadding)` on the content, no manual inset math).
- [x] Yarn-related icons across the app (`TopLevelDestination.YARNS`, `YarnsListScreen`'s empty
      state/FAB, `SearchScreen`'s yarn-result fallback, `HomeScreen`'s yarn `HomeCard` fallback)
      used `Icons.Filled.Palette` - a paint-palette icon, which reads as "color/theme", not yarn
      (and collides thematically with `AppearanceSettingsScreen`'s legitimate palette-for-theme
      icon). Swapped to `Icons.Filled.Checkroom` (a garment on a hanger - what you actually knit)
      everywhere yarn-specific, chosen after checking the actual `material-icons-extended` jar's
      contents on the remote build host for a real semantic match rather than guessing a name that
      might not exist in the version pinned here.
- [x] Gauge calculator (SNA-11) was only reachable via a `HomeScreen` top-bar icon button - added
      `TopLevelDestination.GAUGE` so it's also a navbar-customizable destination (Settings ->
      Navigation). No other wiring needed: `Route.Gauge`'s single `composable<Route.Gauge>` entry
      in `StricknaniNavHost` already works identically regardless of whether it's reached via the
      Home button or a bottom-nav tap, and `NavbarCustomization.sanitize`'s existing "missing
      destination -> appended as visible" rule (for a destination added in a later release)
      applies here unchanged, so anyone with an already-saved navbar preference gets it appended
      automatically rather than needing a special case.
- [x] `HomeSyncStatusCard` (`ui/home/SyncStatusCard.kt`): a persistent Home-screen card reporting
      sync freshness - "like in syncwich and nyetbox" (user request, 2026-08-18). Simpler than
      syncwich's full `SyncStatus` state machine (no live-syncing/stale-threshold tracking, which
      needs deeper WorkManager state observation not scoped into this pass) but covers what this
      app's data actually exposes today: refreshing (`HomeViewModel.isRefreshing`, already existed
      for pull-to-refresh), queued-but-unsynced local edits (`PendingMutationDao.observeCount()`,
      SNA-8's write queue), a replay failure (`PendingMutationDao.observeFailed()`), or a plain
      last-synced timestamp (`SyncStateDao.observeAll()`, the same query `SettingsViewModel`'s Sync
      screen already used). Shown above both the empty and populated Home states.
- [x] `YarnDetailScreen`'s "Used in" linked-projects list was plain `Text(project.name)` rows -
      "makes it more distinctive" (user request, 2026-08-18). Added a 48dp preview-image thumbnail
      (or a folder-icon fallback) per row, matching `HomeCard`'s existing
      image-or-fallback-icon pattern exactly. Needed threading `previewUrl` through
      `YarnDetailViewModel.LinkedProject` (`ProjectEntity.previewUrl` was already there, just
      wasn't in the mapped-down DTO) and `resolveMediaUrl` (already available in
      `YarnDetailContent`, just not previously used for this list).

Status: **done** (2026-08-18) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest" ":app:lintDebug"` (`BUILD SUCCESSFUL`, lint clean, existing
`NavbarCustomizationTest` still passes unchanged with 6 destinations since it iterates
`TopLevelDestination.entries` rather than hardcoding a count), then confirmed for real on the
Zenfone 10: Settings now shows a "Settings" title; the bottom nav shows a clothes-hanger icon for
Yarns and a new "Gauge" tab; Home shows a "Synced / Last synced just now" card; and - after linking
a real test yarn to the "SNA-22 Markdown Test" project via the API (`yarn_ids` field on
`PUT /api/v1/projects/{id}`, since no CLI command exists for this) - the yarn's "Used in" row shows
a thumbnail (a folder-icon fallback here, since that particular test project has no title image)
next to the project name instead of bare text. Test yarn deleted afterward; one harmless leftover
PAT ("temp-test-token") remains on the isolated `ai@anika.blue` test account. Mi Pad 4/Pixel 5 not
redeployed this pass (Mi Pad needs re-onboarding per SNA-22's note; Pixel 5 was reachable earlier
this session but not re-checked for this specific batch).

## SNA-29: Fix `ProjectDetailScreen` LazyColumn duplicate-key crash

- [x] Found via live crash-testing on the Pixel 5 (2026-08-19): scrolling a project's detail
      screen crashed with `java.lang.IllegalArgumentException: Key "3" was already used` (a
      `LazyColumn` requires globally-unique keys across every `items()`/`itemsIndexed()` call
      inside it, not just within each call). `ProjectDetailContent`'s single outer `LazyColumn` had
      both the linked-yarns row (`items(linkedYarns, key = { it.id })`) and the steps list
      (`items(detail.steps, key = { it.id })`) keyed by their raw, unprefixed database id - since
      yarns and steps are independent auto-increment tables, a yarn and a step can easily land on
      the same id (confirmed by reproducing it: creating yarns 8/9/10 and a project whose 10 steps
      landed on ids 8-17 crashes instantly pre-fix). Fixed by prefixing each section's key
      (`"yarn-${it.id}"` / `"step-${it.id}"`) so they can never collide. Audited every other
      `LazyColumn`/`LazyRow` in the app (`YarnDetailScreen`, `YarnsListScreen`,
      `ProjectsListScreen`) - none of the others mix two differently-sourced id spaces inside one
      list scope, so this was the only spot with the bug.

Status: **done** (2026-08-19) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`), then reproduced the exact pre-fix crash scenario
against production (a real project with yarn ids 8/9/10 and step ids 8-17) and confirmed on the
Zenfone 10 with the fixed build: the project detail screen renders both sections correctly and
survives repeated aggressive fling-scrolls in both directions with no crash and no exception in
logcat. Deployed to Zenfone 10, Mi Pad 4, and Pixel 5.

## SNA-31: Move per-item actions into an overflow menu

- [x] `ProjectDetailScreen`'s and `YarnDetailScreen`'s `TopAppBar` each had three always-visible
      `IconButton`s (Share, Edit, Favorite). Replaced with a single `MoreVert` icon that opens a
      `DropdownMenu` with `DropdownMenuItem`s for Favorite/Unfavorite, Edit, and Share (in that
      order - favorite toggle first since it's the single most common action). Researched first
      whether syncwich keeps Favorite pinned outside the overflow menu for exactly this reason, but
      the user explicitly asked for "edit, favorite etc" to move into the menu, so all three moved
      together rather than special-casing favorite. Noted but out of scope: neither screen (nor
      their view models) has a delete action anywhere yet - a real gap, but a separate concern from
      "move existing actions into a menu".

Status: **done** (2026-08-19) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`) and confirmed for real on the Zenfone 10: the
project detail `TopAppBar` now shows just a back arrow and a 3-dot menu; tapping it opens
Favorite/Edit/Share as expected. Deployed to Zenfone 10 and Mi Pad 4 (Pixel 5 skipped this pass -
the user was actively using it for an interactive Termux/Claude session at the time, confirmed via
`dumpsys activity activities`, so it was left untouched rather than force-stopping their
foreground app).

## SNA-32: "New X" FAB collapses to icon-only while scrolled

- [x] Ported syncwich's exact one-line pattern rather than a custom `derivedStateOf`/nested-scroll
      implementation: `ExtendedFloatingActionButton`'s built-in `expanded: Boolean` param wired
      directly to `!listState.canScrollBackward`, where `listState` is the same
      `rememberLazyListState()` passed to the screen's `LazyColumn`. `canScrollBackward` is already
      a reactive `State<Boolean>` Compose reads on every scroll frame, so this needed no extra
      state plumbing - just hoisting a `LazyListState` above the `Scaffold` in both
      `YarnsListScreen` and `ProjectsListScreen` (it didn't exist there before; the `LazyColumn` was
      using an implicit unremembered state).

Status: **done** (2026-08-19) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`). The `ai@anika.blue` test account only had a handful
of yarns, not enough to scroll, so created 17 more via the API to get real scrollable content;
confirmed live on the Zenfone 10 that the FAB shows "New yarn" (icon+label) at the top of the list
and collapses to icon-only once scrolled down, then re-expands back to icon+label on returning to
the top - both directions work. Test yarns left in place on the isolated test account (harmless,
same account SNA-28 already accumulates test data on).

## SNA-35: In-app crash handler with a copyable stack trace

- [x] Ported nyetbox's mechanism near-verbatim (no `CrashActivity`, no manifest changes, no Intent
      extras): `crash/CrashReport.kt` installs a `Thread.UncaughtExceptionHandler` in
      `StricknaniApp.onCreate()` (first line) that formats the throwable (build info, device info,
      thread name, stack trace) and persists it via a **synchronous** `SharedPreferences` commit
      (`edit(commit = true)`, since the process may die before an async `apply()` write lands) -
      then delegates to whatever handler was previously registered so normal fatal-crash behavior
      (system dialog, process death) is unaffected. `MainActivity.onCreate()` reads and clears the
      pending report on the *next* launch and shows `ui/common/CrashReportDialog.kt` (an
      `AlertDialog` with a selectable monospace stack trace, "Copy report", and "Restart app" -
      relaunches via `packageManager.getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK or
      FLAG_ACTIVITY_CLEAR_TASK`, then `finishAffinity()`). Redaction regex adapted to Stricknani's
      own token format (`sna_...` PATs) rather than nyetbox's NetBox token pattern.
      `CrashReportTest.kt` covers both the formatter's redaction and the handler's
      save-then-delegate behavior, adapted from nyetbox's own test.

Status: **done** (2026-08-19) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`, new unit tests pass), then confirmed for real on the
Zenfone 10 by forcing an actual crash (`adb shell am crash blue.anika.wolle.debug "..."`) and
relaunching: the dialog appeared with the full report (build/device/thread/stack trace, tokens
redacted), Copy worked, and Restart app cleanly relaunched straight to Home with no residual state.

## SNA-34: Developer mode easter egg + libraries screen

- [x] Ported syncwich's `DeveloperModeTapState`/`DeveloperModeTapAction` near-verbatim into
      `SettingsViewModel` (Stricknani uses one shared settings view model rather than
      per-category ones like syncwich, so the tap state lives there instead of a dedicated
      `AboutSettingsViewModel`): 7 taps on the About screen's "Build" row within a rolling 2s
      window sets a new `AppPreferencesRepository.developerMode` DataStore flag, with toast
      feedback at each step ("N more taps...", "Developer mode enabled", or "You are already a
      developer" if already unlocked). Currently a pure easter egg with no gated feature behind it
      yet, matching upstream syncwich's own current state.
- [x] `ui/settings/LibrariesScreen.kt`: hand-maintained static list (no `aboutlibraries` Gradle
      plugin, matching syncwich's choice) of the app's actual runtime dependencies from
      `gradle/libs.versions.toml`, each row opening its GitHub URL. New `Route.Libraries`
      destination reachable from a "Libraries" row added to the About screen's second card
      (above "License").

Status: **done** (2026-08-19) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`), then confirmed for real on the Zenfone 10: tapping
"Build" repeatedly showed the countdown toasts, then "Developer mode enabled", then (on a further
tap) "You are already a developer" - confirming the DataStore flag persisted; tapping "Libraries"
opened the new screen with all dependencies listed correctly. One real quirk hit during testing:
rapid-fire `adb shell input tap` calls under ~150ms apart didn't all register as discrete clicks
(only 2 of 7 landed) - not an app bug, just `adb input tap`'s own dispatch timing; spacing taps
~400ms apart worked reliably.

## SNA-30: Redesign Project detail view with cards

- [x] `ProjectDetailScreen`'s content was a flat `LazyColumn` of bare rows separated by
      `HorizontalDivider`s - replaced with a new `DetailSectionCard` (a rounded, elevated
      `surfaceContainer` card matching Settings' `SettingsGroupCard` visual language) grouping each
      section (Details facts + tags, Linked yarns, Description, Steps, Notes) into its own card,
      with dividers only *within* a card between its own entries. Linked-yarn rows now use a new
      shared `LinkedEntityRow` (thumbnail-or-`Checkroom`-icon leading avatar + name, clickable) -
      the exact same pattern `YarnDetailScreen`'s "Used in" list already used for linked projects,
      just extracted so both directions show a real photo where one exists. Needed adding
      `previewUrl` to `ProjectDetailViewModel.LinkedYarn` (already present on `YarnEntity`, just
      wasn't threaded through).

Status: **done** (2026-08-19) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`), then confirmed for real on the Zenfone 10 against
the "Crash Repro Project" test project (3 linked yarns + 10 steps): each section now renders as its
own rounded card, linked yarns show the clothes-hanger fallback icon (none of the test yarns have a
photo), and tapping through to a linked yarn still navigates correctly and its own "Used in" card
(unchanged) still finds its way back.

## SNA-33: Harden offline sync - conflict handling for deletes/edits from both sides

- [x] Audited the existing sync stack first (SNA-8's write queue, `ProjectRepository`/
      `YarnRepository`, `WriteReplayWorker`) and found it was accidental last-write-wins with one
      real bug: a queued local edit whose target was deleted server-side in the meantime got
      retried forever against a 404, with no way to clear it. No version/timestamp check existed
      anywhere, backend or client.
- [x] **Backend**: `ProjectWriteRequest`/`YarnWriteRequest` gained an optional
      `expected_updated_at`. `update_project`/`update_yarn` reject with 409 (the entity's current
      server state in the body) when it's set and no longer matches - optimistic concurrency,
      opt-in so callers that omit it (the web UI, which always edits the live row it's looking at)
      keep the old unconditional-write behavior. Comparison is tz-tolerant (`_differs`/`_as_utc`)
      since SQLite hands back naive datetimes for what are logically UTC-stored values. 5 new
      backend tests cover the conflict, the non-conflicting case, and the yarn equivalent.
- [x] **Android**: `updateProject`/`updateYarn` stamp the queued mutation's payload with the
      `updatedAt` last seen locally. `WriteReplayWorker` now catches the update replay's
      `HttpException` specifically: **409** decodes the server's current state from the error
      body and adopts it into Room (`adoptRemoteProject`/`adoptRemoteYarn`) - the server's edit
      wins, the stale local one is discarded; **404** means the target was deleted elsewhere -
      drops the local row if it's somehow still cached and doesn't try to reapply the edit. Both
      cases call the new `PendingMutationDao.markConflict` (a new `isConflict` column, Room bumped
      to v3) instead of retrying forever - `getAll()` now excludes conflict-resolved rows from
      future replay passes, while `lastErrorMessage` stays populated so the existing "Sync issue"
      Home card still surfaces what happened. A plain 404 on a **delete** replay is treated as
      success (idempotent - the row's already gone, which was the goal). `WriteReplayWorkerTest.kt`
      covers the JSON-envelope-unwrapping logic in isolation (extracted as a top-level
      `parseConflictDetail` function specifically so it doesn't need a mocked `HttpException`).

Status: **done** (2026-08-19) - verified via `nix develop -c uv run pytest -q` (269 passed) and
`just gradle rofl-13.brkn.lol ":app:assembleDebug" ":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`),
then confirmed for real end-to-end on the Zenfone 10 against production: created a test project,
synced it to the device, disabled the device's Wi-Fi, edited the project locally (queuing a
conflicting mutation), edited the *same* project via `curl` directly against the API while the
device was still offline, then re-enabled Wi-Fi. `WriteReplayWorker` logged `Resolved conflict for
mutation 2 (project/update): This project was edited elsewhere...` within seconds of reconnecting;
the device's copy switched to the server's edit ("Server Wins This Time"), and Home showed "Sync
issue - 1 change couldn't reach the server yet" rather than silently overwriting the newer edit or
retrying forever. Also hit and fixed a real deploy-tooling gap along the way: `just deploy rofl-10`
had rebuilt the correct `stricknani` derivation (confirmed via a direct `nix build
github:pschmitt/stricknani/<rev>#stricknani` on the host) but `switch-to-configuration` didn't
restart the running `stricknani.service` process despite its unit file changing - had to
`systemctl restart` by hand to pick it up. Worth a closer look in `nixos-config.git` at some point,
but out of scope for this ticket.

## SNA-36: Performance pass - scrolling smoothness and sync throughput

- [x] Ran a research agent over the sync stack and every list/detail screen first rather than
      guessing - found several concrete, verifiable issues rather than applying generic advice:
- [x] **Detail-screen recompose storm** (highest impact): `ProjectDetailViewModel`/
      `YarnDetailViewModel` each `combine()`d their own entity with the *other* repository's full
      `observeAll()` (for the linked-yarns/linked-projects list), which meant any unrelated yarn
      edit anywhere in the app re-ran `decodeDetail` (a full JSON parse of steps/images/
      attachments) for whatever project detail screen happened to be open - and vice versa. Split
      the JSON decode into its own upstream step gated by `distinctUntilChanged()` on the entity
      itself, so only this row's own actual change triggers a re-decode; the `combine()` downstream
      still re-runs on an unrelated yarn/project change, but now only redoes the cheap filter/map,
      not the JSON parse.
- [x] **Unthrottled full-list search**: `SearchViewModel`/`ProjectsListViewModel`/
      `YarnsListViewModel` each re-filtered their entire cached list on every keystroke with no
      debounce. Added a separate `debounce(250ms).distinctUntilChanged()` copy of the query flow
      feeding the filter, while the text field itself still updates instantly (no typing lag) -
      `@OptIn(FlowPreview::class)` since `debounce` is still a preview API in this Kotlin
      coroutines version.
- [x] **Category sync always rewrites the table**: `CategoryRepository.sync()` called
      `replaceAll()` (delete + reinsert) unconditionally every sync pass, which invalidates Room's
      whole-table `observeAll()` Flow (Room's invalidation tracking is per-table, not per-row) even
      when nothing changed - forcing `ProjectsListScreen`'s category filter chips to recompose on
      every background sync tick. Added `CategoryDao.getAllOnce()` and skip the write when the
      fetched set already matches the cache.
- [x] **Small wins**: memoized `ProjectDetailScreen`'s per-recomposition `steps.sortedBy{}` with
      `remember(detail.steps)`; both detail screens' image/photo carousels now reuse the
      already-`remember`ed `imageUrls`/`photoUrls` list instead of calling `resolveMediaUrl` a
      second time per item.
- [ ] **Deliberately not done this pass** (noted by the audit, real but larger/riskier): delta-sync
      has no server-side pagination at all - a first sync or a long-offline device pulls every row
      (each with its full detail JSON) in one unbounded response. Needs a backend API change
      (limit/cursor), not just an app-side fix - worth its own ticket rather than folding into a
      "quick performance pass".

Status: **done** (2026-08-19) - verified via `just gradle rofl-13.brkn.lol ":app:assembleDebug"
":app:testDebugUnitTest"` (`BUILD SUCCESSFUL`, no warnings), then confirmed for real on the
Zenfone 10: search still filters correctly (typed "Crash", got the 3 matching test yarns after the
debounce settles), and a project detail screen with linked yarns + 10 steps still renders correctly
with steps in the right order and thumbnails intact - the memoization/gating changes are purely
about *when* work re-runs, not *what* gets computed, so no behavior changed, only frequency.

## SNA-37: German (DE) translations + in-app language picker

- [x] **Full string extraction** (user chose "extract everything" over a partial/infra-only
      scope): every hardcoded English UI literal across all ~29 Composable files, 2 enums
      (`TopLevelDestination`/`SettingsCategory`, whose `label`/`title`/`subtitle` fields became
      `@StringRes` ints + `@Composable` extension functions since enum constructors can't call
      `stringResource`), and ~9 ViewModels' error/toast messages (needed `@ApplicationContext
      Context` injected into each, since `getString()` isn't available outside Compose) - 224
      `<string>` resources total, plus 2 `<plurals>` (sync status's "N change(s)" count).
      Parallelized across 4 forks by disjoint file list, with `strings.xml` itself reserved for
      sequential editing to avoid a shared-file race - see below for what went wrong.
- [x] **Fork coordination failure, caught by verification, not by trust**: 2 of the 4 dispatched
      forks (each a `fork`-type subagent, which inherits the *entire* parent conversation including
      the "I just dispatched 4 forks" tool calls) misread their own inherited context and believed
      *they* were the coordinator - one tried to re-dispatch the other 3 forks (correctly rejected:
      nested forking isn't allowed), the other burned its whole turn polling `ListAgents` in a
      loop. Neither touched a single one of its assigned files. Caught by diffing actual file
      content/`git status` against each fork's *claimed* file list rather than trusting the
      self-reported summary - both groups' 12 files (`HomeScreen.kt`, `OnboardingScreen.kt`,
      `QrScannerDialog.kt`, `ProjectDetailScreen.kt`, `ProjectEditorScreen.kt`,
      `ProjectsListScreen.kt`, `NavigationSettingsScreen.kt`, `SettingsScreen.kt`,
      `SyncSettingsScreen.kt`, `YarnDetailScreen.kt`, `YarnEditorScreen.kt`, `YarnsListScreen.kt`)
      were done directly instead of re-dispatching more forks. The other 2 forks (settings screens
      + search, `MainActivity.kt`) completed correctly.
- [x] A `general-purpose` research agent (not a fork, so no shared-context confusion) then swept
      every remaining file - `ui/gauge/GaugeCalculatorScreen.kt` (the whole screen, never in the
      original 4-way split), `ui/home/SyncStatusCard.kt` (needed Android `<plurals>` for the
      "N change(s)" count text, not the manual `if (n==1) "" else "s"` string-concat trick),
      `CrashReportDialog.kt`, `ImageViewerDialog.kt`, `SearchField.kt`, both enums, and the
      ViewModel-layer error strings - found and reported with exact code snippets rather than
      silently missed.
- [x] `android/app/src/main/res/values-de/strings.xml` - hand-translated (not machine-translated)
      German for all 224 strings; `English`/`Deutsch` labels themselves are deliberately left
      untranslated (native self-names, standard language-picker convention). Verified
      `values/strings.xml` and `values-de/strings.xml` declare the exact same `name=` set both
      directions (`comm -3` on sorted name lists) and that every `R.string.*`/`R.plurals.*`
      reference in the Kotlin source has a matching definition (and vice versa) before considering
      extraction complete.
- [x] In-app language picker: `AppearanceSettingsScreen`'s new "Language" `SettingsGroupCard`
      (Follow system / English / Deutsch, mirroring the existing Theme card's `RadioButton` list),
      backed by a new `AppLanguage` enum (`data/settings/AppLanguage.kt`) and
      `SettingsViewModel.appLanguage`/`setAppLanguage()`.
- [x] **`AppCompatDelegate.setApplicationLocales()` silently no-ops on a plain `ComponentActivity`**
      (this app's `MainActivity` isn't `AppCompatActivity`) - confirmed empirically: tapping
      "Deutsch" updated the picker's own radio-button state (so the click handler and ViewModel
      state were fine) but `adb shell cmd locale get-app-locales blue.anika.wolle.debug` stayed
      `[]` and the UI stayed in English even after an explicit `activity.recreate()`. Running the
      exact same `cmd locale set-app-locales ... --locales de` from the shell worked immediately
      and rendered the German strings correctly, isolating the bug to the in-app
      `AppCompatDelegate` call specifically, not the resources/locale-matching. Fixed by calling
      the platform `LocaleManager.setApplicationLocales()` directly on API 33+ (what the shell
      command does under the hood) and keeping `AppCompatDelegate` only as the API < 33 fallback
      (persisted via the manifest's `AppLocalesMetadataHolderService` auto-store opt-in, added
      alongside `androidx.appcompat` as a new dependency - `androidx-appcompat = "1.7.1"`).
- [x] `AppearanceSettingsScreen`'s language rows call `activity?.recreate()` (via
      `LocalActivity.current`, not a raw `LocalContext.current as Activity` cast - the latter is an
      Android Lint error, `ContextCastToActivity`) after `setAppLanguage()` so already-composed
      strings actually refresh instead of only affecting the next cold start.

Status: **done** (2026-08-19) - `just check` (ktfmt + unit tests + Android Lint) green after the
`LocalActivity`/`LocaleManager` fixes. Verified for real on the Zenfone 10: fresh install renders
in English (device locale) by default with correct singular text ("1 change couldn't reach the
server yet."); Settings → Appearance → Language → Deutsch flips every screen to German instantly
(`Erscheinungsbild`, `Design`, `System folgen`, `Sprache`, and the bottom nav labels
`Start`/`Projekte`/`Wolle`/`Suche`/`Maschenprobe`/`Einstellungen` all confirmed via screenshot);
`adb shell cmd locale get-app-locales` correctly reports `[de]` while selected and `[]` (no
override) after switching back to "Follow system" - reset to "Follow system" before finishing so
the test device is left in its default state.

## SNA-39: Replace the yarn icon with `mdi:sheep`

- [x] Replace the Android yarn icon wherever it is used in navigation, lists, details, and
      supporting UI with the Material Design Icons sheep icon. Added a local Compose `ImageVector`
      from the MDI 7.4.47 `mdi:sheep` path and replaced all five yarn-specific `Checkroom` call
      sites: navigation, home favorites, search results, linked project yarns, and the yarn list.
- [x] Preserve existing accessibility labels and `Icon` tint behavior so the sheep icon follows the
      current Material theme in light and dark modes.

Status: **done** (2026-08-19; remotely verified with `just check` on `rofl-13.brkn.lol`: ktfmt,
unit tests, and `lintDebug` all passed).
## SNA-38: Pull-to-refresh gestures with user feedback

- [x] Home pull-to-refresh refreshes categories, projects, and yarns.
- [x] Projects and Yarns list pull-to-refresh refreshes the complete relevant collection (including
      category metadata for Projects).
- [x] Project and Yarn detail pull-to-refresh fetches the current item and only its currently
      linked yarns/projects.
- [x] Shared foreground feedback reports in-progress, changed, no-change, offline, and error
      outcomes in English and German; cached Room content remains visible on failures.
- [x] An atomic refresh guard prevents duplicate concurrent refreshes; unit tests cover duplicate
      calls and changed/no-change/offline/error outcomes.

Status: **done** (2026-08-19) - verified with remote `just check` on `rofl-13.brkn.lol`:
`ktfmtCheck`, `:app:testDebugUnitTest`, and `lintDebug` all passed after remote formatting.

## SNA-40: Add snackbar feedback for mutation actions

- [x] Show translated success feedback for actions such as favorite/unfavorite, create, edit, and
      delete across project and yarn screens.
- [x] Show clear queued/offline and error feedback when a mutation is stored locally or cannot be
      applied, while preserving the existing pending-change indicator.
- [x] Use a shared snackbar/event path so feedback is consistent across screens and does not rely
      on transient ViewModel state surviving navigation.
- [x] Add focused UI or ViewModel tests covering success, queued, and failure outcomes for the
      mutation actions.
- [x] Run `just check` remotely on `rofl-13.brkn.lol` and verify the messages in English and German.

Status: **done** (2026-08-19; codex/android-sna40-sna41) - verified with remote `just check` and
`just e2e-build` on `rofl-13.brkn.lol`; focused tests cover the shared event bus, queued/offline/error
outcomes, confirmation callbacks, and English/German resources.

## SNA-41: Confirm destructive deletes and style trash actions

- [x] Require an explicit confirmation dialog before deleting projects, yarns, and every other
      deletable entity exposed by the Android UI.
- [x] Make the confirmation identify the item being deleted and offer a safe cancel action before
      any offline mutation is queued or sent to the server.
- [x] Render the trash/delete icon in each overflow menu with the theme's red destructive color,
      while preserving its accessible label and touch target.
- [x] Add focused tests covering cancel, confirm, and red destructive-icon semantics for project
      and yarn deletion flows.
- [x] Run `just check` remotely on `rofl-13.brkn.lol` and verify the behavior in English and German.

Status: **done** (2026-08-19; codex/android-sna40-sna41) - verified with remote `just check` and
`just e2e-build` on `rofl-13.brkn.lol`; focused journeys cover both project and yarn overflow menus,
cancel behavior, and item-preserving confirmation flows.

## SNA-42: Publish Android assets and releases to Google Play

- [x] Added Fastlane-compatible en-US store copy and a draft declaration file covering the privacy
      policy URL, content-rating completion, and data-safety completion. The checked-in Play icon
      (512×512) and feature graphic (1024×500) are validated without a network call.
- [x] Extended the disposable phone/tablet screenshot matrix with an optional review-PR path;
      successful light-theme captures are consolidated into the three Play screenshot buckets and
      dark-theme captures remain available as review diagnostics.
- [x] Added manual `.github/workflows/play-store.yaml` and `play-store-assets.yaml` workflows. A
      default dispatch only validates/builds; publication requires the explicit input,
      `PLAY_PUBLISH_ENABLED=true`, and the relevant service-account credential.
- [x] Reused the existing CI signing keystore/version properties and added a dry-run-safe asset
      uploader plus structural metadata/PNG/AAB/authentication checks without writing any secret to
      the repository.
- [x] Added `android/docs/play-store-release.md` with the staged internal-testing and rollback
      runbook.
- [ ] Verify the listing and release flow in the Play Console before enabling production release.

Status: in progress (2026-08-19; repository scaffolding and the deployed public privacy-policy
route are verified, but external Play Console listing, form completion, and service-account setup
remain unverified).

## SNA-43: Normalize navbar taps to each destination's root view

- [x] Make tapping a top-level navbar item navigate to that destination's root route, including
      returning from nested settings categories or other child/detail screens.
- [x] Keep the action a no-op when the current route is already that destination's root view, with
      no duplicate back-stack entries or unnecessary state loss.
- [x] Apply the same behavior consistently to every visible navbar destination, not only Settings,
      while preserving saved state and deep-link behavior.
- [x] Add focused navigation tests for root, nested, and repeated navbar taps across all destinations.
- [x] Run `just check` remotely on `rofl-13.brkn.lol` and verify the behavior in English and German.

Status: **done** (2026-08-19; remotely verified with `just check rofl-13.brkn.lol`).

## SNA-44: Render Markdown and embedded images in notes and stitch samples

- [x] Investigate why Markdown content and embedded images in project/yarn notes and stitch samples
      do not render like descriptions and steps.
- [x] Render the supported Markdown syntax consistently, resolving authorized relative media URLs
      through the configured server and preserving offline cached images where available.
- [x] Handle missing/invalid images and plain text safely without blank sections or crashes.
- [x] Add focused rendering/URL-resolution tests for notes and stitch samples, including embedded
      images, offline cache behavior, and English/German UI labels.
- [x] Run `just check` remotely on `rofl-13.brkn.lol` and verify the result on a real Android screen.

Status: **done** (2026-08-19; remotely verified with `ktfmtCheck`, 45 unit tests, and `lintDebug` on
`rofl-13.brkn.lol`).

## SNA-45: Scale down Markdown heading styles

- [x] Render Markdown headings at a compact scale that fits the Android card hierarchy; an `h1`
      must not visually overpower card titles and surrounding content.
- [x] Keep heading levels distinct and readable across notes, stitch samples, descriptions, and
      steps, including light/dark themes and accessibility font scaling.
- [x] Add focused rendering tests or screenshot coverage for heading sizes in the affected content
      surfaces.
- [x] Run `just check` remotely on `rofl-13.brkn.lol` and verify the result on a real Android screen.

Status: **done** (2026-08-19) - compact heading typography and focused tests landed; combined remote
`just check rofl-13.brkn.lol` passed after integration.

## SNA-46: Use the shared android-app-ci actions

- [x] Audit Android build, lint, E2E, screenshot, signing, and release workflows against the
      reusable actions exposed by `pschmitt/android-app-ci`.
- [x] Replace duplicated setup/remote-Gradle/diagnostic shell glue with the shared actions where
      their contracts fit, while keeping Stricknani-specific fixture and test steps explicit.
- [x] If the shared actions do not support the required source paths or artifacts, add the smallest
      compatible upstream fixes and pin/document the consumed action interfaces.
- [x] Keep local `just` targets as developer-facing wrappers, but make hosted CI exercise the shared
      action path and verify Android Lint, E2E, screenshots, signing, and release artifacts.
- [x] Run the affected workflows and `just check` remotely on `rofl-13.brkn.lol` after migration.

Status: **done** (2026-08-19) - workflow migration landed with upstream nested-project/artifact
support in `pschmitt/android-app-ci`; remote Android check passed.

## SNA-47: Reduce redundant "Already up to date" notifications

- [x] Identify which sync/status events currently emit the "Already up to date" notification,
      especially repeated navbar taps and destination-root navigation.
- [x] Suppress duplicate or non-actionable notifications while retaining useful feedback for an
      explicit user refresh or a completed sync with changed data.
- [x] Add focused tests covering repeated navigation, explicit refresh, and changed/no-change sync
      feedback in English and German.
- [x] Run `just check` remotely on `rofl-13.brkn.lol` and verify the resulting notification volume
      on a real Android device.

Status: **done** (2026-08-19) - automatic no-change refreshes are silent while explicit refresh
feedback remains; combined remote `just check rofl-13.brkn.lol` passed.

## SNA-48: Add a warm yellow tone to the Notes card

- [x] Apply a subtle yellow-toned Material 3 surface/container to Notes cards across the affected
      project and yarn content screens.
- [x] Keep text, icons, contrast, dark-theme behavior, and accessibility readable at all supported
      font scales.
- [x] Add focused UI or screenshot coverage for the Notes card in light and dark themes.
- [x] Run `just check` remotely on `rofl-13.brkn.lol` and verify the result on a real Android
      device.

Status: **done** (2026-08-19) - shared accessible NotesCard styling, theme coverage, and screenshot
states landed; combined remote `just check rofl-13.brkn.lol` passed.

## SNA-49: Fix Stitch sample image and HTML entity rendering

- [x] Reproduce the Stitch sample rendering issue against project 3 on `wolle.anika.blue`, including
      missing images and literal `&nbsp;` text.
- [x] Normalize or sanitize the sample's HTML/Markdown before rendering so entities display as
      whitespace and authorized image URLs resolve through the configured server and image cache.
- [x] Preserve safe handling for malformed content, missing images, and offline cached samples.
- [x] Add focused rendering/URL-resolution tests and screenshot coverage for a populated Stitch
      sample in English and German.
- [x] Run `just check` remotely on `rofl-13.brkn.lol` and verify the result on a real Android device.

Status: **done** (2026-08-19) - HTML entity decoding, HTML-image normalization, authenticated
media resolution, fixture data, and screenshot coverage landed; combined remote `just check
rofl-13.brkn.lol` passed.

## SNA-50: Add an Android categories view

- [x] Add a categories destination reachable from the app's navigation, showing the user's
      categories with loading, empty, offline, and error states.
- [x] Load categories through the existing offline-first repository/sync flow and keep the view
      consistent with the project and yarn list screens.
- [x] Add focused Compose/navigation coverage for the destination and its states.

Status: **done** (2026-08-19) - offline-first categories destination and state/navigation tests
landed; combined remote `just check rofl-13.brkn.lol` passed.

## SNA-51: Import projects from Android

- [x] Add an explicit project-import flow in the Android app, including a discoverable entry point
      and progress/success/error feedback.
- [x] Reuse the backend's supported project import behavior and preserve offline-first semantics
      for the resulting project data and images where applicable.
- [x] Add confirmation and failure handling so a partially completed import is not silently lost.
- [x] Add focused tests for starting, completing, cancelling, and failing an import.

Status: **done** (2026-08-19) - URL import UI/state machine, Bearer-authenticated backend
boundary, offline outbox persistence, and project/step image persistence landed; remote
`just check rofl-13.brkn.lol` passed.

<!-- vim: set ft=markdown et ts=2 sw=2 : -->
