<p align="center">
  <img src="branding/icon.svg" alt="Stricknani" width="128">
</p>

<h1 align="center">Stricknani</h1>

<p align="center">A private, self-hosted place to plan knitting projects, track yarn, and calculate gauge.</p>

<p align="center">
  <a href="https://nixbot.brkn.lol/repos/github/pschmitt/stricknani"><img src="https://nixbot.brkn.lol/repos/github/pschmitt/stricknani/badge.svg?branch=rust" alt="nixbot"></a>
  <a href="https://github.com/pschmitt/stricknani/actions/workflows/test.yaml"><img src="https://github.com/pschmitt/stricknani/actions/workflows/test.yaml/badge.svg?branch=rust" alt="Rust branch checks"></a>
</p>

This `rust` branch runs the Rust server and a static Svelte web app. It is an
acceptance and preview branch; it does not mean production has switched from
the Python runtime. Keep Rust and Python databases and media separate. The
Rust service does not migrate or reuse Python storage automatically.

## Features

- Organize knitting projects, steps, notes, photos, and yarn.
- Manage yarn and categories, with links between yarn and projects.
- Calculate stitch and row counts from pattern and personal gauge.
- Use the browser app with the Rust API v2, while Android continues to use the
  compatible API v1.
- Install the web app as a PWA. Offline support caches the public app shell and
  static assets; it does not cache signed-in pages or user data.

## Install and quick start

### Run the Rust container locally

Requirements: Docker and a checkout of this branch. Build the image from the
repository root and start it with a persistent data volume:

```bash
docker build -t stricknani:rust .
docker volume create stricknani-data
docker run -d \
  --name stricknani \
  --restart unless-stopped \
  -p 127.0.0.1:7674:7674 \
  -v stricknani-data:/var/lib/stricknani \
  -e TRUSTED_HOSTS=localhost,127.0.0.1 \
  -e SESSION_COOKIE_SECURE=false \
  stricknani:rust
```

This example binds to loopback for local HTTP use. The container stores its
Rust-owned SQLite database and uploads under `/var/lib/stricknani`; keep that
volume across restarts. Check `http://localhost:7674/healthz`, then create the
first administrator by supplying a password through standard input:

```bash
docker exec -i stricknani \
  /bin/stricknani-server create-admin admin@example.invalid \
  < /path/to/protected-admin-password
```

The protected file must contain a password of at least 12 characters.

For a public deployment, terminate HTTPS at a trusted reverse proxy, set
`SESSION_COOKIE_SECURE=true`, and configure `TRUSTED_HOSTS` with the exact host
authority clients use. Do not expose the app directly to the Internet.
Registration, external imports, AI, OCR, and Wayback features are disabled by
default. See the [runtime guide](docs/rust-runtime-transition.md) before
changing runtime settings or storage.

### Run the package directly

The default flake package is Rust. For a local run, configure a Rust-owned
SQLite database and media directory before starting the server:

```bash
export STRICKNANI_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/stricknani-rust"
mkdir -p "$STRICKNANI_DATA_DIR/media"
export RUST_DATABASE_URL="sqlite:$STRICKNANI_DATA_DIR/stricknani.sqlite3"
export RUST_MEDIA_ROOT="$STRICKNANI_DATA_DIR/media"
export SESSION_COOKIE_SECURE=false
nix build '.#stricknani'
nix run '.#stricknani' -- create-admin admin@example.invalid < /path/to/protected-admin-password
nix run '.#stricknani'
```

The container configures its own database and media paths. Set `BIND_HOST`,
`BIND_PORT`, and `TRUSTED_HOSTS` for a different listener. See the
[runtime guide](docs/rust-runtime-transition.md) for all settings and
[CLI notes](docs/rust-candidate.md#storage-and-startup) for startup behavior.

## Development

Enter the Rust development shell to run the native and repository checks:

```bash
nix develop '.#rust'
export UV_PYTHON=python3.13
just ci-setup-py
just test
just check
```

`just check` includes lint, strict Clippy, native tests, shipping-harness
tests, web checks, and translation checks. The Python implementation remains
available for legacy development and reference work:

```bash
nix develop '.#python'
just setup
just run-python
```

Use `just test-python` for the Python reference suite and its coverage gate,
or `just check-python` for its lint, tests, and translation checks. The root
`Dockerfile` builds the Rust runtime; `Dockerfile.python` retains the Python
image recipe used by the existing Android end-to-end workflow.

## Storage and rollout boundary

The Rust runtime requires an isolated SQLite database and media directory. It
does not fall back to the Python `DATABASE_URL` or `MEDIA_ROOT` and does not
automatically migrate Python data. The snapshot importer and its verification
steps are documented in the [migration plan](docs/rust-migration-plan.md).
Production remains on its separately pinned Python module until Rust
acceptance, a production input update, and a separately approved cutover are
complete.

## Documentation

| Topic | Guide |
| --- | --- |
| Documentation index | [`docs/README.md`](docs/README.md) |
| Runtime outputs and rollout boundary | [`docs/rust-runtime-transition.md`](docs/rust-runtime-transition.md) |
| Migration, verification, and rollback | [`docs/rust-migration-plan.md`](docs/rust-migration-plan.md) |
| Current acceptance checkpoint | [`docs/rust-rewrite-checkpoint.md`](docs/rust-rewrite-checkpoint.md) |
| Native API v1 contract | [`docs/rust-android-inventory.md`](docs/rust-android-inventory.md) |
| PWA cache scope | [`docs/rust-pwa.md`](docs/rust-pwa.md) |
| Web UI plan and T120 queue | [`WEB-UI-REWRITE.md`](WEB-UI-REWRITE.md) |
| Contributor instructions | [`AGENTS.md`](AGENTS.md) |
| Current work queue | [`TODO.md`](TODO.md) |

## License

GPL-3.0-only

## Maintainer

Philipp Schmitt ([`@pschmitt`](https://github.com/pschmitt))
