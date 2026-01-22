"""make_project_category_nullable

Revision ID: 3b710fa1e811
Revises: 4067701db7fd
Create Date: 2026-01-17 18:57:34.306724

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "3b710fa1e811"
down_revision: str | None = "4067701db7fd"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Make category column nullable in projects table."""
    # SQLite doesn't support ALTER COLUMN directly, so we need to use batch mode
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
