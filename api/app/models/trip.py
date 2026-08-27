"""여행 엔티티와 데이터베이스 불변 조건."""

from __future__ import annotations

import uuid
from datetime import date, datetime

from sqlalchemy import CheckConstraint, Date, DateTime, ForeignKey, Index, Integer, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base
from app.models.auth import TimestampMixin


class Trip(TimestampMixin, Base):
    """계산된 상태와 낙관적 버전을 사용하는 사용자 소유 여행."""

    __tablename__ = "trips"
    __table_args__ = (
        CheckConstraint("char_length(name) BETWEEN 2 AND 30", name="ck_trips_name_length"),
        CheckConstraint(
            "end_date - start_date BETWEEN 0 AND 6",
            name="ck_trips_date_range",
        ),
        Index("ix_trips_user_deleted_at", "user_id", "deleted_at"),
    )

    trip_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(30), nullable=False)
    start_date: Mapped[date] = mapped_column(Date, nullable=False)
    end_date: Mapped[date] = mapped_column(Date, nullable=False)
    timezone: Mapped[str] = mapped_column(
        String(40), server_default="Asia/Seoul", nullable=False
    )
    version: Mapped[int] = mapped_column(Integer, server_default="1", nullable=False)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


Index(
    "ix_trips_user_lower_name_active",
    Trip.user_id,
    func.lower(Trip.name),
    postgresql_where=Trip.deleted_at.is_(None),
)
