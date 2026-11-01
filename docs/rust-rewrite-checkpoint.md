# Rust rewrite checkpoint — 2026-11-01

This is an implementation checkpoint, not a production release. Production
traffic and storage remain on Python. Rust package, app, image, and NixOS-module
defaults on this branch do not authorize production cutover. See `REWRITE.md`
and T119 in `TODO.md` for the separate approval boundary.

## Implementation acceptance evidence

- On revision `2cd090e`, the native check
  [41315290376](https://github.com/pschmitt/stricknani/actions/runs/41315290376)
  passed 207 Rust tests across 34 targets and 58 shipping-harness tests. The
  native test job ran; the legacy `test` and `test-nix` jobs were skipped by
  workflow filters.
- Android Room/WorkManager replay passed against a disposable Rust fixture in
  [41315290345](https://github.com/pschmitt/stricknani/actions/runs/41315290345).
  ktfmt and Android Lint passed in
  [41315352294](https://github.com/pschmitt/stricknani/actions/runs/41315352294).
  Android unit tests, Gradle build, and four APK artifact uploads passed in
  [41315355350](https://github.com/pschmitt/stricknani/actions/runs/41315355350).
- Full Android UI workflow
  [41315348917](https://github.com/pschmitt/stricknani/actions/runs/41315348917)
  passed after autoSmoke
  [41315290332](https://github.com/pschmitt/stricknani/actions/runs/41315290332).
  Both `connectedDebugAndroidTest` invocations succeeded, covering
  `StricknaniFullE2eTest` and `StricknaniLiveWriteE2eTest`. The workflow emitted
  no instrumentation count summary, so none is claimed. The CI watcher reported
  all six runs for `2cd090e` successful.
- The full Python reference suite passed 357 tests plus one skip. Python 3.13
  coverage was 74.23%, below the unchanged 80% reference gate; that run is not a
  passing coverage gate. The Python 3.15 coverage job retains the threshold.
- The copied-data migration rehearsal passed 34 independent verification checks
  and 38 candidate API checks, including ownership, records, relationships, and
  original media bytes. No live production database or media was used.
- The Docker image built from `dc3d78c` passed HTTP, CLI, and container health
  checks as UID/GID 65532:65532 with a read-only root; the runtime closure had no
  Python paths. Optional external integrations were disabled and disposable
  state was removed. A real OCR request also passed against a disposable fixture.
- The styled preview is deployed through candidate-only infrastructure
  [commit `4780494aa`](https://github.com/pschmitt/nixos-config/commit/4780494aa);
  the system upgrade succeeded. Anonymous navigation showed four expected
  labels and hid Projects/Yarns. Authenticated navigation and all 13 focused web
  tests passed. The preview uses native login, disabled signup, and separate
  database/media storage. Production continues to use Python with separate
  storage and routing.
- Earlier Nix build, full flake, strict Clippy, formatting, i18n, CSS/browser,
  and Rust package evidence is summarized in
  [`rust-acceptance.md`](rust-acceptance.md). Dependency findings remain scoped
  and dated in `rust-security-review.md`; the dependency audit is not clean.

The earlier full Android UI attempts exposed a malformed PNG IDAT CRC and an
editor visibility timeout. The corrected photo fixture and offline edit were
verified in the successful run above; the original PNG returns 400 without
adding a photo, and the corrected PNG returns 201. Assertions and timeouts were
retained. Fixture startup tests confirm generated credentials are not printed
locally and are masked in GitHub Actions (8 fixture tests and 4 health tests).

The CLI inventory records intentional differences: domain automation uses the
authenticated API/browser rather than a second direct-database CLI, and browser
print/save is not equivalent to the legacy generated PDF. These differences are
not hidden as parity claims.

## Production boundary

Implementation acceptance evidence is complete for this checkpoint. The copied
snapshot migration rehearsal is complete, but a final production snapshot,
write freeze, fresh import, routing switch, and rollback have not been executed.
The runbook is in `rust-migration-plan.md`. Production cutover requires the
user's separate explicit approval; until then production remains on Python.
