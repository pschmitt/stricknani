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
captured when the test fails, so a broken later state does not discard earlier evidence. Passing
runs can optionally open/update a review PR that places the captures in the Fastlane-compatible
Play Store screenshot buckets.

Run it from GitHub Actions with **Android screenshots → Run workflow**. The workflow uploads the
screenshots, connected-test XML/HTML reports, logcat, fixture logs on failure, and host/emulator
diagnostics as separate artifacts. With `open_pr=true`, the successful light-theme captures are
also consolidated into the three Fastlane-compatible screenshot buckets for review; dark-theme
captures remain available in the diagnostic artifacts because Play allows at most eight images per
device bucket.

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

When the workflow is dispatched with `open_pr=true`, it uses the shared screenshot PR tail to
commit only the three device buckets. Review and merge that PR before running **Play Store Assets**;
that asset workflow is separate and remains dry-run-only unless its explicit upload input and the
repository-level `PLAY_PUBLISH_ENABLED=true` gate are both present.
