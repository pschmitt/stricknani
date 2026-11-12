# Install and run

The server needs its own SQLite database (`STRICKNANI_DATABASE_URL`) and media
directory (`STRICKNANI_MEDIA_ROOT`).

The historical `RUST_` prefix of the server variables is gone (`RUST_SIGNUP_ENABLED`
is now `SIGNUP_ENABLED`); the database, media and Sentry variables use the
`STRICKNANI_` prefix. The old names are no longer read.

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
across restarts (the database runs in WAL mode: back it up with `sqlite3 .backup`,
not by copying the file alone). On startup, before applying pending schema
migrations to an existing database, the server writes a verified snapshot to
`/var/lib/stricknani/backups/pre-migrate-<from>-to-<to>-<timestamp>.sqlite3`
(`STRICKNANI_BACKUP_DIR` changes the directory,
`STRICKNANI_PRE_MIGRATE_BACKUP=false` turns it off,
`STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION` keeps the newest 10 by default) and
refuses to start if that fails. The NixOS module additionally backs up before
every package change; see `docs/rust-nixos.md`. Check `http://localhost:7674/healthz`, then create the first
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
To let people create their own accounts (web and Android), set
`SIGNUP_ENABLED=true`. While it is off, the sign-in page and the Android
app show a card that points to this repository and offers a setup prompt for an
AI coding agent. Without self-service registration, add accounts with
`stricknani-server create-user EMAIL`.

To let the Android app open this instance's project and yarn links directly
(for example a link sent by messenger), set `ANDROID_APP_LINK_FINGERPRINTS` to
the SHA-256 fingerprints of the app's signing certificates (comma separated,
`apksigner verify --print-certs app.apk` shows them). The server then answers
`/.well-known/assetlinks.json`; `ANDROID_APP_PACKAGE` (comma separated) overrides the package names
`blue.anika.wolle` and `blue.anika.wolle.debug`. On a self-hosted domain the user still enables the link
under the app's "Open by default" settings, since the app can only verify
domains it names itself.

### Passkeys

People can sign in with a passkey (WebAuthn: fingerprint, face or screen lock)
as an optional alternative to their password, on the web and in the Android app.
Passwords keep working and a passkey never replaces them. Adding or removing a
passkey asks for a fresh confirmation (password, linked login or another
passkey), and a passkey can confirm sensitive actions the same way.

* **HTTPS and a DNS name.** Browsers only offer WebAuthn on HTTPS (and on
  `localhost` for development). Passkeys are off on an instance whose
  `TRUSTED_HOSTS` holds only IP addresses.
* **RP ID.** The relying-party ID is the instance host name: the first DNS name in
  `TRUSTED_HOSTS`, or `PASSKEY_RP_ID` (a trusted host or a parent domain of one).
  Passkeys are bound to it: changing the host name or the RP ID makes every
  registered passkey unusable (the password still works). Origins accepted are the
  trusted DNS names under the RP ID, over `https` when `SESSION_COOKIE_SECURE=true`.
* **Android.** The app signs in and registers through Credential Manager. It needs
  `ANDROID_APP_LINK_FINGERPRINTS` (see above): the server then adds
  `delegate_permission/common.get_login_creds` to `/.well-known/assetlinks.json`
  so Android lets the app use this site's passkeys, and accepts the app's origin
  `android:apk-key-hash:<base64url SHA-256 of the signing certificate>`, one per
  fingerprint. The app and the web app therefore share the same passkeys.
* **Switch off.** `PASSKEYS_ENABLED=false` hides the buttons and answers
  `404 passkeys_unavailable`; registered passkeys stay in the database.
* An account holds at most 10 passkeys, which are removed with the account. A
  disabled account cannot sign in with a passkey. Hardware keys that cannot store
  a discoverable credential can confirm sensitive actions but not sign in without
  an email address.

Optional Sentry error reporting stays off unless `STRICKNANI_SENTRY_DSN_FILE` (or
`STRICKNANI_SENTRY_DSN`) is set; see [rust-nixos.md](rust-nixos.md#error-reporting-sentry).

## Nix package

The default flake package is the Rust server. Configure a database and media
directory before starting it:

```sh
export STRICKNANI_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/stricknani"
mkdir -p "$STRICKNANI_DATA_DIR/media"
export STRICKNANI_DATABASE_URL="sqlite:$STRICKNANI_DATA_DIR/stricknani.sqlite3"
export STRICKNANI_MEDIA_ROOT="$STRICKNANI_DATA_DIR/media"
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
just test
just check
```

`just check` runs Nix lint, strict Clippy, native tests, web checks and translation checks. The web app has its own commands in
[`web/README.md`](../web/README.md). `just run` starts the Rust dev server.

There is no Python in this repository any more. The i18n catalog generators
live in `web/scripts/i18n/` (`npm run i18n:*`, wrapped by `just i18n-*`) and the
Playwright browser acceptance suite and fixture CLIs in `web/e2e/`
(`npm --prefix web run e2e`, `fixture`, `seed`, `summarize-e2e`,
`verify-import`; run `npm ci --prefix web` first). The legacy Python
application is the signed tag `v0.1.0` on `main`. The root `Dockerfile` builds
the Rust runtime.
