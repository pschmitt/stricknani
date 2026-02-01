"""add is_ai_enhanced to yarns

Revision ID: 55054a3b00c8
Revises: 10634dd5e7f1
Create Date: 2026-02-01 21:35:14.002622

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "55054a3b00c8"
down_revision: str | None = "10634dd5e7f1"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table("yarns", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column(
                "is_ai_enhanced",
                sa.Boolean(),
                nullable=False,
                server_default="0",
            )
        )


def downgrade() -> None:
    with op.batch_alter_table("yarns", schema=None) as batch_op:
        batch_op.drop_column("is_ai_enhanced")
