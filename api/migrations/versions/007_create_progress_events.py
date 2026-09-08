"""위치 감지 이벤트 저장소와 전환 FK를 생성한다.

Revision ID: 007_create_progress_events
Revises: 006_progress_response_snapshot
Create Date: 2026-09-08
"""

from collections.abc import Sequence

from alembic import op
from geoalchemy2 import Geography
import sqlalchemy as sa

revision: str = "007_create_progress_events"
down_revision: str | None = "006_progress_response_snapshot"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """위치 이벤트를 저장하고 진행 전환의 발생 이벤트를 연결한다."""
    op.create_table(
        "progress_events",
        sa.Column("progress_event_id", sa.Uuid(), nullable=False),
        sa.Column("client_event_id", sa.Uuid(), nullable=False),
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("item_id", sa.Uuid(), nullable=False),
        sa.Column("event_type", sa.String(20), nullable=False),
        sa.Column("geofence_id", sa.String(255), nullable=False),
        sa.Column("location", Geography(geometry_type="POINT", srid=4326), nullable=False),
        sa.Column("accuracy_meters", sa.Numeric(6, 2), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("received_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("accepted", sa.Boolean(), nullable=False),
        sa.Column("rejection_reason", sa.String(80)),
        sa.CheckConstraint("event_type IN ('DWELL', 'EXIT', 'REENTER')", name="ck_progress_events_type"),
        sa.CheckConstraint("accuracy_meters >= 0", name="ck_progress_events_accuracy"),
        sa.ForeignKeyConstraint(["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["item_id"], ["itinerary_items.item_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("progress_event_id"),
        sa.UniqueConstraint("client_event_id", name="uq_progress_events_client_event"),
    )
    op.create_index(
        "ix_progress_events_day_occurred",
        "progress_events",
        ["trip_day_id", sa.text("occurred_at DESC")],
    )
    op.create_foreign_key(
        "fk_progress_transitions_trigger_event",
        "progress_transitions",
        "progress_events",
        ["trigger_event_id"],
        ["progress_event_id"],
    )


def downgrade() -> None:
    """전환 FK를 제거한 뒤 위치 이벤트 저장소를 삭제한다."""
    op.drop_constraint("fk_progress_transitions_trigger_event", "progress_transitions", type_="foreignkey")
    op.drop_index("ix_progress_events_day_occurred", table_name="progress_events")
    op.drop_table("progress_events")
