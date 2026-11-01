# T120 implementation checkpoint

The new web UI is in progress on branch `rust`. The implementation brief is
`WEB-UI-REWRITE.md`; shared design decisions are in `web-ui-design.md`.
The existing Rust preview remains usable while new routes are built at `/app`.

## Latest verified state

The live preview runs `6cb58fb`, deployed through infrastructure commit
`d580a6033b76d19413c4f618d839fcd3ad5e4f6b`. HTTPS, native login, assets,
strict CSP, isolated storage and unchanged production executable were verified.
The deployment agent sent the requested Signal notification with the preview
link. All six source CI runs passed. Earlier sections below retain historical
checkpoint evidence; their pending deployment statements describe that time.

Newer committed checkpoints await the next integrated build and rollout:

- `976b621`: full-resolution project and step images in print, verified with
  real uploaded images and print-media browser assertions.
- `22acd26`: yarn browsing, editing, photos, favorites, deletion and category
  management. Real Chromium journeys passed at 320, 390 and 1280 pixels with
  touch-target and strict-CSP checks; frontend checks and 23 unit tests passed.
- `9cdbab6`: project creation/editing, ordered steps, yarn association, media,
  deletion and conflict recovery. Browser acceptance enters through visible
  Create/Edit links and verifies draft preservation, both conflict choices,
  uploads and print. Frontend checks and 51 unit tests passed. Session race
  tests prevent older bootstrap responses from replacing newer auth state.
- `ecd9aff`: owner-scoped OCR and safe current-link archive status/enqueue.
  OCR has bounded image reads and a separate per-owner quota. The full Rust
  suite on the exact post-limiter commit passed 251 tests across 42 suites.
  Formatting, strict Clippy and health checks passed. OCR/archive frontend
  controls remain unfinished.
- `085f282`: public stateless gauge calculation shares the existing Rust
  calculation, with decimal-string output preserving exact large results in
  JavaScript. The full Rust suite passed 257 tests, plus formatting, strict
  Clippy and four health tests. The new gauge screen remains unfinished.
- `91d2ad0`: category create/rename inputs lock during saving, verified by
  delayed real API requests, so completed saves cannot clear newer typing.
- `fecc2ce`: import text/file/URL entry and editable preview, optional AI,
  normal project/yarn creation, explicit remote-image selection and draft
  preservation. Real Chromium acceptance covers cancellation and failure of
  review replacement, delayed saves, narrow layouts, touch targets and CSP.
  Both source catalogs include the new strings. Python runner formatting was
  corrected in `2799efd` after the Nix pre-commit gate caught a missed check.
- `f5f51cc`: the unhashed browser translation catalog uses `private, no-store`
  so new deployments do not reuse stale translations. Nine security/frontend
  tests and formatting passed.
- `b21683c`: obsolete or aborted requests cannot expire a newer authenticated
  session through a late 401. Deferred client/session regression tests,
  frontend checks, 83 unit tests and the static build passed.
- `3172f21`: v2 project tag arrays preserve complete labels on writes and
  reads; v1 retains its established normalization. The full Rust suite passed
  258 tests, strict Clippy and formatting; four health tests passed. The normal
  server binary was rebuilt for the frontend round-trip browser regression.

Reader step navigation, the complete frontend/backend tag round-trip, and
removal of outdated placeholder copy are in browser acceptance. Account API
extraction and the public offline/update design are in progress. Gauge UI,
account UI, signup, admin/audit, PWA/cache and the complete
accessibility/performance/multi-browser/real-phone acceptance remain open.

Deployment limitations are recorded separately from application checks. The
public infrastructure no-build flake check fails on a missing Termux Home
Manager derivation, reproduced on its unchanged baseline. The application
flake checks, package, container and exact host build passed for `6cb58fb`.
The upgrade wrapper exited unsuccessfully after activation, while the switch
unit, exact running closure and both application services were verified; the
wrapper failure's precise cause remains unresolved. Neither limitation is
reported as a passed gate.

## First wave

- Luna foundation: SvelteKit/TypeScript static build, shared components,
  responsive shell, typed API client and actual browser login/session flows.
- Luna API: cookie-authenticated v2 bootstrap/login/logout and focused security
  tests, leaving Android bearer v1 independent.
- Luna packaging: bounded static frontend router, strict CSP manifest handling,
  reproducible asset package, container and frontend CI integration.
- Coordinator: shared interfaces, router wiring, ownership conflict resolution,
  code review and acceptance evidence.

The first native checks passed 220 Rust tests, formatting and strict Clippy,
including browser-purpose cookie authentication, signed pre-authentication
CSRF, v1 isolation, and six static-route/CSP tests. Python health checks passed
(4 tests). The frontend passed type/format checks, 12 API-client tests and its
static build. The reproducible asset package and real browser foundation
acceptance now pass. Full-system/container verification and the first preview
switch are pending; these results are not full UI acceptance. Remaining work
includes project/yarn frontend workflows, editing,
uploads, imports, settings/admin, translation completion, browser testing,
performance measurements and isolated preview rollout.

