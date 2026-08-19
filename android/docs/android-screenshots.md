# Android screenshot capture

The manual `Android screenshots` workflow captures review-only screenshots from a disposable,
seeded Stricknani fixture. It runs a dedicated instrumentation test in six emulator jobs:

- phone, light theme
- phone, dark theme
- 7-inch tablet, light theme
- 10-inch tablet, light theme
- 7-inch tablet, dark theme
- 10-inch tablet, dark theme

Each job captures named `PNG` states for onboarding, home, project list, project detail, yarn list,
yarn detail, settings, and the offline cached-data feedback state. A final emulator frame is also
captured when the test fails, so a broken later state does not discard earlier evidence.

Run it from GitHub Actions with **Android screenshots → Run workflow**. The workflow uploads the
screenshots, connected-test XML/HTML reports, logcat, fixture logs on failure, and host/emulator
diagnostics as separate artifacts. The files are intentionally not committed or uploaded to the
Play Console: Stricknani has no store publishing action, credentials, or asset-review PR wired into
this lane.

The screenshot test uses the same deterministic fixture records as `android-e2e.yaml`:
`Heirloom Baby Blanket` provides a project detail and `Riverbend Merino DK` provides a yarn detail.
The offline state is produced by enabling emulator airplane mode, pulling to refresh Home, and
capturing the cached-data snackbar before restoring connectivity.

The repository cannot execute this lane on the development host. Android Gradle validation must be
run remotely through the Android justfile, for example:

```sh
cd android
just e2e-build rofl-13.brkn.lol
```

That remote recipe compiles the APKs only; the phone/tablet emulator matrix is GitHub Actions-owned.
In particular, no physical device is a substitute for the tablet profiles or the deterministic
light/dark matrix. A workflow run is therefore required to verify actual emulator rendering.
