-- Passkeys (WebAuthn). A user may register a few passkeys as an optional
-- alternative to the password. The relying party stores the credential id, the
-- user handle sent to authenticators and the serialized credential (public key,
-- signature counter and backup flags, maintained by webauthn-rs); the counter and
-- backup flags are mirrored in columns for display. Nothing here is secret: a
-- credential holds only a public key.
CREATE TABLE passkeys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id BLOB NOT NULL UNIQUE CHECK (length(credential_id) BETWEEN 16 AND 1024),
    user_handle BLOB NOT NULL CHECK (length(user_handle) = 16),
    passkey TEXT NOT NULL CHECK (length(passkey) <= 32768),
    nickname TEXT NOT NULL CHECK (length(nickname) BETWEEN 1 AND 100),
    transports TEXT NOT NULL DEFAULT '[]' CHECK (length(transports) <= 256),
    sign_count INTEGER NOT NULL DEFAULT 0 CHECK (sign_count >= 0),
    backup_eligible INTEGER NOT NULL DEFAULT 0 CHECK (backup_eligible IN (0, 1)),
    backup_state INTEGER NOT NULL DEFAULT 0 CHECK (backup_state IN (0, 1)),
    created_at INTEGER NOT NULL,
    last_used_at INTEGER
);
CREATE INDEX passkeys_user ON passkeys(user_id, created_at);

-- Passkey management is a step-up purpose and passkey use an auth-audit action.
-- SQLite cannot alter a CHECK constraint, so the two tables that enumerate them
-- are rebuilt (the same way 0022 did). The sets below already include the
-- backup-restore additions of the same kind; the audit table keeps the bounded
-- `details` column those additions introduced. Rows are copied as they are.
CREATE TABLE auth_reauth_proofs_new (
    proof_hash TEXT PRIMARY KEY CHECK (
        length(proof_hash) = 64 AND proof_hash NOT GLOB '*[^0-9a-f]*'
    ),
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_token_id INTEGER NOT NULL REFERENCES api_tokens(id) ON DELETE CASCADE,
    purpose TEXT NOT NULL CHECK (purpose IN (
        'link_identity', 'unlink_identity', 'admin_unlink_identity',
        'change_password', 'create_api_token', 'revoke_api_token',
        'delete_account', 'restore_backup', 'manage_passkeys'
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
        OR (purpose IN ('change_password', 'create_api_token', 'delete_account',
                        'restore_backup', 'manage_passkeys')
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
        'api_token_created', 'account_deleted', 'backup_restored',
        'passkey_registered', 'passkey_removed', 'passkey_login_succeeded',
        'passkey_reauthenticated'
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
