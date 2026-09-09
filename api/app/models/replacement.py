"""일정 변경 미리보기와 승인 이력 엔티티."""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Index, Integer, JSON, String, UniqueConstraint, desc, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class RoutePreview(Base):
    """승인 전 계산이 끝난 대체 경로 미리보기."""

    __tablename__ = "route_previews"
    __table_args__ = (
        CheckConstraint(
            "status IN ('PENDING','APPROVED','REJECTED','SUPERSEDED')",
            name="ck_route_previews_status",
        ),
        UniqueConstraint(
            "detection_id", "idempotency_key", name="uq_route_previews_detection_idempotency"
        ),
        Index(
            "uq_route_previews_pending_detection",
            "detection_id",
            unique=True,
            postgresql_where=text("status = 'PENDING'"),
        ),
        Index("ix_route_previews_detection", "detection_id", desc("created_at")),
    )

    preview_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    detection_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("detections.detection_id", ondelete="CASCADE"), nullable=False
    )
    trip_day_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("trip_days.trip_day_id", ondelete="CASCADE"), nullable=False
    )
    item_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("itinerary_items.item_id", ondelete="CASCADE"), nullable=False
    )
    original_place_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("places.place_id"), nullable=False)
    alternative_place_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("places.place_id"), nullable=False)
    schedule_version: Mapped[int] = mapped_column(Integer, nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(255), nullable=False)
    request_fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    route_payload: Mapped[dict[str, object]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), nullable=False
    )
    total_duration_seconds: Mapped[int] = mapped_column(Integer, nullable=False)
    total_distance_meters: Mapped[int] = mapped_column(Integer, nullable=False)
    provider: Mapped[str] = mapped_column(String(20), nullable=False)
    comparison: Mapped[dict[str, object]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), nullable=False
    )
    response_snapshot: Mapped[dict[str, object]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), nullable=False
    )
    status: Mapped[str] = mapped_column(String(20), server_default="PENDING", nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=text("now()"), nullable=False
    )


class PlaceReplacement(Base):
    """승인된 장소 변경과 되돌리기 판정 근거."""

    __tablename__ = "place_replacements"
    __table_args__ = (
        UniqueConstraint("preview_id", name="uq_place_replacements_preview"),
        Index("ix_place_replacements_day", "trip_day_id", desc("approved_at")),
    )

    replacement_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    preview_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("route_previews.preview_id"), nullable=False)
    detection_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("detections.detection_id"), nullable=False)
    trip_day_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("trip_days.trip_day_id", ondelete="CASCADE"), nullable=False
    )
    item_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("itinerary_items.item_id"), nullable=False)
    original_place_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("places.place_id"), nullable=False)
    new_place_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("places.place_id"), nullable=False)
    before_schedule_version: Mapped[int] = mapped_column(Integer, nullable=False)
    approved_schedule_version: Mapped[int] = mapped_column(Integer, nullable=False)
    approved_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    undo_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(255), nullable=False)
    response_snapshot: Mapped[dict[str, object]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), nullable=False
    )
    undone_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    undo_schedule_version: Mapped[int | None] = mapped_column(Integer)


__all__ = ["PlaceReplacement", "RoutePreview"]
