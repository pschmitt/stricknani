# T119 Nix and container packaging review

Read-only review of the candidate outputs in the current worktree. No Nix
evaluation, build, container run, or test suite was executed.

## Findings

- **Future module switch must align the port setting.** The module currently
  remains intentionally Python-backed, but it exports `PORT` from
  `services.stricknani.port`, while Rust reads `BIND_PORT`. If the module is
  switched to Rust without changing this, a non-default configured port will
  still bind to `7674`, while the proxy and health check use the configured
  port. [nix/module.nix](../nix/module.nix#L206-L210),
  [rust/src/main.rs](../rust/src/main.rs#L8-L12)
- **Rust formatting and Clippy are not checked in CI.** `buildRustPackage`
  runs Cargo tests by default, so the flake's `rust-build` check builds and
  runs the existing Rust tests. Those tests cover the current gauge and health
  slice; the image smoke check exercises `/healthz` only. Add `cargo fmt
  --check` and Clippy to CI/flake checks, and later add API/integration tests
  for the `/api/v1/` and Android workflows before treating Rust CI as full
  acceptance evidence. [nix/rust-package.nix](../nix/rust-package.nix#L7-L21),
  [flake.nix](../flake.nix#L141-L145),
  [.github/workflows/nix.yaml](../.github/workflows/nix.yaml#L45-L55),
  [.github/workflows/nix.yaml](../.github/workflows/nix.yaml#L74-L90)

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

The candidate currently implements health and the gauge page, not the
`/api/v1/` Android contract or the remaining authenticated web workflows.
The one-time importer, isolated staging run, data verification, and cutover
runbook are still outstanding. T119.8 and T119.9 track the final build switch
and acceptance work. [docs/rust-port-decisions.md](rust-port-decisions.md#L138-L155),
[TODO.md](../TODO.md#L58-L66)

The root Dockerfile and existing production image workflow remain Python by
design for now. When acceptance is complete, switch the existing package/app,
NixOS module default, Dockerfile and GHCR release path together; remove
Python from the final runtime closure and remove obsolete HTMX-specific assets
only after the Rust web implementation no longer uses them. [Dockerfile](../Dockerfile#L1-L2),
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

## Integration review evidence (2026-10-31)

The coordinator checked the findings against the package and module sources.
In a separate remote copy of this worktree, `cargo fmt --check`,
`cargo clippy --all-targets --all-features -- -D warnings`, and
`cargo test --all-features` passed (8 tests, 0 failures). This verifies only
the existing health/static/gauge foundation; it does not establish API,
Android, migration, container, or production-cutover acceptance. Nix package
and container builds were not repeated as part of this review.
