"""일정 구성 엔티티와 데이터베이스 불변 조건."""

from __future__ import annotations

import uuid
from datetime import date, datetime

from geoalchemy2 import Geography
from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base
from app.models.auth import TimestampMixin


class TripDay(TimestampMixin, Base):
    """한 여행의 날짜별 일정과 진행 상태."""

    __tablename__ = "trip_days"
    __table_args__ = (
        UniqueConstraint("trip_id", "visit_date", name="uq_trip_days_trip_visit_date"),
        UniqueConstraint("trip_id", "day_number", name="uq_trip_days_trip_day_number"),
        CheckConstraint("day_number BETWEEN 1 AND 7", name="ck_trip_days_day_number"),
        CheckConstraint(
            "status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED')",
            name="ck_trip_days_status",
        ),
        CheckConstraint("schedule_version >= 1", name="ck_trip_days_schedule_version"),
    )

    trip_day_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    trip_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("trips.trip_id", ondelete="CASCADE"), nullable=False
    )
    visit_date: Mapped[date] = mapped_column(Date, nullable=False)
    day_number: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    status: Mapped[str] = mapped_column(
        String(20), server_default="NOT_STARTED", nullable=False
    )
    schedule_version: Mapped[int] = mapped_column(
        Integer, server_default="1", nullable=False
    )
    actual_started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    start_location: Mapped[object | None] = mapped_column(
        Geography(geometry_type="POINT", srid=4326, spatial_index=False)
    )
    start_accuracy_meters: Mapped[float | None] = mapped_column(Numeric(6, 2))
    start_captured_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    detection_active: Mapped[bool] = mapped_column(
        Boolean, server_default=text("false"), nullable=False
    )
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    trip: Mapped[object] = relationship("Trip")
    items: Mapped[list[ItineraryItem]] = relationship(
        back_populates="trip_day", cascade="all, delete-orphan"
    )


class Place(TimestampMixin, Base):
    """일정에서 참조하는 외부 장소의 최소 스냅샷."""

    __tablename__ = "places"
    __table_args__ = (
        CheckConstraint(
            "tour_content_id IS NOT NULL OR google_place_id IS NOT NULL",
            name="ck_places_provider_id",
        ),
        CheckConstraint(
            "category IN ('NATURE', 'HISTORY_CULTURE', 'FOOD', 'CAFE', 'SHOPPING', 'OTHER')",
            name="ck_places_category",
        ),
        Index(
            "uq_places_tour_content_id",
            "tour_content_id",
            unique=True,
            postgresql_where=text("tour_content_id IS NOT NULL"),
        ),
        Index(
            "uq_places_google_place_id",
            "google_place_id",
            unique=True,
            postgresql_where=text("google_place_id IS NOT NULL"),
        ),
        Index("ix_places_location", "location", postgresql_using="gist"),
    )

    place_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    tour_content_id: Mapped[str | None] = mapped_column(String(255))
    google_place_id: Mapped[str | None] = mapped_column(String(255))
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    category: Mapped[str] = mapped_column(String(30), nullable=False)
    tour_category_1: Mapped[str | None] = mapped_column(String(120))
    tour_category_2: Mapped[str | None] = mapped_column(String(120))
    tour_category_3: Mapped[str | None] = mapped_column(String(120))
    address: Mapped[str | None] = mapped_column(Text)
    location: Mapped[object] = mapped_column(
        Geography(geometry_type="POINT", srid=4326, spatial_index=False), nullable=False
    )
    image_url: Mapped[str | None] = mapped_column(Text)


class ItineraryItem(TimestampMixin, Base):
    """날짜별 일정에 포함된 순서 있는 장소 항목."""

    __tablename__ = "itinerary_items"
    __table_args__ = (
        UniqueConstraint(
            "trip_day_id", "sequence", name="uq_itinerary_items_day_sequence"
        ),
        CheckConstraint("sequence BETWEEN 1 AND 10", name="ck_itinerary_items_sequence"),
        CheckConstraint(
            "status IN ('PLANNED', 'EN_ROUTE', 'ARRIVED', 'COMPLETED', 'SKIPPED')",
            name="ck_itinerary_items_status",
        ),
        CheckConstraint(
            "planned_stay_minutes BETWEEN 30 AND 360 "
            "AND planned_stay_minutes % 30 = 0",
            name="ck_itinerary_items_stay_minutes",
        ),
        CheckConstraint(
            "stay_source IN ('RECOMMENDED', 'USER_ADJUSTED')",
            name="ck_itinerary_items_stay_source",
        ),
        CheckConstraint(
            "transport_mode_to_next IS NULL OR "
            "transport_mode_to_next IN ('WALK', 'TRANSIT', 'CAR')",
            name="ck_itinerary_items_transport_mode",
        ),
    )

    item_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    trip_day_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("trip_days.trip_day_id", ondelete="CASCADE"), nullable=False
    )
    place_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("places.place_id"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    status: Mapped[str] = mapped_column(String(20), server_default="PLANNED", nullable=False)
    planned_stay_minutes: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    stay_source: Mapped[str] = mapped_column(String(20), nullable=False)
    transport_mode_to_next: Mapped[str | None] = mapped_column(String(20))
    estimated_arrival_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    estimated_departure_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    actual_arrived_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    actual_departed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    auto_departure_suppressed: Mapped[bool] = mapped_column(
        Boolean, server_default=text("false"), nullable=False
    )

    trip_day: Mapped[TripDay] = relationship(back_populates="items")
    place: Mapped[Place] = relationship()
