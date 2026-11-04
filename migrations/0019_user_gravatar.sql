-- Per-user opt-out for the server-side Gravatar fallback (default on).
ALTER TABLE users ADD COLUMN use_gravatar INTEGER NOT NULL DEFAULT 1
    CHECK (use_gravatar IN (0, 1));
