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
- [x] A replay failure leaves the mutation queued with `lastErrorMessage` recorded
      (`PendingMutationDao.observeFailed()` exposed for a future conflict-banner UI - not
      surfaced anywhere yet, see caveat below) rather than silently dropping the local edit; the
      worker returns `Result.retry()` when any mutation failed

Status: **mostly done** (2026-08-18), landed together with SNA-10 - see that entry for scope
(text fields only this pass; step reordering + image/photo upload deferred). **Not done**: no UI
surfaces `observeFailed()` yet, so a stuck/conflicting queued mutation is currently invisible to
the user beyond it simply not showing as synced - a conflict-banner UI is a follow-up. **Not
verified**: an actual end-to-end replay against a running Stricknani server (no reachable test
server was set up this session, same gap as SNA-6/7/9) - verified so far only via a successful
remote debug build (`just build debug rofl-13.brkn.lol`, `BUILD SUCCESSFUL`) confirming the outbox
/worker/DI wiring compiles and Room's schema migration (v1 -> v2, `fallbackToDestructiveMigration`)
is consistent; not yet installed/exercised on a physical device this session.

### Android app screens

## SNA-9: Core browsing screens

- [x] Home/dashboard: favorites (projects + yarns) and recently-updated projects, pull-to-refresh
- [x] Projects list: search, category filter chips (+ inline "add category" dialog), favorite
      toggle, pull-to-refresh
- [x] Project detail: steps, images, needles/stitch sample/other materials, linked yarns
      (tappable, resolved from `yarn_ids` against the local yarn cache), notes
- [x] Yarn stash list + detail: search by name/brand/colorway, favorites-only filter, photos
- [x] Category management: create via the Projects screen's filter row (`CategoriesApi` direct
      call + `CategoryRepository.sync()`); rename/delete not implemented (categories.py has no
      such endpoints yet either)
- [x] Global search across projects and yarns - client-side over the Room cache, so it works fully
      offline with zero network call

Status: done (2026-08-18), with one caveat below. No dedicated "tag filter chips" UI landed
(the checklist item was originally "category/tag filter chips") - category filtering is real;
tag filtering was scoped out to keep this task bounded and can follow as a small addition.

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
      via `FilterChip`s. Step reordering and image/attachment picker/upload are **explicitly
      deferred** to a follow-up (multipart upload is materially more work and doesn't block the
      outbox mechanism itself being real and testable)
- [x] Yarn create/edit form (`ui/yarns/YarnEditorScreen.kt`/`YarnEditorViewModel.kt`): same text
      -field shape as the project form, mirroring `YarnWriteRequest`. Photo picker/upload
      deferred, same reasoning
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

Status: **mostly done** (2026-08-18) - verified via a successful remote debug build (`just build
debug rofl-13.brkn.lol`, `BUILD SUCCESSFUL`) after fixing two real compile errors caught along the
way: (1) `androidx.compose.material3.ExposedDropdownMenu`/`ExposedDropdownMenuBox` doesn't resolve
against this project's Material3 1.4.0 (the API this session initially reached for isn't present
under that name/signature in this version) - replaced the category picker with a plain text field
plus `FilterChip` suggestion chips instead of chasing the right dropdown API, which is simpler and
matches the category-filter chip row already used on `ProjectsListScreen`; (2) `Modifier.weight()`
inside `YarnEditorScreen`'s weight/length `Row` failed because `weight` was (wrongly) imported as a
top-level symbol - it's a `RowScope`-member extension, not a top-level function, and resolves
automatically inside a `Row { }` lambda without any import. **Not verified**: installed/exercised
on a physical device, or an actual live create/edit/delete round-trip against a running Stricknani
server (no reachable test server was set up this session, same recurring gap noted in
SNA-6/7/8/9). No unit tests added - `ProjectEditorViewModel`/`YarnEditorViewModel` are mostly
Android-lifecycle-bound (`SavedStateHandle`, `Flow` collection) with the same "not enough isolable
pure logic yet" reasoning as SNA-6.

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

Status: not started

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

<!-- vim: set ft=markdown et ts=2 sw=2 : -->
