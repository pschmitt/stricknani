"""add_other_materials_to_projects

Revision ID: 284f83a882b1
Revises: f56dd5536682
Create Date: 2026-02-08 14:38:00.243070

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "284f83a882b1"
down_revision: str | None = "f56dd5536682"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("projects", sa.Column("other_materials", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("projects", "other_materials")
