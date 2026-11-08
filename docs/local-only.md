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

## Steps

1. Library and JNI entry point (`src/local.rs`, feature `android-local`),
   PAT-only mode in the router, CLI-free account bootstrap. Tests on the
   host for the bootstrap and the PAT-only router.
2. Gradle: build the library with `cargo ndk` (arm64, x86_64), package it
   under `jniLibs`, behind a build flag so CI without the NDK still builds.
3. Android: `LocalServer` (start/stop, port, token), onboarding choice,
   hidden features, settings showing "Only on this phone".
4. Emulator E2E: local mode end to end (create project with photo, restart,
   data still there, export).
5. Size check, Play declaration review, docs.
