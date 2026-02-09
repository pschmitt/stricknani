# Stricknani justfile

set shell := ["bash", "-c"]

# Default port for dev server
dev_port := env("PORT", "7674")

default:
  @just --choose

# Setup uv environment
[group: 'dev']
setup:
  uv venv
  uv pip install -e ".[dev]"

# CI helpers
[group: 'ci']
ci-setup-py:
  uv sync --extra dev

# Run dev server with reload. Use -b to skip opening the browser.
[group: 'dev']
[positional-arguments]
run *args:
  ./scripts/run.sh --port "{{ dev_port }}" {{ args }}

# Run linters
[group: 'lint']
lint: lint-ruff lint-mypy lint-js lint-css

[group: 'lint']
lint-ruff:
  uv run ruff check .

[group: 'lint']
lint-mypy:
  uv run mypy .

biome_action := if env('CI', '') != '' { 'ci' } else { 'lint' }

[group: 'lint']
lint-js:
  biome "{{ biome_action }}" stricknani/static/js

[group: 'lint']
lint-css:
  biome "{{ biome_action }}" stricknani/static/css

# Check translations
[group: 'i18n']
i18n-check:
  uv run python -m stricknani.scripts.check_translations

# Extract translation strings from source files
[group: 'i18n']
i18n-extract:
  uv run python -m babel.messages.frontend extract \
    -F babel.cfg \
    -o stricknani/locales/messages.pot stricknani

# Update translation catalogs from the template
[group: 'i18n']
i18n-update: i18n-extract
  uv run python -m babel.messages.frontend update \
    -i stricknani/locales/messages.pot \
    -d stricknani/locales

# Compile catalogs after edits
[group: 'i18n']
i18n-compile:
  uv run python -m babel.messages.frontend compile -d stricknani/locales

# Format code
[group: 'fmt']
fmt: fmt-ruff fmt-nix fmt-biome

[group: 'fmt']
fmt-ruff:
  uv run ruff format .
  uv run ruff check --fix .

[group: 'fmt']
fmt-nix:
  statix fix flake.nix
  statix fix nix/

[group: 'fmt']
fmt-biome: fmt-js fmt-css

[group: 'fmt']
fmt-js:
  biome format --write stricknani/static/js

[group: 'fmt']
fmt-css:
  biome format --write stricknani/static/css

# Trim trailing whitespace
[group: 'fmt']
trim:
  ./scripts/trim.sh

# Run tests
[group: 'test']
test:
  uv run pytest -v

# Sync vendored assets
alias vendor-sync := vendir-sync
[group: 'vendir']
vendir-sync *args:
  vendir sync {{ args }}

# Ensure vendored assets are up to date
alias vendor-check := vendir-check
[group: 'vendir']
vendir-check: vendir-sync
  git diff --exit-code -- vendir.lock.yml stricknani/static/vendor

# Run all checks (lint + test)
check: lint lint-nix test i18n-check

# Lint Nix files
[group: 'lint']
[group: 'nix']
lint-nix:
  statix check flake.nix
  statix check nix/

# Build with Nix
[group: 'build']
[group: 'nix']
build-nix:
  nix build

# Build Nix container
[group: 'build']
[group: 'nix']
[group: 'container']
build-container:
  nix build '.#stricknani-docker'

# Build Docker image
[group: 'build']
[group: 'container']
build-image:
  docker build -t stricknani:latest .

# Push image to GHCR
[group: 'container']
push-image:
  #!/usr/bin/env bash
  set -euo pipefail
  COMMIT_SHA=$(git rev-parse HEAD)
  docker tag stricknani:latest ghcr.io/pschmitt/stricknani:${COMMIT_SHA}
  docker tag stricknani:latest ghcr.io/pschmitt/stricknani:latest
  docker push ghcr.io/pschmitt/stricknani:${COMMIT_SHA}
  docker push ghcr.io/pschmitt/stricknani:latest

# Seed demo data
[group: 'demo']
demo-data *args:
  mkdir -p ./media
  uv run python -m stricknani.scripts.seed_demo {{ args }}

[group: 'demo']
[confirm("Delete media dir?")]
purge-media:
  rm -rf ./media
  mkdir -p ./media

# Reset demo data and re-seed
[group: 'demo']
[confirm("Delete database and media dir?")]
demo-reset: purge-media (demo-data "--reset")

# Run CLI with arguments
[group: 'cli']
cli *args:
  uv run stricknani-cli {{ args }}

# AI ingestion helpers (CLI-only)
[group: 'cli']
ai *args:
  uv run stricknani-cli ai {{ args }}

# Convenience wrappers
# Usage:
# - `just ai-schema` (project)
# - `just ai-schema yarn`
[group: 'cli']
ai-schema target="project":
  uv run stricknani-cli ai schema --target {{ target }}

[group: 'cli']
ai-ingest *args:
  uv run stricknani-cli ai ingest {{ args }}

# Create or update admin user
[group: 'cli']
admin-create email password='':
  #!/usr/bin/env bash
  ARGS=(
    --admin
    --email "{{ email }}"
  )
  if [[ -n "{{ password }}" ]]
  then
    ARGS+=(--password "{{ password }}")
  fi

  uv run stricknani-cli user create "${ARGS[@]}"

# List all users
[group: 'cli']
user-list:
  uv run stricknani-cli user list

# Database migrations
[group: 'db']
migrate-create name:
  uv run alembic --config stricknani/alembic.ini revision --autogenerate -m "{{ name }}"

[group: 'db']
migrate-up:
  uv run alembic --config stricknani/alembic.ini upgrade head

[group: 'db']
migrate-down:
  uv run alembic --config stricknani/alembic.ini downgrade -1

# Run alembic with arguments
[group: 'db']
alembic *args:
  uv run alembic --config stricknani/alembic.ini {{ args }}

[group: 'db']
sql *args:
  #!/usr/bin/env sh
  sqlite3 ./stricknani.db {{ args }}

# Deploy to production
[group: 'actions']
deploy *args:
  ./scripts/deploy.sh {{ args }}

# Run Renovate locally (requires GitHub token)
[group: 'actions']
renovate *args:
  ./scripts/renovate.sh {{ args }}
