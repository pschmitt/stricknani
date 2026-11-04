# Stricknani in the NixOS module

The NixOS module runs the Rust server:

```nix
services.stricknani = {
  enable = true;
  hostName = "knitting.example.invalid";
  nginx.enable = true;
};
```

The Python backend was removed from the module together with the legacy
application. Rust is the only implementation; `services.stricknani.backend`
remains as an option that accepts only `"rust"`, so existing configurations
keep evaluating. The legacy Python release is the signed tag `v0.1.0` on
`main`, and its module revision is the rollback.

The module selects the Rust package by default and uses
`/var/lib/stricknani-rust` as its data directory. Its default database is
`rust.sqlite3` and its default media directory is `media` under that data
directory. The legacy Python service used `/var/lib/stricknani`; the module
rejects that directory as `dataDir`, so keep Rust paths separate from the
legacy database and media tree. Do not configure Rust with the legacy data
paths.

This branch's module default does not change a deployed service whose
production configuration still imports a separately pinned older module
revision. Advancing that production input, migrating data, and switching
traffic remain separate steps that require the documented acceptance and
approval process.

The module translates its service options into the Rust environment names:
`STRICKNANI_DATABASE_URL`, `STRICKNANI_MEDIA_ROOT`, `BIND_HOST`, `BIND_PORT`,
`TRUSTED_HOSTS`, and `SESSION_COOKIE_SECURE`. It deliberately does not pass the
legacy `DATABASE_URL` or `MEDIA_ROOT` aliases in Rust mode. Rust supports
file-backed SQLite only; the module rejects PostgreSQL and in-memory URLs for
this backend. Rust SQLite URLs may use either `sqlite:/absolute/path` or
`sqlite:///absolute/path`; scheduled backups normalize both forms before
calling `sqlite3`.

`registration.enable` (default `false`) sets `SIGNUP_ENABLED`: when `true`,
anyone who can reach the instance can create a member account on the web or in
the Android app. Leave it off for a private instance.

`sessionCookieSecure` defaults to the module's HTTPS virtual-host setting. Set
it to `true` when HTTPS terminates at an external proxy. A non-loopback Rust
listener also requires secure cookies and an explicit trusted-host allowlist;
the module supplies the configured hostname, aliases, and loopback names.
Rust feature flags and provider credentials can be supplied with the existing
`extraConfig` and `secretKeyFile` runtime interfaces. For example, configure
`AI_ENABLED=true` and the provider settings only when enabling AI. Keep
those values in the protected environment file, not in Nix expressions. Set
database and media paths through `databaseUrl` and `mediaRoot`, not
`extraConfig`, so the server and backup job use the same paths.

The system CLI command remains `stricknani-cli`. In Rust mode it forwards its
arguments to `stricknani-server` while running as the service account and
providing the Rust database and media settings. Rust commands include
`create-admin EMAIL`, `create-user EMAIL`, `disable-user EMAIL`,
`revoke-token ID`, and `import-snapshot`. Account passwords are read from
standard input, not command-line arguments.

Rust startup migrates only its own empty Rust database. It does not upgrade the
Python/Alembic schema, bootstrap an administrator from Python's
`INITIAL_ADMIN_*` environment variables, or import Python records. Create the
first administrator explicitly with `stricknani-cli create-admin EMAIL` and
provide the password on standard input. Importing data from the legacy Python
app uses a protected, quiescent copy and the `import-snapshot` command.

For example, use a protected password file rather than placing the password in
the command line:

```sh
stricknani-cli create-admin admin@example.invalid < /secure/path/admin-password
```

The Rust module backup archives the configured SQLite database and media root.
PostgreSQL URLs are rejected (the legacy module's PostgreSQL backup went away
with the Python backend). A backup does not make legacy Python data usable in
the Rust schema.

## Error reporting (Sentry)

Error reporting is optional and off unless the operator configures a DSN. With
none, the server never starts the Sentry SDK, the clients are told it is
unavailable (and hide their settings), and the tunnel route answers 404.

| NixOS option | Environment variable | Meaning |
| --- | --- | --- |
| `sentry.dsnFile` | `STRICKNANI_SENTRY_DSN_FILE` | Absolute runtime path of a file holding the DSN. Preferred; keep the DSN out of the Nix store. `STRICKNANI_SENTRY_DSN` is the plain-value alternative for containers. |
| `sentry.frontendDsnFile` | `STRICKNANI_SENTRY_FRONTEND_DSN_FILE` | Optional separate DSN for the web and Android apps (`STRICKNANI_SENTRY_FRONTEND_DSN` also works). Falls back to the server DSN, so one Sentry project can serve everything. |
| `sentry.environment` | `STRICKNANI_SENTRY_ENVIRONMENT` | Environment name (default `production`). |
| `sentry.tracesSampleRate` | `STRICKNANI_SENTRY_TRACES_SAMPLE_RATE` | 0 to 1 (default `0`: errors only). |

The service user must be able to read the file (for example through
`LoadCredential`). Use a current-style DSN without a secret key; the public
part is handed to the web app and the Android app, which send their reports
through the server's same-origin tunnel `POST /api/v2/telemetry/sentry` and
never contact the Sentry host themselves. The tunnel only forwards envelopes
addressed to the configured DSN, caps them at 256 KiB, scrubs them again, and
drops them when the signed-in user turned error reporting off.

Every event carries a `component` tag (`server`, `web` or `android`) and a release of the form `stricknani-<component>@<version>+<commit>`, so one project can be filtered. The tunnel forwards an envelope to the host and project of whichever configured DSN it names (matched by key and project id).

What is reported: panics and `error!` log events from the server, plus errors
from the web and Android apps (message, stack trace, app version, browser or OS
version). Request headers, cookies, bodies, query strings, IP addresses, emails
and tokens are removed before anything leaves the server; the only user field is
the numeric account id. Users opt out under Account, Preferences ("Send error
reports"), and in the Android app's settings; errors raised while serving an
opted-out user's request are dropped. The privacy policy page describes this to
users.
