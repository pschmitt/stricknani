"""add project description

Revision ID: a49c99e32d8a
Revises: f1a2b3c4d5e6
Create Date: 2026-01-31 21:44:13.170673

"""
from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = 'a49c99e32d8a'
down_revision: str | None = 'f1a2b3c4d5e6'
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _column_exists(table_name: str, column_name: str) -> bool:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    return any(
        column["name"] == column_name for column in inspector.get_columns(table_name)
    )


def upgrade() -> None:
    if _column_exists("projects", "description"):
        return

    op.add_column("projects", sa.Column("description", sa.Text(), nullable=True))


def downgrade() -> None:
    if not _column_exists("projects", "description"):
        return

    with op.batch_alter_table("projects") as batch_op:
        batch_op.drop_column("description")
