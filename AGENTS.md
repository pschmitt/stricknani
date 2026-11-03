# Agent Instructions

Use this file as the operational source of truth for working in Stricknani.

## Read Order (for future agents)

1. `AGENTS.md` (this file) for workflow and hard rules
2. `TODO.md` for active execution queue
3. `README.md` for runtime/configuration
4. `docs/spec-and-implementation.md` for full product spec and historical implementation context

## Concord Workspace

- Coordinate under the primary Stricknani Concord workspace:
  - Workspace ID: `ws_L2hvbWUvcHNjaG1pdHQvZGV2ZWwvcHJpdmF0ZS9wc2NobWl0dC9zdHJpY2tuYW5pLmdpdA`
  - Repo root: `/home/pschmitt/devel/private/pschmitt/stricknani.git`
- When working from worktrees (such as `stricknani-rust-port.worktree`) or subdirectories, pass `workspace_id: "ws_L2hvbWUvcHNjaG1pdHQvZGV2ZWwvcHJpdmF0ZS9wc2NobWl0dC9zdHJpY2tuYW5pLmdpdA"` (or export `CONCORD_REPO_ROOT=/home/pschmitt/devel/private/pschmitt/stricknani.git`) so active tasks, claims, and overlap checks are shared.

- Read the coordinator's Concord inbox directly at every handoff, before long
  build/deploy waits, and before reporting status; do not rely only on live hooks.
  Reply in the original thread with a concrete interface, commit, or next action.
  Check receipts when a peer appears blocked; queued messages are not delivery.
  After a peer restarts, resolve its current session identity from Concord
  presence or its new message before sending another handoff. Re-address
  pending coordination to the current session and obtain an acknowledgment;
  a queued message to the previous session is not a successful handoff.

## Critical Requirements

### Rust rewrite workflow

- Follow [`REWRITE.md`](REWRITE.md) and track progress under T119 in `TODO.md`.
- Keep rewrite work in the dedicated `rust` branch/worktree; preserve unrelated changes in the primary checkout.
- Delegate isolated implementation and inventory tasks to GPT-6 Luna (`gpt-6-luna`) agents with explicit file ownership. The coordinator integrates their work and reviews API contracts, authorization, and data migration safety.
- When spawning a Codex subagent, select `model="gpt-6-luna"` and `fork_turns="none"` explicitly.
- The coordinator claims each delegated file scope before handoff. If Concord
  cannot identify a child session, keep that coordinator-owned claim and route
  evidence through the coordinator; do not invent an identity or repeat CLI
  registration attempts instead of doing the assigned work.
- Commit and push small checkpoints regularly, including work in progress. Do not wait for the entire rewrite to be complete. State which checks passed, failed, or remain pending in the checkpoint notes; a checkpoint is not a release or deployment approval.
- After each push, use the `ci-watch` skill and continue independent work while CI runs.
- Run expensive Cargo, Gradle, and Nix work on a designated remote build host. Evaluate Nix dev shells from a clean source snapshot, then run tools in a separate working directory containing build caches. Keep credentials and copied data outside both source trees.
- Distribute independent builds and browser checks across the available build
  hosts in the private infrastructure skill. Check current CPU and memory load
  when choosing a host, and reuse verified artifacts and dependency caches.
  Let live jobs finish before moving follow-up work to another host.
- For detached remote checks, record the command's exit status in the run's
  own directory as well as its log. A successful-looking log tail does not
  prove that the complete command passed. Keep the process or tool handle
  until the exit status has been collected.
- Run formatting, focused checks and strict Clippy before the full suite.
  Stop at the first failing gate, fix it, and preserve the failed receipt.
  Mirror every remote fix into the authoritative local source before reporting
  a checkpoint; compare all executed Rust files against that source.
- Keep Cargo's execution directory and owned dependency target stable across
  retries. Use separate immutable Nix source snapshots and receipt directories;
  changing the Cargo source path can rebuild otherwise unchanged crates.
  Other agents' builds on the same host do not require stopping or waiting
  when caches are isolated and the host has capacity.
