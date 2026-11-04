# Android E2E tests

The `Android E2E` workflow uses a disposable Stricknani container and an API-34 `google_apis`
emulator. It never points the app at a production server or uses personal data.

Pull requests run `StricknaniSmokeTest`, covering onboarding with the fixture URL/token, seeded
sync, project detail navigation, and the account settings route. Start the longer
`StricknaniFullE2eTest` lane with **Actions → Android E2E → Run workflow → full**; it adds cached
browsing, search, and settings coverage. Both lanes receive the fixture URL and token only as
instrumentation arguments (`e2e_base_url` and `e2e_token`).

The manual `focused` lane runs the route/accessibility, dialog, empty-state, sync-feedback, and
offline-cache instrumentation classes separately so a failure identifies the affected surface.

The fixture is the disposable native Rust server started by `npm --prefix web run fixture -- start`
(sources in [`web/e2e/lib/`](../../web/e2e/lib/fixture.ts)). The workflow builds the Rust candidate with
Nix, seeds the deterministic records with `npm --prefix web run seed`, which validates them through
the API and mints a disposable PAT, and removes the private directory and server process on every
workflow exit path (`npm --prefix web run fixture -- stop`). Run `npm ci --prefix web` once first.

Every run uploads connected-test XML/HTML reports, named instrumentation screenshots, logcat,
fixture logs, a final emulator screenshot, and host diagnostics. For local Gradle verification use
the remote recipe from `android/`:

```sh
just e2e-build rofl-13.brkn.lol
```

The emulator lane is intentionally CI-owned; no local emulator or production endpoint is required.
