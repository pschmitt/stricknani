"""add is_ai_enhanced to projects

Revision ID: 10634dd5e7f1
Revises: 8b1a9d2f3c4e
Create Date: 2026-02-01 21:31:04.604236

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "10634dd5e7f1"
down_revision: str | None = "8b1a9d2f3c4e"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table("projects", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column(
                "is_ai_enhanced",
                sa.Boolean(),
                nullable=False,
                server_default="0",
            )
        )


def downgrade() -> None:
    with op.batch_alter_table("projects", schema=None) as batch_op:
        batch_op.drop_column("is_ai_enhanced")
