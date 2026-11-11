-- Legacy media URL aliases survive the trash: trashing a file moves its
-- aliases here (the live rows would be removed with the media row by the
-- cascade), a restore puts them back and a purge drops them with the trashed
-- row.
CREATE TABLE trashed_media_url_aliases (
    subdir TEXT NOT NULL CHECK (subdir IN ('projects', 'yarns')),
    parent_id INTEGER NOT NULL,
    legacy_filename TEXT NOT NULL,
    legacy_thumbnail TEXT,
    media_id INTEGER NOT NULL REFERENCES trashed_media(id) ON DELETE CASCADE,
    PRIMARY KEY (subdir, parent_id, legacy_filename)
);
CREATE INDEX trashed_media_url_aliases_media ON trashed_media_url_aliases (media_id);
