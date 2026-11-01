# One-time Python data migration

## Initial administrator bootstrap

For a fresh Rust database, create the first administrator explicitly with
`stricknani-server create-admin EMAIL`. The command reads the password from
standard input, enforces the same policy as account creation (at least 12
Unicode characters and at most 1,024 UTF-8 bytes), and sets the administrator
role in the same account insert. Keep the password out of command arguments;
supply it through a protected input source, for example:

```sh
stricknani-server create-admin admin@example.invalid < /secure/path/admin-password
```

`create-user` continues to create a regular member account.

The Rust schema is independent of the Python schema. Migrations `0007` through `0011`
retain legacy metadata needed by the one-time importer and old media URL aliases. These
migrations do not import records, access production storage, or authorize cutover.

## Preservation fields

The importer must populate the following fields from a verified isolated copy:

| Source data | Rust storage |
| --- | --- |
| Normalized login email and exact source email | `users.email` using the login normalization rule and `users.legacy_email` |
| `users.profile_image` and `users.token_version` | `users.profile_image`, `users.legacy_token_version` |
| API token last-use time | `api_tokens.last_used_at` as Unix epoch seconds |
| Category and project-step creation times | `categories.created_at`, `project_steps.created_at` |
| Legacy project free-text yarn, archive request time and failure status | `projects.legacy_yarn_text`, `projects.archive_requested_at`, `projects.archive_failed` |
| Yarn archive request time and failure status | `yarns.archive_requested_at`, `yarns.archive_failed` |
| Project image title flag, creation time, original image type and nullable stitch-sample flag | `media_files.legacy_is_title_image`, `media_files.created_at`, `media_files.legacy_image_type`, `media_files.is_stitch_sample` |
| Source media table and its original row ID | `media_files.legacy_source`, `media_files.legacy_id` |
| Audit rows, nullable FK actor, original actor ID, entity, action, details and creation time | `audit_events` |
| Avatar file metadata and user association | `user_avatars` |

`user_avatars.filename` and `users.profile_image` retain the source stored filename.
The importer must copy original avatar bytes into Rust-owned media storage and preserve
the association. A missing thumbnail can be regenerated; it must not cause the original
avatar to be dropped. Audit actors use `ON DELETE SET NULL`, so historical events survive
account deletion while retaining their original event fields.
When an old audit actor has no corresponding account row, set `actor_user_id` to
`NULL` and keep its source ID in `legacy_actor_user_id`.

## Identity, relationships, and collisions

Preserve account, project, yarn, category, step, token, and source audit IDs where the
Rust table can represent them. Normalize imported login emails with the same
`trim().to_lowercase()` rule used by authentication, and retain the source spelling in
`users.legacy_email`. Before writing imported rows, count source emails that collide
after this normalization and source categories for one owner that collide under the
Rust case-insensitive category key. If either count is nonzero, fail closed. Do not
auto-merge, drop, or alias records.

Project `category` and `yarn` source columns are free text. Map project categories to
the same owner's exact-name category row. When a project uses an exact category label
with no category row, create an owner category with that exact label and null creation
time; the source has no category timestamp for this synthesized value. If category
labels collide case-insensitively, fail closed. Always retain the legacy yarn text in
`projects.legacy_yarn_text`; project-yarn joins are additive and must not replace this
value. Preserve all many-to-many join pairs.

Python media IDs are independently allocated in `images`, `yarn_images`, and
`attachments`, while Rust stores those objects in one `media_files` table. Therefore
`media_files.id` alone cannot identify a source row. Populate `(legacy_source,
legacy_id)` with the source table namespace, and preserve each original numeric ID as
`public_id` within its legacy namespace. Never assume an ID is globally unique across
media tables. New physical filenames are opaque; `media_url_aliases` preserves old
path basenames. If multiple source rows in one owner directory share a basename, retain
every row and public ID, but reuse the old URL alias only when their bytes match.
Otherwise fail closed because one old URL cannot represent both contents.

Preserve nullable values as nullable: `images.is_stitch_sample` may be `NULL`, `0`, or
`1`; do not coerce `NULL` to false. The source `is_title_image` value is retained in
`legacy_is_title_image`. Keep source `image_type` in `legacy_image_type` even if the
runtime content type is validated separately. Imported legacy images leave Rust
`is_primary` false because its primary-title index allows one row while Python allowed
multiple title images; API v1 reads the preserved legacy flag for these rows. Convert legacy naive SQLite timestamps
to canonical UTC RFC3339 text because the source application stores UTC values without
an offset. Token timestamps become Unix epoch seconds and lose sub-second precision
because the Rust token schema stores integers. Do not synthesize timestamps for old
rows; synthesized categories have no source date and remain null.

