# Stricknani justfile

# Default port for dev server
dev_port := env_var_or_default("PORT", "7674")

# Setup uv environment
setup:
    uv venv
    uv pip install -e ".[dev]"

# Run dev server with reload
run:
    uv run uvicorn stricknani.main:app --reload --host 0.0.0.0 --port {{dev_port}} --log-level debug --access-log

# Run linters
lint:
    uv run ruff check .
    uv run mypy .

# Format code
fmt:
    uv run ruff format .
    uv run ruff check --fix .

# Run tests
test:
    uv run pytest -v

# Run all checks (lint + test)
check: lint test

# Build with Nix
build-nix:
    nix build

# Build Nix container
build-container:
    nix build .#stricknani-docker

# Build Docker image
build-image:
    docker build -t stricknani:latest .

# Push image to GHCR
push-image:
    #!/usr/bin/env bash
    set -euo pipefail
    COMMIT_SHA=$(git rev-parse HEAD)
    docker tag stricknani:latest ghcr.io/pschmitt/stricknani:${COMMIT_SHA}
    docker tag stricknani:latest ghcr.io/pschmitt/stricknani:latest
    docker push ghcr.io/pschmitt/stricknani:${COMMIT_SHA}
    docker push ghcr.io/pschmitt/stricknani:latest

# Seed demo data
demo-data:
    uv run python -m stricknani.scripts.seed_demo

# Run CLI with arguments
cli *ARGS:
    uv run stricknani-cli {{ARGS}}

# Create or update admin user
admin-create email password='':
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ -z "{{password}}" ]]; then
      uv run stricknani-cli user create --email "{{email}}" --admin
    else
      uv run stricknani-cli user create --email "{{email}}" --password "{{password}}" --admin
    fi

# List all users
user-list:
    uv run stricknani-cli user list

# Database migrations
migrate-create name:
    uv run alembic --config stricknani/alembic.ini revision --autogenerate -m "{{name}}"

migrate-up:
    uv run alembic --config stricknani/alembic.ini upgrade head

migrate-down:
    uv run alembic --config stricknani/alembic.ini downgrade -1
