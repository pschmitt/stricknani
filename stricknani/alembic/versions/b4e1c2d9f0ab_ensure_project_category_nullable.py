"""ensure_project_category_nullable

Revision ID: b4e1c2d9f0ab
Revises: 9f8d2d49f2b1
Create Date: 2026-01-30 21:17:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

# revision identifiers, used by Alembic.
revision: str = "b4e1c2d9f0ab"
down_revision: str | None = "7c7d5a3c1f2a"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _is_category_nullable() -> bool:
    connection = op.get_bind()
    inspector = inspect(connection)
    for column in inspector.get_columns("projects"):
        if column.get("name") == "category":
            return bool(column.get("nullable"))
    return True


def upgrade() -> None:
    """Ensure category column is nullable in projects table."""
    if _is_category_nullable():
        return
    with op.batch_alter_table("projects", schema=None) as batch_op:
        batch_op.alter_column(
            "category",
            existing_type=sa.String(length=50),
            nullable=True,
        )


def downgrade() -> None:
    """Revert category column to NOT NULL."""
    with op.batch_alter_table("projects", schema=None) as batch_op:
        batch_op.alter_column(
            "category",
            existing_type=sa.String(length=50),
            nullable=False,
        )
