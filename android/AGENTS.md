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
`AGENTS.md`'s Documentation Layout section. The current backend/API is Rust; older Python/FastAPI
migration references may remain in historical docs. Backend work the app depends on is tracked in
the repo-root `TODO.md` (`T-N` prefix), not here.

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
(`cd android && nix develop`). The repo-root `flake.nix` covers the Rust backend and web
application; Android tooling (JDK, Android SDK, `just`, `ktfmt`) is deliberately a separate devShell
scoped to this directory, not mixed into the root one.

## Markdown selection menus

- In the pinned Compose API, standard menu `leadingIcon` IDs can be Android theme
  attributes. Resolve them through the active theme; app drawable IDs pass through.
- Keep popup visibility separate from the touch toolbar request lifetime. Dismissal
  hides the popup, while the request stays suspended until the field cancels it;
  returning on dismiss lets layout updates reopen it immediately. Keep position
  coordinates in a plain remembered holder to avoid layout/snapshot feedback.
- After changing [MarkdownSelectionMenu.kt](app/src/main/kotlin/blue/anika/wolle/ui/common/MarkdownSelectionMenu.kt),
  verify a touch selection on a device: icons, a formatting action, outside/Back
  dismissal, and reopening with a new selection. Compilation and lint do not prove
  these platform interactions.

## Builds

- **Never run Gradle builds locally on this machine** - use an ad-hoc `rworker` (see the `rworker` skill) or a designated remote build host from
  the private `brkn-infra` skill (`just sync`, `just gradle`, `just build [variant]`, `just lint`,
  `just test`, `just check`, `just fetch`, `just build-fetch`, run from `android/`). Remote build
  directory: `~/build/stricknani-android` (see `android/justfile`).
- **`just build` does NOT copy the APK back** - it only builds remotely. If you then run
  `just <device>-install "./dist/..."` without an intervening `just fetch`, you reinstall
  whatever stale APK already happened to be in the local `./dist/` directory, with no error or
  warning that anything is wrong - `adb install -r` reports "Success" either way. Confirmed the
  hard way (2026-08-18): iterating on a real layout bug this way produced two "the fix had zero
  visual effect" false negatives in a row, burning a real debugging detour before the stale-APK
  explanation was found. Use `just build-fetch` (or `just deploy-all`, which
  call it) whenever you need the on-device result to reflect the latest source - never bare
  `just build` followed by a manual install step.
- **Debug builds via `just` are signed with the CI keystore** (`export ENABLE_DEBUG_SIGNING` in
  `android/justfile`, staged from rbw like release signing, so rbw must be unlocked), sharing the
  signing key of the rolling `latest` release's debug APKs. A debug build signed with any other key
  cannot be updated in-app and an install over it fails (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
- **ktfmt is nix-only** (SNA-69): the root `flake.nix` pre-commit hook, `just lint`/`just format`
  and CI's `android-lint` ktfmt job (Checks workflow) (shared workflow, `nix develop .#ktfmt`) all run the same
  nixpkgs `ktfmt` (kotlinlang style) from the root `flake.lock`. The app does not use the ktfmt
  Gradle plugin any more. If CI's ktfmt job fails anyway, apply its `ktfmt-diff-patch` artifact.
- CI workflows here are thin callers (`android/.github` doesn't exist - see repo-root
  `.github/workflows/`: the `android-lint` job of `checks.yaml`, `android` of `build.yaml`,
  `android-e2e`/`android-replay` of `e2e.yaml`, plus `release.yaml` (GitHub release and Google Play),
  `play-store-assets.yaml` and `android-screenshots.yaml`). The build, lint, signed release, and Play bundle lanes call
  the fleet's reusable `pschmitt/android-app-ci` workflows with `project-directory: android`,
  while E2E and screenshot capture retain their fixture-specific emulator/test orchestration. The
  shared screenshot-PR tail, JDK/Gradle setup, keystore decoding, KVM setup, and emulator wait
  helper are reused directly. The Android jobs stay path-filtered to `android/**` (plus their fixture
  inputs where applicable, through each workflow's `changes` job) so backend/web-only changes don't
  run them and vice versa. A manual dispatch of a workflow runs all of its jobs.
- E2E and replay runs cover different checks from the build and lint jobs; record each job's
  revision and result separately.

## Physical test devices

Use the fleet's approved test devices; see the private `brkn-infra` skill and shared doc for
connection details and gotchas. `just deploy-all [variant]` builds, fetches,
and installs on the configured targets. Release-build signing and Play Store
parity are still deferred (SNA-12) - this only covers debug-build device installs.

## Architecture

See `android/TODO.md`'s "Architecture summary" section for the current state of: offline-first
data flow, PAT auth (backend: `T74`/`stricknani/routes/api/`), delta-sync (`T77`), Room schema
approach, and Material 3 Expressive theming. That section is kept up to date as the app is built
out - treat it as the living architecture doc, this file as the operational/workflow one.

## Dangerous actions

Every destructive or irreversible action goes through the device-unlock guard
([DangerousActionGuard.kt](app/src/main/kotlin/blue/anika/wolle/ui/common/DangerousActionGuard.kt)):
fingerprint, face or the screen lock, per-device setting "Unlock to confirm dangerous actions"
(Settings -> Account, on by default, for every user, never synced). Examples: permanent deletion
(purge), account deletion, restoring a backup over live data, transferring ownership, revoking
tokens, unlinking logins, disabling or impersonating a user, resetting a password. Not guarded:
moving to the trash, editing, and anything an undo or a re-share trivially reverts.

- The guard runs **after** the action's own confirmation dialog, never instead of it.
- Usage, one line: `guard.confirm(R.string.unlock_reason_xxx) { performAction() }`. Add a
  lower-case reason ("delete permanently") to `values/` and `values-de/` as
  `unlock_reason_xxx`; the prompt title reads "Confirm: <reason>".
- In a ViewModel inject `DangerousActionGuard` and wrap the function that sends the request (this
  is unit-testable with `FakeDangerousActionGuard`); in a composable use
  `val guard = rememberDangerousActionGuard()`.
- A cancelled prompt does nothing and shows nothing. With the setting off, or on a device without a
  screen lock, the action just runs (after its dialog).
- Queued/offline writes: guard the user's tap, not the replay; the unlock must never gate
  `WriteReplayWorker` or sync.
- A new guarded action needs a test that it does not run when the unlock is refused.

## Notifications

- Every notification is clickable: set a content intent (`setContentIntent`) that opens the
  screen the notification is about - the project, the changed list, Settings -> About - routed
  through `MainActivity`. No notification may only inform. Cover the content intent in the
  notifier's test.
- Say what happened in the text ("Projects changed on another device"), not a vague "something
  new"; notify only for real changes, not for this device's own writes coming back.
- Web notifications follow the same rule: clicking one focuses the app and opens the same
  target.

# vim: set ft=markdown et ts=2 sw=2 :
