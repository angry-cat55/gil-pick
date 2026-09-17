"""사용자 위치기반서비스 약관 동의 기록을 추가한다.

Revision ID: 018_add_lbs_consent
Revises: 017_notification_delivery_state
Create Date: 2026-09-17
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "018_add_lbs_consent"
down_revision: str | None = "017_notification_delivery_state"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """기존 사용자는 미동의 상태로 두고 동의 시각과 버전은 비워 둔다."""
    op.add_column(
        "users",
        sa.Column("lbs_agreed", sa.Boolean(), server_default=sa.text("false"), nullable=False),
    )
    op.add_column("users", sa.Column("lbs_agreed_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("users", sa.Column("lbs_version", sa.String(length=20), nullable=True))
    op.create_check_constraint(
        "ck_users_lbs_consent_complete",
        "users",
        "(lbs_agreed = false AND lbs_agreed_at IS NULL AND lbs_version IS NULL) OR "
        "(lbs_agreed = true AND lbs_agreed_at IS NOT NULL AND lbs_version IS NOT NULL)",
    )


def downgrade() -> None:
    """위치기반서비스 약관 동의 기록을 제거한다."""
    op.drop_constraint("ck_users_lbs_consent_complete", "users", type_="check")
    op.drop_column("users", "lbs_version")
    op.drop_column("users", "lbs_agreed_at")
    op.drop_column("users", "lbs_agreed")
