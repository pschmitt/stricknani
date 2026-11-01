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
| Existing Android v1 contract; future breaks under v2 | `projects.rs`, `yarns.rs`, `categories.rs`, `sync.rs`, `media.rs`, authentication and dedicated DTO projections. Tests cover conflicts, idempotent favorites, foreign owners, nested steps, media namespaces/aliases, token expiry, pagination and bound cursors. Version policy is in `rust-port-decisions.md`. Kotlin HTTP/JVM checks and the actual Room/WorkManager replay passed. | Core verified; final broader UI run pending |
| Fresh native data model and one-time migration | Embedded SQLx migrations plus `importer.rs`. Fixture import tests cover publication, relationships and malformed inputs. The independent read-only verifier checked the actual isolated copied snapshot: all 34 checks pass; a further 38 deployed API checks covered ownership and original media bytes. | Verified |
| Reassessed security, imports, configuration, CLI and background work | Dedicated tests for authentication, hosts/headers/cache, sanitization, SSRF redirects, bounded document subprocesses, AI/OCR, durable archive jobs and import images. Optional external services are off in staging. Operator CLI selection, API/browser alternatives and intentional differences are recorded in `rust-cli-selection.md`. | Verified |
| Nix/package/app/check/dev-shell/container use Python-free Rust | Default flake package/app/image/dev shell, root Dockerfile and NixOS module select Rust. Explicit Python reference artifacts remain separate. Fresh NixOS evaluation confirms the Rust executable and independent default storage; explicit Python still evaluates correctly. Production imports its separately pinned old Python revision. | Verified |

## Engineering and operational evidence

- The styled runtime revision `7162771` passed
  [Nix Build](https://github.com/pschmitt/stricknani/actions/runs/41300342149)
  and [Android Rust Replay](https://github.com/pschmitt/stricknani/actions/runs/41300342197).
  The Nix package's check phase runs Cargo tests, formatting and strict Clippy.
- Revision `3004996` passed [Nix Build](https://github.com/pschmitt/stricknani/actions/runs/41306167867).
  The final local-to-builder checks passed 206 Rust tests in 34 suites, strict
  Clippy, formatting, Ruff/mypy, JS/CSS/template checks, Nix lint and i18n.
  The Rust-first `CI=1 just check` also passed with 55 shipping-harness tests.
  The [native CI job at `3745154`](https://github.com/pschmitt/stricknani/actions/runs/41307787025)
  independently confirms 206 Rust tests and 55 harness tests passed.
- The full Python reference suite passed 357 tests with one skip. Its Python
  3.13 coverage was 74.23%, below the unchanged 80% reference gate; that coverage
  run is not a passing gate. The existing Python 3.15 coverage job retains the
  gate, while the Nix Python parity lane remains test-only. Default development
  commands now test Rust and its shipping tooling; explicit `test-python` and
  `check-python` retain the legacy coverage commands.
- The Dockerfile image for `dc3d78c` built and ran as UID/GID 65532:65532 with a read-only
  root. HTTP health, the native health CLI and container health passed; the
  runtime closure contains no Python paths. Disposable containers and build
  fixtures from that check were removed.
- The live preview uses native application login, disables signup and external
  side effects, and has independent database/media storage. Public root requests
  redirect to `/login`; unauthenticated API requests return 401, signup 404.
  The production application continues to use Python and separate storage/routing.
- The styled preview was updated through candidate-only infrastructure
  [commit `4780494aa`](https://github.com/pschmitt/nixos-config/commit/4780494aa);
  the system upgrade succeeded. A live anonymous browser
  check saw the four expected navigation labels and no Projects or Yarns links;
  the authenticated navigation regression and all 13 web tests passed. The preview
  uses native login, disabled signup and independent storage. Production still
  runs Python with separate storage and routing.
- The migration report and independent verifier report remain in protected
  operations storage outside every source/build snapshot. The verifier report
  was rechecked during this audit and still contains 34 passing checks.
- Dependency audit findings are explicitly retained with scoped decisions and
  review dates in `rust-security-review.md`; the dependency audit is not clean.

## Remaining acceptance

The earlier broader Android UI run
([run 41305936894](https://github.com/pschmitt/stricknani/actions/runs/41305936894))
exposed a queued yarn-photo timeout caused by an invalid IDAT CRC in its PNG.
The fixture was corrected and independently checked through the packaged API:
the original bytes return 400 without adding a photo, while the corrected bytes
return 201 and add one. The offline journey now edits a description while
preserving the shared project name for later journeys. The final broader UI run
is pending in [run 41311343609](https://github.com/pschmitt/stricknani/actions/runs/41311343609);
the paired lint and build runs are also pending:
[41311346438](https://github.com/pschmitt/stricknani/actions/runs/41311346438) and
[41311349505](https://github.com/pschmitt/stricknani/actions/runs/41311349505).
The fixture helper masks generated credentials only in GitHub Actions; its
startup-boundary tests and mandatory health tests pass (8 and 4 tests).
The CLI
inventory records the intentional command-surface changes; generated project PDF
export is replaced by non-equivalent browser print/save, and that limitation is
retained explicitly rather than claimed as parity.

Final production write-freeze, fresh snapshot, import/verification, same-origin
routing and rollback procedures are documented in `rust-migration-plan.md`.
They are conditional future operations requiring the user's explicit approval;
this audit does not execute or authorize them.

The final broader UI, lint, and build workflow results remain pending as listed
above.
