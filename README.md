<p align="center">
  <img src="branding/icon.svg" alt="Stricknani" width="128">
</p>

<h1 align="center">Stricknani</h1>

<p align="center">A private, self-hosted place to plan knitting projects, track yarn, and calculate gauge.</p>

<p align="center">
  <a href="https://nixbot.brkn.lol/repos/github/pschmitt/stricknani"><img src="https://nixbot.brkn.lol/repos/github/pschmitt/stricknani/badge.svg?branch=rust" alt="nixbot"></a>
  <a href="https://github.com/pschmitt/stricknani/actions/workflows/test.yaml"><img src="https://github.com/pschmitt/stricknani/actions/workflows/test.yaml/badge.svg?branch=rust" alt="Rust branch checks"></a>
</p>

The `rust` branch runs a Rust server with a SvelteKit web app. Production
still runs the Python release v0.1.0 until the planned v1.0.0 cutover. Keep
Rust and Python databases and media separate: the Rust server does not reuse
or migrate Python storage on its own.

## Features

- **Projects and yarn**: steps, notes, photos, categories, and links between
  yarn and projects.
- **Gauge calculator**: stitch and row counts from pattern and personal gauge.
- **Web app**: a SvelteKit PWA in `web/`, served by the Rust server over API
  v2. The offline cache holds only the public app shell and static assets,
  never signed-in pages or user data. See [`docs/rust-pwa.md`](docs/rust-pwa.md).
- **Android app**: an offline-first native client in `android/`, package
  `blue.anika.wolle`. Installed apps keep using API v1; API v2 is the
  native and web API.
- **Optional OIDC sign-in**: Authelia support is planned, see
  [`docs/oidc-plan.md`](docs/oidc-plan.md).

## Install

Build and start the container, then create the first administrator:

```sh
docker build -t stricknani:rust .
docker volume create stricknani-data
docker run -d --name stricknani -p 127.0.0.1:7674:7674 \
  -v stricknani-data:/var/lib/stricknani \
  -e TRUSTED_HOSTS=localhost,127.0.0.1 \
  -e SESSION_COOKIE_SECURE=false stricknani:rust
docker exec -i stricknani /bin/stricknani-server create-admin \
  admin@example.invalid < /path/to/protected-admin-password
```

The app listens on `http://localhost:7674`. Public deployments, the Nix
package, and development setup are in [`docs/install.md`](docs/install.md).

## Documentation

| Topic | Guide |
| --- | --- |
| Install, run, and develop | [`docs/install.md`](docs/install.md) |
| Runtime settings and rollout boundary | [`docs/rust-runtime-transition.md`](docs/rust-runtime-transition.md) |
| Migration, verification, and rollback | [`docs/rust-migration-plan.md`](docs/rust-migration-plan.md) |
| Production cutover | [`docs/production-cutover-checklist.md`](docs/production-cutover-checklist.md) |
| Web API v2 contract | [`docs/web-v2-contract.md`](docs/web-v2-contract.md) |
| Android API v1 contract | [`docs/rust-android-inventory.md`](docs/rust-android-inventory.md) |
| Android v2 migration | [`docs/android-v2-migration.md`](docs/android-v2-migration.md) |
| Web app | [`web/README.md`](web/README.md), [`WEB-UI-REWRITE.md`](WEB-UI-REWRITE.md) |
| Android app | [`android/AGENTS.md`](android/AGENTS.md) |
| All documents | [`docs/README.md`](docs/README.md) |
| Contributor instructions | [`AGENTS.md`](AGENTS.md) |
| Work queue | [`TODO.md`](TODO.md) |

## License

GPL-3.0-only, see [`LICENSE`](LICENSE).
