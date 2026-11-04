# Stricknani justfile
#
# The product is the Rust server (rust/), the Svelte web app (web/) and the
# Android app (android/). The Python in this repository is development tooling
# only: i18n catalog generators (scripts/) and browser/CI harnesses (ci/).
#
# Tooling model: every recipe runs inside the Nix dev shell, which provides
# cargo/clippy/rustfmt, node/npm, python (babel, pytest, mypy, playwright and
# shapely), ruff, biome, statix, nixfmt, vendir and resvg. Inside `nix develop`
# (or direnv's `use flake`) recipes run as they are; anywhere else
# scripts/just-shell re-executes each recipe line through `nix develop`
# (STRICKNANI_NIX_SHELL picks the shell, default `.#rust`). Add missing tools to
# the shell in flake.nix instead of wrapping them in a recipe. Android recipes
# live in android/justfile and are not affected.

set shell := ["./scripts/just-shell", "-c"]

# Default port for dev server
dev_port := env("PORT", "7674")

default:
  @just --choose

# Run the Rust dev server. Set RUST_DATABASE_URL and RUST_MEDIA_ROOT to
# disposable Rust-owned storage paths before starting it.
[group: 'dev']
[positional-arguments]
run *args:
  #!/usr/bin/env bash
  set -euo pipefail
  : "${RUST_DATABASE_URL:?Set RUST_DATABASE_URL to a disposable Rust database}"
  : "${RUST_MEDIA_ROOT:?Set RUST_MEDIA_ROOT to a Rust-owned media directory}"
  BIND_PORT="${BIND_PORT:-{{ dev_port }}}" cargo run --manifest-path rust/Cargo.toml -- serve {{ args }}

# Lint the Python tooling (scripts/, ci/, tests/) and the Rust server's static
# CSS. Rust, web and Nix lint recipes are `rust-lint`, `web-check` and
# `lint-nix`.
[group: 'lint']
lint: lint-ruff lint-mypy lint-css

[group: 'lint']
lint-ruff:
  ruff check .

[group: 'lint']
lint-mypy:
  mypy .

biome_action := if env('CI', '') != '' { 'ci' } else { 'lint' }

[group: 'lint']
lint-css:
  biome "{{ biome_action }}" rust/static/css

# Check translations
[group: 'i18n']
i18n-check:
  python -m scripts.check_translations
  python -m scripts.generate_rust_i18n --check
  python -m scripts.generate_web_i18n --check

# Rebuild the POT template from the Rust and web sources. Babel only writes the
# empty template (babel.cfg has no gettext sources left); the Rust and web
# extractors then fill it.
[group: 'i18n']
i18n-extract:
  python -m babel.messages.frontend extract \
    -F babel.cfg \
    -o stricknani/locales/messages.pot scripts
  python -m scripts.extract_rust_i18n
  python -m scripts.generate_web_i18n --extract

# Update translation catalogs from the template
[group: 'i18n']
i18n-update: i18n-extract
  python -m babel.messages.frontend update \
    -i stricknani/locales/messages.pot \
    -d stricknani/locales
  python -m scripts.generate_rust_i18n --seed-german
  python -m scripts.generate_rust_i18n
  python -m scripts.generate_web_i18n --update

# Compile catalogs after edits
[group: 'i18n']
i18n-compile:
  python -m babel.messages.frontend compile -d stricknani/locales
  python -m scripts.generate_rust_i18n
  python -m scripts.generate_web_i18n

# Format code
[group: 'fmt']
fmt: fmt-ruff fmt-nix fmt-css fmt-rust

# Cargo formatting is fast; cargo builds and tests should run on the remote
# build host described in AGENTS.md.
[group: 'fmt']
fmt-rust:
  cargo fmt --manifest-path rust/Cargo.toml

[group: 'fmt']
fmt-ruff:
  ruff format .
  ruff check --fix .

[group: 'fmt']
fmt-nix:
  statix fix flake.nix
  statix fix nix/

[group: 'fmt']
fmt-css:
  biome format --write rust/static/css

# Trim trailing whitespace
[group: 'fmt']
trim:
  ./scripts/trim.sh

# List pending tasks from TODO.md
[group: 'dev']
todo *args:
  ./scripts/todo.sh {{ args }}

# List done tasks from TODO.md
[group: 'dev']
todo-done:
  ./scripts/todo.sh --done

# List WIP tasks from TODO.md
[group: 'dev']
todo-wip:
  ./scripts/todo.sh --wip

# List todo tasks from TODO.md
[group: 'dev']
todo-todo:
  ./scripts/todo.sh --todo

# Python tooling tests: i18n generators and the browser/CI harness helpers.
[group: 'test']
test-harness:
  pytest -q

# Native Rust suite plus the Python tooling tests.
[group: 'test']
test: rust-test test-harness

# Run Rust tests on the remote build host described in AGENTS.md.
[group: 'test']
rust-test:
  cargo test --manifest-path rust/Cargo.toml --all-targets

# Validate Rust formatting and run strict Clippy on the remote build host.
[group: 'lint']
rust-lint:
  cargo fmt --manifest-path rust/Cargo.toml --check
  cargo clippy --manifest-path rust/Cargo.toml --all-targets --all-features -- -D warnings

# Sync vendored assets
alias vendor-sync := vendir-sync
[group: 'vendir']
vendir-sync *args:
  vendir sync {{ args }}

# Ensure vendored assets are up to date
alias vendor-check := vendir-check
[group: 'vendir']
vendir-check: vendir-sync
  git diff --exit-code -- vendir.lock.yml rust/static/vendor

# Run every repository check: Python tooling, Rust, web, Nix and i18n.
check: lint lint-nix rust-lint test i18n-check web-check web-test web-build

# Browser dependencies are locked and installed only for development/builds.
[group: 'web']
web-install:
  npm --prefix web ci

[group: 'web']
web-check: web-install
  npm --prefix web run check
  npm --prefix web run format:check

[group: 'web']
web-test: web-install
  npm --prefix web test

[group: 'web']
web-build: web-install
  npm --prefix web run build

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

# Deploy to production
[group: 'actions']
deploy *args:
  ./scripts/deploy.sh {{ args }}

# Run Renovate locally (requires GitHub token)
[group: 'actions']
renovate *args:
  ./scripts/renovate.sh {{ args }}

# Regenerate derived brand assets (PNGs, favicon, Android vectors) from branding/*.svg
branding:
  python branding/build.py
