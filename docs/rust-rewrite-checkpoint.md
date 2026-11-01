# Rust rewrite checkpoint — 2026-11-01

This branch is an implementation checkpoint, not a release or production replacement.
Production defaults and traffic remain unchanged. Follow `REWRITE.md` and T119 in
`TODO.md` for acceptance and the cutover approval boundary.

The checkpoint adds browser CRUD/media/account/admin workflows, explicit browser/API
token purposes, protected avatars, legacy metadata and media-ID preservation, a
one-time copied-snapshot importer, safe URL fetching and document parsing, process
limits for document workers, gettext integration, candidate image hardening, and an
Android instrumentation replay test. Search/filter controls are still being integrated.
The import-service and PWA modules still need application routing integration.

Evidence recorded during integration:

- The previous committed API candidate passed 51 Rust tests, Kotlin contract and JVM
  tests, Nix checks, and container health/runtime-closure checks.
- The importer fixture suite passed 10 tests and the safe-fetch policy suite passed
  six tests. Later path-handling changes still need another run.
- The new Android replay instrumentation test compiles; its emulator run is pending.
- Python type checking passed for 189 files. The full Python suite had 311 passes,
  one skip, and one failure because new Rust labels require regenerated translations.
- The latest full Rust check stopped on worker helper imports; that fix is present
  but awaits a rerun. New parser, account/avatar, search, and worker integration still
  need the complete formatting, lint, and test pass.

No production data has been imported. Copied-production rehearsal, complete external
integrations, staging acceptance, final Python-free default outputs, and production
cutover remain outstanding. The dependency-audit findings are tracked separately in
`rust-security-review.md`.

## Follow-up integration

The import preview/service and PWA routers are now connected to the application.
The document worker is selected explicitly by the server; URL imports remain disabled
unless `RUST_EXTERNAL_IMPORTS_ENABLED=true`. Public PWA resources retain their own
cache policy; private routes remain `no-store`.

The updated gettext extraction found 99 Rust messages. Catalog generation, compilation,
translation checks, and the ten translation/health tests pass. The new Android replay
instrumentation test compiles after formatting. Rust worker, document parser, import
preview/create, snapshot import, and media integration tests pass in the current remote
run. PWA assertions needed adjustment for generated cache names and reviewed German
translations; the full Rust suite and strict Clippy check are still being completed.

## Verified browser and import checkpoint

The complete current Rust suite passes: 130 tests, formatting, and Clippy across all
targets/features with warnings denied. This includes valid PDF extraction and compressed
page limits, imported records exercised through actual API/media responses, browser
search/pagination, uploads, account changes, and avatar replacement/cleanup.

The Kotlin JVM suite was explicitly rerun against the updated disposable Rust server
and passed. Debug and instrumentation APKs build. Emulator replay is still pending.
The earlier Nix CI failure identified formatting that is corrected in this checkpoint;
the fresh Nix/container checks and dependency audit remain separate work.

## Account, audit, and external-integration checkpoint

Added public signup, shared new-password policy, atomic account roles, native audit
recording/admin browsing, and bounded create-time URL image imports. Optional AI
provider configuration and opt-in import enhancement are wired; native OCR core is
present, with its route integration still in progress. External providers are off
by default and tests use injected transports.

Remote formatting and strict Clippy pass. The expanded Rust suite currently stops
on three audit-view assertions requiring review; subsequent suites are pending.
The Python replay-runner and health checks pass (17 tests). Real emulator replay
now reaches instrumentation, but the app process crashes; this is not acceptance
evidence. The previous pushed checkpoint e6fd81b passed CI. This is a work-in-progress
checkpoint, with full Python checks, updated translations, Android crash diagnosis,
and fresh package/image validation still pending. Production remains untouched.

## Verified Android replay and optional services

The complete Rust suite now passes 202 tests with formatting and strict Clippy.
This includes durable archival job leasing/recovery, bounded retries, preserved
accepted saves, authenticated/CSRF-protected archive and OCR routes, and optional
browser controls. Public signup is disabled by default, matching the production
policy; tests explicitly enable it where required. AI provenance survives browser
preview and creation, and the Android import client has a dedicated bounded timeout.

Actual Android Room/WorkManager replay passed on the disposable API 34 x86_64
emulator. Updated JVM tests and both APK builds pass. Python formatting, Ruff,
and mypy (195 files) pass; the complete suite passed 325 tests plus one skip before
the latest fixture-helper additions. Those helpers, replay runner, and health now
pass 23 focused tests. The CI fixture helper also starts and cleans a real isolated
Rust process without exposing its generated credentials.

A private production snapshot was copied and successfully imported into separate
Rust storage. Independent record/media verification and protected staging acceptance
are still in progress. No live database/media was connected to Rust or changed.
NixOS has an explicit Rust mode with separate storage while Python remains the module
default. Fresh Nix evaluation/build/image checks for these latest changes are pending.
The previous WIP CI failure was Python formatting in the new replay runner/tests;
that formatting has been corrected. The new KVM-backed replay workflow still needs
its first CI run. This checkpoint does not authorize production cutover.

## Live preview and runtime transition

The preview is available at [rust.anika.blue](https://rust.anika.blue), using its
own application login and a separate imported production snapshot. Public signup
and external side effects are disabled. An unrequested Authelia gate was removed;
public requests now reach the native login, and unauthenticated API requests return
401. Production continues to serve Python with separate storage and routing.

All 34 independent migration checks pass. A further 38 checks through the actual
candidate API verified copied ownership, records and original media bytes; temporary
verification tokens were removed. The protected snapshot and its verification report
remain outside all source trees. A real Tesseract OCR request on a disposable native
fixture also passed, with its fixture removed afterward.

The expanded Rust suite passed 205 tests before the final health-probe adjustment;
strict Clippy and the five CLI tests pass with the bounded local HTTP probe. The
Python reference/harness suite passes 336 tests plus one skip, with Ruff and mypy
(197 files) clean. The Docker-format image built and passed native health checks
as UID/GID 65532:65532 with a read-only root. The Nix candidate closure has 42 paths
and zero Python runtime paths.

Default package, app, image, Dockerfile, dev shell, and development run recipe now
select Rust. Explicit Python artifacts remain for reference/rollback. Fresh final
Nix/container checks are being completed after removing unused legacy frontend
assets. The KVM replay workflow's initial failure was its action executing each
script line in a separate POSIX shell; it now invokes one checked Bash script.
That final CI replay is still pending. Production cutover remains a separate decision.

## Usable styling follow-up

The Rust templates now consistently load the shared Material stylesheet and a
small Rust-scoped stylesheet. The new rules fill missing layout/component classes
for navigation, page widths, forms, cards, tables, responsive grids and readable
project text. This is a usability fix; pixel-perfect matching and the future UI
overhaul remain out of scope, as the user clarified.

The focused browser-route tests pass. Desktop and 390px mobile browser checks
show the expected bounded content width and no horizontal overflow. The new CSS
passes Biome with zero diagnostics, and statix, the full Nix flake check and the
Rust package build pass. The install step also stops preserving source read-only
permissions before pruning unused legacy assets. Existing app.css/material.css
warnings were left untouched. The styled package is ready for preview deployment.
