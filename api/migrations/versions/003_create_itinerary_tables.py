"""일정 구성 테이블을 생성한다.

Revision ID: 003_create_itinerary_tables
Revises: 002_create_trip_table
Create Date: 2026-09-06
"""

from collections.abc import Sequence

from alembic import op
from geoalchemy2 import Geography
import sqlalchemy as sa

revision: str = "003_create_itinerary_tables"
down_revision: str | None = "002_create_trip_table"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _timestamps() -> tuple[sa.Column, sa.Column]:
    return (
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def upgrade() -> None:
    """날짜, 장소 스냅샷, 일정 항목과 제약을 생성한다."""
    op.create_table(
        "trip_days",
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("trip_id", sa.Uuid(), nullable=False),
        sa.Column("visit_date", sa.Date(), nullable=False),
        sa.Column("day_number", sa.SmallInteger(), nullable=False),
        sa.Column("status", sa.String(20), server_default="NOT_STARTED", nullable=False),
        sa.Column("schedule_version", sa.Integer(), server_default="1", nullable=False),
        sa.Column("actual_started_at", sa.DateTime(timezone=True)),
        sa.Column("start_location", Geography("POINT", srid=4326, spatial_index=False)),
        sa.Column("start_accuracy_meters", sa.Numeric(6, 2)),
        sa.Column("start_captured_at", sa.DateTime(timezone=True)),
        sa.Column("detection_active", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True)),
        *_timestamps(),
        sa.CheckConstraint("day_number BETWEEN 1 AND 7", name="ck_trip_days_day_number"),
        sa.CheckConstraint("status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED')", name="ck_trip_days_status"),
        sa.CheckConstraint("schedule_version >= 1", name="ck_trip_days_schedule_version"),
        sa.ForeignKeyConstraint(["trip_id"], ["trips.trip_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("trip_day_id"),
        sa.UniqueConstraint("trip_id", "visit_date", name="uq_trip_days_trip_visit_date"),
        sa.UniqueConstraint("trip_id", "day_number", name="uq_trip_days_trip_day_number"),
    )
    op.create_table(
        "places",
        sa.Column("place_id", sa.Uuid(), nullable=False),
        sa.Column("tour_content_id", sa.String(255)),
        sa.Column("google_place_id", sa.String(255)),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("category", sa.String(30), nullable=False),
        sa.Column("tour_category_1", sa.String(120)),
        sa.Column("tour_category_2", sa.String(120)),
        sa.Column("tour_category_3", sa.String(120)),
        sa.Column("address", sa.Text()),
        sa.Column("location", Geography("POINT", srid=4326, spatial_index=False), nullable=False),
        sa.Column("image_url", sa.Text()),
        *_timestamps(),
        sa.CheckConstraint("tour_content_id IS NOT NULL OR google_place_id IS NOT NULL", name="ck_places_provider_id"),
        sa.CheckConstraint("category IN ('NATURE', 'HISTORY_CULTURE', 'FOOD', 'CAFE', 'SHOPPING', 'OTHER')", name="ck_places_category"),
        sa.PrimaryKeyConstraint("place_id"),
    )
    op.create_index("uq_places_tour_content_id", "places", ["tour_content_id"], unique=True, postgresql_where=sa.text("tour_content_id IS NOT NULL"))
    op.create_index("uq_places_google_place_id", "places", ["google_place_id"], unique=True, postgresql_where=sa.text("google_place_id IS NOT NULL"))
    op.create_index("ix_places_location", "places", ["location"], postgresql_using="gist")
    op.create_table(
        "itinerary_items",
        sa.Column("item_id", sa.Uuid(), nullable=False),
        sa.Column("trip_day_id", sa.Uuid(), nullable=False),
        sa.Column("place_id", sa.Uuid(), nullable=False),
        sa.Column("sequence", sa.SmallInteger(), nullable=False),
        sa.Column("status", sa.String(20), server_default="PLANNED", nullable=False),
        sa.Column("planned_stay_minutes", sa.SmallInteger(), nullable=False),
        sa.Column("stay_source", sa.String(20), nullable=False),
        sa.Column("transport_mode_to_next", sa.String(20)),
        sa.Column("estimated_arrival_at", sa.DateTime(timezone=True)),
        sa.Column("estimated_departure_at", sa.DateTime(timezone=True)),
        sa.Column("actual_arrived_at", sa.DateTime(timezone=True)),
        sa.Column("actual_departed_at", sa.DateTime(timezone=True)),
        sa.Column("completed_at", sa.DateTime(timezone=True)),
        sa.Column("auto_departure_suppressed", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        *_timestamps(),
        sa.CheckConstraint("sequence BETWEEN 1 AND 10", name="ck_itinerary_items_sequence"),
        sa.CheckConstraint("status IN ('PLANNED', 'EN_ROUTE', 'ARRIVED', 'COMPLETED', 'SKIPPED')", name="ck_itinerary_items_status"),
        sa.CheckConstraint("planned_stay_minutes BETWEEN 30 AND 360 AND planned_stay_minutes % 30 = 0", name="ck_itinerary_items_stay_minutes"),
        sa.CheckConstraint("stay_source IN ('RECOMMENDED', 'USER_ADJUSTED')", name="ck_itinerary_items_stay_source"),
        sa.CheckConstraint("transport_mode_to_next IS NULL OR transport_mode_to_next IN ('WALK', 'TRANSIT', 'CAR')", name="ck_itinerary_items_transport_mode"),
        sa.ForeignKeyConstraint(["trip_day_id"], ["trip_days.trip_day_id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["place_id"], ["places.place_id"]),
        sa.PrimaryKeyConstraint("item_id"),
        sa.UniqueConstraint("trip_day_id", "sequence", name="uq_itinerary_items_day_sequence"),
    )


def downgrade() -> None:
    """일정 구성 테이블을 의존 관계의 역순으로 제거한다."""
    op.drop_table("itinerary_items")
    op.drop_table("places")
    op.drop_table("trip_days")
