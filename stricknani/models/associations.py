"""Association tables for many-to-many relationships."""

from sqlalchemy import Column, ForeignKey, Table, UniqueConstraint

from stricknani.models.base import Base

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
