"""Add link_archive_failed column

Revision ID: f1a2b3c4d5e6
Revises: badbda3d0d91
Create Date: 2026-01-31 21:30:00.000000

"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f1a2b3c4d5e6'
down_revision: Union[str, None] = 'badbda3d0d91'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    with op.batch_alter_table('projects', schema=None) as batch_op:
        batch_op.add_column(sa.Column('link_archive_failed', sa.Boolean(), nullable=False, server_default='0'))

    with op.batch_alter_table('yarns', schema=None) as batch_op:
        batch_op.add_column(sa.Column('link_archive_failed', sa.Boolean(), nullable=False, server_default='0'))


def downgrade() -> None:
    with op.batch_alter_table('yarns', schema=None) as batch_op:
        batch_op.drop_column('link_archive_failed')

    with op.batch_alter_table('projects', schema=None) as batch_op:
        batch_op.drop_column('link_archive_failed')
