ALTER TABLE audit_events ADD COLUMN resource_name_snapshot TEXT;
ALTER TABLE audit_events ADD COLUMN change_version INTEGER;
ALTER TABLE audit_events ADD COLUMN safe_changes TEXT;
