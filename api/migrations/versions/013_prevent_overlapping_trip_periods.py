"""사용자별 활성 여행 기간 중복을 금지한다.

Revision ID: 013_prevent_trip_overlap
Revises: 012_add_kakao_route_provider
Create Date: 2026-09-15
"""

from collections.abc import Sequence

from alembic import op

revision: str = "013_prevent_trip_overlap"
down_revision: str | None = "012_add_kakao_route_provider"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """기존 중복이 없을 때만 GiST exclusion constraint를 추가한다."""
    op.execute("CREATE EXTENSION IF NOT EXISTS btree_gist")
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM trips left_trip
                JOIN trips right_trip
                  ON left_trip.user_id = right_trip.user_id
                 AND left_trip.trip_id < right_trip.trip_id
                 AND daterange(left_trip.start_date, left_trip.end_date, '[]')
                     && daterange(right_trip.start_date, right_trip.end_date, '[]')
                WHERE left_trip.deleted_at IS NULL
                  AND right_trip.deleted_at IS NULL
            ) THEN
                RAISE EXCEPTION 'active trip periods overlap; resolve existing rows before migration';
            END IF;
        END $$
        """
    )
    op.execute(
        """
        ALTER TABLE trips
        ADD CONSTRAINT ex_trips_user_active_period
        EXCLUDE USING gist (
            user_id WITH =,
            daterange(start_date, end_date, '[]') WITH &&
        )
        WHERE (deleted_at IS NULL)
        """
    )


def downgrade() -> None:
    """사용자별 활성 여행 기간 중복 제약을 제거한다."""
    op.execute("ALTER TABLE trips DROP CONSTRAINT ex_trips_user_active_period")
