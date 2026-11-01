# T119 acceptance audit

This audit follows the current rewrite brief, including its separate production
cutover approval boundary. A passing build alone does not establish product
acceptance. The implementation lives on branch `rust`; unrelated changes in the
primary checkout and the user's uncommitted `REWRITE.md` edits are preserved.

## Required outcomes

| Brief requirement | Implementation and verification | Status |
| --- | --- | --- |
| Standalone native Rust service; no Python bridge | `rust/Cargo.toml`, `rust/src/main.rs`, Axum/Tokio/SQLx/Askama modules. The default package and Docker runtime contain no Python. | Verified |
| Useful web workflows; replaceable presentation | `rust/src/web.rs`, `web_accounts.rs`, `web_signup.rs`; separate Askama templates and `static/css/rust.css`. Router integration tests exercise CRUD, steps, categories, favorites, accounts, media, validation, CSRF and ownership. Live desktop/mobile styling checks passed. Offline stylesheet precaching was corrected and its six PWA tests pass. | Verified |
| Existing Android v1 contract; future breaks under v2 | `projects.rs`, `yarns.rs`, `categories.rs`, `sync.rs`, `media.rs`, authentication and dedicated DTO projections. Tests cover conflicts, idempotent favorites, foreign owners, nested steps, media namespaces/aliases, token expiry, pagination and bound cursors. Version policy is in `rust-port-decisions.md`. Kotlin HTTP/JVM checks and the actual Room/WorkManager replay passed. | Verified core; broader UI run failed and is being diagnosed |
| Fresh native data model and one-time migration | Embedded SQLx migrations plus `importer.rs`. Fixture import tests cover publication, relationships and malformed inputs. The independent read-only verifier checked the actual isolated copied snapshot: all 34 checks pass; a further 38 deployed API checks covered ownership and original media bytes. | Verified |
| Reassessed security, imports, configuration, CLI and background work | Dedicated tests for authentication, hosts/headers/cache, sanitization, SSRF redirects, bounded document subprocesses, AI/OCR, durable archive jobs and import images. Optional external services are off in staging. Operator CLI selection, API/browser alternatives and intentional differences are recorded in `rust-cli-selection.md`. | Verified |
| Nix/package/app/check/dev-shell/container use Python-free Rust | Default flake package/app/image/dev shell and root Dockerfile select Rust. Explicit Python reference artifacts remain separate. NixOS Rust mode has separate paths; the production module default remains Python until separately approved cutover. | Verified |

## Engineering and operational evidence

- The styled runtime revision `7162771` passed
  [Nix Build](https://github.com/pschmitt/stricknani/actions/runs/41300342149)
  and [Android Rust Replay](https://github.com/pschmitt/stricknani/actions/runs/41300342197).
  The Nix package's check phase runs Cargo tests, formatting and strict Clippy.
- The expanded Rust suite had 205 tests before the last health-probe change;
  the subsequent complete Nix package check passed with that change included.
  The Python reference/harness suite passed 336 tests with one skip, Ruff and
  mypy. The newer API seeder/fixture/health checks passed 24 tests and a real
  disposable Rust HTTP smoke test.
- The Dockerfile image for `7162771` built and ran as UID/GID 65532:65532 with a
  read-only root. HTTP `/healthz`, the native health CLI and container health
  passed. Its 40 store paths contained no Python packages. Disposable containers,
  image tag and build fixtures from that check were removed.
- The live preview uses native application login, disables signup and external
  side effects, and has independent database/media storage. Public root requests
  redirect to `/login`; unauthenticated API requests return 401, signup 404.
  The production application continues to use Python and separate storage/routing.
- The migration report and independent verifier report remain in protected
  operations storage outside every source/build snapshot. The verifier report
  was rechecked during this audit and still contains 34 passing checks.
- Dependency audit findings are explicitly retained with scoped decisions and
  review dates in `rust-security-review.md`; the dependency audit is not clean.

## Remaining acceptance

The broader Android UI run reached instrumentation but failed without exposing
the failing assertion. Bounded, sanitized JUnit diagnostics are being added before
the next run. Final CI must cover the offline precache correction too. The CLI
inventory records the intentional command-surface changes; generated project PDF
export is replaced by non-equivalent browser print/save, and that limitation is
retained explicitly rather than claimed as parity.

Final production write-freeze, fresh snapshot, import/verification, same-origin
routing and rollback procedures are documented in `rust-migration-plan.md`.
They are conditional future operations requiring the user's explicit approval;
this audit does not execute or authorize them.
