"""Database models for Stricknani."""

from datetime import datetime
from enum import Enum

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
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
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

    # Relationships
    projects: Mapped[list["Project"]] = relationship(
        "Project", back_populates="owner", cascade="all, delete-orphan"
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
    instructions: Mapped[str | None] = mapped_column(Text, nullable=True)
    comment: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, index=True
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow
    )

    # Foreign key
    owner_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id"), index=True)

    # Relationships
    owner: Mapped["User"] = relationship("User", back_populates="projects")
    images: Mapped[list["Image"]] = relationship(
        "Image", back_populates="project", cascade="all, delete-orphan"
    )


class Image(Base):
    """Image model."""

    __tablename__ = "images"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    filename: Mapped[str] = mapped_column(String(255))
    original_filename: Mapped[str] = mapped_column(String(255))
    image_type: Mapped[str] = mapped_column(String(20))
    alt_text: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

    # Foreign key
    project_id: Mapped[int] = mapped_column(Integer, ForeignKey("projects.id"))

    # Relationships
    project: Mapped["Project"] = relationship("Project", back_populates="images")
