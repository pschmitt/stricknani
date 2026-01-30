"""add_link_archive_requested_at_to_projects

Revision ID: 5b9f1d2c7a4e
Revises: 2f6d6c8c5e3a
Create Date: 2026-01-30 22:16:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "5b9f1d2c7a4e"
down_revision: str | None = "2f6d6c8c5e3a"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add archive requested timestamp column to projects table."""
    with op.batch_alter_table("projects", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column("link_archive_requested_at", sa.DateTime(), nullable=True)
        )


def downgrade() -> None:
    """Drop archive requested timestamp column from projects table."""
    with op.batch_alter_table("projects", schema=None) as batch_op:
        batch_op.drop_column("link_archive_requested_at")
