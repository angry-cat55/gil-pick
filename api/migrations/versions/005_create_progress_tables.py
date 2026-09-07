"""여행 진행 테이블과 진행 version을 생성한다.

Revision ID: 005_create_progress_tables
Revises: 004_create_route_table
Create Date: 2026-09-07
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "005_create_progress_tables"
down_revision: str | None = "004_create_route_table"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """진행 version, 전환 감사 기록과 미계획 구간 저장소를 추가한다."""
    op.add_column(
        "trip_days",
        sa.Column("progress_version", sa.Integer(), server_default="0", nullable=False),
    )
    op.create_check_constraint(
        "ck_trip_days_progress_version", "trip_days", "progress_version >= 0"
    )
    op.create_table(
        "progress_transitions",
        sa.Column("transition_id", sa.Uuid(), nullable=False),
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("primary_item_id", sa.Uuid()),
        # progress_events는 F007에서 생성되므로 그때 FK를 함께 추가한다.
        sa.Column("trigger_event_id", sa.Uuid()),
        sa.Column("transition_type", sa.String(30), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("source", sa.String(30), nullable=False),
        sa.Column("decision", sa.String(30)),
        sa.Column("affected_items", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("detected_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("auto_finalize_at", sa.DateTime(timezone=True)),
        sa.Column("confirmed_at", sa.DateTime(timezone=True)),
        sa.Column("undo_deadline", sa.DateTime(timezone=True)),
        sa.Column("cancelled_at", sa.DateTime(timezone=True)),
        sa.Column("undone_at", sa.DateTime(timezone=True)),
        sa.Column("schedule_version_before", sa.Integer(), nullable=False),
        sa.Column("schedule_version_after", sa.Integer()),
        sa.Column("progress_version_after", sa.Integer(), nullable=False),
        sa.Column("idempotency_key", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "schedule_version_before >= 1 AND "
            "(schedule_version_after IS NULL OR schedule_version_after >= 1)",
            name="ck_progress_transitions_schedule_versions",
        ),
        sa.CheckConstraint(
            "progress_version_after >= 1",
            name="ck_progress_transitions_progress_version",
        ),
        sa.ForeignKeyConstraint(
            ["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(["primary_item_id"], ["itinerary_items.item_id"]),
        sa.PrimaryKeyConstraint("transition_id"),
        sa.UniqueConstraint(
            "trip_day_id",
            "idempotency_key",
            name="uq_progress_transitions_day_idempotency",
        ),
    )
    op.create_table(
        "progress_segments",
        sa.Column("progress_segment_id", sa.Uuid(), nullable=False),
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("from_item_id", sa.Uuid()),
        sa.Column("to_item_id", sa.Uuid(), nullable=False),
        sa.Column("transport_mode", sa.String(20), nullable=False),
        sa.Column("provider", sa.String(20), nullable=False),
        sa.Column("duration_seconds", sa.Integer(), nullable=False),
        sa.Column("distance_meters", sa.Integer(), nullable=False),
        sa.Column("computed_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "transport_mode IN ('WALK', 'TRANSIT', 'CAR')",
            name="ck_progress_segments_transport_mode",
        ),
        sa.CheckConstraint(
            "provider IN ('TMAP', 'ODSAY')", name="ck_progress_segments_provider"
        ),
        sa.CheckConstraint(
            "duration_seconds >= 0", name="ck_progress_segments_duration"
        ),
        sa.CheckConstraint(
            "distance_meters >= 0", name="ck_progress_segments_distance"
        ),
        sa.ForeignKeyConstraint(
            ["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["from_item_id"], ["itinerary_items.item_id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["to_item_id"], ["itinerary_items.item_id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("progress_segment_id"),
    )
    op.create_index(
        "uq_progress_segments_items",
        "progress_segments",
        ["trip_day_id", "from_item_id", "to_item_id"],
        unique=True,
        postgresql_where=sa.text("from_item_id IS NOT NULL"),
    )
    op.create_index(
        "uq_progress_segments_start",
        "progress_segments",
        ["trip_day_id", "to_item_id"],
        unique=True,
        postgresql_where=sa.text("from_item_id IS NULL"),
    )


def downgrade() -> None:
    """진행 저장 구조를 의존 관계의 역순으로 제거한다."""
    op.drop_index("uq_progress_segments_start", table_name="progress_segments")
    op.drop_index("uq_progress_segments_items", table_name="progress_segments")
    op.drop_table("progress_segments")
    op.drop_table("progress_transitions")
    op.drop_constraint("ck_trip_days_progress_version", "trip_days", type_="check")
    op.drop_column("trip_days", "progress_version")