- When syncing source into cached remote Cargo builds, use checksums and refresh changed Rust source timestamps. Preserved older mtimes can leave a stale executable even after a successful Cargo command; verify a new CLI command actually exists in the built artifact.
- Before sharing a browser-fixture executable, copy it into a versioned artifact
  directory outside Cargo's target directory and record its source revision and
  checksum. `cargo test` can rebuild executable and example targets too; a shared
  target path is not a frozen artifact, even without an explicit build command.
- Prioritize a usable, styled Rust preview. Pixel-perfect matching and the planned full UI redesign are out of scope.
- The Rust preview uses its own application login. Do not add Authelia or another extra login layer unless the user requests it; keep public signup disabled when serving copied data.
- Keep Python production and Rust candidate storage separate. Production migration and cutover require the explicit approval described in `REWRITE.md`.
- Investigate credential-scanner alerts before recording an incident; distinguish intentional public test fixtures, validation blacklists, and source identifiers from actual credential values.
- Delegated agents report scanner alerts to the coordinator without opening
  incident queues. The coordinator handles triage and any required targeted
  record; never read unrelated incident entries to confirm a write.

### Web UI rewrite workflow

- Follow [`WEB-UI-REWRITE.md`](WEB-UI-REWRITE.md) and track implementation under
  T120. The T119 limit on visual redesign applied to the Rust rewrite, not T120.
- Prioritize usable phone layouts and compact navigation. Keep mobile language
  preferences in Account settings instead of a dedicated global toolbar; retain
  compact signed-out language access. Use blue accents and neutral surfaces in
  both themes, with readable button foregrounds and restrained motion.
- Claude Code owns all web and Android UI implementation, UI helper fixes, and
  UI preview deployments. Codex coordinates backend/API/data work, operations,
  and independent acceptance review; delegate scoped non-UI work to GPT-6 Luna.
  Freeze shared interfaces before parallel work and give each writer
  non-overlapping files.
- Prepare the Android companion alongside the web rewrite under `android/`'s
  own instructions and SNA task queue. Review actual PAT/API contracts and new
  website links before changing native calls; preserve offline caches, queued
  writes and account isolation. Browser sessions and CSRF are not a native
  authentication replacement. Coordinate shared backend changes explicitly.
- Planning does not start implementation or authorize production cutover.
  Keep the existing preview usable while building and reviewing the new UI.
- The UI owner deploys verified UI milestones to the authorized isolated Rust
  preview so progress is visible. Review the exact candidate pin, checks and
  isolation before switching it. Coordinate backend checkpoints with that owner
  so neither deployment rolls back the other's changes.
  Do not wait for the entire UI rewrite to deploy useful increments. Production
  cutover remains a separate decision.
- The user explicitly requests a Signal notification after every successful
  preview version rollout. The deployment agent loads `signal-notify`, verifies
  the deployed version and HTTPS route, then sends a short change summary with
  the working clickable preview link. Send one notification per deployed
  version; do not announce a deployment before it is verified.
- During substantial active work, also send high-level Signal checklists with
  **Done / WIP / Next** at milestones and about every 15 minutes during long
  runs. Distinguish pushed source, verified builds and live deployments. Keep
  routine compiler and test debugging out of these summaries.

### Native API v2 migration

- Follow T121 in `TODO.md` and [`docs/android-v2-migration.md`](docs/android-v2-migration.md).
  Codex owns server changes and API/data review; Claude owns Android migration
  and all UI work. Send the client owner each tested server checkpoint.
- Keep API v1 available and unchanged until a separate retirement decision.
  Inventory installed clients and internal callers, including legacy HTML
  handlers, before proposing removal. Production cutover stays separately gated.
- Explicit Bearer credentials select strict API-purpose PAT authentication;
  invalid credentials never fall back to a cookie. Cookie mutations retain
  same-origin and CSRF checks. Preserve owner/admin checks in shared services.
