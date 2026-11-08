# Local-only Android mode (T130)

The Android app can work without a Stricknani server: the app starts the
Rust server itself, on loopback, with its database and media in app storage.
The rest of the app (sync, offline queue, media, history, timers) stays as it
is; it talks to `http://127.0.0.1:<port>` instead of a remote server.

## Spike results (2026-11-08)

- `stricknani-server` cross-compiles for `aarch64-linux-android` (API 26) with
  NDK r28c and `cargo ndk`, without code changes. No C dependency needed
  patching; SQLite is bundled, TLS is rustls.
- Size: 40.9 MB unstripped, 27.6 MB stripped, 11.5 MB gzip (about what the
  APK adds per ABI). `opt-level = "z"` and LTO are not tried yet.
- On the Zenfone 10 (Android 15), started from `adb shell` with only
  `STRICKNANI_DATABASE_URL`, `STRICKNANI_MEDIA_ROOT`, `BIND_HOST=127.0.0.1` and
  `BIND_PORT`: migrations ran, `/healthz` and `/api/v2/meta` answered,
  `create-user` wrote the account. RSS 17 MB, 11 threads.
- Cookie login answers 403 without the login CSRF cookie, as designed. The
  native client needs a PAT, which the local mode mints itself (below).

## Design

- **Packaging.** The server becomes a library (`cdylib`) with a small JNI
  entry point (`start(dataDir, port) -> token`, `stop()`), loaded by the app
  and run on its own Tokio runtime in the app process. Android 10+ does not
  allow executing files from app data directories, and an in-process server
  has no child process to supervise. The `stricknani-server` binary stays
  unchanged; both share `src/lib.rs`.
- **Security.** Any app on the device can connect to a loopback port. The
  local server therefore accepts only Bearer PAT requests: no cookie login,
  no sign-up, no OIDC, no web UI (`WEB_ROOT` unset). The PAT is created on
  first start and kept in the app's private storage.
- **Account.** One local account, created on first start (random password,
  never shown); the app gets a PAT for it directly through the library.
- **Features.** Available: projects, yarns, categories, media and thumbnails,
  history, timers, offline queue, backups (T129, already includes data).
  Hidden: sharing, admin, sign-in/account management, web push, OCR
  (no tesseract), URL and AI imports (they need the network and server-side
  credentials; URL imports could come back later).
- **Onboarding.** A choice on the first screen: "Use a server" or "Only on
  this phone". The local mode can later move to a server through the
  existing export and restore (T129).
- **ABIs.** arm64-v8a first; x86_64 for the emulator in CI. armeabi-v7a
  only if a real device needs it.

## Status

- Done: the server as a library with JNI entry points (`src/local.rs`, `src/local_jni.rs`),
  Bearer-only router, account and PAT bootstrap (`me@stricknani.local`), host tests.
- Done: Android `LocalServer` (started on demand before any request), two-step onboarding with
  "Only on this phone", local mode hides imports, sharing and server account management, and
  offers "Use a server instead" (signs out; the local data stays in `filesDir/local-server`).
- Done: CI builds the library for arm64-v8a, armeabi-v7a and x86_64 (`release.yaml`,
  `local-server-libs` job) and packages it into the APKs and the Play bundle. Locally:
  `cargo ndk -t arm64-v8a --platform 26 rustc --release --lib --crate-type cdylib`, then pass the
  jniLibs-style directory as `-PlocalServerLibs=<dir>` or put it at `android/local-server-libs`.
- Open: an emulator E2E journey for local mode (the E2E workflow does not build the library
  yet), a path to move local data to a server other than export and restore, and the APK size
  (about +11 MB per ABI).
