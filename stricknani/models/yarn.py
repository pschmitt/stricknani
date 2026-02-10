"""Yarn models."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, Integer, String, Text, text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from stricknani.models.associations import project_yarns, user_favorite_yarns
from stricknani.models.base import Base

if TYPE_CHECKING:
    from stricknani.models.project import Project
    from stricknani.models.user import User


class Yarn(Base):
    """Yarn stash entry."""

    __tablename__ = "yarns"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    brand: Mapped[str | None] = mapped_column(String(120), nullable=True)
    colorway: Mapped[str | None] = mapped_column(String(120), nullable=True)
    dye_lot: Mapped[str | None] = mapped_column(String(120), nullable=True)
    fiber_content: Mapped[str | None] = mapped_column(String(255), nullable=True)
    weight_category: Mapped[str | None] = mapped_column(String(80), nullable=True)
    recommended_needles: Mapped[str | None] = mapped_column(String(255), nullable=True)
    weight_grams: Mapped[int | None] = mapped_column(Integer, nullable=True)
    length_meters: Mapped[int | None] = mapped_column(Integer, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    link: Mapped[str | None] = mapped_column(String(2048), nullable=True)
    link_archive: Mapped[str | None] = mapped_column(String(2048), nullable=True)
    link_archive_requested_at: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True
    )
    link_archive_failed: Mapped[bool] = mapped_column(Boolean, default=False)
    is_ai_enhanced: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC), index=True, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime,
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
        nullable=False,
    )

    @property
    def archive_pending(self) -> bool:
        """Check if an archive is requested but not yet available."""
        return bool(
            self.link
            and self.link_archive_requested_at
            and not self.link_archive
            and not self.link_archive_failed
        )

    owner_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), index=True
    )

    owner: Mapped[User] = relationship("User", back_populates="yarns")
    photos: Mapped[list[YarnImage]] = relationship(
        "YarnImage",
        back_populates="yarn",
        cascade="all, delete-orphan",
        order_by=lambda: (YarnImage.is_primary.desc(), YarnImage.created_at.desc()),
    )
    projects: Mapped[list[Project]] = relationship(
        "Project",
        secondary=lambda: project_yarns,
        back_populates="yarns",
    )
    favorited_by: Mapped[list[User]] = relationship(
        "User",
        secondary=lambda: user_favorite_yarns,
        back_populates="favorite_yarns",
    )


class YarnImage(Base):
    """Images attached to yarn stash entries."""

    __tablename__ = "yarn_images"
    __table_args__ = (
        Index(
            "uq_yarn_images_primary_per_yarn",
            "yarn_id",
            unique=True,
            sqlite_where=text("is_primary = 1"),
            postgresql_where=text("is_primary = true"),
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    original_filename: Mapped[str] = mapped_column(String(255), nullable=False)
    alt_text: Mapped[str] = mapped_column(String(255), nullable=False, default="")
    is_primary: Mapped[bool] = mapped_column(Boolean, default=False, server_default="0")
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC)
    )

    yarn_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("yarns.id", ondelete="CASCADE"), index=True
    )

    yarn: Mapped[Yarn] = relationship("Yarn", back_populates="photos")
