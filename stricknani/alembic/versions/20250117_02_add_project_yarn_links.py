"""Add project-yarn links.

Revision ID: 20250117_02
Revises: 20250117_01
Create Date: 2025-01-17 00:10:00.000000
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision = "20250117_02"
down_revision = "20250117_01"
branch_labels: tuple[str, ...] | None = None
depends_on: tuple[str, ...] | None = None


def upgrade() -> None:
    op.create_table(
        "project_yarns",
        sa.Column("project_id", sa.Integer(), nullable=False),
        sa.Column("yarn_id", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["yarn_id"], ["yarns.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("project_id", "yarn_id"),
        sa.UniqueConstraint("project_id", "yarn_id", name="uq_project_yarn"),
    )


def downgrade() -> None:
    op.drop_table("project_yarns")
