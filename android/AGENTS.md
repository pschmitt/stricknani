# AGENTS.md

Repository instructions for AI coding agents working in `android/` - the Stricknani Android app, a
native offline-first client for the Stricknani web app (`stricknani/`, one directory up).

See `.just/android-app-ci/AGENTS-shared.md` for the fleet-wide task-tracking convention, dev
environment (`nix develop`/`git-hooks.nix`), CI-is-the-sole-lint-authority rule, and physical test
device docs (this app shares the fleet's devices - see "Physical test devices" below) - read it
alongside this file, not instead of it.

## Project shape

The Stricknani Android app is a Kotlin/Jetpack Compose app: a Material You, **offline-first**
client for a self-hosted [Stricknani](../README.md) server (knitting projects and yarn stash).
Package `blue.anika.wolle` (a separate, personal namespace predating this repo - not renamed to
match the app/product name), debug applicationId `blue.anika.wolle.debug`, GPL-3.0 (matches the
repo-root `LICENSE`). Single `:app` Gradle module - this app doesn't need a multi-module split.

**Unlike the sibling apps (syncwich, nyetbox, jollyfin, augh), this one lives inside the backend's
own repo** (`stricknani.git/android/`) rather than a dedicated repo of its own - see root
`AGENTS.md`'s Documentation Layout section. Backend API work the app depends on lands in
`stricknani/` (Python/FastAPI), tracked in the repo-root `TODO.md` (`T-N` prefix), not here.

## Task tracking

- This project's `TODO.md` prefix is `SNA-N`. It lives at `android/TODO.md`, not the repo-root
  `TODO.md` (that one's for the web app, `T-N` prefix) - see that file's own header for how the
  two relate (a handful of `SNA-N` entries are backend work with a matching `T-N` counterpart).
- Any user message that starts with `todo: ` (case-insensitive) is a direct instruction to add a
  new `SNA-N` entry to `android/TODO.md` for whatever follows the prefix, rather than acting on it
  immediately - file the backlog entry (`not started`, with a checklist inferred from the ask) and
  confirm back to the user, instead of implementing it in that turn.
- Keep `android/README.md` aligned with current user-facing behavior, setup instructions, and
  release process, the same way the repo-root `README.md` works for the web app.

## Dev environment

See the shared doc for the `nix develop`/`git-hooks.nix` basics, run from within `android/`
(`cd android && nix develop`). The repo-root `flake.nix` covers the Python web app only - Android
tooling (JDK, Android SDK, `just`, `ktfmt`) is deliberately a separate devShell scoped to this
directory, not mixed into the root one.

## Builds

- **Never run Gradle builds locally on this machine** - always build on `rofl-13.brkn.lol` or
  `rofl-14.brkn.lol` instead (`just sync`, `just gradle`, `just build [variant]`, `just lint`,
  `just test`, `just check`, `just fetch`, `just build-fetch`, run from `android/`). Remote build
  directory: `~/build/stricknani-android` (see `android/justfile`).
- See the shared doc for the CI-lint-authority rule and `ktfmt-diff-patch` retrieval procedure.
- CI workflows here are hand-written (`android/.github` doesn't exist - see repo-root
  `.github/workflows/android-*.yaml`), not the fleet's reusable `lint.yaml`/`build.yaml` called
  directly: those reusable workflows assume the Gradle project sits at the repo root, which isn't
  true in a monorepo. The workflows here reuse only the shared
  `pschmitt/android-app-ci/.github/actions/setup-jdk-gradle` composite action (which is
  cwd-agnostic) and run `./gradlew` with `working-directory: android` themselves, path-filtered to
  `android/**` so Python-only changes don't trigger an Android CI run and vice versa.

## Physical test devices

Same fleet hardware as the sibling apps - Zenfone 10 / Mi Pad 4 / Pixel 5, see the shared doc for
connection details/gotchas. `android/justfile` has the same device identifiers committed as
defaults (`zenfone_serial`, `mipad_host`, `px5_host`); `just deploy-all [variant]` builds, fetches,
and installs on all three. Confirmed working live 2026-08-18. Release-build signing and Play Store
parity are still deferred (SNA-12) - this only covers debug-build device installs.

## Architecture

See `android/TODO.md`'s "Architecture summary" section for the current state of: offline-first
data flow, PAT auth (backend: `T74`/`stricknani/routes/api/`), delta-sync (`T77`), Room schema
approach, and Material 3 Expressive theming. That section is kept up to date as the app is built
out - treat it as the living architecture doc, this file as the operational/workflow one.

# vim: set ft=markdown et ts=2 sw=2 :
