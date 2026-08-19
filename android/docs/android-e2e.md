# Android E2E tests

The `Android E2E` workflow uses a disposable Stricknani container and an API-34 `google_apis`
emulator. It never points the app at a production server or uses personal data.

Pull requests run `StricknaniSmokeTest`, covering onboarding with the fixture URL/token, seeded
sync, project detail navigation, and the account settings route. Start the longer
`StricknaniFullE2eTest` lane with **Actions → Android E2E → Run workflow → full**; it adds cached
browsing, search, and settings coverage. Both lanes receive the fixture URL and token only as
instrumentation arguments (`e2e_base_url` and `e2e_token`).

The fixture is defined in [`ci/stricknani/docker-compose.yml`](../../ci/stricknani/docker-compose.yml).
The workflow builds the repository image, waits for `/healthz`, runs the existing deterministic
`seed_demo` command, validates the seeded records and representative images through the API, and
mints a disposable PAT. Docker volumes are removed on every workflow exit path.

Every run uploads connected-test XML/HTML reports, named instrumentation screenshots, logcat,
fixture logs, a final emulator screenshot, and host diagnostics. For local Gradle verification use
the remote recipe from `android/`:

```sh
just e2e-build rofl-13.brkn.lol
```

The emulator lane is intentionally CI-owned; no local emulator or production endpoint is required.
