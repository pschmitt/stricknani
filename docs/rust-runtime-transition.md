# Rust runtime outputs and container

This branch makes the Rust service the default flake runtime and NixOS module
backend while keeping the Python runtime available through an explicit backend
selection and for the existing Android end-to-end workflow. Production still
uses a separately pinned older module revision, so this source change does not
alter or deploy the production service. Rust acceptance and a separately
approved production cutover remain pending.

## Flake outputs

| Output | Runtime |
| --- | --- |
| `.#default`, `.#stricknani`, `nix run` | Rust server |
| `.#stricknani-rust` | Rust compatibility alias |
| `.#stricknani-docker` | Rust container image using the stable image name |
| `.#stricknani-rust-docker` | Rust candidate image |

The repository's root `Dockerfile` builds `.#stricknani` from the pinned flake
and copies its Nix runtime closure into a `scratch` image. The final image has
no Python runtime. The Python image recipe and `.#stricknani-python*` outputs
were removed with the legacy application; the last Python release is the signed
tag `v0.1.0` on `main`.

For source checkouts, build the default Rust image with:

```bash
docker build -t stricknani:rust .
```

See the repository README for run and first-admin examples. Direct flake
package users must set `RUST_DATABASE_URL` and `RUST_MEDIA_ROOT`. The service
accepts only a SQLite URL and an absolute media directory. It does not fall
back to Python's `DATABASE_URL` or `MEDIA_ROOT`.

## Rust container settings

The image listens on port `7674` and runs as UID/GID `65532`. Mount persistent
storage at `/var/lib/stricknani`; the configured SQLite database is
`/var/lib/stricknani/stricknani.sqlite3` and uploads go under
`/var/lib/stricknani/media`. The image provides the directory with the right
ownership, but the container runtime must preserve it across restarts. A
read-only root filesystem can be used with writable `/tmp` and persistent data
mounts.

Set `TRUSTED_HOSTS` to the exact host authority clients use, including a port
when applicable. The service requires this allowlist when bound off loopback;
it does not trust forwarded host or scheme headers. For a public deployment,
terminate TLS at a trusted reverse proxy and set
`SESSION_COOKIE_SECURE=true`. Registration, external imports, AI, OCR, and
Wayback access are disabled in the image defaults; the server defaults these
features off as well. Configure them explicitly only when the deployment needs
them. AI enhancement requires both provider configuration and per-request
opt-in; see [AI provider settings](rust-ai.md).

The container health check runs `stricknani-server health-check`. It probes
only `127.0.0.1:<BIND_PORT>/healthz`, sends the first configured trusted host
as the `Host` header, disables proxy use and redirects, and has a three-second
timeout. It prints only a fixed status or error.

Create an administrator by running `stricknani-server create-admin EMAIL` in
the container and supplying a password of at least 12 Unicode characters and
at most 1,024 UTF-8 bytes on standard input.
Do not put it in command arguments or shell history. The Rust service uses its
own database and media paths. It does not automatically migrate or bootstrap
the Python service's data; use the documented snapshot importer on a protected
copy when preparing migration evidence.

## Rollout boundary

This branch's NixOS module defaults to Rust; set
`services.stricknani.backend = "python"` to select the Python package and
legacy data paths explicitly. The production configuration still imports its
separately pinned older module revision, so changing this branch's default does
not switch the deployed service. Production remains on Python until Rust
acceptance, a production input update, and a separately approved cutover are
complete. Keep Python data and Rust data separate during staging.
