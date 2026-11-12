-- Native (Android) administrator impersonation. The web keeps recording an
-- impersonation on the administrator's browser session. A native client instead
-- receives a short-lived bearer credential bound to (target account,
-- impersonating administrator). It lives in its own table on purpose: it is not
-- an API-purpose PAT, so it never shows up in, counts against or can be managed
-- through the account's token list, and cannot answer a step-up challenge.
CREATE TABLE impersonation_tokens (
    id INTEGER PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    admin_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    expires_at INTEGER NOT NULL,
    revoked_at INTEGER,
    CHECK (admin_user_id <> target_user_id),
    CHECK (expires_at > created_at)
);
CREATE INDEX impersonation_tokens_admin_idx
    ON impersonation_tokens(admin_user_id) WHERE revoked_at IS NULL;
