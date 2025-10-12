# Stricknani

🧶 **A Mealie for knitting** — A self-hosted web app for managing knitting projects.

## Features

- 📝 Manage knitting projects with photos, notes, and metadata
- 📐 Built-in gauge calculator for stitch adjustments
- 🔒 Private and self-hosted
- 🚀 Fast and lightweight
- 📱 Mobile-friendly interface

## Quick Start

### Using Docker

```bash
docker run -d \
  -p 7874:7874 \
  -v stricknani-data:/app/media \
  -e SECRET_KEY=your-secret-key \
  ghcr.io/pschmitt/stricknani:latest
```

### Development

Requirements:
- Python 3.13+
- uv
- just

```bash
# Clone the repository
git clone https://github.com/pschmitt/stricknani.git
cd stricknani

# Setup environment
just setup

# Run development server
just run
```

The app will be available at http://localhost:7674

## Configuration

Configuration is done via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `SECRET_KEY` | Secret key for sessions | (required in production) |
| `PORT` | Port to listen on | `7674` |
| `DATABASE_URL` | Database connection string | `sqlite:///./stricknani.db` |
| `MEDIA_ROOT` | Directory for uploaded files | `./media` |
| `FEATURE_SIGNUP_ENABLED` | Enable user signup | `true` |

## Development

### Available Commands

```bash
just setup          # Setup development environment
just run            # Run development server
just lint           # Run linters (ruff + mypy)
just fmt            # Format code
just test           # Run tests
just check          # Run all checks (lint + test)
just build-image    # Build Docker image
just push-image     # Push to GHCR
just demo-data      # Seed demo data
```

### Testing

```bash
# Run all tests
just test

# Run specific test file
uv run pytest tests/test_gauge.py -v
```

### Linting

The project uses:
- **Ruff** for linting and formatting
- **MyPy** for type checking (strict mode)

```bash
# Run all linters
just lint

# Auto-format code
just fmt
```

## Architecture

- **Framework:** FastAPI + Jinja2 + HTMX
- **Database:** SQLite (dev), PostgreSQL (production-ready)
- **Auth:** JWT-based session cookies
- **Storage:** Local filesystem (S3-compatible coming soon)

## License

GPL-3.0-only

## Maintainer

Philipp Schmitt ([@pschmitt](https://github.com/pschmitt))
