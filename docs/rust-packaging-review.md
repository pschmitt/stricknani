# T119 Nix and container packaging status

## Current packaging state

The Rust server is the default flake package, app, and container runtime on the
`rust` branch. The explicit Python package and image remain available for
development and rollback. The NixOS production module still defaults to Python;
the isolated Rust mode uses separate database and media paths. This preserves
the production boundary until acceptance is complete and the user separately
approves cutover. See [the runtime transition guide](rust-runtime-transition.md)
and [NixOS module guide](rust-nixos.md).

The root [Dockerfile](../Dockerfile) builds the Rust default package into a
`scratch` image. [Dockerfile.python](../Dockerfile.python) retains the Python
image recipe used by the existing Android end-to-end workflow. The Rust
candidate image runs as UID/GID `65532:65532`, uses a dedicated working
directory, and expects staging to supply isolated persistent storage.

The Rust package check runs formatting, strict Clippy, and Rust tests. The
current package has no Python runtime dependency. A remote `nix flake check`,
Rust package build, and target system closure build passed after the latest
styled-source update. The Dockerfile image from that revision also passed
HTTP, CLI, and container health checks with a read-only root as UID/GID
65532:65532. Its 40 store paths contain no Python packages.

## Remaining packaging and acceptance work

- Keep the passing container health and Python-free closure checks as release gates.
- Keep the deployed styled candidate separated from production storage and
  routing; its desktop and mobile viewport checks have passed.
- Keep the NixOS production module's Python default until acceptance is
  complete and the user explicitly approves cutover.
- At any later cutover, use the documented final snapshot, backup, verification,
  and rollback procedure. Do not treat the branch's Rust package defaults as
  authorization to move production traffic.

## Evidence boundary

The `rust-rewrite-checkpoint.md` records the migration rehearsal, Android
replay, importer verification, CSS review, and latest remote build evidence.
Earlier candidate-package reviews and intermediate test counts in older
checkpoints describe prior implementation states; consult the current
checkpoint and T119 in [`TODO.md`](../TODO.md#L57-L66) for current status. No
CI result is claimed by this packaging note.
