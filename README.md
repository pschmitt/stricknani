<p align="center">
  <img src="branding/icon.svg" alt="Stricknani" width="128">
</p>

<h1 align="center">Stricknani</h1>

<p align="center">A private, self-hosted place to plan knitting projects, track yarn, and calculate gauge.</p>

<p align="center">
  <a href="https://nixbot.brkn.lol/repos/github/pschmitt/stricknani"><img src="https://nixbot.brkn.lol/repos/github/pschmitt/stricknani/badge.svg?branch=main" alt="nixbot"></a>
  <a href="https://github.com/pschmitt/stricknani/actions/workflows/test.yaml"><img src="https://github.com/pschmitt/stricknani/actions/workflows/test.yaml/badge.svg?branch=main" alt="Main branch tests"></a>
</p>

Stricknani is a self-hosted knitting project manager. The `main` branch runs
the Python application: FastAPI serves server-rendered Jinja pages enhanced
with HTMX. The Android companion uses the versioned API v1.

## Features

- Organize projects, steps, notes, and photos.
- Keep a yarn collection and link yarn to projects.
- Calculate stitch and row counts from pattern and personal gauge.
- Import project and yarn details from supported sources.
- Run privately on your own server, with responsive web and Android clients.

## Install and quick start

### Run from source

Requirements: Python 3.12 or newer, [uv](https://docs.astral.sh/uv/), and
[just](https://just.systems/).

```bash
git clone https://github.com/pschmitt/stricknani.git
cd stricknani
just setup
cp .env.sample .env
# Set INITIAL_ADMIN_EMAIL and INITIAL_ADMIN_PASSWORD in .env.
just run
```

Public signup is disabled by default; the initial administrator settings create
your first login. Open `http://localhost:7674`. The development runner creates temporary signing
keys when none are supplied. For a persistent deployment, set strong
`SECRET_KEY` and `CSRF_SECRET_KEY` values and configure the database, media
directory, and allowed hosts. See [`.env.sample`](.env.sample) for the
available settings.

### Run the Python container

Build the image from this branch, then run it with persistent storage. Copy
`.env.sample` to a private `.env` file first, set production signing keys and
the initial administrator credentials, and keep that file out of source
control.

```bash
docker build -t stricknani:python .
docker volume create stricknani-data
docker run -d \
  --name stricknani \
  --restart unless-stopped \
  -p 127.0.0.1:7674:7674 \
  --env-file .env \
  -e DATABASE_URL=sqlite:////app/media/stricknani.db \
  -e MEDIA_ROOT=/app/media \
  -v stricknani-data:/app/media \
  stricknani:python
```

The container listens on port `7674`; `/healthz` is its health endpoint. Put
public deployments behind HTTPS and set `ALLOWED_HOSTS` to the hostnames that
serve the app.

## Configuration

The application reads configuration from environment variables. These are the
settings most deployments need:

| Variable | Purpose |
| --- | --- |
| `SECRET_KEY`, `CSRF_SECRET_KEY` | Signing and CSRF protection; set strong values in persistent deployments. |
| `DATABASE_URL` | SQLAlchemy database URL; SQLite is the development default. |
| `MEDIA_ROOT` | Directory for uploaded media. |
| `ALLOWED_HOSTS` | Comma-separated host allowlist. |
| `FEATURE_SIGNUP_ENABLED` | Enable public account signup; disabled by default. |
| `INITIAL_ADMIN_EMAIL`, `INITIAL_ADMIN_PASSWORD` | Create the first administrator when the database has no users. |

AI import, Wayback lookup, language, cookie, and observability options are also
listed in [`.env.sample`](.env.sample). Keep credentials in a protected
environment file or secret manager, not in source control.

## Development

```bash
just lint
just test
just i18n-check
just check
```

`just check` runs the repository lint, test, Nix lint, and translation checks.
The disposable browser suites are available with `just e2e-smoke` and
`just e2e-full`.

## Documentation

| Topic | Guide |
| --- | --- |
| Documentation index | [`docs/README.md`](docs/README.md) |
| Product scope and implementation | [`docs/spec-and-implementation.md`](docs/spec-and-implementation.md) |
| Android companion | [`android/README.md`](android/README.md) |
| Android privacy | [`android/PRIVACY.md`](android/PRIVACY.md) |
| Contributor instructions | [`AGENTS.md`](AGENTS.md) |
| Current work queue | [`TODO.md`](TODO.md) |

## License

GPL-3.0-only

## Maintainer

Philipp Schmitt ([`@pschmitt`](https://github.com/pschmitt))
