"""Import target base classes.

Targets are responsible for persisting extracted data to the database
as Projects, Yarns, Steps, etc.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from stricknani.importing.models import ExtractedData, ImportResult


class ImportTarget(ABC):
    """Abstract base class for import targets.

    A target takes ExtractedData and persists it to the database
    as the appropriate entity (Project, Yarn, etc.).
    """

    def __init__(self, db: AsyncSession, owner_id: int) -> None:
        """Initialize the target.

        Args:
            db: Database session
            owner_id: ID of the user who will own the imported entity
        """
        self.db = db
        self.owner_id = owner_id

    @property
    @abstractmethod
    def target_type(self) -> str:
        """Return the type of entity this target creates (e.g., 'project', 'yarn')."""
        pass

    @abstractmethod
    async def create(self, data: ExtractedData) -> ImportResult:
        """Create an entity from extracted data.

        Args:
            data: The extracted data to persist

        Returns:
            ImportResult with the created entity info

        Raises:
            TargetError: If creation fails
        """
        pass


class TargetError(Exception):
    """Exception raised when target creation fails."""

    def __init__(self, message: str, target_type: str | None = None) -> None:
        """Initialize the error.

        Args:
            message: Error message
            target_type: Type of target that failed
        """
        super().__init__(message)
        self.target_type = target_type


__all__ = ["ImportTarget", "TargetError"]
