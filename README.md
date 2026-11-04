<p align="center">
  <img src="branding/mascot.svg" alt="Stricknani" width="200">
</p>

<h1 align="center">Stricknani</h1>

<p align="center">A private, self-hosted place to plan knitting projects, track yarn, and calculate gauge.</p>

<p align="center">
  <a href="https://nixbot.brkn.lol/repos/github/pschmitt/stricknani"><img src="https://nixbot.brkn.lol/repos/github/pschmitt/stricknani/badge.svg?branch=main" alt="nixbot"></a>
  <a href="https://github.com/pschmitt/stricknani/actions/workflows/test.yaml"><img src="https://github.com/pschmitt/stricknani/actions/workflows/test.yaml/badge.svg?branch=main" alt="Checks"></a>
</p>

Stricknani is a Rust server with a SvelteKit web app and an offline-first
Android app. Your projects, yarn and photos stay on your own server.

## Features

- **Projects and yarn**: steps, notes, photos, categories, favourites, and
  links between yarn and projects, with a per-item change history.
- **Home**: continue knitting, favourites, recent items and a recent activity
  feed of your changes.
- **Imports**: create a project draft from text, a file or a pattern link.
- **Gauge calculator**: stitch and row counts from pattern and personal gauge.
- **Web app**: a SvelteKit PWA in `web/`, served by the Rust server over API
  v2. The offline cache holds only the public app shell and static assets,
  never signed-in pages or user data. See [`docs/rust-pwa.md`](docs/rust-pwa.md).
- **Android app**: an offline-first native client in `android/`, package
  `blue.anika.wolle`, as signed APKs on
  [GitHub Releases](https://github.com/pschmitt/stricknani/releases). Connect it
  to your server with one tap from the web app (QR code or link).
- **Sign-in**: email and password, plus optional OpenID Connect sign-in such
  as Authelia. See
  [`docs/oidc-server-configuration.md`](docs/oidc-server-configuration.md).
- **Profile pictures**: uploads, or Gravatar fetched by the server; it can be
  turned off per account and per instance.

## Install

Build and start the container, then create the first administrator:

```sh
docker build -t stricknani .
docker volume create stricknani-data
docker run -d --name stricknani -p 127.0.0.1:7674:7674 \
  -v stricknani-data:/var/lib/stricknani \
  -e TRUSTED_HOSTS=localhost,127.0.0.1 \
  -e SESSION_COOKIE_SECURE=false stricknani
docker exec -i stricknani /bin/stricknani-server create-admin \
  admin@example.invalid < /path/to/protected-admin-password
```

The app listens on `http://localhost:7674/app/`. Public deployments, the Nix
package and NixOS module, and development setup are in
[`docs/install.md`](docs/install.md).

## Documentation

| Topic | Guide |
| --- | --- |
| Install, run, and develop | [`docs/install.md`](docs/install.md) |
| Releases, deploys, translations, vendoring | [`docs/maintaining.md`](docs/maintaining.md) |
| OpenID Connect sign-in | [`docs/oidc-server-configuration.md`](docs/oidc-server-configuration.md), [`docs/oidc-authelia-settings.md`](docs/oidc-authelia-settings.md) |
| Web app and PWA | [`web/README.md`](web/README.md), [`docs/rust-pwa.md`](docs/rust-pwa.md) |
| Web API v2 contract | [`docs/web-v2-contract.md`](docs/web-v2-contract.md) |
| Android app | [`android/AGENTS.md`](android/AGENTS.md), [`docs/android-v2-parity.md`](docs/android-v2-parity.md) |
| All documents | [`docs/README.md`](docs/README.md) |
| Contributor instructions | [`AGENTS.md`](AGENTS.md) |
| Work queue | [`TODO.md`](TODO.md) |

## License

GPL-3.0-only, see [`LICENSE`](LICENSE).
