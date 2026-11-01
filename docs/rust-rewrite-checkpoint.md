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
