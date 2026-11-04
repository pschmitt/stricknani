-- Keep local password hashes intact. This capability only controls whether a
-- local password may authenticate the account.
ALTER TABLE users
    ADD COLUMN password_enabled INTEGER NOT NULL DEFAULT 1
    CHECK (password_enabled IN (0, 1));

CREATE TABLE oidc_identities (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_id TEXT NOT NULL CHECK (length(provider_id) BETWEEN 1 AND 128),
    issuer TEXT NOT NULL COLLATE BINARY CHECK (length(issuer) BETWEEN 1 AND 2048),
    subject TEXT NOT NULL COLLATE BINARY CHECK (length(subject) BETWEEN 1 AND 255),
    verified_email TEXT CHECK (verified_email IS NULL OR length(verified_email) <= 320),
    linked_at INTEGER NOT NULL,
    UNIQUE (issuer, subject)
);
CREATE INDEX oidc_identities_user ON oidc_identities(user_id);
CREATE INDEX oidc_identities_provider ON oidc_identities(provider_id, user_id);

-- The identity reference is nullable so unlink can revoke grants and then
-- remove the identity while retaining a typed origin/provider marker.
ALTER TABLE api_tokens ADD COLUMN auth_origin TEXT NOT NULL DEFAULT 'local'
    CHECK (auth_origin IN ('local', 'oidc'));
ALTER TABLE api_tokens ADD COLUMN oidc_identity_id INTEGER
    REFERENCES oidc_identities(id) ON DELETE SET NULL;
ALTER TABLE api_tokens ADD COLUMN oidc_provider_id TEXT
    CHECK (oidc_provider_id IS NULL OR length(oidc_provider_id) BETWEEN 1 AND 128);
ALTER TABLE api_tokens ADD COLUMN reauth_at INTEGER;
CREATE INDEX api_tokens_oidc_identity ON api_tokens(oidc_identity_id)
    WHERE oidc_identity_id IS NOT NULL;
CREATE TRIGGER api_tokens_auth_origin_insert
BEFORE INSERT ON api_tokens
WHEN NOT (
    (NEW.auth_origin = 'local' AND NEW.oidc_identity_id IS NULL AND NEW.oidc_provider_id IS NULL)
    OR
    (NEW.auth_origin = 'oidc' AND NEW.oidc_provider_id IS NOT NULL
        AND NEW.oidc_identity_id IS NOT NULL AND NEW.expires_at IS NOT NULL
        AND NEW.expires_at > NEW.created_at
        AND ((NEW.purpose = 'api' AND NEW.expires_at <= NEW.created_at + 604800)
            OR (NEW.purpose = 'browser' AND NEW.expires_at <= NEW.created_at + 1209600))
        AND EXISTS (SELECT 1 FROM oidc_identities i WHERE i.id = NEW.oidc_identity_id
            AND i.user_id = NEW.user_id AND i.provider_id = NEW.oidc_provider_id))
)
BEGIN
    SELECT RAISE(ABORT, 'invalid authentication token origin');
END;
CREATE TRIGGER api_tokens_auth_origin_update
BEFORE UPDATE OF auth_origin, oidc_identity_id, oidc_provider_id, expires_at, created_at, purpose, user_id ON api_tokens
WHEN NOT (
    (NEW.auth_origin = 'local' AND NEW.oidc_identity_id IS NULL AND NEW.oidc_provider_id IS NULL)
    OR
    (NEW.auth_origin = 'oidc' AND NEW.oidc_provider_id IS NOT NULL
        AND NEW.expires_at IS NOT NULL AND NEW.expires_at > NEW.created_at
        AND ((NEW.purpose = 'api' AND NEW.expires_at <= NEW.created_at + 604800)
            OR (NEW.purpose = 'browser' AND NEW.expires_at <= NEW.created_at + 1209600))
        AND (NEW.oidc_identity_id IS NULL OR EXISTS (
            SELECT 1 FROM oidc_identities i WHERE i.id = NEW.oidc_identity_id
                AND i.user_id = NEW.user_id AND i.provider_id = NEW.oidc_provider_id)))
)
BEGIN
    SELECT RAISE(ABORT, 'invalid authentication token origin');
END;

-- PAT step-up proofs are one-time and bound to one exact operation/target.
-- Only a digest is stored; raw proofs are returned once by the issuing helper.
CREATE TABLE auth_reauth_proofs (
    proof_hash TEXT PRIMARY KEY CHECK (
        length(proof_hash) = 64 AND proof_hash NOT GLOB '*[^0-9a-f]*'
    ),
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_token_id INTEGER NOT NULL REFERENCES api_tokens(id) ON DELETE CASCADE,
    purpose TEXT NOT NULL CHECK (purpose IN (
        'link_identity', 'unlink_identity', 'admin_unlink_identity',
        'change_password', 'create_api_token', 'revoke_api_token'
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
        OR (purpose IN ('change_password', 'create_api_token')
            AND target_user_id = user_id AND provider_id IS NULL
            AND identity_id IS NULL AND target_token_id IS NULL)
        OR (purpose = 'revoke_api_token' AND target_user_id = user_id
            AND provider_id IS NULL AND identity_id IS NULL AND target_token_id IS NOT NULL)
    )
);
CREATE INDEX auth_reauth_proofs_user ON auth_reauth_proofs(user_id, expires_at);

-- Auth events are intentionally separate from resource history. The schema
-- has no free-text/details column so credentials and provider claims cannot
-- accidentally be included in an audit row.
CREATE TABLE auth_audit_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL CHECK (action IN (
        'identity_linked', 'identity_unlinked', 'identity_admin_unlinked',
        'oidc_login_succeeded', 'oidc_token_minted', 'oidc_provider_tokens_revoked'
    )),
    actor_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    target_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    identity_id INTEGER,
    provider_id TEXT CHECK (provider_id IS NULL OR length(provider_id) BETWEEN 1 AND 128),
    created_at INTEGER NOT NULL
);
CREATE INDEX auth_audit_events_target ON auth_audit_events(target_user_id, created_at);
