"""경로 계산 결과 엔티티와 데이터베이스 불변 조건."""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, CheckConstraint, DateTime, ForeignKey, Index, Integer, JSON, String, UniqueConstraint, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base
from app.models.auth import TimestampMixin
from app.models.itinerary import TripDay


class Route(TimestampMixin, Base):
    """일정 version별 정규화 경로 또는 실패 결과."""

    __tablename__ = "routes"
    __table_args__ = (
        UniqueConstraint("trip_day_id", "schedule_version", name="uq_routes_day_schedule_version"),
        CheckConstraint("schedule_version >= 1", name="ck_routes_schedule_version"),
        CheckConstraint("status IN ('READY', 'FAILED', 'HISTORICAL')", name="ck_routes_status"),
        CheckConstraint("provider IS NULL OR provider IN ('TMAP', 'ODSAY', 'MIXED')", name="ck_routes_provider"),
        CheckConstraint(
            "(status = 'READY' AND total_duration_seconds IS NOT NULL AND total_duration_seconds >= 0 AND total_distance_meters IS NOT NULL AND total_distance_meters >= 0 AND route_payload IS NOT NULL AND failure_code IS NULL) OR "
            "(status = 'FAILED' AND total_duration_seconds IS NULL AND total_distance_meters IS NULL AND route_payload IS NULL AND failure_code IS NOT NULL) OR "
            "(status = 'HISTORICAL' AND is_active = false)", name="ck_routes_state_fields"),
        Index("uq_routes_active_day", "trip_day_id", unique=True, postgresql_where=text("is_active = true")),
    )

    route_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    trip_day_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("trip_days.trip_day_id", ondelete="CASCADE"), nullable=False)
    schedule_version: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, server_default=text("true"), nullable=False)
    provider: Mapped[str | None] = mapped_column(String(20))
    total_duration_seconds: Mapped[int | None] = mapped_column(Integer)
    total_distance_meters: Mapped[int | None] = mapped_column(Integer)
    route_payload: Mapped[dict[str, object] | None] = mapped_column(JSON().with_variant(JSONB, "postgresql"))
    failure_code: Mapped[str | None] = mapped_column(String(50))
    calculated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    trip_day: Mapped[TripDay] = relationship(back_populates="routes")
