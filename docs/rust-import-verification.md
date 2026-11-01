# Verify an isolated Rust import

`ci/stricknani/verify_rust_import.py` compares a copied, quiescent legacy
SQLite snapshot and its media tree with the published Rust import directory.
It opens both databases in SQLite read-only immutable mode and never contacts
the application, host, or network. The tool does not create or modify files.

Run it only against protected copies, with filesystem access restricted to the
operator performing the rehearsal:

```sh
python ci/stricknani/verify_rust_import.py \
  --source-db /protected/copy/legacy.sqlite3 \
  --source-media /protected/copy/media \
  --destination /protected/copy/rust-import
```

All three paths must exist and be absolute canonical paths. The source SQLite
database is rejected if `-wal` or `-shm` sidecars are present. Source and Rust
media roots must be separate. The Rust import directory must contain
`database.sqlite3` and `media/`.

The single JSON output contains only named checks, aggregate row counts, and
named mismatch counts. It never prints paths, row IDs, emails, filenames,
password/token hashes, media hashes, or source content. Exit status is zero
when every check passes and nonzero on mismatches or invalid inputs. Preserve
the output alongside the rehearsal notes, but store it with the protected
snapshot evidence because it reveals aggregate inventory counts.

## Coverage

The verifier checks SQLite integrity and target foreign keys; source and target
counts; user IDs, normalized login email and exact legacy email, password hash,
account metadata; token IDs, owner, name, token hash, and expiry/usage times;
category and project/yarn/step IDs and ownership; project category and yarn
relationships; favorites; and legacy audit events. Additional Rust-native
audit events are allowed and counted separately so later activity is not
mistaken for a failed import.

For image, attachment, and yarn-photo records it matches
`(legacy_source, legacy_id)`, checks owner/parent metadata and retained nullable
legacy fields, verifies the namespace-scoped `public_id`, checks the legacy URL
alias, and compares SHA-256 digests of original source and registered Rust
media bytes. Avatar associations and original bytes are checked in the same
way. The digest is kept in memory and never included in output.

Legacy naive SQLite timestamps are interpreted as UTC and compared at
microsecond precision with Rust RFC3339 timestamps. Token timestamps are
stored as integer epoch seconds, so comparison uses the source timestamp
truncated to seconds. Login email is normalized for authentication while the
original spelling remains in `legacy_email`. A null project tag string is
normalized to `[]`. Categories synthesized from project text may increase the
Rust category count; original category rows must still be present. Generated
Rust audit events may increase the Rust audit count; every source event must
remain unchanged.

The report does not validate regenerated thumbnails pixel-for-pixel, re-run
image decoding, or prove that the snapshot was copied consistently before the
verifier starts. Follow the snapshot/write-freeze and rollback procedure in
[the migration plan](rust-migration-plan.md), then verify owner access and
media rendering on the isolated candidate separately. Never point this tool,
the importer, or the candidate server at live production storage.
