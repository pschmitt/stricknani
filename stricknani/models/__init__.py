"""Database models for Stricknani."""

import json
from datetime import UTC, datetime
from enum import Enum

from sqlalchemy import (
    Boolean,
    Column,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Table,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    """Base class for all models."""

    pass


class ProjectCategory(str, Enum):
    """Project category enum."""

    PULLOVER = "Pullover"
    JACKE = "Jacke"
    SCHAL = "Schal"
    MUTZE = "Mütze"
    STIRNBAND = "Stirnband"


class ImageType(str, Enum):
    """Image type enum."""

    PHOTO = "photo"
    DIAGRAM = "diagram"


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

    # Relationships
    projects: Mapped[list["Project"]] = relationship(
        "Project", back_populates="owner", cascade="all, delete-orphan"
    )
    favorite_projects: Mapped[list["Project"]] = relationship(
        "Project",
        secondary=lambda: user_favorites,
        back_populates="favorited_by",
    )
    favorite_yarns: Mapped[list["Yarn"]] = relationship(
        "Yarn",
        secondary=lambda: user_favorite_yarns,
        back_populates="favorited_by",
    )
    yarns: Mapped[list["Yarn"]] = relationship(
        "Yarn", back_populates="owner", cascade="all, delete-orphan"
    )
    categories: Mapped[list["Category"]] = relationship(
        "Category", back_populates="owner", cascade="all, delete-orphan"
    )


user_favorites: Table = Table(
    "user_favorites",
    Base.metadata,
    Column("user_id", ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
    Column(
        "project_id",
        ForeignKey("projects.id", ondelete="CASCADE"),
        primary_key=True,
    ),
    UniqueConstraint("user_id", "project_id", name="uq_user_favorite"),
)


user_favorite_yarns: Table = Table(
    "user_favorite_yarns",
    Base.metadata,
    Column("user_id", ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
    Column(
        "yarn_id",
        ForeignKey("yarns.id", ondelete="CASCADE"),
        primary_key=True,
    ),
    UniqueConstraint("user_id", "yarn_id", name="uq_user_favorite_yarn"),
)


class Project(Base):
    """Project model."""

    __tablename__ = "projects"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(255), index=True)
    category: Mapped[str] = mapped_column(String(50))
    yarn: Mapped[str | None] = mapped_column(String(255), nullable=True)
    needles: Mapped[str | None] = mapped_column(String(255), nullable=True)
    gauge_stitches: Mapped[int | None] = mapped_column(Integer, nullable=True)
    gauge_rows: Mapped[int | None] = mapped_column(Integer, nullable=True)
    comment: Mapped[str | None] = mapped_column(Text, nullable=True)
    tags: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC), index=True
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime,
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
    )

    # Foreign key
    owner_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id"), index=True)

    # Relationships
    owner: Mapped["User"] = relationship("User", back_populates="projects")
    images: Mapped[list["Image"]] = relationship(
        "Image", back_populates="project", cascade="all, delete-orphan"
    )
    steps: Mapped[list["Step"]] = relationship(
        "Step",
        back_populates="project",
        cascade="all, delete-orphan",
        order_by="Step.step_number",
    )
    favorited_by: Mapped[list["User"]] = relationship(
        "User",
        secondary=lambda: user_favorites,
        back_populates="favorite_projects",
    )
    yarns: Mapped[list["Yarn"]] = relationship(
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


class Category(Base):
    """User-defined project categories."""

    __tablename__ = "categories"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(100))
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC), nullable=False
    )
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE")
    )

    owner: Mapped["User"] = relationship("User", back_populates="categories")


class Yarn(Base):
    """Yarn stash entry."""

    __tablename__ = "yarns"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    brand: Mapped[str | None] = mapped_column(String(120), nullable=True)
    colorway: Mapped[str | None] = mapped_column(String(120), nullable=True)
    fiber_content: Mapped[str | None] = mapped_column(String(255), nullable=True)
    weight_category: Mapped[str | None] = mapped_column(String(80), nullable=True)
    weight_grams: Mapped[int | None] = mapped_column(Integer, nullable=True)
    length_meters: Mapped[int | None] = mapped_column(Integer, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC), index=True, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime,
        default=lambda: datetime.now(UTC),
        onupdate=lambda: datetime.now(UTC),
        nullable=False,
    )

    owner_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), index=True
    )

    owner: Mapped["User"] = relationship("User", back_populates="yarns")
    photos: Mapped[list["YarnImage"]] = relationship(
        "YarnImage",
        back_populates="yarn",
        cascade="all, delete-orphan",
        order_by=lambda: YarnImage.created_at.desc(),
    )
    projects: Mapped[list["Project"]] = relationship(
        "Project",
        secondary=lambda: project_yarns,
        back_populates="yarns",
    )
    favorited_by: Mapped[list["User"]] = relationship(
        "User",
        secondary=lambda: user_favorite_yarns,
        back_populates="favorite_yarns",
    )


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

    # Foreign key
    project_id: Mapped[int] = mapped_column(Integer, ForeignKey("projects.id"))

    # Relationships
    project: Mapped["Project"] = relationship("Project", back_populates="steps")
    images: Mapped[list["Image"]] = relationship(
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
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC)
    )

    # Foreign keys
    project_id: Mapped[int] = mapped_column(Integer, ForeignKey("projects.id"))
    step_id: Mapped[int | None] = mapped_column(
        Integer, ForeignKey("steps.id"), nullable=True
    )

    # Relationships
    project: Mapped["Project"] = relationship("Project", back_populates="images")
    step: Mapped["Step | None"] = relationship("Step", back_populates="images")


class YarnImage(Base):
    """Images attached to yarn stash entries."""

    __tablename__ = "yarn_images"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    original_filename: Mapped[str] = mapped_column(String(255), nullable=False)
    alt_text: Mapped[str] = mapped_column(String(255), nullable=False, default="")
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(UTC)
    )

    yarn_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("yarns.id", ondelete="CASCADE"), index=True
    )

    yarn: Mapped["Yarn"] = relationship("Yarn", back_populates="photos")


project_yarns: Table = Table(
    "project_yarns",
    Base.metadata,
    Column(
        "project_id",
        ForeignKey("projects.id", ondelete="CASCADE"),
        primary_key=True,
    ),
    Column(
        "yarn_id",
        ForeignKey("yarns.id", ondelete="CASCADE"),
        primary_key=True,
    ),
    UniqueConstraint("project_id", "yarn_id", name="uq_project_yarn"),
)
