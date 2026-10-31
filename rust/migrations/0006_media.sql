CREATE TABLE media_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id INTEGER REFERENCES projects(id) ON DELETE CASCADE,
    yarn_id INTEGER REFERENCES yarns(id) ON DELETE CASCADE,
    step_id INTEGER REFERENCES project_steps(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('title', 'step', 'photo', 'attachment')),
    filename TEXT NOT NULL UNIQUE,
    original_filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    width INTEGER CHECK (width IS NULL OR width > 0),
    height INTEGER CHECK (height IS NULL OR height > 0),
    alt_text TEXT NOT NULL DEFAULT '',
    is_primary INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1)),
    is_safe_inline INTEGER NOT NULL DEFAULT 0 CHECK (is_safe_inline IN (0, 1)),
    CHECK (
        (kind IN ('title', 'attachment') AND project_id IS NOT NULL
            AND yarn_id IS NULL AND step_id IS NULL)
        OR (kind = 'step' AND project_id IS NOT NULL
            AND yarn_id IS NULL AND step_id IS NOT NULL)
        OR (kind = 'photo' AND project_id IS NULL
            AND yarn_id IS NOT NULL AND step_id IS NULL)
    )
);

CREATE INDEX media_files_project ON media_files(project_id, kind, id);
CREATE INDEX media_files_yarn ON media_files(yarn_id, kind, id);
CREATE INDEX media_files_step ON media_files(step_id, id);
CREATE UNIQUE INDEX media_files_primary_title
    ON media_files(project_id) WHERE kind = 'title' AND is_primary = 1;
CREATE UNIQUE INDEX media_files_primary_photo
    ON media_files(yarn_id) WHERE kind = 'photo' AND is_primary = 1;

CREATE TABLE media_gc (
    filename TEXT PRIMARY KEY,
    subdir TEXT NOT NULL CHECK (subdir IN ('projects', 'yarns')),
    parent_id INTEGER NOT NULL
);

CREATE TRIGGER media_files_gc AFTER DELETE ON media_files
BEGIN
    INSERT OR IGNORE INTO media_gc (filename, subdir, parent_id)
    VALUES (
        OLD.filename,
        CASE WHEN OLD.project_id IS NOT NULL THEN 'projects' ELSE 'yarns' END,
        COALESCE(OLD.project_id, OLD.yarn_id)
    );
END;