## Importer operation

`rust/src/importer.rs` accepts only absolute canonical input paths. It opens the source
SQLite file read-only and immutable, rejects `-wal`/`-shm` sidecars, and validates the
snapshot before reading. The source database and media must already be a copied,
private, quiescent snapshot; the importer does not create that copy and must never be
pointed at production storage. Do not modify the snapshot while an import is running.
The importer hashes the database and media tree before reading and checks them again
before publication, rejects symlinks in the source media tree, and opens source media
files without following leaf symlinks on Linux. These checks detect ordinary changes
during import; they do not make a writable or adversarial source safe. Keep the source
copy read-only to all other processes for the duration of the import. The destination
parent must be outside the source media tree.

The importer creates a private temporary directory beside the requested destination,
initializes a fresh Rust database, copies and hashes original media, regenerates bounded
JPEG thumbnails, syncs the staged files, and writes an aggregate counts-only report.
On Linux it atomically publishes the complete directory with `NOREPLACE`; it never
replaces an existing destination. Failures before publication remove only the
importer's staging directory. The report counts rows whose distinct original filenames
share one legacy thumbnail URL; all media rows and original filename aliases are kept,
and the first deterministically ordered row retains that thumbnail URL.

Reports and errors must not include account identifiers, filenames, password hashes,
token hashes, or media hashes. The CLI that selects the snapshot must keep access
restricted and document the source copy, backup, and rollback locations without
publishing sensitive data.

## Rehearsal and cutover boundary

Implement and rehearse the importer only against a protected copy of the database and
media. Produce a report with row counts by source table, preserved IDs and source
keys, unresolved category collisions, orphaned relationships, missing media files,
and audit/avatar counts. Verify owner access and media references on the isolated Rust
candidate. This plan and migration do not perform a production snapshot or cutover;
production cutover remains a separate user-approved operation after acceptance.

The importer has been rehearsed on a copied snapshot. The recorded rehearsal passed
34 independent verifier checks and 38 checks through the candidate API, including
ownership, record, and original-media-byte checks. This evidence does not replace a
fresh copy made after a final production write freeze.

## Cutover and rollback runbook

This is an operational plan, not authorization to cut over. Perform it only after
the full acceptance gates pass and the user explicitly approves production cutover.
Use the production service's configured paths and service controls from its private
deployment configuration; do not add those host-specific values to this repository.

### Before the window

1. Confirm the exact Rust package/container revision, tested runtime configuration,
   importer result, and independent verification procedure. Confirm the Rust
   destination uses a fresh directory separate from both Python and any earlier Rust
   database or media.
2. Schedule a maintenance window. Tell browser and Android users to stop writes and
   keep the Android app offline during the freeze; queued Android mutations can replay
   when connectivity returns.
3. Prepare an operator-controlled maintenance response at the public edge for both
   browser and API requests. Preserve the existing production host and URL paths so a
   cutover changes the upstream only; it must not redirect clients to another host or
   path.
4. Ensure AI, OCR, external imports, Wayback, and other external side effects remain
   disabled until their production configuration has been reviewed separately.

### Freeze writes and create the final copy

1. Enable maintenance/write blocking at the edge and wait for in-flight browser and
   API writes to finish. Do not rely on asking clients to stop as the only write
   barrier.
2. Stop the Python web service, scheduled jobs, workers, and any other process that
   can modify the database or media. Confirm the SQLite database is closed before
   snapshotting. Keep maintenance active and prevent Android sync from reaching
   either backend.