- Prove delta cursors, tombstones, queued writes, retries and account isolation
  against the exact client/server revisions. Matching route names alone do not
  establish offline parity.

### Frontend integration checks

- Fixture runners own their `--private-dir`: pass a fresh, nonexistent leaf
  path, rather than creating it first. Create only its private parent when
  needed, and use the fixture environment loader instead of guessing JSON keys.
- When a packaged fixture must override `WEB_ROOT`, use its underlying server
  executable rather than the Nix launcher, which can replace that environment
  value. Verify the executable's file type and checksum before launching, and
  prove that the fixture serves the requested web root before update tests.
- Reuse validated shared image fixtures. Ad-hoc tiny PNG payloads can contain
  invalid CRCs and fail uploads before a UI probe begins; avoid extra synthetic
  media setup when the existing seeded fixture already covers the check.
- Keep private native IPC runtime paths short enough for Unix-domain sockets;
  a deeply nested build/report directory can exceed the socket path limit.
  Use an owned mode-0700 runtime directory and keep reports separately.
- A phone test on an existing personal browser profile needs an owned-target
  connector. A raw Playwright browser connection can enumerate other targets
  internally even when the test never reads `context.pages`. Keep discovery,
  global cookie/storage operations and personal page data outside the test.
- Device subprocess errors can reveal identifiers through their command/args
  even when stderr is captured. Use checked return codes or catch and replace
  exceptions with safe fixed statuses; never format the original command or
  `CalledProcessError`. Return only exact allowlisted readiness booleans.
- When recording an exposure, edit only its matching incident-note section
  without returning the note body. Do not read an entire incident queue to
  confirm a write; unrelated incident metadata is private too.
- For isolated feature checks, start from committed source and overlay only the
  assigned files. Do not copy another agent's unfinished routes into that
  snapshot. Run integration gates again on the exact committed checkpoint.
- Run the frontend formatter from `web/` with that snapshot's configuration
  and lockfile; matching formatter versions alone do not prove matching settings.
  Before evaluating a source snapshot, exclude Git metadata, generated outputs,
  and dependency/cache symlinks. Use an explicit `path:` flake reference.
- Run Python helper lint from the frozen project root with its `pyproject.toml`
  and locked tool version. Default-config diagnostics and baseline comparisons
  do not establish that the repository lint gate passed.
- For Python browser runners, run both `ruff check` and `ruff format --check`
  on the changed files. The Rust-first `just check` does not run the Python
  formatter; a passing lint check alone does not satisfy the Nix format gate.
- In Svelte effects, compute new filter/draft objects in local variables before
  assigning reactive state. Reading an object immediately after replacing it
  in the same effect can create a dependency loop.
- Abort obsolete loads and guard asynchronous results with the captured user
  and resource identity, or a request generation. This includes mutation
  success, failure and cleanup: dynamic routes may reuse the same component.
- Keep `wait_for_function` predicates synchronous. The pinned Playwright treats
  a returned Promise as truthy before its boolean result resolves. For async
  conditions such as worker registration or CacheStorage, use a bounded helper
  that awaits `page.evaluate` and checks the resolved boolean. Cover both an
  always-false timeout and a later-true condition in the helper's regressions.
- For Playwright setup through `page.evaluate`, use a block-bodied `() => { ... }`
  with no return so it resolves to `undefined`; an expression-bodied assignment
  may return a callable that Playwright invokes. Keep `const` and `let`
  declarations inside that function body or a scoped IIFE to avoid repeated
  top-level lexical declarations. Pass an explicitly synchronous arrow
  predicate to `wait_for_function` (for example, `() => Boolean(window.ready)`);
  do not use an async predicate or an assignment expression that returns a
  callable.
- Browser URL changes and API responses do not prove that controls or data
  have rendered. Wait for the intended visible element before assertions;
  scope single-element reads explicitly when several content blocks match.
