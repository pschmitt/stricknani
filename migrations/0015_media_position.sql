ALTER TABLE media_files ADD COLUMN position INTEGER NOT NULL DEFAULT 0
    CHECK (position >= 0);
ALTER TABLE media_files ADD COLUMN is_project_cover INTEGER NOT NULL DEFAULT 0
    CHECK (is_project_cover IN (0, 1));

CREATE TEMP TABLE migration_project_cover AS
SELECT id
FROM (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY project_id
               ORDER BY CASE
                   WHEN COALESCE(legacy_is_title_image,
                       CASE WHEN kind = 'title' AND is_primary = 1 THEN 1 ELSE 0 END) = 1
                   THEN 0 ELSE 1
               END, id
           ) AS rank
    FROM media_files
    WHERE kind IN ('title', 'step')
)
WHERE rank = 1;

UPDATE media_files SET is_project_cover = 1
WHERE id IN (SELECT id FROM migration_project_cover);
DROP TABLE migration_project_cover;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY project_id, step_id
               ORDER BY id
           ) - 1 AS position
    FROM media_files
    WHERE kind IN ('title', 'step')
)
UPDATE media_files
SET position = (SELECT position FROM ranked WHERE ranked.id = media_files.id)
WHERE id IN (SELECT id FROM ranked);

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY yarn_id
               ORDER BY is_primary DESC, id DESC
           ) - 1 AS position
    FROM media_files
    WHERE kind = 'photo'
)
UPDATE media_files
SET position = (SELECT position FROM ranked WHERE ranked.id = media_files.id)
WHERE id IN (SELECT id FROM ranked);

CREATE UNIQUE INDEX media_files_title_position
    ON media_files(project_id, position)
    WHERE kind = 'title' AND step_id IS NULL;
CREATE UNIQUE INDEX media_files_step_position
    ON media_files(project_id, step_id, position)
    WHERE kind = 'step';
CREATE UNIQUE INDEX media_files_yarn_photo_position
    ON media_files(yarn_id, position)
    WHERE kind = 'photo';
CREATE UNIQUE INDEX media_files_project_cover
    ON media_files(project_id) WHERE kind IN ('title', 'step') AND is_project_cover = 1;