The first foundation checkpoint is `7949163`. A fresh source check subsequently
passed the complete `just check` gate, including MyPy (203 files), 64 Python
harness tests, translation checks and the frontend checks/build. Use
`UV_PYTHON=python3.13` and run `just ci-setup-py` inside the Nix shell before
checking a fresh checkout.

Actual Chromium testing found and corrected a 44px navigation-target miss.
It also identified SvelteKit's generated screen-reader route announcer as the
source of an inline-style CSP violation. Only that pinned framework style's
exact hash is now allowed through `style-src-attr`; arbitrary inline styles
and scripts remain blocked. The integrated browser run passed login, protected
cookie/CSRF/session/logout, direct routes, responsive layout, touch targets and
strict CSP with no unexpected browser errors. See `web-ui-browser-check.md`.
The browser/fixture tests pass (16), with MyPy and Ruff clean after targeted
typing fixes.

Project list/detail/create/update/delete/favorite operations now have shared
typed Rust services and v2 adapters, including sanitized HTML companions and
optimistic conflicts. A regression proves patterns longer than the 8 KiB auth
limit work; project JSON has its own bounded 2 MiB limit and stable 413 errors.
The latest full Rust suite passed 224 tests, with strict Clippy and formatting.
Project and yarn frontend routes remain explicitly unfinished in this milestone.

The user requests regular verified milestone deployments and a Signal message
after each new preview version lands, with a short summary and working link.
The deployment Luna owns that notification after HTTPS/version verification.

## Current integration wave

The committed foundation candidate is `2d3b663`. Its clean-source `just check`
passed, and the reproducible Rust/static-asset package built successfully.
Both registered CI runs passed. Flake checks and the candidate container build
also passed. The isolated host closure built and the preview rollout was
verified on 2026-11-01. The new UI is available at `/app`; native login, strict
CSP, disabled signup and separate preview storage remain in place. Production
keeps its previous service executable. The deployment Luna sent the requested
Signal notification after HTTPS and version checks passed.

The project library and pattern reader are being verified against the real
Rust API with synthetic fixtures. Their current frontend checks, static build
and 22 unit tests passed in the isolated project snapshot. Real Chromium
acceptance now passes after correcting the library lifecycle and synchronizing
browser assertions with rendered data. It covers search/category/tag/favorite
filters, sanitized instructions, ordered steps, linked yarns, the single-image
320px viewer with Escape/focus restoration, text sizing, print, and logout under
strict CSP. The runner passes MyPy, Ruff and 21 focused Python tests. The
coordinator reviewed masked phone screenshots. The exact committed `6cb58fb`
clean-source `just check` also passes: all Rust test groups, 72 Python harness
tests, zero frontend diagnostics, 22 Vitest tests, and the static build. The
next feature rollout remains pending; concurrent yarn work is excluded from
this project checkpoint.
Review found that `6cb58fb` print includes text and steps but omits reference
images. A separate fix now prints full-resolution project and step photos,
with real uploaded step-image coverage under print media. Its browser run,
type/build checks, MyPy/Ruff and 21 runner tests pass. This fix is separate from
the currently deploying reader checkpoint.
The reader includes sanitized instructions, ordered steps, linked yarn details,
reference images, attachments, text sizing and print output. Editing, deletion,
media mutations and persistent current-step navigation remain separate work.

Shared yarn/category operations and browser adapters are committed in
`facf1db`, without schema changes. Additional ownership, CSRF, cross-origin and
body-limit tests pass; the full Rust suite passes 229 tests, with formatting
and strict Clippy clean. The four Python health tests also pass. The API Luna
has completed the browser media operations. The frontend Luna owns the project
screens and translation catalogs. The deployment Luna now
implements yarn/category screens in disjoint feature directories and retains
responsibility for the next verified preview rollout and Signal notification.

The browser media operations are committed in `16ab089`: project title/step
images, attachments, yarn photos and deletion reuse the existing typed storage
operations. Their ownership, CSRF, image processing, file limits, audit and
cleanup behavior remain shared with v1. The full Rust suite passes 233 tests,
formatting and strict Clippy pass, and the four health tests pass. Frontend
upload workflows are still being implemented.

Import previews are committed in `46fa060`. URL/text/file preview reuses the
isolated parser, preserves safe fetching and source limits, requires cookie
authentication and same-origin CSRF, and calls AI only on explicit opt-in.
Responses report fallback honestly; a separate owner limiter bounds preview
work. Reviewed drafts use normal project/yarn creation. All 244 Rust tests,
formatting, strict Clippy and the four health checks pass; the import UI is
still pending.

A preliminary project-screen build measured 61,003 gzip bytes across all 31
JavaScript assets and 9,758 gzip bytes across seven CSS assets. This measures
the whole current static asset set, not a browser's first-route transfer or
mobile startup performance. The acceptance lab measurements remain pending.

Production continues to serve Python with separate storage. Preserve the user's
uncommitted `REWRITE.md` and unrelated primary-checkout changes.
