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
`/var/lib/stricknani` as its data directory. Its default database is
`stricknani.sqlite3` and its default media directory is `media` under that
data directory, the same layout as the container image.

The module translates its service options into the Rust environment names:
`STRICKNANI_DATABASE_URL`, `STRICKNANI_MEDIA_ROOT`, `BIND_HOST`, `BIND_PORT`,
`TRUSTED_HOSTS`, and `SESSION_COOKIE_SECURE`. It deliberately does not pass the
legacy `DATABASE_URL` or `MEDIA_ROOT` aliases in Rust mode. Rust supports
file-backed SQLite only; the module rejects PostgreSQL and in-memory URLs for
this backend. Rust SQLite URLs may use either `sqlite:/absolute/path` or
`sqlite:///absolute/path`; scheduled backups normalize both forms before
calling `sqlite3`.

File-backed databases run in WAL mode behind a pool of four connections, so
reads do not wait for writes; the first start of this version converts an
existing rollback-journal database to WAL (the setting persists in the file).
The live database is then the main file plus `-wal` and `-shm` sidecars: back
it up only with `sqlite3 .backup` (as the module does) or `VACUUM INTO`, never
by copying the main file alone. Write transactions use `BEGIN IMMEDIATE`; see
the module docs of `src/db.rs`.

`registration.enable` (default `false`) sets `SIGNUP_ENABLED`: when `true`,
anyone who can reach the instance can create a member account on the web or in
the Android app. Leave it off for a private instance.

`android.appLinkFingerprints` (default empty) sets
`ANDROID_APP_LINK_FINGERPRINTS`: the signing certificates of the Android app
builds that may open this instance's links (Android App Links, served as
`/.well-known/assetlinks.json`).

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

`nginx.maxRestoreSize` (default `"2g"`) is the largest request body nginx accepts
on `= /api/v2/account/import`, the data export restore (see `docs/data-export.md`).
That location gets its own `client_max_body_size`, switches request buffering off
so the archive streams to the server, and allows 600 s for a slow upload. The
server caps a restore at 2 GiB itself; raise or lower the option to match.

The system CLI command remains `stricknani-cli`. In Rust mode it forwards its
arguments to `stricknani-server` while running as the service account and
providing the Rust database and media settings. Rust commands include
`create-admin EMAIL`, `create-user EMAIL`, `disable-user EMAIL`,
`set-avatar EMAIL PATH`, `clear-avatar EMAIL`, `revoke-token ID`, and
`import-snapshot`. Account passwords are read from
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

## Backups and upgrades

All backups go to `backup.dir` (default `<dataDir>/backups`). The module also
passes it to the server as `STRICKNANI_BACKUP_DIR`, and the service may write
there. Three kinds of files live in it:

| File | Written by | When |
| --- | --- | --- |
| `stricknani-<YYYYmmdd-HHMMSS>.tar.gz` | scheduled job (`backup.schedule`, `backup.retention`) | nightly; database and media |
| `predeploy-<version>-<YYYYmmdd-HHMMSS>.sqlite3` | module, `ExecStartPre` | the service starts with a different package |
| `pre-migrate-<from>-to-<to>-<YYYYmmdd-HHMMSS>.sqlite3` | server | startup finds pending schema migrations |

So bumping the pin needs no manual backup; check that a `predeploy-*` file
appeared after the rollout. All times are UTC and the files are mode 0600.

**Pre-deploy backup** (`backup.beforeUpgrade.enable`, default `true`;
`backup.beforeUpgrade.retention`, default 10). Before every start the module
compares the store path of `services.stricknani.package` with the one recorded
in `<dataDir>/.predeploy-backup-package`. If they differ and the database
exists, it runs `sqlite3 .backup`, checks the copy with
`PRAGMA integrity_check`, prunes `predeploy-*` beyond the retention (nothing
else) and records the new path. If the backup fails, the service does not start
and the next attempt retries. The record is written right after the backup, not
after a successful start: a new version that crash-loops therefore does not take
a backup per attempt and push the real pre-upgrade copy out of the retention.
Restarts with the same package, and configuration-only changes, take no backup.
A first installation (no database yet) takes none either.

