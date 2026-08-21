"""add fk indexes and image dimensions

Revision ID: 2a72e6c2c6ab
Revises: dc5805a0c03f
Create Date: 2026-07-10 06:46:36.259179

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "2a72e6c2c6ab"
down_revision: str | None = "dc5805a0c03f"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_index(
        op.f("ix_attachments_project_id"),
        "attachments",
        ["project_id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_categories_user_id"),
        "categories",
        ["user_id"],
        unique=False,
    )
    op.add_column("images", sa.Column("width", sa.Integer(), nullable=True))
    op.add_column("images", sa.Column("height", sa.Integer(), nullable=True))
    op.create_index(
        op.f("ix_images_project_id"),
        "images",
        ["project_id"],
        unique=False,
    )
    op.create_index(
        "ix_images_project_id_is_title_image",
        "images",
        ["project_id", "is_title_image"],
        unique=False,
    )
    op.create_index(
        op.f("ix_images_step_id"),
        "images",
        ["step_id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_steps_project_id"),
        "steps",
        ["project_id"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index(op.f("ix_steps_project_id"), table_name="steps")
    op.drop_index(op.f("ix_images_step_id"), table_name="images")
    op.drop_index("ix_images_project_id_is_title_image", table_name="images")
    op.drop_index(op.f("ix_images_project_id"), table_name="images")
    op.drop_column("images", "height")
    op.drop_column("images", "width")
    op.drop_index(op.f("ix_categories_user_id"), table_name="categories")
    op.drop_index(op.f("ix_attachments_project_id"), table_name="attachments")
