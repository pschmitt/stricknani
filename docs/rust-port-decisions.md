# Rust rebuild decisions

T119 rebuilds product capabilities in Rust. The current `/api/v1/` routes and
Android-facing request/response semantics are an explicit compatibility
boundary for the first Rust release. Python internals, schema, and migration
history are design evidence only. The Kotlin app is in scope and must be tested
against the Rust service.

## Web and rendering

Use Axum with Tokio for the standalone HTTP service. It provides the server
boundary for the HTML application and Android API without binding Rust to the
Python runtime.

Use Askama for server-rendered HTML. Keep templates in separate HTML files,
with explicit Rust view-context structs and compile-time template checks. This
fits the size of the existing page surface while making template inputs
visible to Rust's type checker. Rewrite Jinja templates instead of preserving
their syntax.

Maud is the alternative if keeping markup in Rust source is more valuable than
separate HTML files. Its compile-time HTML macro and escaping are attractive,
but moving the app's broad set of pages into Rust macro code makes the initial
port and later markup review larger. Askama is the better starting balance.

MiniJinja deliberately follows Jinja2 closely; that minimizes template
conversion but keeps the old dynamic template model. The new brief explicitly
allows replacing Jinja, so it is not the selected renderer. Leptos SSR and
hydration provide a full Rust component model, but add a WASM build and client
runtime that the current server-rendered product does not need. Keep ordinary
pages progressively usable and add small JavaScript enhancements only where
they help a workflow.

