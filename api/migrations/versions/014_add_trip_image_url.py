"""여행 대표 이미지 URL을 추가한다.

Revision ID: 014_add_trip_image_url
Revises: 013_prevent_trip_overlap
Create Date: 2026-09-15
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "014_add_trip_image_url"
down_revision: str | None = "013_prevent_trip_overlap"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """nullable 대표 이미지 URL 컬럼을 추가한다."""
    op.add_column("trips", sa.Column("image_url", sa.Text(), nullable=True))


def downgrade() -> None:
    """대표 이미지 URL 컬럼을 제거한다."""
    op.drop_column("trips", "image_url")
