# Install and run

The Rust server is the default runtime on the `rust` branch. It needs its own
SQLite database and media directory. It does not fall back to the Python
`DATABASE_URL` or `MEDIA_ROOT` and does not migrate Python data. The snapshot
importer is described in the [migration plan](rust-migration-plan.md).

## Container

Requirements: Docker and a checkout of this branch. Build the image from the
repository root and start it with a persistent data volume:

```sh
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
SQLite database and uploads under `/var/lib/stricknani`; keep that volume
across restarts. Check `http://localhost:7674/healthz`, then create the first
administrator with a password from standard input:

```sh
docker exec -i stricknani \
  /bin/stricknani-server create-admin admin@example.invalid \
  < /path/to/protected-admin-password
```

The password must have at least 12 characters.

For a public deployment, terminate HTTPS at a trusted reverse proxy, set
`SESSION_COOKIE_SECURE=true`, and set `TRUSTED_HOSTS` to the exact host
authority clients use. Do not expose the app directly to the Internet.
Registration, external imports, AI, OCR, and Wayback are disabled by default.

## Nix package

The default flake package is the Rust server. Configure a database and media
directory before starting it:

```sh
export STRICKNANI_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/stricknani-rust"
mkdir -p "$STRICKNANI_DATA_DIR/media"
export RUST_DATABASE_URL="sqlite:$STRICKNANI_DATA_DIR/stricknani.sqlite3"
export RUST_MEDIA_ROOT="$STRICKNANI_DATA_DIR/media"
export SESSION_COOKIE_SECURE=false
nix run '.#stricknani' -- create-admin admin@example.invalid < /path/to/protected-admin-password
nix run '.#stricknani'
```

Set `BIND_HOST`, `BIND_PORT`, and `TRUSTED_HOSTS` for a different listener.
The [runtime guide](rust-runtime-transition.md) lists all settings. Startup
behavior is in the [CLI notes](rust-candidate.md#storage-and-startup).

## Development

Enter the Rust development shell for the native and repository checks:

```sh
nix develop '.#rust'
export UV_PYTHON=python3.13
just ci-setup-py
just test
just check
```

`just check` runs lint, strict Clippy, native tests, shipping-harness tests,
web checks, and translation checks. The web app has its own commands in
[`web/README.md`](../web/README.md).

The Python implementation stays available for reference work:

```sh
nix develop '.#python'
just setup
just run-python
```

Use `just test-python` for the Python suite and its coverage gate, or
`just check-python` for its lint, tests, and translation checks. The root
`Dockerfile` builds the Rust runtime; `Dockerfile.python` keeps the Python
image recipe used by the Android end-to-end workflow.
