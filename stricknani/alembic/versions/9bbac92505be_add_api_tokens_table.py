"""add api_tokens table

Revision ID: 9bbac92505be
Revises: 371d2ef2091f
Create Date: 2026-08-18 12:16:47.686004

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "9bbac92505be"
down_revision: str | None = "371d2ef2091f"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "api_tokens",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("token_hash", sa.String(length=64), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("last_used_at", sa.DateTime(), nullable=True),
        sa.Column("expires_at", sa.DateTime(), nullable=True),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_api_tokens_id"), "api_tokens", ["id"], unique=False)
    op.create_index(
        op.f("ix_api_tokens_token_hash"), "api_tokens", ["token_hash"], unique=True
    )
    op.create_index(
        op.f("ix_api_tokens_user_id"), "api_tokens", ["user_id"], unique=False
    )


def downgrade() -> None:
    op.drop_index(op.f("ix_api_tokens_user_id"), table_name="api_tokens")
    op.drop_index(op.f("ix_api_tokens_token_hash"), table_name="api_tokens")
    op.drop_index(op.f("ix_api_tokens_id"), table_name="api_tokens")
    op.drop_table("api_tokens")
