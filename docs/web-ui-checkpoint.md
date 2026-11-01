# T120 implementation checkpoint

The new web UI is in progress on branch `rust`. The implementation brief is
`WEB-UI-REWRITE.md`; shared design decisions are in `web-ui-design.md`.
The existing Rust preview remains usable while new routes are built at `/app`.

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
coordinator reviewed masked phone screenshots. The final clean-source full
gate and the next feature rollout remain pending; concurrent yarn work is
excluded from this project checkpoint.
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

A preliminary project-screen build measured 61,003 gzip bytes across all 31
JavaScript assets and 9,758 gzip bytes across seven CSS assets. This measures
the whole current static asset set, not a browser's first-route transfer or
mobile startup performance. The acceptance lab measurements remain pending.

Production continues to serve Python with separate storage. Preserve the user's
uncommitted `REWRITE.md` and unrelated primary-checkout changes.