3. Create a private, root-owned backup directory with mode `0700` under
   `/mnt/data/backups/stricknani/<date>-pre-rust-cutover`. Keep the date and suffix
   specific to this operation. The following commands are templates; replace only
   the uppercase path placeholders with the configured source and an empty Rust
   destination. Run them on the operator's trusted build/operations host, not against
   live source paths from an unreviewed shell:

   ```sh
   set -eu
   umask 077
   BACKUP_DIR=/mnt/data/backups/stricknani/YYYY-MM-DD-pre-rust-cutover
   SOURCE_DB=/absolute/path/to/frozen-production.sqlite3
   SOURCE_MEDIA=/absolute/path/to/frozen-production-media
   RUST_IMPORT_ROOT=/absolute/path/to/new-rust-import

   test ! -e "$BACKUP_DIR"
   install -d -o root -g root -m 0700 "$BACKUP_DIR" "$BACKUP_DIR/media"
   sqlite3 "$SOURCE_DB" ".backup '$BACKUP_DIR/source.sqlite3'"
   rsync -a --numeric-ids -- "$SOURCE_MEDIA/" "$BACKUP_DIR/media/"
   test ! -e "$BACKUP_DIR/source.sqlite3-wal"
   test ! -e "$BACKUP_DIR/source.sqlite3-shm"
   test "$(sqlite3 "$BACKUP_DIR/source.sqlite3" 'PRAGMA integrity_check;')" = ok
   ```

   The DB and media must be copied from the same frozen state. Keep the source
   snapshot private and unmodified. If sidecars exist, the integrity check fails, a
   symlink is present in the media tree, or the copy fails, stop and investigate;
   never weaken importer validation. Do not delete or overwrite prior backups.

### Import and verify before routing

1. Ensure `RUST_IMPORT_ROOT` does not exist and is outside the snapshot media tree.
   Run the importer from the exact Rust candidate checkout:

   ```sh
   stricknani-server import-snapshot \
     --source-db "$BACKUP_DIR/source.sqlite3" \
     --source-media "$BACKUP_DIR/media" \
     --destination "$RUST_IMPORT_ROOT"
   ```

   The importer atomically publishes a new Rust directory and refuses to replace an
   existing destination. Configure the Rust service to use
   `sqlite:$RUST_IMPORT_ROOT/database.sqlite3` and `$RUST_IMPORT_ROOT/media`; do not
   point it at Python paths.
2. Run the independent read-only verifier against the same snapshot and destination.
   Keep its aggregate report inside the protected backup directory:

   ```sh
   python ci/stricknani/verify_rust_import.py \
     --source-db "$BACKUP_DIR/source.sqlite3" \
     --source-media "$BACKUP_DIR/media" \
     --destination "$RUST_IMPORT_ROOT" \
     > "$BACKUP_DIR/verification.json"
   ```

   Require exit status zero and all checks to pass. Do not expose the report or
   snapshot outside the operators authorized to handle the copied data.
3. While maintenance remains active, start the Rust service on the isolated imported
   paths. Check its local health command, browser login, API token authentication,
   a sample project/yarn read, original media delivery, and a two-user ownership
   boundary. Confirm no external side-effect worker is enabled and that the Python
   database and media remain unchanged. On any mismatch, keep maintenance active and
   do not route public traffic.

### Route and verify

After the user-approved cutover decision, update the public reverse-proxy upstream
from Python to Rust while preserving the exact production origin, `/api/v1/` paths,
TLS behavior, and host header. Do not use a redirect or change the Android base URL.
Keep the write barrier active while checking the production-origin health endpoint,
browser login, API authentication, project/yarn reads, media reads, and authorization
between two owners. Record only aggregate pass/fail outcomes, not credentials or
copied record contents. Lift maintenance only after those checks pass.

Keep the Python database/media and the protected final snapshot unchanged during
this observation period. The Rust service owns its imported database/media and
subsequent writes; it does not dual-write to Python.

### Rollback

**Before Rust accepts writes:** re-enable the Python upstream at the same origin and
paths, verify Python health and login, then lift maintenance. The Python database
and media were left unchanged, so this rollback does not need to reconstruct them.
Stop Rust without deleting its imported destination or the final snapshot; retain
both for diagnosis.

**After Rust accepts writes:** do not blindly switch the proxy back to Python. Rust
changes are not present in the Python database, and the importer is one-way. Reapply
the write barrier, stop writers, take a separate protected backup of the current
Rust database and media, and reconcile/export all changes made since cutover using a
reviewed data-recovery plan. Keep maintenance active until the reconciliation has
been verified and the user approves the recovery choice. If no safe reconciliation
is available, restore Rust service from its preserved state rather than lose accepted
writes by routing to stale Python data. Never mutate the pre-cutover snapshot or
Python datastore to force a rollback.

After either path, record the chosen outcome and verification summary, retain backups
according to the service's data-retention policy, and require a separate user decision
before attempting a later cutover.

## Archive compatibility

Legacy accepted archive requests without a snapshot or failure are seeded into the
durable outbox as availability-only jobs. They do not resubmit the save request.
No worker runs unless `RUST_WAYBACK_ENABLED=true`; staging keeps it false. Polling
is bounded, and a failed request can be retried explicitly from its owned detail
page. Source links longer than the archival limit retain their original metadata
but require a corrected link before archival can resume.
