-- Tombstones are independent of deleted rows, for owner-scoped offline sync.
CREATE TABLE deletion_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('project', 'yarn')),
    entity_id INTEGER NOT NULL,
    changed_at TEXT NOT NULL
);
CREATE INDEX deletion_events_sync ON deletion_events(owner_id, entity_type, changed_at, id);
