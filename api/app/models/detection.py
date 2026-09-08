"""여행 변수 감지 결과 엔티티."""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Index, JSON, Numeric, String, Text, desc, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


class Detection(Base):
    """한 일정 항목의 마지막 변수 평가와 사용자 처리 상태."""

    __tablename__ = "detections"
    __table_args__ = (
        CheckConstraint("score IS NULL OR (score >= 0 AND score < 10)", name="ck_detections_score"),
        CheckConstraint("primary_type IN ('CONGESTION','WEATHER','OPERATING_HOURS')", name="ck_detections_primary_type"),
        CheckConstraint("status IN ('ACTIVE','RESOLVED','DISMISSED','INVALIDATED')", name="ck_detections_status"),
        Index("uq_detections_active_fingerprint", "fingerprint", unique=True, postgresql_where=text("status = 'ACTIVE'")),
        Index("ix_detections_day_status_detected", "trip_day_id", "status", desc("detected_at")),
        Index("ix_detections_item", "item_id"),
    )

    detection_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    trip_day_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("trip_days.trip_day_id", ondelete="CASCADE"), nullable=False)
    item_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("itinerary_items.item_id", ondelete="CASCADE"), nullable=False)
    primary_type: Mapped[str] = mapped_column(String(30), nullable=False)
    status: Mapped[str] = mapped_column(String(20), server_default="ACTIVE", nullable=False)
    eta: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    score: Mapped[Decimal | None] = mapped_column(Numeric(7, 6))
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    evaluation_snapshot: Mapped[dict[str, object]] = mapped_column(JSON().with_variant(JSONB, "postgresql"), nullable=False)
    fingerprint: Mapped[str] = mapped_column(String(255), nullable=False)
    detected_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"), nullable=False)
    last_evaluated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"), nullable=False)
    read_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    trip_day: Mapped[object] = relationship("TripDay")
    item: Mapped[object] = relationship("ItineraryItem")

    @staticmethod
    def make_fingerprint(trip_day_id: uuid.UUID, item_id: uuid.UUID) -> str:
        """날짜와 일정 항목으로 ACTIVE 중복 억제 키를 만든다."""
        return f"{trip_day_id}:{item_id}"