Sources: [Askama](https://askama.rs/en/stable/doc/askama/index.html),
[Askama Axum integration](https://askama.rs/en/stable/frameworks.html),
[Maud](https://docs.rs/maud/latest/maud/),
[MiniJinja](https://docs.rs/minijinja/latest/minijinja/),
[Leptos SSR](https://book.leptos.dev/ssr/index.html).

## Data and Android API direction

Keep the current `/api/v1/` routes and Android-facing request/response
semantics as the initial Rust v1 contract, including authentication, paging,
delta sync, uploads, and media. Keep the Rust domain and database schema
independent of SQLAlchemy's shape. Test the current Kotlin app against this
Rust v1 implementation. Stage the same paths on `rust.anika.blue`; on
production cutover, the existing production URL will serve Rust directly,
without a client-side 3xx redirect.

Within v1, make only backward-compatible API changes: add endpoints or
optional request fields, and add response fields that Android clients safely
ignore. Avoid changing field meaning, authentication, pagination/cursor
behavior, or mutation semantics within that version. Put future breaking
changes under `/api/v2` and update the Kotlin client in the same release. The
current v1 preservation is an explicit initial API boundary, not a reason to
copy the Python data model or migration history. Keep the existing public
production URL stable at cutover by routing it to Rust; clients must not receive
an HTTP redirect during cutover.

The starting database choice is SQLite with SQLx and embedded SQL migrations.
Stricknani is a single self-hosted application with local media and modest
write concurrency; a separate database service is not needed for the initial
Rust product. Use foreign keys and transactional updates, and test migrations
from a fresh database. This is a new schema, not a translation of SQLAlchemy
models or Alembic revisions. Reassess only if product/deployment requirements
show a concrete need for PostgreSQL.

Add a one-time importer from an isolated copy of the production database and
media into the fresh Rust schema. Rehearse it against a separate copy, then
verify records, relationships, owner permissions, and media references. The
final cutover procedure must take a final snapshot after an approved write
freeze, verify the imported copy, and document backup and rollback steps. Rust
must never connect to or share live Python production storage.

Model ownership and related data explicitly in Rust: accounts, projects,
yarns, categories, favorites, project steps, media metadata, audit/change
events, and revocable device credentials. Give offline synchronization a
monotonic server cursor and explicit deletion records. Treat client mutations
as idempotent operations with conflict results so a queued Android edit can
be retried without duplication. Keep the current `/api/v1` routes and payloads
as the external contract while implementing those semantics over the new
internal model.

Keep the Android app's Room cache and offline mutation queue as the product
shape. First run its current API flow against Rust v1 with disposable
SQLite/media state. Update Retrofit DTOs/services only for discovered
integration defects or additive features; test the Kotlin client against the
actual Rust HTTP service. Android unit tests with mocked responses are useful
but do not replace that integration evidence.

## Browser behavior

Keep the product's useful workflows recognizable: account access; project
and yarn browsing/search/filtering; create, edit, delete, and import; category
and favorite management; photos, attachments, and project steps; gauge
calculation; and administrative operations. HTMX fragments, headers, swap
targets, and Jinja syntax can go. Ordinary GET forms and POST/redirect/GET
flows are preferred; small JavaScript/fetch enhancements can support search
suggestions, favorite toggles, or instant calculator output.

## Gauge calculation choice

Use integer arithmetic for counts rather than copying Python's floating-point
`round()` behavior. Round to the nearest whole stitch/row, with exact halves
rounded up; this is easy to explain to knitters and avoids float precision
surprises. Validate positive, bounded counts with a clear form error. The
existing Python calculator is useful as a source of examples, not a rounding
or error-payload contract. The Android calculator remains a native offline
feature; no network API is needed for it.

## Parallel candidate isolation

The authorized `https://rust.anika.blue` candidate is a later validation
step. Use a dedicated service configuration, credentials, database, media and
import-trace directories, and its own service identity and mounts. A copy of
production data may be used after the importer is safe; protect candidate
access because copied data may be private. Never reuse or share live production
database URLs, volumes, credentials, session keys, or queues. Disable
background jobs until their behavior and side effects are implemented and
reviewed; later give every enabled job isolated inputs and outputs. Keep
candidate routing separate from the production hostname and verify every
environment variable and mount before startup. No candidate is deployed as
part of this code slice.

## Nix and container direction

The existing flake's `default`, `stricknani`, `stricknani-docker`, NixOS module,
root `Dockerfile`, and GHCR workflow currently build and run the Python app.
During the rewrite, preserve these production-facing outputs until the Rust
backend and Android/browser acceptance surface are complete. Add separate
Rust package, app, check, dev-shell, and candidate image outputs; exercise them
without publishing an image or changing the default deployment package. The
final runtime must contain no Python. At completion, switch the existing
deployment-facing outputs and container workflow to the Rust binary, then
verify the Nix package, image startup/health, and the Android integration
against the same versioned `/api/v1/` service.

## Review findings and remaining work

- The current Rust slice provides health and one complete Askama gauge page
  flow. Gauge arithmetic is a pure domain module; Askama view data and the
  template stay in the presentation layer so the planned visual redesign can
  replace them without changing the calculation. It does not yet provide the
  current `/api/v1/` contract or the rest of the browser workflows.
- The Android client is substantial and already offline-first. API work must
  cover current v1 sync, queued writes, uploads, token enrollment, and media
  access; testing only the meta or health endpoint would not satisfy the
  Android integration requirement.
- The first browser slice will be the gauge calculator because it is
  independent of authentication and persistent data. Follow it with a
  project or yarn offline-sync slice that includes Rust storage, API, Kotlin
  client behavior, and a real backend integration test.
- Acceptance and production cutover are distinct. Candidate deployment is
  allowed only after config, database, media, jobs, and routing are verified
  isolated; production remains unchanged until the user's explicit approval.

## Account and synchronization implementation decisions

The Rust-owned SQLite schema has its own ownership marker and embedded SQLx
migrations. Startup refuses Python/Alembic and unrelated databases before
applying migrations. Candidate configuration requires explicit Rust database
and media paths, and rejects paths shared with legacy settings when present.
Accounts use Argon2id; copied bcrypt hashes are upgraded after successful login.
PAT hashes remain SHA-256 and tokens retain the recognizable `sna_` prefix.

Categories use foreign-key references; project/yarn associations and ordered
steps have explicit relational tables. IDs use AUTOINCREMENT to prevent reused
IDs from colliding with offline tombstones. Relation changes advance affected
resource timestamps. Persisted timestamps have one canonical UTC microsecond
format (`Z`); sync normalizes incoming offsets before text comparisons.

See [candidate development](rust-candidate.md) for CLI/configuration, incremental
verification results, limiter/proxy limitations, and outstanding acceptance.
