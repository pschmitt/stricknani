-- Preserve the source media table's independent integer IDs in API v1.
ALTER TABLE media_files ADD COLUMN public_id INTEGER;
UPDATE media_files SET public_id = id;

CREATE TABLE media_id_sequences (
    namespace TEXT PRIMARY KEY CHECK (namespace IN ('image', 'photo', 'attachment')),
    next_id INTEGER NOT NULL CHECK (next_id > 0)
);

INSERT INTO media_id_sequences (namespace, next_id)
SELECT 'image', COALESCE(MAX(public_id), 0) + 1 FROM media_files
WHERE kind IN ('title', 'step');
INSERT INTO media_id_sequences (namespace, next_id)
SELECT 'photo', COALESCE(MAX(public_id), 0) + 1 FROM media_files
WHERE kind = 'photo';
INSERT INTO media_id_sequences (namespace, next_id)
SELECT 'attachment', COALESCE(MAX(public_id), 0) + 1 FROM media_files
WHERE kind = 'attachment';

CREATE UNIQUE INDEX media_files_public_id
    ON media_files (
        CASE kind
            WHEN 'title' THEN 'image'
            WHEN 'step' THEN 'image'
            WHEN 'photo' THEN 'photo'
            WHEN 'attachment' THEN 'attachment'
        END,
        public_id
    );

CREATE TRIGGER media_files_public_id_required_insert
BEFORE INSERT ON media_files
WHEN NEW.public_id IS NULL OR NEW.public_id <= 0
BEGIN
    SELECT RAISE(ABORT, 'media public_id must be a positive integer');
END;

CREATE TRIGGER media_files_public_id_required_update
BEFORE UPDATE OF public_id ON media_files
WHEN NEW.public_id IS NULL OR NEW.public_id <= 0
BEGIN
    SELECT RAISE(ABORT, 'media public_id must be a positive integer');
END;

-- Keep sequences above explicitly imported public IDs as well as API inserts.
CREATE TRIGGER media_files_public_id_sequence_insert
AFTER INSERT ON media_files
BEGIN
    UPDATE media_id_sequences
    SET next_id = MAX(next_id, NEW.public_id + 1)
    WHERE namespace = CASE NEW.kind
        WHEN 'title' THEN 'image'
        WHEN 'step' THEN 'image'
        WHEN 'photo' THEN 'photo'
        WHEN 'attachment' THEN 'attachment'
    END;
END;

CREATE TRIGGER media_files_public_id_sequence_update
AFTER UPDATE OF public_id, kind ON media_files
BEGIN
    UPDATE media_id_sequences
    SET next_id = MAX(next_id, NEW.public_id + 1)
    WHERE namespace = CASE NEW.kind
        WHEN 'title' THEN 'image'
        WHEN 'step' THEN 'image'
        WHEN 'photo' THEN 'photo'
        WHEN 'attachment' THEN 'attachment'
    END;
END;
