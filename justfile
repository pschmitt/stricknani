# Stricknani justfile
#
# The product is the Rust server (repository root), the Svelte web app (web/) and the
# Android app (android/). The development tooling is TypeScript/Node under web/:
# the i18n catalog generators (web/scripts/i18n) and the Playwright browser
# acceptance suite (web/e2e).
#
# Tooling model: every recipe runs inside the Nix dev shell, which provides
# cargo/clippy/rustfmt, node/npm, the Playwright browsers, statix, nixfmt and resvg. Inside `nix develop`
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

# Run the Rust dev server. Set STRICKNANI_DATABASE_URL and STRICKNANI_MEDIA_ROOT to
# disposable Rust-owned storage paths before starting it.
[group: 'dev']
[positional-arguments]
run *args:
  #!/usr/bin/env bash
  set -euo pipefail
  : "${STRICKNANI_DATABASE_URL:?Set STRICKNANI_DATABASE_URL to a disposable Rust database}"
  : "${STRICKNANI_MEDIA_ROOT:?Set STRICKNANI_MEDIA_ROOT to a Rust-owned media directory}"
  BIND_PORT="${BIND_PORT:-{{ dev_port }}}" cargo run -- serve {{ args }}

# Lint the repository tooling. Rust, web and Nix lint recipes are `rust-lint`,
# `web-check` and `lint-nix`.
[group: 'lint']
lint: lint-nix web-check

# Check translations
[group: 'i18n']
i18n-check:
  npm --prefix web run --silent i18n:check

# Rebuild the POT template from the Rust and web sources.
[group: 'i18n']
i18n-extract:
  npm --prefix web run --silent i18n:extract

# Update translation catalogs from the sources
[group: 'i18n']
i18n-update:
  npm --prefix web run --silent i18n:update

# Compile catalogs after edits
[group: 'i18n']
i18n-compile:
  npm --prefix web run --silent i18n:compile

# Format code
[group: 'fmt']
fmt: fmt-web fmt-nix fmt-rust

# Cargo formatting is fast; cargo builds and tests should run on the remote
# build host described in AGENTS.md.
[group: 'fmt']
fmt-rust:
  cargo fmt

[group: 'fmt']
fmt-web: web-install
  npm --prefix web exec -- prettier --write .

[group: 'fmt']
fmt-nix:
  statix fix flake.nix
  statix fix nix/

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

# Native Rust suite plus the web (Vitest) suite.
[group: 'test']
test: rust-test web-test

# Run Rust tests on the remote build host described in AGENTS.md.
[group: 'test']
rust-test:
  cargo test --all-targets

# Validate Rust formatting and run strict Clippy on the remote build host.
[group: 'lint']
rust-lint:
  cargo fmt --check
  cargo clippy --all-targets --all-features -- -D warnings

# Run every repository check: Rust, web, Nix and i18n. The browser acceptance
# suite (`web-e2e`) needs built binaries and runs separately.
check: lint-nix rust-lint rust-test i18n-check web-check web-test web-build

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

# Playwright browser acceptance suite against the packaged fixture. Set
# STRICKNANI_SERVER_BINARY, STRICKNANI_WEB_ROOT and (for the optional-service
# specs) STRICKNANI_FIXTURE_BINARY; pass Playwright arguments such as a spec path.
[group: 'web']
web-e2e *args: web-install
  npm --prefix web run e2e -- {{ args }}

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
  #!/usr/bin/env bash
  set -euo pipefail
  image_path=$(nix build --no-link --print-out-paths '.#stricknani-docker')
  docker load --input "$image_path"
  docker tag ghcr.io/pschmitt/stricknani:latest stricknani:latest

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

# Regenerate derived brand assets (PNGs, favicon, Android vectors) from branding/*.svg.
# The generator is the repository's only Python (geometry via shapely); it runs
# through a one-off nix-shell and is not part of any dev shell.
branding:
  nix-shell -I nixpkgs=flake:nixpkgs -p resvg 'python3.withPackages (ps: [ ps.shapely ])' --run 'python branding/build.py'
