"""User model."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from stricknani.models.associations import user_favorite_yarns, user_favorites
from stricknani.models.base import Base

if TYPE_CHECKING:
    from stricknani.models.category import Category
    from stricknani.models.project import Project
    from stricknani.models.yarn import Yarn


class User(Base):
    """User model."""

    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC)
    )
    profile_image: Mapped[str | None] = mapped_column(String(255), nullable=True)

    projects: Mapped[list[Project]] = relationship(
        "Project", back_populates="owner", cascade="all, delete-orphan"
    )
    favorite_projects: Mapped[list[Project]] = relationship(
        "Project",
        secondary=lambda: user_favorites,
        back_populates="favorited_by",
    )
    favorite_yarns: Mapped[list[Yarn]] = relationship(
        "Yarn",
        secondary=lambda: user_favorite_yarns,
        back_populates="favorited_by",
    )
    yarns: Mapped[list[Yarn]] = relationship(
        "Yarn", back_populates="owner", cascade="all, delete-orphan"
    )
    categories: Mapped[list[Category]] = relationship(
        "Category", back_populates="owner", cascade="all, delete-orphan"
    )

    @property
    def avatar_url(self) -> str:
        """Get the user's avatar URL."""
        from stricknani.config import config
        from stricknani.utils.files import get_file_url
        from stricknani.utils.gravatar import gravatar_url

        if self.profile_image:
            media_path = config.MEDIA_ROOT / "users" / str(self.id) / self.profile_image
            if not media_path.exists():
                return gravatar_url(self.email)
            return get_file_url(self.profile_image, self.id, subdir="users")
        return gravatar_url(self.email)

    @property
    def avatar_thumbnail_url(self) -> str:
        """Get the user's avatar thumbnail URL."""
        from pathlib import Path

        from stricknani.config import config
        from stricknani.utils.files import get_thumbnail_url
        from stricknani.utils.gravatar import gravatar_url

        if self.profile_image:
            thumb_name = f"thumb_{Path(self.profile_image).stem}.jpg"
            thumb_path = (
                config.MEDIA_ROOT / "thumbnails" / "users" / str(self.id) / thumb_name
            )
            if thumb_path.exists():
                return get_thumbnail_url(self.profile_image, self.id, subdir="users")

            media_path = config.MEDIA_ROOT / "users" / str(self.id) / self.profile_image
            if media_path.exists():
                from stricknani.utils.files import get_file_url

                return get_file_url(self.profile_image, self.id, subdir="users")
            return gravatar_url(self.email, size=96)
        return gravatar_url(self.email, size=96)
