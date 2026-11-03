-- Record API token creation in the authentication audit. SQLite cannot alter a
-- CHECK constraint, so rebuild the table with the extended action set. Nothing
-- references auth_audit_events.
CREATE TABLE auth_audit_events_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL CHECK (action IN (
        'identity_linked', 'identity_unlinked', 'identity_admin_unlinked',
        'oidc_login_succeeded', 'oidc_token_minted', 'oidc_provider_tokens_revoked',
        'api_token_created'
    )),
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    target_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    identity_id INTEGER,
    provider_id TEXT CHECK (provider_id IS NULL OR length(provider_id) BETWEEN 1 AND 128),
    created_at INTEGER NOT NULL
);
INSERT INTO auth_audit_events_new
    (id, action, actor_user_id, target_user_id, identity_id, provider_id, created_at)
    SELECT id, action, actor_user_id, target_user_id, identity_id, provider_id, created_at
    FROM auth_audit_events;
DROP TABLE auth_audit_events;
ALTER TABLE auth_audit_events_new RENAME TO auth_audit_events;
CREATE INDEX auth_audit_events_target ON auth_audit_events(target_user_id, created_at);
