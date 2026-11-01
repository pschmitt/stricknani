CREATE TABLE media_url_aliases (
    subdir TEXT NOT NULL CHECK (subdir IN ('projects', 'yarns')),
    parent_id INTEGER NOT NULL,
    legacy_filename TEXT NOT NULL,
    legacy_thumbnail TEXT,
    media_id INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
    PRIMARY KEY (subdir, parent_id, legacy_filename)
);

CREATE UNIQUE INDEX media_url_aliases_thumbnail_lookup
    ON media_url_aliases (subdir, parent_id, legacy_thumbnail)
    WHERE legacy_thumbnail IS NOT NULL;
