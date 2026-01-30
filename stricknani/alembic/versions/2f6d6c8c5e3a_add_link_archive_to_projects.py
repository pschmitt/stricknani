"""add_link_archive_to_projects

Revision ID: 2f6d6c8c5e3a
Revises: b4e1c2d9f0ab
Create Date: 2026-01-30 22:05:00.000000

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "2f6d6c8c5e3a"
down_revision: str | None = "b4e1c2d9f0ab"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add archive link column to projects table."""
    with op.batch_alter_table("projects", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column("link_archive", sa.String(length=2048), nullable=True)
        )


def downgrade() -> None:
    """Drop archive link column from projects table."""
    with op.batch_alter_table("projects", schema=None) as batch_op:
        batch_op.drop_column("link_archive")
