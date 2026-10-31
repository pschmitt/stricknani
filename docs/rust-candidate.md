# Rust candidate development

The candidate is developed on the `rust` branch. The Python production outputs
remain the default while the Rust product is incomplete. Do not route production
traffic to this candidate or share production storage with it.

## Storage and startup

The Rust binary requires `RUST_DATABASE_URL` (SQLite) and an absolute
`RUST_MEDIA_ROOT`. It does not fall back to `DATABASE_URL` or `MEDIA_ROOT`.
When both sets are present, matching database or media paths are rejected.
An existing database must carry the Rust application ownership marker; a
Python/Alembic or unrelated SQLx database is rejected before Rust migrations run.
An empty database is initialized using embedded SQL migrations.

Set `BIND_HOST` to an IP address. Port precedence is `BIND_PORT`, then `PORT`,
then 7674. `STATIC_ROOT` can override the packaged static asset directory.
The Nix wrapper supplies its installed static directory automatically.

`stricknani-server --help` does not need storage configuration. The service
runs with `stricknani-server serve` (or no subcommand).

## Accounts

`stricknani-server create-user EMAIL` reads a password from standard input.
Provide it through a protected file descriptor or a password prompt that pipes
into stdin, rather than putting a password into a command argument or shell
history. Newly created accounts require a password of 12–1024 bytes and use
Argon2id. Imported bcrypt passwords are verified and upgraded to Argon2id on a
successful password login; importing accounts must preserve their IDs and PAT
hashes so Android credentials remain usable.

`disable-user EMAIL` disables an account and its API access. `revoke-token ID`
revokes an individual token. These administrative CLI commands require access
to the candidate database; there is no unauthenticated account bootstrap HTTP
endpoint. Browser account management is a later product slice.

Password-token requests reserve rate-limit attempts atomically and refund
successful attempts. The limiter retains at most 4,096 hashed IP/email keys and
fails closed when capacity is exhausted. It resets on restart and is not a
shared multi-instance limiter. Password checks have bounded concurrency.
Peer address is used directly; forwarded headers are not implicitly trusted.
A trusted reverse-proxy policy is required before a shared public deployment.

## API and data behavior

Initial `/api/v1/` preserves the Android-facing contract. New schema internals
are independent of Alembic. Categories are referenced by project foreign keys;
renames/deletes advance project timestamps. Yarn/project association changes
also advance timestamps on affected resources so offline synchronization sees
both sides. Deletion tombstones survive entity deletion. Resource IDs are not
reused.

All persisted resource and sync timestamps use UTC, six fractional digits, and
`Z`. Offset-aware and naive UTC incoming watermarks are normalized before SQL
comparisons. Timestamp watermarks and opaque pagination cursors remain distinct.
Future incompatible protocol changes belong under `/api/v2/`.

## Verification and remaining acceptance

Remote Rust formatting, Clippy, and 51 tests passed after integrating project,
yarn, category, sync, authentication, and media workflows. The current Kotlin
Retrofit interfaces passed an actual HTTP contract test against a disposable
Rust service: login, CRUD, favorites, synchronization, uploads, and protected
media. The complete Android JVM test suite also passed against that fixture. The
existing Python suite passed 306 tests with one skip; Ruff and mypy also passed. `nix flake check`, the candidate image build, and a disposable Podman health
test passed; the Rust runtime closure has eight paths and no Python interpreter.
These are incremental results, not final release acceptance or
emulator/UI end-to-end evidence.

The current work still needs complete browser workflows, imports and external
integrations, the one-time importer and rehearsal, actual Android-to-Rust
integration, full container verification, and isolated candidate acceptance.
Media and sync changes must be tested together with their parent resource DTOs.
The final Python-free default package and production switch remain gated on
that acceptance. Production cutover requires the user's explicit approval.