- After viewport changes, wait for two animation frames before geometry
  assertions; the resize acknowledgement can precede responsive layout. Await
  font readiness when measuring text. Keep numeric measurements in failure
  messages without including form values or fixture credentials.
- Sequence native dialog tests with `dialog.open` and visible/hidden state.
  `aria-expanded` can update later through the close event and is not a reliable
  signal for immediately reopening a dialog.
- Give selects an explicit, unique label association and verify their accessible
  names. Nesting option text inside a wrapping label can make the name include
  every option; translated exact-label browser checks should catch this.
- Preserve drafts on validation, conflict and network failures. Allow media
  changes only on a saved, clean form, then reload the full resource. Never
  advance its conflict timestamp blindly while retaining stale draft fields.
- Verify print with real project and step images as well as text. Hidden
  controls must not hide reference diagrams; print should use full media URLs.
- Verify the realised frontend package before deployment. SvelteKit build
  versions can change generated HTML and CSP hashes while immutable JS/CSS
  remain identical; when they differ, run the browser and PWA gates against
  the packaged web root rather than relying on a separately built artifact.
- Resolve the deployment configuration's actual package and followed inputs
  before expensive candidate builds. A standalone application flake may use
  a different dependency revision from its deployment consumer. Build and test
  the canonical package first to avoid repeating compilation and browser gates.
- If that package is exposed through a service's `restartTriggers` list, require
  exactly one entry, evaluate its `drvPath`, then build `'<drv>^out'`. Building
  the list selector or a bare `.drv` does not realize the package output.
- Label each hash with its exact object and algorithm. Keep application-input
  NAR hashes separate from deployment-snapshot NAR hashes, and manifest-file
  hashes separate from file-set digests. Use one manifest convention when
  comparing copies, or compare their files directly.
- Before launching a browser acceptance run, verify the checksum of the actual
  runner file at its execution path, not just a synchronized source copy.
  For Python module runners, change into the intended source root and assert
  the imported module's resolved `__file__` and checksum in that same process
  environment. `PYTHONPATH` alone does not prevent an older working directory
  from shadowing the intended package.
  Freeze the runner and static root together; check that the generated worker
  matches its source template and manifest substitutions. Record these hashes
  with the receipt so stale runners or builds cannot be mistaken for failures
  of newer source.
- Keep diagnostic runner overlays in the same repository-relative directory
  structure as the frozen source. Runners derive fixture assets from their own
  file location; copy or link the required asset directories and verify them
  before launching. Keep private diagnostic logs and exit receipts outside the
  fixture's cleanup-owned directory; report only sanitized failure details.
- When shared cards place subtitle or metadata text inside a link, scope browser
  locators to the card, assert the expected title as part of its accessible
  name, and verify the destination separately. Keep owner-scoped thumbnail URLs
  distinct from upload and mutation endpoints.
- The header chevron follows the logical parent route through one shared route
  map. This supersedes the earlier history-based requirement. Hide the control
  on top-level sections while keeping the logo aligned. The coordinated header
  revision combines the chevron, logo and name in one link: it targets the
  logical parent on nested pages and home on top-level pages. Keep a minimum
  44px target, preserve dirty-form guards and reduced motion, and remove
  duplicate in-page back links. A deep link must stay inside the app.
- Use shared, bounded decorative icons alongside action labels. Preserve
  accessible names, keyboard behavior and compact phone layouts; existing
  icon-only controls do not need a second icon.
- App updates are independent per tab. Worker activation must not reload a
  page or depend on other tabs replying. Keep each tab's dirty/busy guards;
  automatic full loads apply only to clean same-origin link/goto navigation,
  never popstate, hash-only, external or unload navigation. Cache only public
  assets and retain the newest three versions. Keep the update chip separate
  from owner-scoped action feedback and respect reduced motion.
- When changing shared chrome or avatars, inventory selectors across all
  shipping browser runners, including signup and optional-service branches.
  Verify account identity through the session and Account page.
