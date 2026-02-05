# Stricknani justfile

prod_host := "rofl-10.brkn.lol"

# Default port for dev server
dev_port := env_var_or_default("PORT", "7674")

default:
  @just --choose

# Setup uv environment
setup:
  uv venv
  uv pip install -e ".[dev]"

# Run dev server with reload. Use -b to skip opening the browser.
run args='':
  {{ if args == "-b" { "" } else { "(sleep 2 && ${BROWSER:-xdg-open} http://localhost:" + dev_port + " &)" } }}
  IMPORT_TRACE_ENABLED=1 uv run uvicorn stricknani.main:app --reload --host 0.0.0.0 --port {{dev_port}} --log-level debug --access-log

# Run linters
lint:
  uv run ruff check .
  uv run mypy .

# Check translations
i18n-check:
  uv run python -m stricknani.scripts.check_translations

# Extract translation strings from source files
i18n-extract:
  uv run python -m babel.messages.frontend extract -F babel.cfg -o stricknani/locales/messages.pot stricknani

# Update translation catalogs from the template
i18n-update: i18n-extract
  uv run python -m babel.messages.frontend update -i stricknani/locales/messages.pot -d stricknani/locales

# Compile catalogs after edits
i18n-compile:
  uv run python -m babel.messages.frontend compile -d stricknani/locales

# Format code
fmt:
  uv run ruff format .
  uv run ruff check --fix .
  statix fix .

# Trim trailing whitespace
trim:
  @echo "Trimming trailing whitespace..."
  @find . -type f \( -name "*.py" -o -name "*.html" -o -name "*.js" -o -name "*.css" -o -name "*.md" -o -name "*.nix" -o -name "*.toml" -o -name "justfile" \) -not -path "*/.*" -exec sed -i 's/[[:space:]]\+$//' {} +


# Run tests
test:
  uv run pytest -v

# Run all checks (lint + test)
check: lint lint-nix test i18n-check

# Lint Nix files
lint-nix:
  statix check

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

# Reset demo data and re-seed
demo-reset:
  uv run python -m stricknani.scripts.seed_demo --reset

# Run CLI with arguments
cli *ARGS:
  uv run stricknani-cli {{ARGS}}

# Create or update admin user
admin-create email password='':
  #!/usr/bin/env bash
  set -euo pipefail
  if [[ -z "{{password}}" ]]
  then
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

# Run alembic with arguments
alembic *ARGS:
  uv run alembic --config stricknani/alembic.ini {{ARGS}}

[positional-arguments]
sql *args:
  #!/usr/bin/env sh
  sqlite3 ./stricknani.db "$@"

# Deploy to production
[positional-arguments]
deploy *args='':
  #!/usr/bin/env zhj

  cd /etc/nixos || exit 9

  zparseopts -D -E -K {c,-commit}=commit

  if ! nix flake update stricknani
  then
    echo_error "Failed to update stricknani flake input"
    exit 1
  fi

  nixos::rebuild --target "{{ prod_host }}" || exit 3

  if [[ -n "$commit" ]]
  then
    if ! git diff --cached --quiet
    then
      echo "Won't commit, there are staged changes!"
      exit 1
    fi

    git add flake.lock
    git commit -m "chore: update stricknani"
    git push
  fi
