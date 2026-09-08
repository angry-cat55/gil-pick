"""여행 진행 전환과 미계획 이동 구간 엔티티."""

from __future__ import annotations

import uuid
from datetime import datetime

from geoalchemy2 import Geography
from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    JSON,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    desc,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


class ProgressEvent(Base):
    """클라이언트가 전송한 위치 감지 이벤트와 수락 결과."""

    __tablename__ = "progress_events"
    __table_args__ = (
        UniqueConstraint(
            "client_event_id", name="uq_progress_events_client_event_id"
        ),
        CheckConstraint(
            "event_type IN ('DWELL', 'EXIT', 'REENTER')",
            name="ck_progress_events_event_type",
        ),
        Index(
            "ix_progress_events_trip_day_occurred_at",
            "trip_day_id",
            desc("occurred_at"),
        ),
    )

    progress_event_id: Mapped[uuid.UUID] = mapped_column(
        primary_key=True, default=uuid.uuid4
    )
    client_event_id: Mapped[uuid.UUID] = mapped_column(nullable=False)
    trip_day_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("trip_days.trip_day_id", ondelete="CASCADE"), nullable=False
    )
    item_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("itinerary_items.item_id", ondelete="CASCADE"), nullable=False
    )
    event_type: Mapped[str] = mapped_column(String(20), nullable=False)
    geofence_id: Mapped[str] = mapped_column(String(255), nullable=False)
    location: Mapped[object] = mapped_column(
        Geography(geometry_type="POINT", srid=4326, spatial_index=False),
        nullable=False,
    )
    accuracy_meters: Mapped[float] = mapped_column(Numeric(6, 2), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    accepted: Mapped[bool] = mapped_column(Boolean, nullable=False)
    rejection_reason: Mapped[str | None] = mapped_column(String(80))

    trip_day: Mapped[object] = relationship("TripDay")
    item: Mapped[object] = relationship("ItineraryItem")


class ProgressTransition(Base):
    """한 진행 요청에서 발생한 모든 상태 변경의 감사 기록."""

    __tablename__ = "progress_transitions"
    __table_args__ = (
        UniqueConstraint(
            "trip_day_id", "idempotency_key", name="uq_progress_transitions_day_idempotency"
        ),
        CheckConstraint(
            "schedule_version_before >= 1 AND "
            "(schedule_version_after IS NULL OR schedule_version_after >= 1)",
            name="ck_progress_transitions_schedule_versions",
        ),
        CheckConstraint(
            "progress_version_after >= 1",
            name="ck_progress_transitions_progress_version",
        ),
    )

    transition_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    trip_day_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("trip_days.trip_day_id", ondelete="CASCADE"), nullable=False
    )
    primary_item_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("itinerary_items.item_id")
    )
    trigger_event_id: Mapped[uuid.UUID | None] = mapped_column()
    transition_type: Mapped[str] = mapped_column(String(30), nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    source: Mapped[str] = mapped_column(String(30), nullable=False)
    decision: Mapped[str | None] = mapped_column(String(30))
    affected_items: Mapped[list[dict[str, object]]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), nullable=False
    )
    request_target_status: Mapped[str | None] = mapped_column(String(20))
    response_snapshot: Mapped[dict[str, object] | None] = mapped_column(
        JSON().with_variant(JSONB, "postgresql")
    )
    detected_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    auto_finalize_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    undo_deadline: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    cancelled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    undone_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    schedule_version_before: Mapped[int] = mapped_column(Integer, nullable=False)
    schedule_version_after: Mapped[int | None] = mapped_column(Integer)
    progress_version_after: Mapped[int] = mapped_column(Integer, nullable=False)
    idempotency_key: Mapped[uuid.UUID] = mapped_column(nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=text("now()"), nullable=False
    )

    trip_day: Mapped[object] = relationship(
        "TripDay", back_populates="progress_transitions"
    )
    primary_item: Mapped[object | None] = relationship(
        "ItineraryItem", foreign_keys=[primary_item_id]
    )


class ProgressSegment(Base):
    """계획 경로에 없어서 별도로 계산한 단일 이동 구간."""

    __tablename__ = "progress_segments"
    __table_args__ = (
        CheckConstraint(
            "transport_mode IN ('WALK', 'TRANSIT', 'CAR')",
            name="ck_progress_segments_transport_mode",
        ),
        CheckConstraint(
            "provider IN ('TMAP', 'ODSAY')", name="ck_progress_segments_provider"
        ),
        CheckConstraint("duration_seconds >= 0", name="ck_progress_segments_duration"),
        CheckConstraint("distance_meters >= 0", name="ck_progress_segments_distance"),
        Index(
            "uq_progress_segments_items",
            "trip_day_id", "from_item_id", "to_item_id",
            unique=True,
            postgresql_where=text("from_item_id IS NOT NULL"),
        ),
        Index(
            "uq_progress_segments_start",
            "trip_day_id", "to_item_id",
            unique=True,
            postgresql_where=text("from_item_id IS NULL"),
        ),
    )

    progress_segment_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    trip_day_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("trip_days.trip_day_id", ondelete="CASCADE"), nullable=False
    )
    from_item_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("itinerary_items.item_id", ondelete="CASCADE")
    )
    to_item_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("itinerary_items.item_id", ondelete="CASCADE"), nullable=False
    )
    transport_mode: Mapped[str] = mapped_column(String(20), nullable=False)
    provider: Mapped[str] = mapped_column(String(20), nullable=False)
    duration_seconds: Mapped[int] = mapped_column(Integer, nullable=False)
    distance_meters: Mapped[int] = mapped_column(Integer, nullable=False)
    computed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    trip_day: Mapped[object] = relationship(
        "TripDay", back_populates="progress_segments"
    )
    from_item: Mapped[object | None] = relationship(
        "ItineraryItem", foreign_keys=[from_item_id]
    )
    to_item: Mapped[object] = relationship(
        "ItineraryItem", foreign_keys=[to_item_id]
    )
