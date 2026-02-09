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

[group: 'ci']
ci-setup-prettier:
  npm install -g prettier

[group: 'ci']
ci-setup-vendir:
  go install carvel.dev/vendir/cmd/vendir@latest

[group: 'ci']
ci-setup-biome:
  npm install -g @biomejs/biome

# Run dev server with reload. Use -b to skip opening the browser.
[group: 'dev']
[positional-arguments]
run *args:
  #!/usr/bin/env bash

  PORT="{{ dev_port }}"
  ARGS=({{ args }})

  CMD=(
    uv run uvicorn stricknani.main:app
    --reload
    --host 0.0.0.0
    --port "$PORT"
    --log-level debug
    --access-log
  )

  ENV_VARS=(
    IMPORT_TRACE_ENABLED=1
  )

  while [[ -n $* ]]
  do
    case "$1" in
      -b|--background)
        DONT_OPEN_BROWSER=1
        shift
        ;;
      -d|--debug)
        ENV_VARS+=("DEBUG=1")
        shift
        ;;
      *)
        break
        ;;
    esac
  done

  if [[ -z "${IN_NIX_SHELL:-}" ]]
  then
    if ! command -v nix &>/dev/null
    then
      echo "WARNING: nix not found; running without nix develop" >&2
    else
      exec nix develop -c just run "${ARGS[@]}"
    fi
  fi

  if [[ -z $DONT_OPEN_BROWSER ]]
  then
    (sleep 2 && ${BROWSER:-xdg-open} "http://localhost:${PORT}") &
  fi

  env "${ENV_VARS[@]}" "${CMD[@]}"

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
fmt: fmt-ruff fmt-nix fmt-prettier

[group: 'fmt']
fmt-ruff:
  uv run ruff format .
  uv run ruff check --fix .

[group: 'fmt']
fmt-nix:
  statix fix flake.nix
  statix fix nix/

[group: 'fmt']
fmt-prettier:
  prettier --write .

[group: 'fmt']
prettier-check:
  prettier --check .

# Trim trailing whitespace
[group: 'fmt']
trim:
  @echo "Trimming trailing whitespace..."

  @find . \
    -type f \
    \( \
      -iname "*.py" \
      -o -iname "*.html" \
      -o -iname "*.js" \
      -o -iname "*.css" \
      -o -iname "*.md" \
      -o -iname "*.nix" \
      -o -iname "*.toml" \
      -o -name "justfile" \
    \) \
    -not -path "*/.*" \
    -not -path "./stricknani/static/vendor/*" \
    -exec sed -i 's/[[:space:]]\+$//' {} +

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
