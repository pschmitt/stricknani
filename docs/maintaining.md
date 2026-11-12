# Maintaining Stricknani

Release, deploy and catalog upkeep for maintainers. Setup and day-to-day
commands are in [install.md](install.md); contributor rules are in
[`AGENTS.md`](../AGENTS.md).

## Checks before a release

Run the local aggregate in the Rust development shell (`nix develop '.#rust'`):

```sh
just check          # lint, Clippy, tests, i18n, web check, web tests, web build
nix flake check     # package and container derivations
just build-image    # the container image, as CI builds it
```

CI splits these checks to reuse the Build workflow's Nix cache. The Checks workflow runs
web/tooling, i18n, Nix lint and Android lint. Build produces and caches packages first; its
`nix-checks` job then runs full x86_64 flake validation and pushes the check outputs for later
source-identical checks. The local `just check` remains the full aggregate.

Cargo, Gradle and Nix builds are heavy. Run them on a build host, not on a
laptop, and keep the build directory outside the source tree. The Android
checks are described in [`android/AGENTS.md`](../android/AGENTS.md).

## Releases

The repository has three release outputs. All of them start from the default
branch.

| Output                | Trigger                                                                  | Result                                                                                                                                             |
| --------------------- | ------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| GitHub release assets | Push to `main`, or a `v*` / `X.Y.Z` tag                                  | A push replaces the rolling `latest` prerelease; a tag publishes a permanent GitHub release. Both carry the same set, see below.                   |
| Play Store bundle     | A `vX.Y.Z` tag                                                           | A signed bundle goes to the internal and closed testing tracks. See [`android/docs/play-store-release.md`](../android/docs/play-store-release.md). |
| Container image       | Every push to `main`; manual Build dispatches can publish a selected tag | CI tests native amd64 and arm64 Nix images, then publishes one GHCR multi-platform manifest with `latest`, branch and commit tags.                 |

