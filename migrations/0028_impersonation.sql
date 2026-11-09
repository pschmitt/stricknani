-- Administrator impersonation. A browser session row records the account it is
-- currently acting as and when that stops; the row's user_id stays the
-- administrator who signed in. Audit events written while impersonating name
-- that administrator.
ALTER TABLE api_tokens ADD COLUMN impersonated_user_id INTEGER
    REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE api_tokens ADD COLUMN impersonation_expires_at INTEGER;
ALTER TABLE audit_events ADD COLUMN impersonated_by_user_id INTEGER
    REFERENCES users(id) ON DELETE SET NULL;
