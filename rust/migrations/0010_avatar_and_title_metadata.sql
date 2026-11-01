ALTER TABLE media_files ADD COLUMN legacy_is_title_image INTEGER
    CHECK (legacy_is_title_image IS NULL OR legacy_is_title_image IN (0, 1));

CREATE TABLE avatar_gc (
    user_id INTEGER NOT NULL CHECK (user_id > 0),
    filename TEXT NOT NULL,
    PRIMARY KEY (user_id, filename)
);

CREATE TRIGGER user_avatars_gc_delete
AFTER DELETE ON user_avatars
BEGIN
    INSERT OR IGNORE INTO avatar_gc (user_id, filename)
    VALUES (OLD.user_id, OLD.filename);
END;

CREATE TRIGGER user_avatars_gc_rename
AFTER UPDATE OF filename ON user_avatars
WHEN OLD.filename <> NEW.filename
BEGIN
    INSERT OR IGNORE INTO avatar_gc (user_id, filename)
    VALUES (OLD.user_id, OLD.filename);
END;