Build, E2E and Release are separate GitHub Actions workflows. Build has no path filters or
change-detection job: Nix selects changed derivations and substitutes the rest from S3.
Both native architectures use the shared `nix-build.yaml` and `nix-checks.yaml` workflows from
[ci-workflows](https://github.com/pschmitt/ci-workflows). Each producer caches the server,
web app, image and dependency artifacts before its validation lane starts. The x86_64
producer also uploads source-identity metadata for consumers. The thin caller retains its
triggers, dependencies, image names and application-specific container health check. E2E
keeps filtered push, pull request and manual-dispatch triggers; Release runs on `main` pushes,
version tags and manual dispatches. Their Nix jobs use
[`ci/wait-for-nix-producer.mjs`](../ci/wait-for-nix-producer.mjs) to find the Build run for the
same source, verify the metadata and required producer jobs, then substitute those outputs.
For pull requests the check matches the PR head identity separately from the merge SHA recorded
by the producer. Tags and manual runs can build independently when no usable producer artifact
exists. Android release and instrumentation jobs continue to run alongside Nix work.
Container publication waits for both native image producers and both validation lanes, then probes and
publishes one multi-platform GHCR image. E2E waits only for the x86_64 package producer, so it
can use the completed package while validation continues.

The release assets (tag releases and `latest` alike; `<tag>` is `latest` or the tag name):

- `stricknani-<tag>-app-{arm64-v8a,armeabi-v7a,x86_64,x86}-{release,debug}.apk`: the eight Android
  APKs (tags and `latest` use different release channels).
- `stricknani-server-<tag>-linux-x86_64` and `-linux-aarch64`: the static (musl) server binary,
  built with Nix (`.#stricknani-server-static`, nix/rust-core-package.nix on `pkgsStatic`), aarch64
  natively on GitHub's arm runners. It has no Nix wrapper: set `WEB_ROOT`,
  `STRICKNANI_DATABASE_URL` and `STRICKNANI_MEDIA_ROOT` (see `--help`); `tesseract` for OCR is not
  bundled.
- `stricknani-web-<tag>.tar.gz`: the built web app (`.#stricknani-web`) that `WEB_ROOT` points to.
- The NixOS service package uses the dynamic Rust core; the release binary and container use the
  same static Rust core. The container shares the web package output and adds its runtime wrapper,
  OCR engine and CA certificates without invoking a nested Nix build inside Docker. Dynamic and
  static cores keep separate dependency artifacts in Nix's cache.
- `SHA256SUMS` over all of the above; it is also the subject of the build-provenance attestation.
  The release body has a short run-without-Nix note.

`release.yaml` builds in separate jobs (`release` for the APKs, `server` for the binaries and web
archive) and publishes in one `publish` job. A newer push cancels the builds of the older run but
never its `publish` job, which queues per ref: cancelling an upload midway once left `latest` with a
single APK. `publish` replaces `latest`'s assets as a whole and deletes anything not in the current
set. To check the assets without touching a release, dispatch the Release workflow with lane
`github-release` and `dry_run` enabled: it builds everything and attaches the would-be assets (and
release notes) to the run as the `release-assets-preview` artifact.

To cut a release:

1. Clear the queues first:
   - **Renovate:** merge the open dependency pull requests that are green.
     Fix the ones that fail (a stale Nix hash is refreshed by the
     `nix-hash-fixup` workflow; anything else needs a look). Leave a comment
     on any you hold back, with the reason.
   - **Sentry** (sentry.io, org `brknlol`, project `stricknani`, through the
     Sentry MCP): go through the unresolved issues. Fix real errors in this
     release; comment on and resolve test or verification events; never leave
     an issue unacknowledged. Release checks report with the environment
     `release-verification`, which is hidden in Sentry's default views; give
     manual test events that environment too. Release builds upload their R8
     mapping (secret `SENTRY_AUTH_TOKEN`), so Android stack traces are readable.
2. Set the version in `Cargo.toml` (`[package] version`), the single source
   for the server, the web app and the Nix packages, and let cargo update
   `Cargo.lock` (any cargo command, e.g. `cargo metadata --offline`). Android
   takes its version name from the tag.
3. Make sure `just check` passes and CI on the default branch is green.
4. Tag the release commit with a semantic version and push the tag:

   ```sh
   git tag -s v1.2.3 -m "v1.2.3"
   git push origin v1.2.3
   ```

5. Watch the Release workflow: its GitHub release and Google Play jobs run in
   parallel. The APK version name is
   taken from the tag; the Play version resolver rejects tags that are not
   semantic versions.
6. Check the release page: four release and four debug APKs, `SHA256SUMS`, and release notes
   that list the Android changes since the previous tag.

The release workflow needs the repository secrets `CI_KEYSTORE_BASE64`,
`CI_KEYSTORE_PASSWORD`, `CI_KEY_ALIAS` and `CI_KEY_PASSWORD`. Without them the
APKs are not signed with the release key and upgrades over an installed app
fail. The Play workflow also needs `PLAY_SERVICE_ACCOUNT_JSON` and the
repository variable `PLAY_PUBLISH_ENABLED=true`.

## Deploying

Production runs from a NixOS configuration that pins this repository as a flake
input. A deployment is a lock update plus a rebuild of the host:

```sh
just deploy              # update the input, rebuild the host
just deploy --commit     # also commit and push the new flake.lock
```

`scripts/deploy.sh` runs `nix flake update stricknani` in the system
configuration checkout and rebuilds the target host. Run it from the machine
that holds that checkout. After the rebuild, check that the served build is the
new one (the version in the web app, or the `/healthz` response) and not just
that the service is active: a deploy can report success while the old process
keeps running.

Before a Rust preview host is upgraded, copy the realized system closure from
the build host to the target and verify it is present. Keep compilation on the
build hosts. See [rust-nixos.md](rust-nixos.md) for the module options.

## Translations

UI text lives in gettext catalogs for English and German:

- `stricknani/locales/en/LC_MESSAGES/messages.po`
- `stricknani/locales/de/LC_MESSAGES/messages.po`

The catalogs still live under `stricknani/locales/` for historical reasons;
nothing else remains in that directory. Add every new string to both. Rust UI
labels are `tr("English", "Deutsch")` calls in `src/`; the web UI uses
`t()` and `msg()` calls in `web/src/`. Keep message IDs literal there, because
the extractor does not follow computed keys. Avoid apostrophes
inside HTML comments; the extractor reads them as quotes. After a change, run
the steps in order, then fix German strings and clear `fuzzy` flags in the
`.po` files:

```sh
just i18n-update     # extract and merge Rust and web strings
just i18n-compile    # compile .mo files, regenerate Rust and web catalogs
just i18n-check      # fail on missing, fuzzy or stale catalogs
```

`web/static/messages.json` and `locales/messages.json` are generated;
commit them with the `.po` files. The generators are Node scripts in `web/scripts/i18n/`
(`npm run i18n:*`), run from the Nix dev shell (`nix develop`) after
`npm ci --prefix web`.

## Dependencies and vendoring

Svelte web app (`web/`): dependencies are npm packages with a committed
`package-lock.json`, bundled by Vite. There is no CDN link and no other vendoring tool.
After changing `web/package.json` or the lockfile, update `npmDepsHash` in
`nix/web-package.nix`, or the Nix build of the web app fails:

```sh
nix run nixpkgs#prefetch-npm-deps web/package-lock.json
```

Rust crates are locked in `Cargo.lock`. Renovate opens the update pull
requests for all of the above.

## Database migrations

The Rust server owns its SQLite schema in `migrations/`; add a numbered
SQL file for every schema change and cover it with a test. The legacy Python
app (tag `v0.1.0`) used Alembic; its schema only matters to the one-way
`stricknani-server import-snapshot` command.
