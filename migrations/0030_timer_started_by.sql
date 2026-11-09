-- Who started the running timer. Timer notifications (reminders, the ongoing
-- notification) go only to this user. NULL for a stopped timer and for a run
-- that was already going when this column was added (nobody is notified then).
ALTER TABLE project_timers ADD COLUMN started_by INTEGER
    REFERENCES users(id) ON DELETE SET NULL;
