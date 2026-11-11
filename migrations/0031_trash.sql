-- Soft delete with a trash: deleting a project or yarn only marks the row.
--
-- A trashed row keeps its steps, media, links, shares and files so that it can
-- be restored. Every read path filters on `deleted_at IS NULL`; the server
-- purges rows whose `deleted_at` is older than the retention period.
ALTER TABLE projects ADD COLUMN deleted_at TEXT;
ALTER TABLE projects ADD COLUMN deleted_by INTEGER REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE yarns ADD COLUMN deleted_at TEXT;
ALTER TABLE yarns ADD COLUMN deleted_by INTEGER REFERENCES users(id) ON DELETE SET NULL;

-- The trash listing and the purge sweep only look at trashed rows.
CREATE INDEX projects_trash ON projects(owner_id, deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX yarns_trash ON yarns(owner_id, deleted_at) WHERE deleted_at IS NOT NULL;

-- Children and categories are not flagged in place: deleting one moves its
-- data into a trash table (the row is removed from the live table, so no
-- existing query has to know about the trash). Media files stay on disk
-- while their row is trashed; the garbage-collection triggers below only
-- queue a file once no live or trashed row names it any more.

-- Project steps. Their images go to `trashed_media` with `with_step = 1`.
CREATE TABLE trashed_steps (
    id INTEGER PRIMARY KEY,
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    step_number INTEGER NOT NULL,
    deleted_at TEXT NOT NULL,
    deleted_by INTEGER REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX trashed_steps_owner ON trashed_steps(owner_id, deleted_at);
CREATE INDEX trashed_steps_project ON trashed_steps(project_id);

-- Project images and attachments, and yarn photos. Same columns as
-- `media_files` (a test keeps both in step), without the live-table
-- constraints, plus the trash bookkeeping.
CREATE TABLE trashed_media (
    id INTEGER PRIMARY KEY,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id INTEGER REFERENCES projects(id) ON DELETE CASCADE,
    yarn_id INTEGER REFERENCES yarns(id) ON DELETE CASCADE,
    step_id INTEGER,
    kind TEXT NOT NULL CHECK (kind IN ('title', 'step', 'photo', 'attachment')),
    filename TEXT NOT NULL UNIQUE,
    original_filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    width INTEGER,
    height INTEGER,
    alt_text TEXT NOT NULL DEFAULT '',
    is_primary INTEGER NOT NULL DEFAULT 0,
    is_safe_inline INTEGER NOT NULL DEFAULT 0,
    created_at TEXT,
    legacy_image_type TEXT,
    is_stitch_sample INTEGER,
    legacy_source TEXT,
    legacy_id INTEGER,
    public_id INTEGER NOT NULL,
    legacy_is_title_image INTEGER,
    position INTEGER NOT NULL DEFAULT 0,
    is_project_cover INTEGER NOT NULL DEFAULT 0,
    -- 1: trashed together with its step (listed and restored with the step).
    with_step INTEGER NOT NULL DEFAULT 0,
    deleted_at TEXT NOT NULL,
    deleted_by INTEGER REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX trashed_media_owner ON trashed_media(owner_id, deleted_at);
CREATE INDEX trashed_media_project ON trashed_media(project_id);
CREATE INDEX trashed_media_yarn ON trashed_media(yarn_id);
CREATE INDEX trashed_media_step ON trashed_media(step_id);

-- Purging a step purges the images that were trashed on their own earlier.
-- (A restored step is back in `project_steps` first, so its images survive.)
CREATE TRIGGER trashed_steps_purged AFTER DELETE ON trashed_steps
WHEN NOT EXISTS (SELECT 1 FROM project_steps WHERE id = OLD.id)
BEGIN
    DELETE FROM trashed_media WHERE step_id = OLD.id AND project_id = OLD.project_id;
END;

DROP TRIGGER media_files_gc;
CREATE TRIGGER media_files_gc AFTER DELETE ON media_files
WHEN NOT EXISTS (SELECT 1 FROM trashed_media WHERE filename = OLD.filename)
BEGIN
    INSERT OR IGNORE INTO media_gc (filename, subdir, parent_id)
    VALUES (
        OLD.filename,
        CASE WHEN OLD.project_id IS NOT NULL THEN 'projects' ELSE 'yarns' END,
        COALESCE(OLD.project_id, OLD.yarn_id)
    );
END;
CREATE TRIGGER trashed_media_gc AFTER DELETE ON trashed_media
WHEN NOT EXISTS (SELECT 1 FROM media_files WHERE filename = OLD.filename)
BEGIN
    INSERT OR IGNORE INTO media_gc (filename, subdir, parent_id)
    VALUES (
        OLD.filename,
        CASE WHEN OLD.project_id IS NOT NULL THEN 'projects' ELSE 'yarns' END,
        COALESCE(OLD.project_id, OLD.yarn_id)
    );
END;

-- Categories. The projects that used the category are remembered so that a
-- restore links them again.
CREATE TABLE trashed_categories (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    project_ids TEXT NOT NULL DEFAULT '[]',
    deleted_at TEXT NOT NULL,
    deleted_by INTEGER REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX trashed_categories_owner ON trashed_categories(user_id, deleted_at);
