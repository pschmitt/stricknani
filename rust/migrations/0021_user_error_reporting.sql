-- Per-user opt-out for operator-configured error reporting (default on; it only
-- has an effect when the instance has a Sentry DSN). The default must match
-- error_reporting::DEFAULT_USER_ENABLED.
ALTER TABLE users ADD COLUMN error_reporting INTEGER NOT NULL DEFAULT 1
    CHECK (error_reporting IN (0, 1));
