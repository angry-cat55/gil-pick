"""사용자 알림 엔티티와 중복·보존 불변 조건."""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Index, String, Text, desc, text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class Notification(Base):
    """도메인 처리와 분리해 저장·전달하는 사용자 알림."""

    __tablename__ = "notifications"
    __table_args__ = (
        CheckConstraint("type IN ('PLACE_CHANGE_SUGGESTION','ARRIVAL_CHECK','DEPARTURE_CHECK','ARRIVAL_AUTO_CONFIRMED','DEPARTURE_AUTO_CONFIRMED')", name="ck_notifications_type"),
        Index("ix_notifications_user_unread", "user_id", "read_at", desc("created_at")),
        Index("ix_notifications_retention", "created_at"),
        Index("uq_notifications_dedup", "user_id", "dedup_key", unique=True, postgresql_where=text("dedup_key IS NOT NULL")),
        Index("ix_notifications_pending", "created_at", postgresql_where=text("sent_at IS NULL")),
    )

    notification_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False)
    trip_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("trips.trip_id", ondelete="SET NULL"))
    trip_day_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("trip_days.trip_day_id", ondelete="SET NULL"))
    item_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("itinerary_items.item_id", ondelete="SET NULL"))
    detection_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("detections.detection_id", ondelete="SET NULL"))
    transition_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("progress_transitions.transition_id", ondelete="SET NULL"))
    type: Mapped[str] = mapped_column(String(50), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    body: Mapped[str] = mapped_column(Text, nullable=False)
    dedup_key: Mapped[str | None] = mapped_column(String(255))
    sent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    read_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=text("now()"), nullable=False)