**Pre-migration backup** (`backup.beforeMigration.enable`, default `true`;
`backup.beforeMigration.retention`, default 10; environment
`STRICKNANI_PRE_MIGRATE_BACKUP`, `STRICKNANI_PRE_MIGRATE_BACKUP_RETENTION`).
This is done by the server itself, so it also protects container and manual
installs, and a NixOS deployment that disabled the pre-deploy backup. At startup
the server compares the migrations recorded in `_sqlx_migrations` with the
embedded ones. A new, empty database or an up-to-date one needs no backup. With
pending migrations the server writes a `VACUUM INTO` snapshot (consistent with
the WAL), verifies it with `PRAGMA integrity_check` and only then migrates. If
the snapshot cannot be written or verified, startup fails with an error and the
database is not changed. It logs one info line with the path and size, never
contents. Pruning only touches `pre-migrate-*.sqlite3`. Outside NixOS the
directory is `STRICKNANI_BACKUP_DIR`, default `backups` next to the database
file.

Both layers complement each other: the module covers every version bump, the
server guarantees a copy right before a schema change on any installation.

To restore a snapshot, stop the service and replace the database file with the
copy (remove stale `-wal` and `-shm` files next to it).

`nix flake check` includes `predeploy-backup`, a NixOS VM test with a stub
server package that checks: no backup on first start or on a same-package
restart, and exactly one verified backup after a package change.

## Admin backups and restore

Administrators get a Backups view (web `/app/admin/backups`, the Android
Admin screen; API `GET /api/v2/admin/backups`). It lists the files in the
backup directory, `STRICKNANI_BACKUP_DIR` (the module sets it to
`services.stricknani.backup.dir`; elsewhere it defaults to `backups` next to the
database file): the nightly `stricknani-<ts>.tar.gz` (`database.sqlite3` plus
`media/`), `predeploy-*`, `pre-migrate-*` and `pre-restore-*` database copies,
and any other `*.sqlite3` / `*.tar.gz` file (shown as "manual"). Only regular
files of that directory are listed; symlinks and hidden files are ignored.
Downloading backups is deliberately not offered: they contain every account's
data.

A restore needs an administrator, a recent re-authentication (action
`restore_backup` of `POST /api/v2/account/reauth`) and the exact backup name.
The server first writes `pre-restore-<ts>.sqlite3` (`VACUUM INTO` plus an
integrity check; the restore aborts if that fails), then stages the chosen
database next to it and persists `.restore-request.json`. It rejects writes
from then on and stops with exit status 75. On the next start, before the
database pool opens, the swap runs: the live database and its `-wal`/`-shm`
move aside, the staged file takes its place, the migrations run (a backup of an
older schema is migrated forward; one from a newer server is refused), the
audit event `backup_restored` is recorded and the aside files are deleted. If
the restored database cannot be opened or migrated the original files are put
back and the failure is shown in the Backups view. A request older than an hour
is discarded.

The module's unit has `RestartForceExitStatus = [ 75 ]`, so systemd restarts the
server within `RestartSec`. Without a supervisor (for example `just run`) the
server exits and the restore completes the next time you start it; set
`STRICKNANI_RESTORE_AUTO_RESTART=false` to keep it running after staging and
restart it yourself. A container needs a restart policy.

Restoring an archive can add the media files that are missing
(`media: "add_missing"`); it never overwrites or deletes a file. After a
restore the server's data epoch (`data_epoch` in `GET /api/v2/meta`) changes,
and the web and Android apps discard their caches and queued offline changes:
changes made on a device but not yet synced are lost.

## Web Push

Timer reminders reach browsers through Web Push when a user turns on
Notifications in Account settings, also with no Stricknani tab open. The
server creates its VAPID key on first use and keeps it in the database, so
there is nothing to provision. `STRICKNANI_PUSH_SUBJECT` optionally sets the
VAPID contact (a `mailto:` or `https:` URI; default: the project page). Pushes
only go to the browsers' push services (Google, Mozilla, Apple, Microsoft),
so the server needs outbound HTTPS to them.

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
