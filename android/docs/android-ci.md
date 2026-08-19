# Android CI workflow boundaries

The Android Gradle project lives in `android/` below the repository root. The hosted workflows
therefore keep only Stricknani-specific triggers and parameters; shared build logic comes from
[`pschmitt/android-app-ci`](https://github.com/pschmitt/android-app-ci).

## Shared lanes

- **Android Build** calls the shared debug build workflow. Unit tests, the four ABI APK artifacts,
  and their paths are supplied relative to `android/`.
- **Android Lint** calls the shared ktfmt/Lint workflow. Auto-fix commits remain disabled; a
  failing ktfmt check still produces the shared `ktfmt-diff-patch` artifact.
- **Release** calls the shared signed APK workflow, including version/revision checksums,
  provenance, GitHub Release publication, and tagged-release screenshot dispatch.
- **Play Store Release** keeps Stricknani's metadata/privacy/production-confirmation validation,
  then calls the shared signed AAB workflow. Publication remains gated by both the manual input
  and `PLAY_PUBLISH_ENABLED=true`.

The reusable workflows interpret Gradle task and output paths relative to `project-directory`,
then resolve uploaded artifacts against the repository workspace. This is the monorepo-specific
contract added upstream for Stricknani and the other apps that may need a nested project.

## App-specific lanes

**Android E2E** and **Android screenshots** retain their local fixture lifecycle, Docker Compose
seeding, instrumentation selection, screenshot naming, and diagnostics. They reuse the shared JDK,
KVM, emulator-wait, and screenshot-PR helpers where the action contract fits. The Play Store Assets
workflow also remains local: it validates Stricknani's declarations and uploads the icon,
feature graphic, and reviewed screenshots through the app-specific script, whereas the shared asset
workflow only delegates a screenshots-only `just` recipe.

Gradle is not run locally. Use the remote `android/justfile` check, and use GitHub Actions for the
hosted emulator and release lanes.
