"""알림 FCM 전달 상태와 제한된 재시도 정보를 추가한다.

Revision ID: 017_notification_delivery_state
Revises: 016_defer_itinerary_sequence
Create Date: 2026-09-16
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "017_notification_delivery_state"
down_revision: str | None = "016_defer_itinerary_sequence"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "notifications",
        sa.Column(
            "delivery_status", sa.String(20), server_default="PENDING", nullable=False
        ),
    )
    op.add_column(
        "notifications",
        sa.Column(
            "delivery_attempts", sa.Integer(), server_default="0", nullable=False
        ),
    )
    op.add_column(
        "notifications", sa.Column("next_attempt_at", sa.DateTime(timezone=True))
    )
    op.create_check_constraint(
        "ck_notifications_delivery_status",
        "notifications",
        "delivery_status IN ('PENDING','SENT','FAILED','NO_DEVICE')",
    )
    op.create_check_constraint(
        "ck_notifications_delivery_attempts", "notifications", "delivery_attempts >= 0"
    )
    op.execute(
        "UPDATE notifications SET delivery_status = 'SENT' WHERE sent_at IS NOT NULL"
    )
    op.drop_index("ix_notifications_pending", table_name="notifications")
    op.create_index(
        "ix_notifications_pending",
        "notifications",
        ["next_attempt_at", "created_at"],
        postgresql_where=sa.text("delivery_status = 'PENDING'"),
    )


def downgrade() -> None:
    op.drop_index("ix_notifications_pending", table_name="notifications")
    op.create_index(
        "ix_notifications_pending",
        "notifications",
        ["created_at"],
        postgresql_where=sa.text("sent_at IS NULL"),
    )
    op.drop_constraint(
        "ck_notifications_delivery_attempts", "notifications", type_="check"
    )
    op.drop_constraint(
        "ck_notifications_delivery_status", "notifications", type_="check"
    )
    op.drop_column("notifications", "next_attempt_at")
    op.drop_column("notifications", "delivery_attempts")
    op.drop_column("notifications", "delivery_status")
