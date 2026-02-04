# Stricknani

🧶 **A Mealie for knitting** — a self-hosted web app for managing knitting projects.

## Features

- Projects with photos, notes, and metadata
- Built-in gauge calculator
- Private, self-hosted, mobile-friendly UI
- FastAPI + Jinja2 + HTMX (no SPA)

## Quick Start

### Docker

```bash
docker run -d \
  -p 7674:7674 \
  -v stricknani-data:/app/media \
  -e SECRET_KEY=your-secret-key \
  ghcr.io/pschmitt/stricknani:latest
```

Health check: `GET /healthz`

### Development

Requirements:
- Python 3.13+
- uv
- just

```bash
git clone https://github.com/pschmitt/stricknani.git
cd stricknani

just setup
just run
```

App runs at http://localhost:7674

## Configuration

All config is via environment variables or `.envrc` (see `.envrc.sample`).

| Variable | Description | Default |
|----------|-------------|---------|
| `SECRET_KEY` | Secret key for sessions | `dev-secret-key-change-in-production` |
| `CSRF_SECRET_KEY` | CSRF secret key | `SECRET_KEY` |
| `PORT` | Port to listen on | `7674` |
| `DEBUG` | Enable debug mode | `false` |
| `TESTING` | Force testing mode | `false` |
| `BIND_HOST` | Host to bind the dev server | `127.0.0.1` |
| `BIND_PORT` | Port to bind the dev server | `7674` |
| `DATABASE_URL` | Database connection string | `sqlite:///./stricknani.db` |
| `MEDIA_ROOT` | Directory for uploaded files | `./media` |
| `IMPORT_TRACE_ENABLED` | Enable import tracing | `false` |
| `IMPORT_TRACE_DIR` | Import trace directory | `./media/import-traces` |
| `IMPORT_TRACE_MAX_CHARS` | Max chars captured per import trace | `12000` |
| `ALLOWED_HOSTS` | Comma-separated host list | `localhost,127.0.0.1` |
| `SESSION_COOKIE_SECURE` | Secure session cookies | `false` |
| `LANGUAGE_COOKIE_SECURE` | Secure language cookie | `false` |
| `COOKIE_SAMESITE` | SameSite policy | `strict` |
| `FEATURE_SIGNUP_ENABLED` | Enable user signup | `false` |
| `FEATURE_WAYBACK_ENABLED` | Enable Wayback Machine snapshots | `false` |
| `FEATURE_AI_IMPORT_ENABLED` | Enable AI-powered pattern import | `true` |
| `DEFAULT_LANGUAGE` | Default language | `de` |
| `OPENAI_API_KEY` | OpenAI API key for AI import | (optional) |
| `SENTRY_DSN_BACKEND` | Sentry DSN for backend | (optional) |
| `SENTRY_DSN_FRONTEND` | Sentry DSN for frontend | (optional) |
| `SENTRY_ENVIRONMENT` | Sentry environment name | `production` |
| `SENTRY_TRACES_SAMPLE_RATE` | Sentry perf sample rate | `0` |
| `SENTRY_FRONTEND_TRACES_SAMPLE_RATE` | Frontend perf sample rate | `0` |
| `LOG_LEVEL` | Logging level override | (optional) |
| `IMPORT_LOG_PATH` | Import log file path | (optional) |
| `INITIAL_ADMIN_EMAIL` | Bootstrap admin email | (optional) |
| `INITIAL_ADMIN_USERNAME` | Bootstrap admin username | (optional) |
| `INITIAL_ADMIN_PASSWORD` | Bootstrap admin password | (optional) |

## AI-Powered Pattern Import

Install AI extras:

```bash
uv pip install -e ".[ai]"
```

Then set `OPENAI_API_KEY` in `.envrc`.

## Developer Workflow

```bash
just setup          # Setup uv environment
just run            # Run dev server (reload)
just lint           # ruff + mypy
just fmt            # ruff format + fixes
just test           # pytest -v
just i18n-check     # Verify translations
just check          # lint + test + i18n-check
just build-image    # Build Docker image
just build-nix      # Build Nix package
just build-container # Build Nix container
just demo-data      # Seed demo data
just demo-reset     # Reset and re-seed demo data
```

When editing UI text, update translations in:
- `stricknani/locales/en/LC_MESSAGES/messages.po`
- `stricknani/locales/de/LC_MESSAGES/messages.po`

Then compile:

```bash
uv run python -m babel.messages.frontend compile -d stricknani/locales
```

## Architecture (Short)

- FastAPI + Jinja2 + HTMX
- SQLite for dev, PostgreSQL optional
- Local filesystem media storage

## License

GPL-3.0-only

## Maintainer

Philipp Schmitt (GitHub: @pschmitt)
