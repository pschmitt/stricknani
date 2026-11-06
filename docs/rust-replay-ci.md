# Rust backend Android replay CI

The `Android Replay` workflow runs the app's Room replay instrumentation
against a disposable build of the Rust server. It runs on pushes to
`main` when Android, Rust, the replay harness (`web/e2e/`, `ci/run-*.sh`), or workflow inputs change. It can
also be started with `workflow_dispatch` from GitHub Actions.

The job builds the Rust server with the `stricknani` flake package, then
builds the debug app and instrumentation APKs before starting an x86_64 Android
emulator. It starts the server on loopback with a fresh SQLite database and
media directory under the runner's temporary directory. AI, OCR, external
imports, and Wayback are disabled. The instrumentation runner selects exactly
one ready emulator and runs only
`RustBackendReplayIntegrationTest`.

The fixture creates a random account for that run. The password is passed to
the server over standard input. Both account values are masked in GitHub Actions
logs and stored only in a mode-0600 JSON file outside the checkout. The replay
helper reads that file directly; it does not export credentials to the process
environment or put them in command arguments. The file, private server
directory, database, and server process are removed in the workflow's cleanup
step. No fixture credentials, server logs, screenshots, or raw logcat are
uploaded. On failure, the workflow retains only the replay runner's bounded,
sanitized instrumentation diagnostic.

The small fixture helper (`npm --prefix web run fixture -- start|stop|run-replay`) can be
exercised without Nix, Gradle, an emulator, or network access with:

```sh
npm ci --prefix web
npm --prefix web test
```
