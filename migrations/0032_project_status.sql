-- Project status: 'in_progress' (default, also for every existing project) or
-- 'done'. Plain archive flags do not apply here: the existing archive fields
-- describe web-archive snapshots of the project link, not the knitting state.
ALTER TABLE projects ADD COLUMN status TEXT NOT NULL DEFAULT 'in_progress'
    CHECK (status IN ('in_progress', 'done'));

CREATE INDEX projects_owner_status ON projects(owner_id, status);
