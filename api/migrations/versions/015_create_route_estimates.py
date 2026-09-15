"""이동 수단별 구간 추정 캐시를 생성한다.

Revision ID: 015_create_route_estimates
Revises: 014_add_trip_image_url
Create Date: 2026-09-15
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "015_create_route_estimates"
down_revision: str | None = "014_add_trip_image_url"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "route_estimates",
        sa.Column("estimate_id", sa.Uuid(), nullable=False),
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("schedule_version", sa.Integer(), nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("transport_mode", sa.String(20), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("estimate_payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("calculated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("schedule_version >= 1", name="ck_route_estimates_schedule_version"),
        sa.CheckConstraint("sequence BETWEEN 1 AND 9", name="ck_route_estimates_sequence"),
        sa.CheckConstraint("transport_mode IN ('WALK', 'TRANSIT', 'CAR')", name="ck_route_estimates_transport_mode"),
        sa.CheckConstraint("status IN ('READY', 'FAILED')", name="ck_route_estimates_status"),
        sa.ForeignKeyConstraint(["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("estimate_id"),
        sa.UniqueConstraint("trip_day_id", "schedule_version", "sequence", "transport_mode", name="uq_route_estimates_input"),
    )


def downgrade() -> None:
    op.drop_table("route_estimates")
