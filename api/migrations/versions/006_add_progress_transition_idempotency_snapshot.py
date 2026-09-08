"""진행 전환 멱등 응답 스냅샷을 추가한다.

Revision ID: 006_progress_response_snapshot
Revises: 005_create_progress_tables
Create Date: 2026-09-08
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "006_progress_response_snapshot"
down_revision: str | None = "005_create_progress_tables"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """상태 전환 요청 식별자와 최초 응답을 저장한다."""
    op.add_column(
        "progress_transitions",
        sa.Column("request_target_status", sa.String(20)),
    )
    op.add_column(
        "progress_transitions",
        sa.Column("response_snapshot", postgresql.JSONB(astext_type=sa.Text())),
    )


def downgrade() -> None:
    """멱등 응답 스냅샷 필드를 제거한다."""
    op.drop_column("progress_transitions", "response_snapshot")
    op.drop_column("progress_transitions", "request_target_status")
