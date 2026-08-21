"""enforce single primary yarn image

Revision ID: dc5805a0c03f
Revises: 3715ace479eb
Create Date: 2026-02-10 17:10:10.643313

"""

from collections import defaultdict
from collections.abc import Sequence
from typing import cast

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "dc5805a0c03f"
down_revision: str | None = "3715ace479eb"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    bind = op.get_bind()

    rows = bind.execute(
        sa.text(
            """
            SELECT id, yarn_id, is_primary
            FROM yarn_images
            ORDER BY yarn_id ASC, is_primary DESC, created_at ASC, id ASC
            """
        )
    ).mappings()

    by_yarn: dict[int, list[dict[str, object]]] = defaultdict(list)
    for row in rows:
        row_dict = dict(row)
        yarn_id = cast(int, row_dict["yarn_id"])
        by_yarn[yarn_id].append(row_dict)

    for _yarn_id, images in by_yarn.items():
        primary_ids = [
            cast(int, item["id"]) for item in images if bool(item["is_primary"])
        ]
        if len(primary_ids) > 1:
            # Keep the earliest primary and demote the rest.
            keep_id = primary_ids[0]
            demote_ids = [pid for pid in primary_ids if pid != keep_id]
            for image_id in demote_ids:
                bind.execute(
                    sa.text(
                        "UPDATE yarn_images SET is_primary = 0 WHERE id = :image_id"
                    ),
                    {"image_id": image_id},
                )
        elif len(primary_ids) == 0 and images:
            # Ensure one deterministic primary when images exist.
            bind.execute(
                sa.text("UPDATE yarn_images SET is_primary = 1 WHERE id = :image_id"),
                {"image_id": cast(int, images[0]["id"])},
            )

    op.create_index(
        "uq_yarn_images_primary_per_yarn",
        "yarn_images",
        ["yarn_id"],
        unique=True,
        sqlite_where=sa.text("is_primary = 1"),
        postgresql_where=sa.text("is_primary = true"),
    )


def downgrade() -> None:
    op.drop_index("uq_yarn_images_primary_per_yarn", table_name="yarn_images")
