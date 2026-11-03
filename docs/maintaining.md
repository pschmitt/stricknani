# Maintaining Stricknani

Release, deploy and catalog upkeep for maintainers. Setup and day-to-day
commands are in [install.md](install.md); contributor rules are in
[`AGENTS.md`](../AGENTS.md).

## Checks before a release

Run the full gate in the Rust development shell (`nix develop '.#rust'`):

```sh
just check          # lint, Clippy, tests, i18n, web check, web tests, web build
nix flake check     # package and container derivations
just build-image    # the container image, as CI builds it
```

Cargo, Gradle and Nix builds are heavy. Run them on a build host, not on a
laptop, and keep the build directory outside the source tree. The Android
checks are described in [`android/AGENTS.md`](../android/AGENTS.md).

## Releases

The repository has three release outputs. All of them start from the default
branch.

| Output | Trigger | Result |
| --- | --- | --- |
| Android APKs | Push to `main`, or a `v*` / `X.Y.Z` tag | A push moves the rolling `latest` prerelease (debug and release APKs). A tag publishes a permanent GitHub release with signed release APKs, `SHA256SUMS` and build provenance. |
| Play Store bundle | A `vX.Y.Z` tag | A signed bundle goes to the internal and closed testing tracks. See [`android/docs/play-store-release.md`](../android/docs/play-store-release.md). |
| Container image | Push to `main` that touches `rust/`, `web/`, `nix/`, the flake or the `Dockerfile` | The image is pushed to GHCR as `latest`, the branch, the tag and the commit, then started and probed on `/healthz`. |

To cut a release:

1. Make sure `just check` passes and CI on the default branch is green.
2. Tag the release commit with a semantic version and push the tag:

   ```sh
   git tag -s v1.2.3 -m "v1.2.3"
   git push origin v1.2.3
   ```

3. Watch the Release and Play Store Release workflows. The APK version name is
   taken from the tag; the Play version resolver rejects tags that are not
   semantic versions.
4. Check the release page: four release APKs, `SHA256SUMS`, and release notes
   that list the Android changes since the previous tag.

The release workflow needs the repository secrets `CI_KEYSTORE_BASE64`,
`CI_KEYSTORE_PASSWORD`, `CI_KEY_ALIAS` and `CI_KEY_PASSWORD`. Without them the
APKs are not signed with the release key and upgrades over an installed app
fail. The Play workflow also needs `PLAY_SERVICE_ACCOUNT_JSON` and the
repository variable `PLAY_PUBLISH_ENABLED=true`.

Production stays on the legacy Python release (tag `v0.1.0` on `main`) until the cutover in
[production-cutover-checklist.md](production-cutover-checklist.md) is approved.
Do not move production to the Rust runtime as part of a release.

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
labels are `tr("English", "Deutsch")` calls in `rust/src/`; the web UI uses
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

`web/static/messages.json` and `rust/locales/messages.json` are generated;
commit them with the `.po` files. The generators are Python (Babel), run with
`uv`; see `pyproject.toml`.

## Dependencies and vendoring

Rust server pages (`rust/static/`): the few third-party browser assets they
need (currently the Space Grotesk font files) are vendored with `vendir`. Add
it to `vendir.yml`, run `just vendor-sync`, and commit `vendir.lock.yml`
together with `rust/static/vendor/**`. CI runs `just vendor-check` and fails
when the vendored files differ from the lock. No CDN links.

Svelte web app (`web/`): dependencies are npm packages with a committed
`package-lock.json`, bundled by Vite. They do not need `vendir.yml` entries.
After changing `web/package.json` or the lockfile, update `npmDepsHash` in
`nix/web-package.nix`, or the Nix build of the web app fails:

```sh
nix run nixpkgs#prefetch-npm-deps web/package-lock.json
```

Rust crates are locked in `rust/Cargo.lock`. Renovate opens the update pull
requests for all of the above.

## Database migrations

The Rust server owns its SQLite schema in `rust/migrations/`; add a numbered
SQL file for every schema change and cover it with a test. The legacy Python
app (tag `v0.1.0`) used Alembic; its schema only matters to the snapshot
importer, which is described in the [migration plan](rust-migration-plan.md).
