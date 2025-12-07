"""Add yarn stash and photos.

Revision ID: 20250117_01
Revises: 20250116_01
Create Date: 2025-01-17 00:00:00.000000
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision = "20250117_01"
down_revision = "20250116_01"
branch_labels: tuple[str, ...] | None = None
depends_on: tuple[str, ...] | None = None


def upgrade() -> None:
    op.create_table(
        "yarns",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("brand", sa.String(length=120), nullable=True),
        sa.Column("colorway", sa.String(length=120), nullable=True),
        sa.Column("fiber_content", sa.String(length=255), nullable=True),
        sa.Column("weight_category", sa.String(length=80), nullable=True),
        sa.Column("weight_grams", sa.Integer(), nullable=True),
        sa.Column("length_meters", sa.Integer(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(),
            nullable=False,
            server_default=sa.func.now(),
            onupdate=sa.func.now(),
        ),
        sa.Column("owner_id", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(["owner_id"], ["users.id"], ondelete="CASCADE"),
    )

    op.create_table(
        "yarn_images",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("filename", sa.String(length=255), nullable=False),
        sa.Column("original_filename", sa.String(length=255), nullable=False),
        sa.Column(
            "alt_text",
            sa.String(length=255),
            nullable=False,
            server_default="",
        ),
        sa.Column(
            "created_at",
            sa.DateTime(),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("yarn_id", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(["yarn_id"], ["yarns.id"], ondelete="CASCADE"),
    )


def downgrade() -> None:
    op.drop_table("yarn_images")
    op.drop_table("yarns")
