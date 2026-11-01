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

Production continues to serve Python with separate storage. Preserve the user's
uncommitted `REWRITE.md` and unrelated primary-checkout changes.
