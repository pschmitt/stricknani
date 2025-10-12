from __future__ import annotations

from datetime import datetime

from enum import Enum as EnumType

from sqlalchemy import DateTime, Enum as SqlEnum, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    username: Mapped[str] = mapped_column(String(50), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(128))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=datetime.utcnow
    )


class Category(Base):
    __tablename__ = "categories"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(100), unique=True)
    description: Mapped[str | None] = mapped_column(Text())

    projects: Mapped[list[Project]] = relationship(
        "Project", back_populates="category", cascade="all, delete-orphan"
    )


class ProjectStatus(str, EnumType):
    IDEATION = "ideation"
    ACTIVE = "active"
    HOLD = "hold"
    COMPLETE = "complete"


class Project(Base):
    __tablename__ = "projects"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(120), unique=True)
    summary: Mapped[str | None] = mapped_column(Text())
    status: Mapped[ProjectStatus] = mapped_column(
        SqlEnum(ProjectStatus, name="project_status"), default=ProjectStatus.IDEATION
    )
    progress: Mapped[int] = mapped_column(Integer, default=0)
    category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"))

    category: Mapped[Category | None] = relationship("Category", back_populates="projects")
    tasks: Mapped[list[Task]] = relationship(
        "Task", back_populates="project", cascade="all, delete-orphan"
    )
    photos: Mapped[list[ProjectPhoto]] = relationship(
        "ProjectPhoto", back_populates="project", cascade="all, delete-orphan"
    )
    updates: Mapped[list[ProjectUpdate]] = relationship(
        "ProjectUpdate", back_populates="project", cascade="all, delete-orphan"
    )


class TaskStatus(str, EnumType):
    TODO = "todo"
    IN_PROGRESS = "in_progress"
    BLOCKED = "blocked"
    DONE = "done"


class Task(Base):
    __tablename__ = "tasks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    title: Mapped[str] = mapped_column(String(150))
    notes: Mapped[str | None] = mapped_column(Text())
    status: Mapped[TaskStatus] = mapped_column(
        SqlEnum(TaskStatus, name="task_status"), default=TaskStatus.TODO
    )
    project_id: Mapped[int] = mapped_column(ForeignKey("projects.id"))

    project: Mapped[Project] = relationship("Project", back_populates="tasks")


class ProjectPhoto(Base):
    __tablename__ = "project_photos"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    project_id: Mapped[int] = mapped_column(ForeignKey("projects.id"))
    title: Mapped[str | None] = mapped_column(String(150))
    filename: Mapped[str] = mapped_column(String(255))
    uploaded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=datetime.utcnow
    )

    project: Mapped[Project] = relationship("Project", back_populates="photos")


class ProjectUpdate(Base):
    __tablename__ = "project_updates"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    project_id: Mapped[int] = mapped_column(ForeignKey("projects.id"))
    headline: Mapped[str] = mapped_column(String(150))
    content: Mapped[str] = mapped_column(Text())
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=datetime.utcnow
    )

    project: Mapped[Project] = relationship("Project", back_populates="updates")
