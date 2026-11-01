-- Fields retained for the one-time import from the Python database and media tree.
ALTER TABLE users ADD COLUMN profile_image TEXT;
ALTER TABLE users ADD COLUMN legacy_token_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE api_tokens ADD COLUMN last_used_at INTEGER;
ALTER TABLE categories ADD COLUMN created_at TEXT;
ALTER TABLE project_steps ADD COLUMN created_at TEXT;

ALTER TABLE projects ADD COLUMN legacy_yarn_text TEXT;
ALTER TABLE projects ADD COLUMN archive_requested_at TEXT;
ALTER TABLE projects ADD COLUMN archive_failed INTEGER NOT NULL DEFAULT 0
    CHECK (archive_failed IN (0, 1));

ALTER TABLE yarns ADD COLUMN archive_requested_at TEXT;
ALTER TABLE yarns ADD COLUMN archive_failed INTEGER NOT NULL DEFAULT 0
    CHECK (archive_failed IN (0, 1));

ALTER TABLE media_files ADD COLUMN created_at TEXT;
ALTER TABLE media_files ADD COLUMN legacy_image_type TEXT;
ALTER TABLE media_files ADD COLUMN is_stitch_sample INTEGER
    CHECK (is_stitch_sample IS NULL OR is_stitch_sample IN (0, 1));
-- IDs in the Python images, yarn_images, and attachments tables each have
-- independent sequences. Keep their source namespace alongside the old ID.
ALTER TABLE media_files ADD COLUMN legacy_source TEXT;
ALTER TABLE media_files ADD COLUMN legacy_id INTEGER;
CREATE UNIQUE INDEX media_files_legacy_source_id
    ON media_files(legacy_source, legacy_id);

CREATE TABLE user_avatars (
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    filename TEXT NOT NULL,
    original_filename TEXT,
    content_type TEXT,
    width INTEGER CHECK (width IS NULL OR width > 0),
    height INTEGER CHECK (height IS NULL OR height > 0),
    created_at TEXT
);

CREATE TABLE audit_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    entity_type TEXT NOT NULL,
    entity_id INTEGER NOT NULL,
    action TEXT NOT NULL,
    details TEXT,
    created_at TEXT NOT NULL
);
-- Retain a source actor ID even when no account row exists to satisfy the FK.
ALTER TABLE audit_events ADD COLUMN legacy_actor_user_id INTEGER;
CREATE INDEX audit_events_actor ON audit_events(actor_user_id);
CREATE INDEX audit_events_entity ON audit_events(entity_type, entity_id);
CREATE INDEX audit_events_created ON audit_events(created_at, id);
