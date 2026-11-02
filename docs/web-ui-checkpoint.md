# T120 implementation checkpoint

The `/app` interface is an incremental Rust preview alongside the existing
Python application. The preview rollout is verified; production has not been
cut over, and its executable and storage remain unchanged.

## Current state — 2026-11-02

The deployed application source is `fe9ff79ce93a87899b15042243e52caa698fdd54`;
the public configuration pin is `6ba1610af9596d1af129f6bc29eb8b835ea2dc3b`.
The normalized source archive SHA-256 is
`f29d412242b3d7d67b9f41d4ba470f6571fbf87eee4fb389c02fc93a0800fe18`. The
verified web and server packages are
`/nix/store/my3zp6kycxxjg3jp70ljbsajvhhd5qrq-stricknani-web-0.1.0` and
`/nix/store/fdz83ix5jn5d0p53call8rbgz8fc6i11-stricknani-server-0.1.0`; the
raw server executable (`bin/.stricknani-server-wrapped`) SHA-256 is
`8e4fd12218576109d28ade9b7e8f1830cc2c58992a77ecdc975f960040a851a5`.

Root verified the live app HTML SHA-256
`73ba77b39da950412853b1ba1fe25edf0f035ce596f4657954d4ec4e783ca84a`, the
app-scoped service worker SHA-256
`24c326c6602b31d36256d4fe5bfd2c9e62416a94e55812bf50f6280f9dd93c9b` with
scope `/app/`, and the generated catalog SHA-256 prefix `f5b67b45` (738
English and 738 German entries). Production, nginx, and nixbot process
identities remained unchanged. The expected nix-daemon restart was limited to
the updated application nix-path pointer. One rollout notification was sent.

Audit-history migration 14 completed and SQLite integrity returned `ok`. The
pre-migration database backup is
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
  compact/library variants; app-local back navigation supports observed
  history and guarded parent fallbacks.

The exact packaged browser gates for admin resources, core project workflows,
yarn/category workflows, accessibility, and PWA behavior all exited 0. The
frontend formatter passed, Svelte checking reported zero errors and warnings,
142 frontend tests passed, and the static build passed. Rust validation passed
304 tests across 49 suites, four health tests, and Clippy.

The exact-source CI watch for harness checkpoint `63d2f1ba` (including PWA and
yarn runner updates) passed all four registered workflows. A public
auto-merge workflow was canceled; that cancellation is not a build failure and
does not establish that all CI is green.

## Remaining acceptance

T120 overall and T120.9 remain in progress. Remaining work includes a real
phone journey, complete native screen-reader acceptance, current contrast and
performance reruns, and a conditional legacy
consumer/cleanup review. Earlier bounded contrast, lab performance, keyboard,
zoom, and device receipts apply only to the artifacts and environments named
in their reports; they are not current packaged reruns. See
[`web-ui-acceptance-audit.md`](web-ui-acceptance-audit.md),
[`web-ui-device-acceptance.md`](web-ui-device-acceptance.md),
[`web-ui-native-a11y.md`](web-ui-native-a11y.md),
[`web-ui-screenreader.md`](web-ui-screenreader.md),
[`web-ui-contrast.md`](web-ui-contrast.md), and
[`web-ui-performance.md`](web-ui-performance.md).

## Packaging and interface work in progress

T120.13 tracks a packaging improvement, not a rollout blocker: frontend
`webPackage` currently participates in `buildRustPackage`, so frontend-only
changes can rebuild Rust. Split the frontend-independent Rust core from final
package assembly, and pass `corePackage` and `webPackage` through the default
package wiring in `nix/module.nix`.
The current preview explicitly overrides the package and is unaffected. No
packaging changes are included in the verified rollout. T120.14 adds action
icons and a compact branded header with native browser Back; its browser
acceptance and deployment are pending.

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
