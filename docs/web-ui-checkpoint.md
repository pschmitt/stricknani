# T120 implementation checkpoint

The `/app` interface is an incremental Rust preview alongside the existing
Python application. The preview rollout is verified; production has not been
cut over, and its executable and storage remain unchanged.

## Current state — 2026-11-02

The deployed application source is `72b1faaec3c3dc488cea7cc9b27a7daa14ac9b83`;
the public configuration pin is `bc0645107fc5e42ac0acfc2e85f366c76a065cf2`.
The source NAR hash is
`sha256-w5Em/zSMHQIHiSQWlpSiYe5RP/+JcCRrU1cMdcQHldk=`. The
verified web and server packages are
`/nix/store/gyqjfr40aiszvyg9l8lxp7i8wq2qlgjc-stricknani-web-0.1.0` and
`/nix/store/n65q658kcrqh0qbqkfnsds8k7m007pa8-stricknani-server-0.1.0`; the
raw executable in the reused `g941cq8pr5mr8v7s4gc879c2kkhsp41b` core package
has SHA-256 `460dd562505f51518d7c65fdabd199e7d27210a55574f7bc83bb428b667fcbcd`.
The canonical system closure is
`/nix/store/yh28g1jfxac7mhpj270sw43r972macdn-nixos-system-rofl-10-26.11.20261101.0bb2eed`.

Root verified the live app HTML SHA-256
`6c61a7102ab75366ccc107f4b7a003e77fbfb8c3a3db59cceb555c2d6ebc6a6f`, the
app-scoped service worker SHA-256
`c1903ccf61860e4a6ae659e2958fce305768024a959fc6c3dd8d3f0c72b72f65` with
scope `/app/`, and the generated catalog SHA-256 prefix `f5b67b45` (738
English and 738 German entries). Production, nginx, and nixbot process
identities remained unchanged during this rollout, preserving the peer's
completed nixbot upgrade. The expected nix-daemon restart was limited to
the updated application nix-path pointer. One rollout notification was sent.

Schema 14 and SQLite integrity `ok` were verified; this milestone introduced
no database migration. The earlier pre-migration backup is
`/mnt/data/backups/stricknani-rust/2026-11-02-pre-audit-history/database.sqlite3`
(SHA-256
`e9ef8e0add28ce2b2cf0f94e8d1bba1b15e1df0080fa1b5315149ecf635b58ec`); the
file is mode 600 in a root-owned mode-700 parent directory.

## Completed T120.10–T120.12

- **T120.10, requested update activation:** explicit reload-to-update with
  cross-tab readiness, dirty-form and in-flight mutation guards, and bounded
  worker/cache handling.
- **T120.11, audit history:** typed change records, resource names and links,
  read-only cross-owner administrator views, protected media, and on-demand
  diffs. Ordinary ownership and mutation checks and API v1 remain unchanged.
- **T120.12, resource cards:** shared linked project/yarn/category cards with
  current authorized thumbnails, domain-icon fallback, and responsive
  compact/library variants.

## Completed T120.13–T120.14

- **T120.13, packaging:** frontend assembly reuses the Rust core derivation;
  clean flakes with different frontend sources retain the same core build.
  The default module passes the core and web package arguments explicitly.
- **T120.14, interface:** shared decorative icons accompany action labels
  throughout imports, projects, yarns, account and administration. The compact
  header uses the existing logo, title and small version line, with the avatar
  at the right edge. Back follows native history, preserves canceled drafts,
  and restores audit filters and scroll after asynchronous list loading.

The exact `52221ad` packages passed core and administrator browser journeys.
The administrator gate covers fresh direct entry, canceled and accepted Back,
filter/scroll restoration, and compact detail chevrons at 390 and 1280px.
Header/icon checks cover 320/390/1280px; import controls passed at 320px in
English and German. Frontend formatting, Svelte checking (zero errors and
warnings), 138 tests, production build and four health tests passed.
The earlier `52221ad` milestone used system closure
`/nix/store/w30pxhwz1vla15dhin7rim33z9b8wwm3-nixos-system-rofl-10-26.11.20261030.1c89083`.

