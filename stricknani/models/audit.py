"""Audit log model."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from stricknani.models.base import Base

if TYPE_CHECKING:
    from stricknani.models.user import User


class AuditLog(Base):
    """Immutable audit log entries for project/yarn events."""

    __tablename__ = "audit_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    actor_user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id"), index=True
    )
    entity_type: Mapped[str] = mapped_column(String(20), index=True)
    entity_id: Mapped[int] = mapped_column(Integer, index=True)
    action: Mapped[str] = mapped_column(String(64), index=True)
    details: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC), index=True
    )

    actor: Mapped[User] = relationship("User")
