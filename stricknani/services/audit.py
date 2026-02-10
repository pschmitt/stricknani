"""Audit logging helpers."""

from __future__ import annotations

import json
from datetime import datetime
from typing import Literal

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from stricknani.models import AuditLog

AuditEntityType = Literal["project", "yarn"]


def _serialize_value(value: object) -> object:
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def _to_json(data: dict[str, object] | None) -> str | None:
    if not data:
        return None
    return json.dumps(data, ensure_ascii=True, sort_keys=True)


def _from_json(raw: str | None) -> dict[str, object]:
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    if isinstance(parsed, dict):
        return parsed
    return {}


def build_field_changes(
    before: dict[str, object],
    after: dict[str, object],
) -> dict[str, dict[str, object]]:
    """Build old/new map for modified fields only."""
    changes: dict[str, dict[str, object]] = {}
    for field in sorted(set(before.keys()) | set(after.keys())):
        left = _serialize_value(before.get(field))
        right = _serialize_value(after.get(field))
        if left != right:
            changes[field] = {"old": left, "new": right}
    return changes


async def create_audit_log(
    db: AsyncSession,
    *,
    actor_user_id: int,
    entity_type: AuditEntityType,
    entity_id: int,
    action: str,
    details: dict[str, object] | None = None,
) -> AuditLog:
    """Create an audit log row inside the current transaction."""
    entry = AuditLog(
        actor_user_id=actor_user_id,
        entity_type=entity_type,
        entity_id=entity_id,
        action=action,
        details=_to_json(details),
    )
    db.add(entry)
    await db.flush()
    return entry


async def list_audit_logs(
    db: AsyncSession,
    *,
    entity_type: AuditEntityType,
    entity_id: int,
    limit: int = 100,
) -> list[AuditLog]:
    """List newest-to-oldest audit logs for one entity."""
    result = await db.execute(
        select(AuditLog)
        .where(
            AuditLog.entity_type == entity_type,
            AuditLog.entity_id == entity_id,
        )
        .options(selectinload(AuditLog.actor))
        .order_by(AuditLog.created_at.desc(), AuditLog.id.desc())
        .limit(max(1, min(limit, 500)))
    )
    return list(result.scalars())


def serialize_audit_log(entry: AuditLog) -> dict[str, object]:
    """Serialize one audit log row for templates/CLI."""
    details = _from_json(entry.details)
    return {
        "id": entry.id,
        "actor_user_id": entry.actor_user_id,
        "actor_email": entry.actor.email if entry.actor else None,
        "entity_type": entry.entity_type,
        "entity_id": entry.entity_id,
        "action": entry.action,
        "details": details,
        "created_at": entry.created_at.isoformat(),
    }