The five broader packaged browser gates below belong to the earlier `fe9ff79`
milestone; they are retained as historical evidence for unchanged features.

The exact packaged browser gates for admin resources, core project workflows,
yarn/category workflows, accessibility, and PWA behavior all exited 0. The
frontend formatter passed, Svelte checking reported zero errors and warnings,
142 frontend tests passed, and the static build passed. Rust validation passed
304 tests across 49 suites, four health tests, and Clippy.

All four CI workflows for harness checkpoint
`2c94f380b6201a75882c4f1a4a9903c4a61f38a9` succeeded. The Android replay job's
first attempt failed while downloading the emulator SDK before tests started;
one retry passed. This result applies to that application checkpoint, not to
unrelated later infrastructure commits.

## Remaining acceptance

Candidate `72b1faaec3c3dc488cea7cc9b27a7daa14ac9b83` is pushed and deployed,
with action hints, shared icons, named photo-dialog focus and associated
validation errors. The canonical Nix server is
`/nix/store/n65q658kcrqh0qbqkfnsds8k7m007pa8-stricknani-server-0.1.0` and web
root is `/nix/store/gyqjfr40aiszvyg9l8lxp7i8wq2qlgjc-stricknani-web-0.1.0`.
The full Rust suite, 150 frontend tests, canonical capability probe, core
Chromium journey, portable accessibility journey and worker/bootstrap byte
checks passed. The committed upgrade completed successfully and the live
assets match that tested package. Strict native Firefox/Orca acceptance also
passed for this package. Physical-phone acceptance and the current-package
cross-engine/lab refresh remain open.
All five CI workflows for `72b1faa` and all four public configuration CI
workflows for `bc0645107` succeeded.

T120 overall and T120.9 remain in progress. The earlier `52221ad` package passed
84 assessable contrast samples; its five-sample mobile lab measured median
LCP 2220ms, CLS 0 and a 40ms interaction candidate, within its stated budgets.
Firefox native 200% zoom also passed for that package. The conditional legacy
consumer review is complete; active consumers require retaining the old assets
for now.

The `52221ad` browser matrix passed Chromium, Firefox and WebKit, including
a real intercepted print-image failure and retry. Remaining work is the
approved physical-phone journey and current-package cross-engine/lab refresh.
Diagnostics remain separate from passing acceptance evidence. See
[device acceptance](web-ui-device-acceptance.md),
[native zoom](web-ui-native-a11y.md),
[screen-reader verification](web-ui-screenreader.md),
[browser matrix](web-ui-browser-check.md),
[contrast](web-ui-contrast.md), [performance](web-ui-performance.md), and
[legacy consumers](web-ui-legacy-cleanup.md).

## Legacy compatibility and cleanup constraints

Do not remove legacy routes or assets as part of T120 completion without a
separate consumer audit and cutover plan. `WEB_ROOT` adds the `/app` frontend;
it does not by itself disable the legacy HTML routers. Android v1 and its
`/projects/:id` and `/yarn/:id` deep links remain consumers. Preserve
`/projects/import`, which serves the Android bearer-token import flow outside
`/api/v1`, along with `/api/v1`, `/media`, and shared backend/media/PDF/import
services. The root-scoped legacy service worker and cached pages also need an
explicit compatibility/retirement plan. Python production code and assets
remain unchanged.

## Historical checkpoints

Earlier v8 and `final-04` receipts are historical evidence for those named
artifacts only; they do not describe the current live version. Previous
milestones added project and yarn workflows, media, imports, account and token
management, admin/audit screens, signup APIs, and print support. Keep their
feature-specific test results tied to their original source and package
provenance. Earlier GTK test dependency issues and rollout recovery notes are
resolved historical context, not current blockers. Aggregate bundle sizes are
not first-route transfer measurements, and synthetic localhost timings do not
establish production field p75.
