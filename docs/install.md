# Install and run

The server needs its own SQLite database (`RUST_DATABASE_URL`) and media
directory (`RUST_MEDIA_ROOT`).

## Container

Requirements: Docker and a checkout of this repository. Build the image from the
repository root and start it with a persistent data volume:

```sh
docker build -t stricknani .
docker volume create stricknani-data
docker run -d \
  --name stricknani \
  --restart unless-stopped \
  -p 127.0.0.1:7674:7674 \
  -v stricknani-data:/var/lib/stricknani \
  -e TRUSTED_HOSTS=localhost,127.0.0.1 \
  -e SESSION_COOKIE_SECURE=false \
  stricknani
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
`stricknani-server --help` lists the commands and settings; the NixOS options
are in [rust-nixos.md](rust-nixos.md).

## Development

Enter the Rust development shell for the native and repository checks:

```sh
nix develop '.#rust'
export UV_PYTHON=python3.13
just ci-setup-py
just test
just check
```

`just check` runs the tooling lint (Ruff, MyPy, Biome on the Rust server's
CSS), Nix lint, strict Clippy, native tests, the tooling tests, web checks and
translation checks. The web app has its own commands in
[`web/README.md`](../web/README.md). `just run` starts the Rust dev server.

The Python in this repository is tooling only: `scripts/` holds the i18n
catalog generators and `ci/stricknani/` the Playwright browser and fixture
harnesses. `uv` installs it from `pyproject.toml` (`just setup`). The legacy
Python application is the signed tag `v0.1.0` on `main`. The root
`Dockerfile` builds the Rust runtime.
