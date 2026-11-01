# T119 Nix and container packaging review

Source review and candidate-image hardening in the current worktree. No Nix
evaluation, build, container run, or test suite was executed for this update.

## Findings

- **The existing NixOS module remains Python-backed.** It exports `PORT` from
  `services.stricknani.port`; the Rust configuration now accepts `PORT` as a
  fallback when `BIND_PORT` is unset. The earlier mismatch finding is
  resolved, but the module/package default must still be switched together
  after Rust acceptance. [nix/module.nix](../nix/module.nix#L206-L210),
  [rust/src/config.rs](../rust/src/config.rs#L82-L90)
- **Rust format and Clippy checks are wired into the package check.**
  `buildRustPackage` runs `preCheck`, which executes `cargo fmt --check` and
  offline Clippy with warnings denied before its default Cargo test phase. The
  `rust-build` flake check therefore covers these checks and Rust tests.
  [.github/workflows/nix.yaml](../.github/workflows/nix.yaml#L36-L40),
  [flake.nix](../flake.nix#L141-L145),
  [nix/rust-package.nix](../nix/rust-package.nix#L15-L25)
- **The Rust workflow runs on pushes to `rust`.** The Nix workflow's push
  trigger includes both `main` and `rust`, with relevant Rust, Nix, flake, and
  container files in its path filter. Its candidate-image smoke check verifies
  `/healthz`; this alone does not establish full product or staging
  acceptance. [.github/workflows/nix.yaml](../.github/workflows/nix.yaml#L4-L26),
  [.github/workflows/nix.yaml](../.github/workflows/nix.yaml#L54-L90)
- **The Rust candidate OCI image now runs as an unprivileged numeric user.**
  Its image config sets `User = "65532:65532"` and a dedicated working
  directory. `fakeRootCommands` creates `/var/lib/stricknani`, assigns it to
  that UID/GID, and makes `/tmp` mode `1777`. The image intentionally does not
  set database or media locations: staging must supply explicit paths on
  dedicated isolated storage. This source change is pending the coordinator's
  remote image build and runtime check; no check is claimed for this update.
  [flake.nix](../flake.nix#L60-L77)

## State and remaining transition work

The current output split is appropriate for this stage: `default`,
`stricknani`, `stricknani-docker`, and the NixOS module default still use the
Python package, while Rust has separately named candidate package, app,
check, development shell, and image outputs. Keep that split until the Rust
product and Android/browser acceptance surface is complete. The candidate
package itself is a Rust binary plus static assets and has no Python runtime
dependency. [flake.nix](../flake.nix#L55-L92),
[nix/rust-package.nix](../nix/rust-package.nix#L7-L21),
[nix/module.nix](../nix/module.nix#L93-L97)

The candidate has advanced beyond the earlier health-and-gauge-only snapshot.
The coordinator reports 51 Rust tests and formatting/Clippy passing, the
Kotlin Retrofit HTTP contract and full Android JVM suite passing, and the Rust
candidate flake check, image build, and Podman health check passing. The
coordinator also reports that the inspected runtime closure has eight paths
and no Python. These are integration results reported by the coordinator, not
commands rerun for this document update. They do not establish the one-time
importer, isolated staging run with protected production data, migration data
verification, or cutover runbook. T119.8 and T119.9 track the final build
switch and acceptance work. [docs/rust-port-decisions.md](rust-port-decisions.md#L138-L155),
[TODO.md](../TODO.md#L58-L66)

The root Dockerfile and existing production image workflow remain Python by
design for now. A root Dockerfile build is currently being monitored by the
coordinator; no result is asserted here. When acceptance is complete, switch
the existing package/app, NixOS module default, Dockerfile and GHCR release
path together; remove Python from the final runtime closure and remove obsolete
HTMX-specific assets only after the Rust web implementation no longer uses
them. [Dockerfile](../Dockerfile#L1-L2),
[docs/rust-port-decisions.md](rust-port-decisions.md#L125-L136)

## Checks required before final switch

- Run `nix flake check`, build the final Nix package and container, and verify
  the image starts and passes its health check.
- Run Rust formatting, Clippy, unit/integration tests, Android checks, and
  test the current Kotlin client against the Rust `/api/v1/` implementation.
- Verify the final runtime dependency closure has no Python interpreter or
  Python application dependencies.
- Exercise the one-time importer against a protected, separate copy of
  production DB and media; verify records, relationships, ownership, and media
  references. Document backup, final write-freeze/snapshot, and rollback.
- Keep candidate DB, media, credentials, jobs, and routing isolated from
  production. Deploy the candidate only to its separate staging endpoint.
  Production remains on Python until acceptance is complete and the user
  explicitly approves cutover. At cutover, import a final post-freeze snapshot
  and verify it before routing the existing production URL to Rust.

## Integration review evidence (2026-11-01)

The coordinator checked the current package, port configuration, and workflow
sources. The coordinator reports 51 Rust tests, formatting and Clippy,
Retrofit contract tests, the full Android JVM suite, `nix flake check`, the
Rust image build and Podman health check passing; it also reports an eight-path
runtime closure without Python. The coordinator is monitoring the root
Dockerfile build. No CI watch was started or polled for this document update.
These reports do not cover the importer, staging data validation, or
production cutover, and production defaults remain Python pending acceptance.

The candidate-image hardening above has not been built or run in this update.
The coordinator will validate it remotely. No Nix evaluation, build, container
run, or test suite was run here.