- Browser Back and its confirmation dialog are asynchronous. Await the dialog
  event and navigation result explicitly. Exercise in-app history through real
  app links; hard document navigation exercises browser unload protection.
  Keep confirmation handlers registered until the expected event arrives;
  a click can finish before SvelteKit's navigation guard opens its dialog.
  Prove this against an actual app link, since a synchronous synthetic
  `window.confirm()` does not exercise that race.
- Before triggering a preview host upgrade, copy the reviewed, realized system
  closure from the build host to the deployment target and verify its presence.
  Keep compilation on the designated build hosts.
- Device checks in an existing browser profile must use a unique per-run
  loopback hostname, not just a new port: cookies are shared across ports.
  Create an owned tab and clear only that test origin during cleanup.

### Vendored Web Dependencies

- The new web UI is planned in [`WEB-UI-REWRITE.md`](WEB-UI-REWRITE.md), tracked
  under T120. This is a fresh frontend; the previous vanilla-JS preference does
  not constrain it. Use the chosen framework and build tool with a committed
  npm lockfile, reproducible installs, and locally bundled assets. These bundled
  dependencies do not need separate `vendir.yml` entries. The rules below still
  apply to standalone dependencies added to the legacy frontend.

- All new frontend dependencies (JS/CSS) must be vendored via `vendir.yml` (no new CDN links).
- After changing `vendir.yml`, run `just vendor-sync` and commit `vendir.lock.yml` and `stricknani/static/vendor/**`.
- Prefer vanilla JS for new UI behavior. Add JS dependencies only when HTMX/browser APIs are insufficient.
- Exception: `stricknani/static/vendor-tiptap/` (TipTap). Its npm packages pull in ~30 transitive modules (prosemirror-*, etc.) resolved via bare specifiers, which `vendir` can't vendor as browser-loadable files without an unmaintainable hand-written import map. It's instead bundled with esbuild into one self-contained file (`just vendor-tiptap`) — see that directory's `package.json`/`entry.js`. It deliberately lives outside `stricknani/static/vendor/` (not `.../vendor/tiptap/`), since that directory is exclusively managed by `vendir sync`, which deletes anything under it not declared in `vendir.yml`. Commit the regenerated `tiptap-bundle.min.js` and `package-lock.json` together after bumping pinned versions there.

### Translations (UI text)

- Keep message IDs literal in each `t()` call. Branch between calls instead of
  passing a conditional key: the web extractor does not discover conditional
  first arguments. When moving labels into shared components, inspect the
  generated catalog delta so active accessible labels are not removed.
- Add new strings to both:
  - `stricknani/locales/en/LC_MESSAGES/messages.po`
  - `stricknani/locales/de/LC_MESSAGES/messages.po`
- Avoid multiline strings in templates (`{% trans %}` / `{{ _(...) }}`) to prevent brittle `msgid`s.
- After template text changes:
  - `just i18n-update`
  - `just i18n-compile`
  - `just i18n-check`

### Database Migrations

- Always create a migration when SQLAlchemy models change.
- Create migration:
  - `uv run alembic -c stricknani/alembic.ini revision -m "description_of_change"`
- Verify migration runs:
  - `uv run alembic -c stricknani/alembic.ini upgrade head`
- Migrations live in `stricknani/alembic/versions/` and must be committed.

### Code Quality

- Zero tolerance for lint/format issues: fix immediately.
- TODO management:
  - When starting a task from `TODO.md`, mark it `wip` immediately.
  - If asked to "clean up", remove all `done` tasks from `TODO.md`.
- Required checks:
  - Lint: `uv run ruff check .`
  - Type check: `uv run mypy .`
  - Format: `just fmt`
  - Native Rust and shipping-harness tests: `just test`
  - Rust-first full check: `just check`
  - Full Python reference suite with its 80% coverage gate: `just test-python`
  - Python reference lint, coverage, and translation gate: `just check-python`
  - Nix lint: `statix check`
- Mandatory after any code change:
  - `uv run pytest tests/test_health.py`
