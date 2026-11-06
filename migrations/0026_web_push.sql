-- Web Push: timer reminders reach a browser even when no Stricknani tab is open.

-- The server's VAPID key (P-256 private scalar), created on first use.
CREATE TABLE push_vapid_key (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    private_key BLOB NOT NULL CHECK (length(private_key) = 32),
    created_at INTEGER NOT NULL
);

-- One row per browser that turned notifications on. The endpoint identifies
-- the browser; signing in with another account there moves the row.
CREATE TABLE push_subscriptions (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE CHECK (length(endpoint) <= 2048),
    p256dh BLOB NOT NULL CHECK (length(p256dh) = 65),
    auth BLOB NOT NULL CHECK (length(auth) = 16),
    created_at INTEGER NOT NULL
);
CREATE INDEX push_subscriptions_user ON push_subscriptions(user_id);

-- A user's reminder schedule for one running timer: the run it belongs to
-- (started_at), the interval and when the next reminder is due. It ends with
-- the run.
CREATE TABLE timer_reminders (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    started_at INTEGER NOT NULL,
    interval_minutes INTEGER NOT NULL CHECK (interval_minutes IN (30, 60, 120)),
    next_at INTEGER NOT NULL,
    locale TEXT NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'de')),
    PRIMARY KEY (user_id, project_id)
);
CREATE INDEX timer_reminders_due ON timer_reminders(next_at);
