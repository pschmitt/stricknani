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
