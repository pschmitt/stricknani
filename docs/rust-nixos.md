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
`RUST_DATABASE_URL`, `RUST_MEDIA_ROOT`, `BIND_HOST`, `BIND_PORT`,
`TRUSTED_HOSTS`, and `SESSION_COOKIE_SECURE`. It deliberately does not pass the
legacy `DATABASE_URL` or `MEDIA_ROOT` aliases in Rust mode. Rust supports
file-backed SQLite only; the module rejects PostgreSQL and in-memory URLs for
this backend. Rust SQLite URLs may use either `sqlite:/absolute/path` or
`sqlite:///absolute/path`; scheduled backups normalize both forms before
calling `sqlite3`.

`sessionCookieSecure` defaults to the module's HTTPS virtual-host setting. Set
it to `true` when HTTPS terminates at an external proxy. A non-loopback Rust
listener also requires secure cookies and an explicit trusted-host allowlist;
the module supplies the configured hostname, aliases, and loopback names.
Rust feature flags and provider credentials can be supplied with the existing
`extraConfig` and `secretKeyFile` runtime interfaces. For example, configure
`RUST_AI_ENABLED=true` and the provider settings only when enabling AI. Keep
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
provide the password on standard input. A data migration must use a protected,
quiescent copy and the Rust `import-snapshot` command as described in
[the migration plan](rust-migration-plan.md). Keep the Python service and its
database/media paths in place until a separately approved cutover.

For example, use a protected password file rather than placing the password in
the command line:

```sh
stricknani-cli create-admin admin@example.invalid < /secure/path/admin-password
```

The Rust module backup archives the configured SQLite database and media root.
PostgreSQL URLs are rejected (the legacy module's PostgreSQL backup went away
with the Python backend). A backup does not make legacy Python data usable in
the Rust schema.
