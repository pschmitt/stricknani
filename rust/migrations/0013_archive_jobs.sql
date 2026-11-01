CREATE TABLE archive_jobs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('project', 'yarn')),
    entity_id INTEGER NOT NULL,
    source_url TEXT NOT NULL CHECK (length(source_url) <= 2048),
    state TEXT NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'running', 'archived', 'failed', 'superseded')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    claim_token INTEGER NOT NULL DEFAULT 0 CHECK (claim_token >= 0),
    save_accepted INTEGER NOT NULL DEFAULT 0 CHECK (save_accepted IN (0, 1)),
    next_attempt_at INTEGER NOT NULL DEFAULT (unixepoch()),
    lease_until INTEGER,
    last_error TEXT,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
    UNIQUE (entity_type, entity_id, source_url),
    CHECK (state = 'running' OR lease_until IS NULL)
);

CREATE INDEX archive_jobs_claim
    ON archive_jobs(state, next_attempt_at, id);
CREATE INDEX archive_jobs_owner_entity
    ON archive_jobs(owner_id, entity_type, entity_id, id);
