"""Project and related models."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from stricknani.models.associations import project_yarns, user_favorites
from stricknani.models.base import Base

if TYPE_CHECKING:
    from stricknani.models.user import User
    from stricknani.models.yarn import Yarn


class Project(Base):
    """Project model."""

    __tablename__ = "projects"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(255), index=True)
    category: Mapped[str | None] = mapped_column(String(50), nullable=True)
    yarn: Mapped[str | None] = mapped_column(String(255), nullable=True)
    needles: Mapped[str | None] = mapped_column(String(255), nullable=True)
    stitch_sample: Mapped[str | None] = mapped_column(Text, nullable=True)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    comment: Mapped[str | None] = mapped_column(Text, nullable=True)
    tags: Mapped[str | None] = mapped_column(Text, nullable=True)
    link: Mapped[str | None] = mapped_column(String(2048), nullable=True)
    link_archive: Mapped[str | None] = mapped_column(String(2048), nullable=True)
    link_archive_requested_at: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True
    )
    link_archive_failed: Mapped[bool] = mapped_column(Boolean, default=False)
    is_ai_enhanced: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC), index=True
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

    updated_at: Mapped[datetime] = mapped_column(
        DateTime,
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
    )

    owner_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id"), index=True)

    owner: Mapped[User] = relationship("User", back_populates="projects")
    images: Mapped[list[Image]] = relationship(
        "Image", back_populates="project", cascade="all, delete-orphan"
    )
    steps: Mapped[list[Step]] = relationship(
        "Step",
        back_populates="project",
        cascade="all, delete-orphan",
        order_by="Step.step_number",
    )
    favorited_by: Mapped[list[User]] = relationship(
        "User",
        secondary=lambda: user_favorites,
        back_populates="favorite_projects",
    )
    yarns: Mapped[list[Yarn]] = relationship(
        "Yarn",
        secondary=lambda: project_yarns,
        back_populates="projects",
    )

    def tag_list(self) -> list[str]:
        """Return tags as a list."""

        if not self.tags:
            return []

        try:
            data = json.loads(self.tags)
        except (ValueError, TypeError):
            data = None

        if isinstance(data, list):
            return [str(tag).strip() for tag in data if str(tag).strip()]

        return [segment.strip() for segment in self.tags.split(",") if segment.strip()]


class Step(Base):
    """Step model for project instructions."""

    __tablename__ = "steps"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    title: Mapped[str] = mapped_column(String(255))
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    step_number: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC)
    )

    project_id: Mapped[int] = mapped_column(Integer, ForeignKey("projects.id"))

    project: Mapped[Project] = relationship("Project", back_populates="steps")
    images: Mapped[list[Image]] = relationship(
        "Image", back_populates="step", cascade="all, delete-orphan"
    )


class Image(Base):
    """Image model."""

    __tablename__ = "images"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    filename: Mapped[str] = mapped_column(String(255))
    original_filename: Mapped[str] = mapped_column(String(255))
    image_type: Mapped[str] = mapped_column(String(20))
    alt_text: Mapped[str] = mapped_column(String(255))
    is_title_image: Mapped[bool] = mapped_column(Boolean, default=False)
    is_stitch_sample: Mapped[bool | None] = mapped_column(
        Boolean, default=False, nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC)
    )

    project_id: Mapped[int] = mapped_column(Integer, ForeignKey("projects.id"))
    step_id: Mapped[int | None] = mapped_column(
        Integer, ForeignKey("steps.id"), nullable=True
    )

    project: Mapped[Project] = relationship("Project", back_populates="images")
    step: Mapped[Step | None] = relationship("Step", back_populates="images")
