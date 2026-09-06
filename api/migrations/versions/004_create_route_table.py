"""경로 계산 결과 테이블을 생성한다.

Revision ID: 004_create_route_table
Revises: 003_create_itinerary_tables
Create Date: 2026-09-06
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "004_create_route_table"
down_revision: str | None = "003_create_itinerary_tables"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "routes",
        sa.Column("route_id", sa.Uuid(), nullable=False), sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("schedule_version", sa.Integer(), nullable=False), sa.Column("status", sa.String(20), nullable=False),
        sa.Column("is_active", sa.Boolean(), server_default=sa.text("true"), nullable=False), sa.Column("provider", sa.String(20)),
        sa.Column("total_duration_seconds", sa.Integer()), sa.Column("total_distance_meters", sa.Integer()),
        sa.Column("route_payload", postgresql.JSONB(astext_type=sa.Text())), sa.Column("failure_code", sa.String(50)),
        sa.Column("calculated_at", sa.DateTime(timezone=True)),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("schedule_version >= 1", name="ck_routes_schedule_version"),
        sa.CheckConstraint("status IN ('READY', 'FAILED', 'HISTORICAL')", name="ck_routes_status"),
        sa.CheckConstraint("provider IS NULL OR provider IN ('TMAP', 'ODSAY', 'MIXED')", name="ck_routes_provider"),
        sa.CheckConstraint(
            "(status = 'READY' AND total_duration_seconds IS NOT NULL AND total_duration_seconds >= 0 AND total_distance_meters IS NOT NULL AND total_distance_meters >= 0 AND route_payload IS NOT NULL AND failure_code IS NULL) OR "
            "(status = 'FAILED' AND total_duration_seconds IS NULL AND total_distance_meters IS NULL AND route_payload IS NULL AND failure_code IS NOT NULL) OR "
            "(status = 'HISTORICAL' AND is_active = false)", name="ck_routes_state_fields"),
        sa.ForeignKeyConstraint(["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("route_id"), sa.UniqueConstraint("trip_day_id", "schedule_version", name="uq_routes_day_schedule_version"),
    )
    op.create_index("uq_routes_active_day", "routes", ["trip_day_id"], unique=True, postgresql_where=sa.text("is_active = true"))


def downgrade() -> None:
    op.drop_index("uq_routes_active_day", table_name="routes")
    op.drop_table("routes")
