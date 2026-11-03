-- Keep sync metadata separate from legacy created_at, which may be NULL and
-- must retain its source meaning.
CREATE TABLE category_sync_metadata (
    category_id INTEGER PRIMARY KEY REFERENCES categories(id) ON DELETE CASCADE,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_category_sync_metadata_owner_updated
    ON category_sync_metadata(owner_id, updated_at, category_id);

CREATE TABLE category_sync_clock (
    owner_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    changed_at TEXT NOT NULL
);

CREATE TABLE category_deletion_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id INTEGER NOT NULL,
    changed_at TEXT NOT NULL
);

CREATE INDEX idx_category_deletion_events_owner_changed
    ON category_deletion_events(owner_id, changed_at, id);

-- Existing categories become visible to a first delta sync from migration
-- time, without rewriting a possibly NULL or historical created_at value.
INSERT INTO category_sync_clock(owner_id, changed_at)
SELECT user_id, strftime('%Y-%m-%dT%H:%M:%f', 'now') || '000Z'
FROM categories
GROUP BY user_id;

INSERT INTO category_sync_metadata(category_id, owner_id, updated_at)
SELECT categories.id, categories.user_id, category_sync_clock.changed_at
FROM categories
JOIN category_sync_clock ON category_sync_clock.owner_id = categories.user_id;

-- These triggers cover every category writer, including v1 CRUD, categories
-- created while saving a project, and the one-time importer. Each owner has
-- one clock shared by event stamps and the sync watermark. If SQLite's wall
-- clock has not advanced, the logical clock advances by one millisecond.
CREATE TRIGGER categories_sync_insert
AFTER INSERT ON categories
BEGIN
    INSERT INTO category_sync_clock(owner_id, changed_at)
    VALUES (NEW.user_id, strftime('%Y-%m-%dT%H:%M:%f', 'now') || '000Z')
    ON CONFLICT(owner_id) DO UPDATE SET changed_at =
        CASE
            WHEN excluded.changed_at > category_sync_clock.changed_at THEN excluded.changed_at
            ELSE strftime(
                '%Y-%m-%dT%H:%M:%f',
                julianday(category_sync_clock.changed_at) + 1.0 / 86400000.0
            ) || '000Z'
        END;

    INSERT INTO category_sync_metadata(category_id, owner_id, updated_at)
    SELECT NEW.id, NEW.user_id, changed_at
    FROM category_sync_clock
    WHERE owner_id = NEW.user_id;
END;

CREATE TRIGGER categories_sync_update
AFTER UPDATE OF name, user_id ON categories
WHEN OLD.name IS NOT NEW.name OR OLD.user_id IS NOT NEW.user_id
BEGIN
    INSERT INTO category_sync_clock(owner_id, changed_at)
    VALUES (NEW.user_id, strftime('%Y-%m-%dT%H:%M:%f', 'now') || '000Z')
    ON CONFLICT(owner_id) DO UPDATE SET changed_at =
        CASE
            WHEN excluded.changed_at > category_sync_clock.changed_at THEN excluded.changed_at
            ELSE strftime(
                '%Y-%m-%dT%H:%M:%f',
                julianday(category_sync_clock.changed_at) + 1.0 / 86400000.0
            ) || '000Z'
        END;

    INSERT INTO category_sync_clock(owner_id, changed_at)
    SELECT OLD.user_id, strftime('%Y-%m-%dT%H:%M:%f', 'now') || '000Z'
    WHERE OLD.user_id IS NOT NEW.user_id
    ON CONFLICT(owner_id) DO UPDATE SET changed_at =
        CASE
            WHEN excluded.changed_at > category_sync_clock.changed_at THEN excluded.changed_at
            ELSE strftime(
                '%Y-%m-%dT%H:%M:%f',
                julianday(category_sync_clock.changed_at) + 1.0 / 86400000.0
            ) || '000Z'
        END;

    INSERT INTO category_deletion_events(owner_id, category_id, changed_at)
    SELECT OLD.user_id, OLD.id, category_sync_clock.changed_at
    FROM category_sync_clock
    WHERE category_sync_clock.owner_id = OLD.user_id
      AND OLD.user_id IS NOT NEW.user_id;

    INSERT INTO category_sync_metadata(category_id, owner_id, updated_at)
    SELECT NEW.id, NEW.user_id, changed_at
    FROM category_sync_clock
    WHERE owner_id = NEW.user_id
    ON CONFLICT(category_id) DO UPDATE SET
        owner_id = excluded.owner_id,
        updated_at = excluded.updated_at;
END;

-- When the owner itself is being deleted, the cascade removes the categories
-- after the user row is gone; sync rows for that owner would violate the
-- foreign keys and are cascaded away anyway, so they are skipped.
CREATE TRIGGER categories_sync_delete
BEFORE DELETE ON categories
BEGIN
    INSERT INTO category_sync_clock(owner_id, changed_at)
    SELECT OLD.user_id, strftime('%Y-%m-%dT%H:%M:%f', 'now') || '000Z'
    WHERE EXISTS (SELECT 1 FROM users WHERE id = OLD.user_id)
    ON CONFLICT(owner_id) DO UPDATE SET changed_at =
        CASE
            WHEN excluded.changed_at > category_sync_clock.changed_at THEN excluded.changed_at
            ELSE strftime(
                '%Y-%m-%dT%H:%M:%f',
                julianday(category_sync_clock.changed_at) + 1.0 / 86400000.0
            ) || '000Z'
        END;

    INSERT INTO category_deletion_events(owner_id, category_id, changed_at)
    SELECT OLD.user_id, OLD.id, changed_at
    FROM category_sync_clock
    WHERE owner_id = OLD.user_id
      AND EXISTS (SELECT 1 FROM users WHERE id = OLD.user_id);
END;
