"""알림 테이블과 활성 FCM 토큰 유일 인덱스를 생성한다.

Revision ID: 011_create_notifications
Revises: 010_replacement_approval
Create Date: 2026-09-10
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "011_create_notifications"
down_revision: str | None = "010_replacement_approval"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """알림 저장소와 발송 대상 토큰 불변 조건을 만든다."""
    op.create_table(
        "notifications",
        sa.Column("notification_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("trip_id", sa.Uuid()), sa.Column("trip_day_id", sa.Uuid()),
        sa.Column("item_id", sa.Uuid()), sa.Column("detection_id", sa.Uuid()),
        sa.Column("transition_id", sa.Uuid()),
        sa.Column("type", sa.String(50), nullable=False),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("body", sa.Text(), nullable=False),
        sa.Column("dedup_key", sa.String(255)),
        sa.Column("sent_at", sa.DateTime(timezone=True)),
        sa.Column("read_at", sa.DateTime(timezone=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("type IN ('PLACE_CHANGE_SUGGESTION','ARRIVAL_CHECK','DEPARTURE_CHECK','ARRIVAL_AUTO_CONFIRMED','DEPARTURE_AUTO_CONFIRMED')", name="ck_notifications_type"),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["trip_id"], ["trips.trip_id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["trip_day_id"], ["trip_days.trip_day_id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["item_id"], ["itinerary_items.item_id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["detection_id"], ["detections.detection_id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["transition_id"], ["progress_transitions.transition_id"], ondelete="SET NULL"),
        sa.PrimaryKeyConstraint("notification_id"),
    )
    op.create_index("ix_notifications_user_unread", "notifications", ["user_id", "read_at", sa.text("created_at DESC")])
    op.create_index("ix_notifications_retention", "notifications", ["created_at"])
    op.create_index("uq_notifications_dedup", "notifications", ["user_id", "dedup_key"], unique=True, postgresql_where=sa.text("dedup_key IS NOT NULL"))
    op.create_index("ix_notifications_pending", "notifications", ["created_at"], postgresql_where=sa.text("sent_at IS NULL"))
    op.create_index("uq_device_sessions_fcm_token", "device_sessions", ["fcm_token"], unique=True, postgresql_where=sa.text("fcm_token IS NOT NULL AND revoked_at IS NULL"))


def downgrade() -> None:
    """알림 저장소와 FCM 토큰 인덱스를 제거한다."""
    op.drop_index("uq_device_sessions_fcm_token", table_name="device_sessions")
    for name in ("ix_notifications_pending", "uq_notifications_dedup", "ix_notifications_retention", "ix_notifications_user_unread"):
        op.drop_index(name, table_name="notifications")
    op.drop_table("notifications")
