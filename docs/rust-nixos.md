# Stricknani backends in the NixOS module

The NixOS module defaults to Rust on this branch:

```nix
services.stricknani = {
  enable = true;
  hostName = "knitting.example.invalid";
  nginx.enable = true;
};
```

To select the Python reference implementation explicitly, set
`services.stricknani.backend = "python"`.

With `backend = "rust"`, the module selects the Rust package by default and
uses `/var/lib/stricknani-rust` as its data directory. Its default database is
`rust.sqlite3` and its default media directory is `media` under that data
directory. With `backend = "python"`, the module selects the Python package
and uses `/var/lib/stricknani`, `stricknani.db`, and `media`. If overriding
`dataDir` or `mediaRoot`, keep Rust paths separate from the Python database
and media tree. Do not configure Rust with the Python data paths.

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
PostgreSQL backup behavior remains available in Python mode and is rejected in
Rust mode. Changing the backend does not convert an existing backup or make
Python data usable in the Rust schema.
