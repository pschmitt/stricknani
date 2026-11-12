-- Which client caused an audit event. Administrator-only: the regular history
-- and activity reads never select these columns. Rows written before this
-- migration keep NULL (shown as an unknown client). No IP address is stored.
ALTER TABLE audit_events ADD COLUMN client_kind TEXT
    CHECK (client_kind IS NULL OR client_kind IN ('web', 'android', 'api', 'system'));
ALTER TABLE audit_events ADD COLUMN client_version TEXT
    CHECK (client_version IS NULL OR length(client_version) <= 64);
ALTER TABLE audit_events ADD COLUMN client_label TEXT
    CHECK (client_label IS NULL OR length(client_label) <= 96);
ALTER TABLE audit_events ADD COLUMN client_user_agent TEXT
    CHECK (client_user_agent IS NULL OR length(client_user_agent) <= 256);
