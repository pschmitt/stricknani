<p align="center">

[![nixbot](https://nixbot.brkn.lol/repos/github/pschmitt/stricknani/badge.svg?branch=main)](https://nixbot.brkn.lol/repos/github/pschmitt/stricknani)
  <img src="branding/icon.svg" alt="Stricknani icon" width="128">
</p>

<h1 align="center">Stricknani</h1>

🧶 **A Mealie for knitting** — a self-hosted web app for managing knitting projects and yarns.

## Features

- Organize knitting projects, steps, photos, notes, and metadata
- Track yarns and link them to projects
- Built-in gauge calculator
- Private, self-hosted, mobile-friendly web UI
- Offline-first Android app using the versioned API v1

The Rust server is the default package and container runtime on this branch.
The rewrite is still in acceptance; this branch change does not mean production
has been switched. See [the runtime transition guide](docs/rust-runtime-transition.md)
for the output mapping and rollout boundary.

## Quick start

Build the Rust container from this checkout and start it with persistent storage:

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

The local-only `SESSION_COOKIE_SECURE=false` override permits sign-in over
plain HTTP on localhost. For a public deployment, use HTTPS through a trusted
reverse proxy, set `SESSION_COOKIE_SECURE=true`, and set `TRUSTED_HOSTS` to the
exact public host authority. Do not expose the app directly to the Internet.

Registration is off by default, so create the first administrator by piping a
password from a protected file to the CLI. The password is read from standard
input and must be at least 12 Unicode characters and no more than 1024 UTF-8
bytes:

```bash
docker exec -i stricknani \
  /bin/stricknani-server create-admin admin@example.invalid \
  < /path/to/protected-admin-password
```

The image listens on port `7674`, runs as UID/GID `65532`, stores the SQLite
database and uploaded media beneath `/var/lib/stricknani`, and includes a
bounded `health-check` command for `/healthz`. Keep that data directory on
persistent storage. Container and Nix output details are in the
[runtime transition guide](docs/rust-runtime-transition.md).

## Rust runtime configuration

The server requires an isolated SQLite database URL and an absolute media
directory. It does not use the legacy Python `DATABASE_URL` or `MEDIA_ROOT` as
fallbacks. The container sets these paths to its persistent data directory;
direct package users must configure them explicitly.

| Variable | Purpose | Default / requirement |
| --- | --- | --- |
| `RUST_DATABASE_URL` | Rust SQLite database | Required; use a Rust-owned database |
| `RUST_MEDIA_ROOT` | Uploaded file directory | Required; absolute path |
| `BIND_HOST` | Listener IP address | `127.0.0.1`; container sets `0.0.0.0` |
| `BIND_PORT` | Listener port | `PORT`, then `7674` |
| `TRUSTED_HOSTS` | Comma-separated exact host authorities | Loopback defaults only on a loopback listener; required otherwise |
| `SESSION_COOKIE_SECURE` | Mark session cookies secure | `false`; required `true` off loopback |
| `RUST_SIGNUP_ENABLED` | Public account registration | `false` |
| `RUST_EXTERNAL_IMPORTS_ENABLED` | Fetch external project sources | `false` |
| `RUST_AI_ENABLED` | Configure the optional AI provider | `false`; requires provider settings and a per-import opt-in |
| `RUST_OCR_ENABLED` | Enable authenticated local OCR | `false` |
| `RUST_WAYBACK_ENABLED` | Enable archive lookup jobs | `false` |
| `STATIC_ROOT` | Override packaged static assets | Packaged asset path |

All optional service flags default to disabled. Keep them disabled unless the
deployment needs the feature. AI credentials and the user opt-in are documented
in [AI provider settings](docs/rust-ai.md); OCR behavior is described in the
[OCR guide](docs/rust-ocr.md). The Wayback and external-import guides describe
their respective network behavior: [Wayback](docs/rust-wayback.md) and
[imports](docs/rust-import-design.md).

For command-line use, build and run the Rust package with:

```bash
nix build '.#stricknani'
nix run '.#stricknani'
```

Supply the required Rust database and media variables first. The
`health-check` command probes only the local
`/healthz` endpoint, has a three-second timeout, and returns fixed errors.
Account commands such as `create-admin EMAIL` also read passwords from stdin;
never put passwords in command arguments or shell history. See
the [CLI and startup notes](docs/rust-candidate.md#storage-and-startup) and the
[transition guide](docs/rust-runtime-transition.md).

## Android and API

The Android app is an offline-first client: Room is the local source of truth,
and synchronization replays queued writes against the server API. Setup,
recovery, emulator testing, and client changes are documented in
[`android/AGENTS.md`](android/AGENTS.md). The API v1 compatibility inventory
records its authentication, CRUD, media, and sync contract in
[`docs/rust-android-inventory.md`](docs/rust-android-inventory.md).

## Development

Rust formatting, tests, Nix evaluation, and builds belong on the designated
remote build host; see [`AGENTS.md`](AGENTS.md). From that host, the Rust
development shell and package are available as:

```bash
nix develop '.#rust'
cargo fmt --manifest-path rust/Cargo.toml -- --check
cargo test --manifest-path rust/Cargo.toml
nix build '.#stricknani'
```

The Python implementation remains available for legacy development and
reference work as `.#stricknani-python`. Its existing local workflow uses
Python 3.13+, `uv`, and `just`:

```bash
nix develop '.#python'
just setup
just run-python
```

The root `Dockerfile` builds the Rust runtime. `Dockerfile.python` and
`.#stricknani-python-docker` retain the Python image artifacts, including the
image used by the existing Android end-to-end workflow. These are compatibility
and development paths; they do not indicate a production cutover.

## Documentation

- [Documentation index](docs/README.md)
- [Product specification and implementation status](docs/spec-and-implementation.md)
- [Migration, verification, and rollback plan](docs/rust-migration-plan.md)
- [Current acceptance checkpoint](docs/rust-rewrite-checkpoint.md)
- [Runtime outputs and rollout boundary](docs/rust-runtime-transition.md)
- [Operational instructions for contributors](AGENTS.md)
- [Active work queue](TODO.md)

## License

GPL-3.0-only

## Maintainer

Philipp Schmitt (GitHub: @pschmitt)