- Trim trailing whitespace in edited files.

### UI Consistency (Projects & Yarns)

- Keep project and yarn pages visually and behaviorally consistent.
- Apply layout/styling changes to both domains.
- Reuse shared resource cards for project, yarn and category references instead
  of rebuilding names, links, thumbnails and missing-image states per page.
  Use compact variants in audit and related-resource lists. Cross-owner audit
  cards must use the separate read-only administrator links and media routes.

### UI Parity (Web & Android)

- The web app (`web/`) and the Android app (`android/`) are one product: keep
  their layout, structure, wording and visual language in sync.
- A UI change on one side (a new card or section, a reordered form, renamed
  copy, a new interaction) needs the matching change on the other side in the
  same task. If that isn't possible yet, for example because it needs an API
  change first, add a follow-up entry to the other side's TODO and mention it
  in the commit message and the hand-off.
- Use the same English and German wording on both sides (`web` catalogs and
  `android/app/src/main/res/values{,-de}/strings.xml`).
- Platform conventions may differ (bottom bar vs. sidebar, sheets vs.
  dialogs), but the order of sections and what each screen offers should
  match.

### Release/CI Parity

- For shared GitHub Actions components, load the `ci-workflows` skill and read
  the current [shared catalog](https://github.com/pschmitt/ci-workflows) and exact
  workflow/action interface before editing callers. Use
  `pschmitt/ci-workflows/...@main`, preserving caller triggers, permissions,
  checks, artifacts and publish gates. Keep application-specific checks here.
- `just check` runs lint, Nix lint, strict Rust checks, the Rust suite,
  shipping-harness Python tests, and i18n checks. It does not run the full
  Python reference suite.
- `just check-python` preserves the full Python reference lint, coverage, and
  translation gate; `just test-python` keeps its 80% coverage threshold.
- When touching build/deploy/release paths, also run:
  - `nix flake check`
  - `just build-image`

## Handy Commands

- Setup: `just setup`
- Dev server: `just run`
- Lint: `just lint`
- Format: `just fmt`
- Native tests: `just test`
- Python reference suite: `just test-python`
- Python reference gate: `just check-python`
- i18n Update: `just i18n-update`
- i18n Compile: `just i18n-compile`
- Full checks: `just check`

## Rust-first checks and Python reference lane

The Rust dev shell is the default runtime and provides Rust and repository
check tools. For Python helper tests or the reference app suite inside that
shell, set `UV_PYTHON=python3.13` before `uv sync` or `uv run`; this selects the
supported Nix Python and its available wheels.

The primary Python coverage job uses Python 3.15 and retains the existing 80%
coverage threshold. The most recent Python 3.13 reference run passed its tests
but reported 74.23% coverage, below that threshold. The Nix Python 3.13 lane
therefore runs the same full reference tests without coverage because
coverage.py reports different line data under that interpreter. This does not
weaken the Python 3.15 gate. Both reference commands remain available
independently of the Rust-first `just test` and `just check` recipes.

For remote Nix evaluation, create a small filtered snapshot from the current
source and evaluate the dev shell there; run checks from a separate cached
checkout. Do not point Nix evaluation at the large Cargo-cache checkout, since
its `target` and virtual environment make source evaluation unnecessarily
expensive. Keep `UV_PYTHON=python3.13` in the dev-shell command environment so
`uv` does not try to build unsupported source wheels for a host-selected Python.

## Documentation Layout

- Operational rules: `AGENTS.md`
- Work queue: `TODO.md`
- New web UI brief and implementation checklist: `WEB-UI-REWRITE.md` (T120)
- User/developer runbook: `README.md`
- Product spec + implementation snapshot: `docs/spec-and-implementation.md`
- Android app (offline-first native client, `android/`, package `blue.anika.wolle`): its own
  `AGENTS.md`/`TODO.md` (`SNA-N` prefix) - read `android/AGENTS.md` before working in `android/`,
  it is the operational source of truth there the same way this file is for `stricknani/`
