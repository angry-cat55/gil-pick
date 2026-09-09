"""일정 변경 미리보기와 승인 이력 테이블을 생성한다.

Revision ID: 009_create_replacement_tables
Revises: 008_create_detections
Create Date: 2026-09-10
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "009_create_replacement_tables"
down_revision: str | None = "008_create_detections"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """미리보기와 장소 변경 이력 저장소를 만든다."""
    op.create_table(
        "route_previews",
        sa.Column("preview_id", sa.Uuid(), nullable=False),
        sa.Column("detection_id", sa.Uuid(), nullable=False),
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("item_id", sa.Uuid(), nullable=False),
        sa.Column("original_place_id", sa.Uuid(), nullable=False),
        sa.Column("alternative_place_id", sa.Uuid(), nullable=False),
        sa.Column("schedule_version", sa.Integer(), nullable=False),
        sa.Column("idempotency_key", sa.String(255), nullable=False),
        sa.Column("request_fingerprint", sa.String(64), nullable=False),
        sa.Column("route_payload", postgresql.JSONB(), nullable=False),
        sa.Column("total_duration_seconds", sa.Integer(), nullable=False),
        sa.Column("total_distance_meters", sa.Integer(), nullable=False),
        sa.Column("provider", sa.String(20), nullable=False),
        sa.Column("comparison", postgresql.JSONB(), nullable=False),
        sa.Column("response_snapshot", postgresql.JSONB(), nullable=False),
        sa.Column("status", sa.String(20), server_default="PENDING", nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint(
            "status IN ('PENDING','APPROVED','REJECTED','SUPERSEDED')",
            name="ck_route_previews_status",
        ),
        sa.ForeignKeyConstraint(["detection_id"], ["detections.detection_id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["item_id"], ["itinerary_items.item_id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["original_place_id"], ["places.place_id"]),
        sa.ForeignKeyConstraint(["alternative_place_id"], ["places.place_id"]),
        sa.PrimaryKeyConstraint("preview_id"),
        sa.UniqueConstraint(
            "detection_id", "idempotency_key", name="uq_route_previews_detection_idempotency"
        ),
    )
    op.create_index(
        "uq_route_previews_pending_detection",
        "route_previews",
        ["detection_id"],
        unique=True,
        postgresql_where=sa.text("status = 'PENDING'"),
    )
    op.create_index(
        "ix_route_previews_detection",
        "route_previews",
        ["detection_id", sa.text("created_at DESC")],
    )

    op.create_table(
        "place_replacements",
        sa.Column("replacement_id", sa.Uuid(), nullable=False),
        sa.Column("preview_id", sa.Uuid(), nullable=False),
        sa.Column("detection_id", sa.Uuid(), nullable=False),
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("item_id", sa.Uuid(), nullable=False),
        sa.Column("original_place_id", sa.Uuid(), nullable=False),
        sa.Column("new_place_id", sa.Uuid(), nullable=False),
        sa.Column("before_schedule_version", sa.Integer(), nullable=False),
        sa.Column("approved_schedule_version", sa.Integer(), nullable=False),
        sa.Column("approved_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("undo_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("undone_at", sa.DateTime(timezone=True)),
        sa.Column("undo_schedule_version", sa.Integer()),
        sa.ForeignKeyConstraint(["preview_id"], ["route_previews.preview_id"]),
        sa.ForeignKeyConstraint(["detection_id"], ["detections.detection_id"]),
        sa.ForeignKeyConstraint(["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["item_id"], ["itinerary_items.item_id"]),
        sa.ForeignKeyConstraint(["original_place_id"], ["places.place_id"]),
        sa.ForeignKeyConstraint(["new_place_id"], ["places.place_id"]),
        sa.PrimaryKeyConstraint("replacement_id"),
        sa.UniqueConstraint("preview_id", name="uq_place_replacements_preview"),
    )
    op.create_index(
        "ix_place_replacements_day",
        "place_replacements",
        ["trip_day_id", sa.text("approved_at DESC")],
    )


def downgrade() -> None:
    """일정 변경 저장소만 제거한다."""
    op.drop_index("ix_place_replacements_day", table_name="place_replacements")
    op.drop_table("place_replacements")
    op.drop_index("ix_route_previews_detection", table_name="route_previews")
    op.drop_index("uq_route_previews_pending_detection", table_name="route_previews")
    op.drop_table("route_previews")
