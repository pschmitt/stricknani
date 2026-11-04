<p align="center">
  <img src="../branding/icon.svg" alt="Stricknani icon" width="128">
</p>

<h1 align="center">Stricknani</h1>

**A Material You, offline-first Android client for your own [Stricknani](../README.md) server.**

The Stricknani Android app connects to a self-hosted Stricknani instance and syncs your knitting
projects and yarn stash for offline-first browsing and editing. No account beyond your own
Stricknani login, no ads, no tracking - your data stays between your phone and your own server.

The app is under active development. Project/yarn browsing, offline queued edits and Rust-backend
integration are implemented; see [TODO.md](TODO.md) for verified milestones and remaining work.

## Features (see TODO.md for status)

- Home dashboard, project and yarn stash browsing/search, category and tag filters
- Create, edit, and delete projects and yarn stash entries, fully offline via a local write queue
- Offline-first: cached content stays fully usable with zero connectivity once synced - a network
  call is only ever a best-effort background refresh, never a requirement for browsing saved data
- Full Material You theming (dynamic, wallpaper-derived color on Android 12+)
- Gauge calculator, ported natively (no network dependency)

## Rust backend and new website

Android uses the Rust backend's token-based API v1 and retains its offline cache and queued edits.
The website's browser API v2 is a separate interface. New website links at `/app/projects/{id}` and
`/app/yarns/{id}` can open the corresponding native detail page; legacy resource links remain
supported. Links use the app's configured server and do not switch accounts.

See [the compatibility plan](docs/rust-api-transition.md) for the API, sync and link transition.

## Installation

### Google Play (closed testing)

Stricknani publishes automatically to Play's internal/closed-testing tracks on every tagged
release. Join the testing program, then install and auto-update via the Play Store:

[Join the Play Store closed test](https://play.google.com/apps/testing/blue.anika.wolle)

See [the Play Store release runbook](docs/play-store-release.md) for the one-time Console setup,
metadata checklist, staged internal-testing flow, and rollback procedure.

### APK / Obtainium

Prefer not to use Play, or want a specific build without joining testing? Install and auto-update
via [Obtainium](https://obtainium.imranr.dev/) pointed at this repository, or grab an APK directly
from the [Releases page](https://github.com/pschmitt/stricknani/releases).

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60">][obtainium-link]

The badge tracks the `release` APK (applicationId `blue.anika.wolle`, no `.debug` suffix, arch
auto-selected) from the ["latest"](https://github.com/pschmitt/stricknani/releases/tag/latest)
pre-release build. Want the debuggable variant instead? Add the app normally in Obtainium via this
repo's URL and adjust the APK filter regex/prerelease settings, or just grab the specific APK from
the [Releases page](https://github.com/pschmitt/stricknani/releases).

Sideloaded APK installs also check GitHub Releases themselves: when a newer build exists, Home
shows an "Update available" card and About a version row, each with an **Update** button. Tagged
builds follow the newest stable release, `latest` builds follow the `latest` pre-release.

**Update channel (About, GitHub builds only).** *Stable* (default) follows the newest `vX.Y.Z`
release by semver; *Latest* follows the rolling `latest` pre-release, compared by the commit its
body names. Switching from Latest to Stable never downgrades: a build ahead of the newest stable
reports "You're ahead of the latest stable release" until a newer stable exists.

**In-app update (release APKs only).** **Update** downloads the APK for your device's ABI from this
repository's GitHub release over HTTPS, checks it against the release's `SHA256SUMS`, checks that it
is the same package signed with the same certificate and not older than the installed version, and
installs it through Android's `PackageInstaller`. The first update asks once in the system dialog
(and for the "Install unknown apps" permission); because Stricknani then is its own installer of
record, later updates install without a confirmation on Android 12+. Errors (checksum mismatch,
failed download, low storage, installer failure) are shown with **Try again** and **Download in
browser**. **Update automatically** (About, off by default) checks twice a day and installs while the
phone is charging and idle, only once silent installs are possible, never in battery saver. Shizuku
is not supported (see `TODO.md` SNA-88). Debug builds (`blue.anika.wolle.debug`) keep the browser
download, since a release APK is a different package.

**Play Store build.** It includes none of this: the check and updater are compiled out when
building the bundle, and the `REQUEST_INSTALL_PACKAGES` permission and updater receivers live in
`app/src/selfupdate/AndroidManifest.xml`, which is merged into non-bundle release builds only. The
store updates those installs.

**Error reporting (GitHub APK builds only).** When the server's operator configures Sentry, Account
settings shows **Send error reports** (on by default; turning it off also stops it at once and is
saved to the account, so the web app follows). The app has no DSN of its own: it reads the public DSN
from `GET /api/v2/meta` and reports through the server's tunnel, so Sentry never sees the phone. It
sends errors only (message, stack trace, app version, device and OS), tagged `component:android`,
with tokens, emails and queries redacted; the on-device crash report is unchanged. The Play bundle
contains none of this (no SDK, no toggle), so its data safety declaration stays "no data collected";
`-PerrorReporting=true|false` overrides the build detection like `-PselfUpdate`.

[obtainium-link]: https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22blue.anika.wolle%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpschmitt%2Fstricknani%22%2C%22author%22%3A%22pschmitt%22%2C%22name%22%3A%22Stricknani%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22.%2A-release%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22trackOnly%5C%22%3Afalse%7D%22%7D

Managing apps declaratively with [declaroid](https://github.com/pschmitt/declaroid) instead? Add:

```yaml
- name: Stricknani
  pkg: blue.anika.wolle
  store: github
  repo: pschmitt/stricknani
```

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

Hosted Android CI uses thin wrappers around the shared
[`pschmitt/ci-workflows`](https://github.com/pschmitt/ci-workflows) workflows for build, lint,
signed release, and Play bundle publication. Since this is a monorepo, those callers pass
`project-directory: android`; APK/AAB/report paths remain relative to that directory. E2E and
screenshot capture stay local because their disposable Stricknani fixture and emulator journeys
are app-specific. See [the Android CI notes](docs/android-ci.md) for the lane boundaries and
artifact contracts.

See [AGENTS.md](AGENTS.md) for the full dev environment, build, and contribution conventions, and
the fleet-wide shared doc it references (`.just/android-app-ci/AGENTS-shared.md`).

This project is licensed under [GPL-3.0](../LICENSE), matching the Stricknani web app.
