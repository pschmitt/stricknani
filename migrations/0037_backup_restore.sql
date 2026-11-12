-- Administrator backup restore. SQLite cannot alter a CHECK constraint, so the
-- two tables that enumerate sensitive actions are rebuilt with the extended
-- sets (the same way 0022 did): `restore_backup` is a step-up purpose, and
-- `backup_restored` an auth-audit action. The audit row gains a bounded
-- `details` column (backup name, pre-restore backup name, media option); it
-- never holds credentials.
CREATE TABLE auth_reauth_proofs_new (
    proof_hash TEXT PRIMARY KEY CHECK (
        length(proof_hash) = 64 AND proof_hash NOT GLOB '*[^0-9a-f]*'
    ),
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_token_id INTEGER NOT NULL REFERENCES api_tokens(id) ON DELETE CASCADE,
    purpose TEXT NOT NULL CHECK (purpose IN (
        'link_identity', 'unlink_identity', 'admin_unlink_identity',
        'change_password', 'create_api_token', 'revoke_api_token',
        'delete_account', 'restore_backup'
    )),
    target_user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_id TEXT CHECK (provider_id IS NULL OR length(provider_id) BETWEEN 1 AND 128),
    identity_id INTEGER,
    target_token_id INTEGER CHECK (target_token_id IS NULL OR target_token_id > 0),
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER,
    CHECK (
        (purpose = 'link_identity' AND target_user_id = user_id
            AND provider_id IS NOT NULL AND identity_id IS NULL AND target_token_id IS NULL)
        OR (purpose = 'unlink_identity' AND target_user_id = user_id
            AND provider_id IS NULL AND identity_id IS NOT NULL AND identity_id > 0
            AND target_token_id IS NULL)
        OR (purpose = 'admin_unlink_identity'
            AND provider_id IS NULL AND identity_id IS NOT NULL AND identity_id > 0
            AND target_token_id IS NULL)
        OR (purpose IN ('change_password', 'create_api_token', 'delete_account', 'restore_backup')
            AND target_user_id = user_id AND provider_id IS NULL
            AND identity_id IS NULL AND target_token_id IS NULL)
        OR (purpose = 'revoke_api_token' AND target_user_id = user_id
            AND provider_id IS NULL AND identity_id IS NULL AND target_token_id IS NOT NULL)
    )
);
INSERT INTO auth_reauth_proofs_new
    (proof_hash, user_id, api_token_id, purpose, target_user_id, provider_id,
     identity_id, target_token_id, expires_at, consumed_at)
    SELECT proof_hash, user_id, api_token_id, purpose, target_user_id, provider_id,
           identity_id, target_token_id, expires_at, consumed_at
    FROM auth_reauth_proofs;
DROP TABLE auth_reauth_proofs;
ALTER TABLE auth_reauth_proofs_new RENAME TO auth_reauth_proofs;
CREATE INDEX auth_reauth_proofs_user ON auth_reauth_proofs(user_id, expires_at);

CREATE TABLE auth_audit_events_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL CHECK (action IN (
        'identity_linked', 'identity_unlinked', 'identity_admin_unlinked',
        'oidc_login_succeeded', 'oidc_token_minted', 'oidc_provider_tokens_revoked',
        'api_token_created', 'account_deleted', 'backup_restored'
    )),
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    target_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    identity_id INTEGER,
    provider_id TEXT CHECK (provider_id IS NULL OR length(provider_id) BETWEEN 1 AND 128),
    created_at INTEGER NOT NULL,
    details TEXT CHECK (details IS NULL OR length(details) <= 2048)
);
INSERT INTO auth_audit_events_new
    (id, action, actor_user_id, target_user_id, identity_id, provider_id, created_at)
    SELECT id, action, actor_user_id, target_user_id, identity_id, provider_id, created_at
    FROM auth_audit_events;
DROP TABLE auth_audit_events;
ALTER TABLE auth_audit_events_new RENAME TO auth_audit_events;
CREATE INDEX auth_audit_events_target ON auth_audit_events(target_user_id, created_at);
