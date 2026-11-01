-- Existing browser sessions are classified as API tokens and must sign in again.
-- Existing and imported API tokens keep their purpose and remain valid.
ALTER TABLE api_tokens
    ADD COLUMN purpose TEXT NOT NULL DEFAULT 'api'
    CHECK (purpose IN ('api', 'browser'));
