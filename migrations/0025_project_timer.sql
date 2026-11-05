CREATE TABLE project_timers (
    project_id INTEGER PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    total_seconds INTEGER NOT NULL DEFAULT 0
        CHECK (total_seconds >= 0 AND total_seconds <= 315576000),
    started_at INTEGER
);
