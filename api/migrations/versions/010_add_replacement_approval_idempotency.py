"""장소 변경 승인 멱등 응답을 저장한다.

Revision ID: 010_replacement_approval
Revises: 009_create_replacement_tables
Create Date: 2026-09-10
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "010_replacement_approval"
down_revision: str | None = "009_create_replacement_tables"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """승인 재전송을 첫 응답으로 복원할 정보를 추가한다."""
    # 이 migration 전에는 승인 API가 없어 정상 경로로 생성된 이력이 없다.
    op.add_column(
        "place_replacements",
        sa.Column("idempotency_key", sa.String(255), nullable=False),
    )
    op.add_column(
        "place_replacements",
        sa.Column("response_snapshot", postgresql.JSONB(), nullable=False),
    )


def downgrade() -> None:
    """승인 멱등 저장 필드를 제거한다."""
    op.drop_column("place_replacements", "response_snapshot")
    op.drop_column("place_replacements", "idempotency_key")
