-- Upload and removal events remember which media they were about, so the
-- audit views can show the picture or file (or say it has since been deleted).
-- media_kind is the public namespace: image (project title and step images),
-- photo (yarn photos) or attachment. media_name is the original file name.
ALTER TABLE audit_events ADD COLUMN media_kind TEXT
    CHECK (media_kind IS NULL OR media_kind IN ('image', 'photo', 'attachment'));
ALTER TABLE audit_events ADD COLUMN media_public_id INTEGER;
ALTER TABLE audit_events ADD COLUMN media_name TEXT;

-- Best-effort backfill for old uploads: the media row that was created within
-- two seconds of the event on the same project or yarn.
UPDATE audit_events
   SET media_kind = c.media_kind,
       media_public_id = c.public_id,
       media_name = c.media_name
  FROM (
    SELECT eid, media_kind, public_id, media_name FROM (
      SELECT e.id AS eid,
             CASE m.kind WHEN 'attachment' THEN 'attachment'
                         WHEN 'photo' THEN 'photo' ELSE 'image' END AS media_kind,
             m.public_id AS public_id,
             COALESCE(NULLIF(m.original_filename, ''), m.filename) AS media_name,
             ROW_NUMBER() OVER (
               PARTITION BY e.id
               ORDER BY abs(julianday(m.created_at) - julianday(e.created_at))
             ) AS rn
        FROM audit_events AS e
        JOIN media_files AS m
          ON (e.entity_type = 'project' AND m.project_id = e.entity_id)
          OR (e.entity_type = 'yarn' AND m.yarn_id = e.entity_id)
       WHERE e.action = 'uploaded'
         AND m.public_id IS NOT NULL
         AND m.created_at IS NOT NULL
         AND abs(julianday(m.created_at) - julianday(e.created_at)) <= 2.0 / 86400
    ) WHERE rn = 1
  ) AS c
 WHERE audit_events.id = c.eid;
