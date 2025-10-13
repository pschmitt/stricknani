"""Add user categories and project tags.

Revision ID: 20250116_01
Revises: 20241109_01_initial_schema
Create Date: 2025-01-16 00:00:00.000000
"""

from __future__ import annotations

from datetime import datetime

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision = "20250116_01"
down_revision = "20241109_01_initial_schema"
branch_labels: tuple[str, ...] | None = None
depends_on: tuple[str, ...] | None = None


def upgrade() -> None:
    op.create_table(
        "categories",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(length=100), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
    )
    op.create_unique_constraint(
        "uq_categories_user_name", "categories", ["user_id", "name"]
    )

    op.add_column("projects", sa.Column("tags", sa.Text(), nullable=True))

    connection = op.get_bind()
    categories = connection.execute(
        sa.text(
            "SELECT DISTINCT owner_id, category FROM projects"
            " WHERE category IS NOT NULL AND TRIM(category) <> ''"
        )
    ).fetchall()

    if categories:
        insert_stmt = sa.text(
            "INSERT INTO categories (user_id, name, created_at)"
            " VALUES (:user_id, :name, :created_at)"
            " ON CONFLICT DO NOTHING"
        )
        for owner_id, name in categories:
            connection.execute(
                insert_stmt,
                {
                    "user_id": owner_id,
                    "name": name.strip(),
                    "created_at": datetime.utcnow(),
                },
            )


def downgrade() -> None:
    op.drop_column("projects", "tags")
    op.drop_constraint("uq_categories_user_name", "categories", type_="unique")
    op.drop_table("categories")
