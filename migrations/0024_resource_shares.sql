-- Sharing of projects and yarns with other accounts.
--
-- `resource_shares` stores explicit grants only. Read-only access to the yarns
-- linked to a shared project is derived at query time from this table plus
-- `project_yarns`; it is never stored here.
CREATE TABLE resource_shares (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL CHECK (kind IN ('project', 'yarn')),
    resource_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('viewer', 'editor')),
    created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (kind, resource_id, user_id)
);
-- "Which resources can this user see?" (lists, sync, access checks).
CREATE INDEX resource_shares_user ON resource_shares(user_id, kind, resource_id);

-- The resource is polymorphic, so a foreign key cannot remove its shares.
CREATE TRIGGER resource_shares_project_deleted AFTER DELETE ON projects
BEGIN
    DELETE FROM resource_shares WHERE kind = 'project' AND resource_id = OLD.id;
    DELETE FROM access_events WHERE entity_type = 'project' AND entity_id = OLD.id;
END;
CREATE TRIGGER resource_shares_yarn_deleted AFTER DELETE ON yarns
BEGIN
    DELETE FROM resource_shares WHERE kind = 'yarn' AND resource_id = OLD.id;
    DELETE FROM access_events WHERE entity_type = 'yarn' AND entity_id = OLD.id;
END;

-- A resource can enter a user's visible set (share, role or owner change,
-- link to a shared project) without its own `updated_at` moving. Sync merges
-- the latest event per user and resource into the delta so such entries appear
-- even when they are older than the client's cursor.
CREATE TABLE access_events (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('project', 'yarn')),
    entity_id INTEGER NOT NULL,
    changed_at TEXT NOT NULL,
    PRIMARY KEY (user_id, entity_type, entity_id)
);

-- Tombstones written because access ended (not because the resource was
-- deleted). They are addressed to the user that lost access and must never be
-- served to the resource's owner or to an administrator's all-scope sync.
ALTER TABLE deletion_events ADD COLUMN shared INTEGER NOT NULL DEFAULT 0
    CHECK (shared IN (0, 1));

-- Share, role-change, revoke, leave and transfer events name the other account.
ALTER TABLE audit_events ADD COLUMN subject_user_id INTEGER
    REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE audit_events ADD COLUMN subject_role TEXT
    CHECK (subject_role IS NULL OR subject_role IN ('viewer', 'editor'));
