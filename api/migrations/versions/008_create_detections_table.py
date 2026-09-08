"""여행 변수 감지 결과 저장소를 생성한다.

Revision ID: 008_create_detections
Revises: 007_create_progress_events
Create Date: 2026-09-08
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "008_create_detections"
down_revision: str | None = "007_create_progress_events"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """감지 결과 테이블과 조회·중복 억제 인덱스를 만든다."""
    op.create_table(
        "detections",
        sa.Column("detection_id", sa.Uuid(), nullable=False),
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("item_id", sa.Uuid(), nullable=False),
        sa.Column("primary_type", sa.String(30), nullable=False),
        sa.Column("status", sa.String(20), server_default="ACTIVE", nullable=False),
        sa.Column("eta", sa.DateTime(timezone=True), nullable=False),
        sa.Column("score", sa.Numeric(7, 6)),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("evaluation_snapshot", postgresql.JSONB(), nullable=False),
        sa.Column("fingerprint", sa.String(255), nullable=False),
        sa.Column("detected_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("last_evaluated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("read_at", sa.DateTime(timezone=True)),
        sa.Column("resolved_at", sa.DateTime(timezone=True)),
        sa.CheckConstraint("score IS NULL OR (score >= 0 AND score < 10)", name="ck_detections_score"),
        sa.CheckConstraint("primary_type IN ('CONGESTION','WEATHER','OPERATING_HOURS')", name="ck_detections_primary_type"),
        sa.CheckConstraint("status IN ('ACTIVE','RESOLVED','DISMISSED','INVALIDATED')", name="ck_detections_status"),
        sa.ForeignKeyConstraint(["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["item_id"], ["itinerary_items.item_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("detection_id"),
    )
    op.create_index("uq_detections_active_fingerprint", "detections", ["fingerprint"], unique=True, postgresql_where=sa.text("status = 'ACTIVE'"))
    op.create_index("ix_detections_day_status_detected", "detections", ["trip_day_id", "status", sa.text("detected_at DESC")])
    op.create_index("ix_detections_item", "detections", ["item_id"])


def downgrade() -> None:
    """감지 결과 저장소를 제거한다."""
    op.drop_index("ix_detections_item", table_name="detections")
    op.drop_index("ix_detections_day_status_detected", table_name="detections")
    op.drop_index("uq_detections_active_fingerprint", table_name="detections")
    op.drop_table("detections")
