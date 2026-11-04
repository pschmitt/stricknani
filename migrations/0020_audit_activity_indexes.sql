-- Recent-activity feed: per-resource and per-actor newest-first scans.
CREATE INDEX audit_events_entity_created
    ON audit_events(entity_type, entity_id, created_at DESC, id DESC);
CREATE INDEX audit_events_actor_created
    ON audit_events(actor_user_id, entity_type, created_at DESC, id DESC);
