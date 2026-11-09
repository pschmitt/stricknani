-- Imports that run on the server and wait for the user's review: start one on the
-- phone, review it days later on the phone or the web. The editor turns a draft
-- into a project or a yarn; saving or dismissing deletes the row.
CREATE TABLE import_drafts (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- What the result becomes.
    target TEXT NOT NULL CHECK (target IN ('project', 'yarn')),
    -- 'url' (source_url is set) or 'text' (the pasted text is not kept).
    source_kind TEXT NOT NULL CHECK (source_kind IN ('url', 'text')),
    source_url TEXT CHECK (source_url IS NULL OR length(source_url) <= 2048),
    use_ai INTEGER NOT NULL DEFAULT 0 CHECK (use_ai IN (0, 1)),
    status TEXT NOT NULL CHECK (status IN ('running', 'ready', 'failed')),
    -- The preview as the preview endpoint returns it (`data`), when ready.
    preview_json TEXT,
    -- The name and first picture of the result, for the cards.
    title TEXT CHECK (title IS NULL OR length(title) <= 512),
    image_url TEXT CHECK (image_url IS NULL OR length(image_url) <= 2048),
    -- The error the preview endpoint would have answered, when failed.
    error_status INTEGER,
    error_json TEXT,
    locale TEXT NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'de')),
    created_at INTEGER NOT NULL,
    finished_at INTEGER,
    -- When the "ready" web push went out (once per draft).
    notified_at INTEGER
);
CREATE INDEX import_drafts_user ON import_drafts(user_id, created_at);
CREATE INDEX import_drafts_unnotified ON import_drafts(status, notified_at);
