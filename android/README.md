# Stricknani

**A Material You, offline-first Android client for your own [Stricknani](../README.md) server.**

The Stricknani Android app connects to a self-hosted Stricknani instance and syncs your knitting
projects and yarn stash for offline-first browsing and editing. No account beyond your own
Stricknani login, no ads, no tracking - your data stays between your phone and your own server.

This app is early-stage: this is currently a repo scaffold and Compose shell (placeholder screens
only), not yet a usable app. See [TODO.md](TODO.md) for the full build-out plan and current
status (`SNA-N` entries).

## Planned features (see TODO.md for status)

- Home dashboard, project and yarn stash browsing/search, category and tag filters
- Create, edit, and delete projects and yarn stash entries, fully offline via a local write queue
- Offline-first: cached content stays fully usable with zero connectivity once synced - a network
  call is only ever a best-effort background refresh, never a requirement for browsing saved data
- Full Material You theming (dynamic, wallpaper-derived color on Android 12+)
- Gauge calculator, ported natively (no network dependency)

## Connecting to your Stricknani server

The app never asks for or stores your Stricknani account password. Instead (once SNA-6 lands):

1. In Stricknani's web UI, go to your user menu -> API Tokens and generate a token.
2. In the app, enter your server's URL and paste that token.

The server URL is never hardcoded into the app - it works with any Stricknani instance you point
it at.

## Development

Gradle builds intentionally run on `rofl-13` or `rofl-14`, not on the local workstation:

```sh
cd android
just check
just build debug
```

`just check` runs ktfmt checks, unit tests, and Android Lint remotely. `just build-fetch debug`
builds remotely and copies the debug APK to `./dist`. The debug application id is
`blue.anika.wolle.debug` (package `blue.anika.wolle` - a separate, personal namespace from the
"Stricknani" app/product name, chosen before this repo existed).

`just deploy-all [variant]` builds, fetches, and installs on the fleet's shared physical test
devices (Zenfone 10, Mi Pad 4, Pixel 5) - see [AGENTS.md](AGENTS.md).

See [AGENTS.md](AGENTS.md) for the full dev environment, build, and contribution conventions, and
the fleet-wide shared doc it references (`.just/android-app-ci/AGENTS-shared.md`).

This project is licensed under [GPL-3.0](../LICENSE), matching the Stricknani web app.
