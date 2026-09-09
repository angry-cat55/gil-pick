"""알림 보존 정책 cleanup."""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, or_, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.models.notification import Notification
from app.models.trip import Trip

logger = logging.getLogger("gilpick.notification.cleanup")


async def cleanup_notifications(session: AsyncSession, *, retention_days: int = 90, now: datetime | None = None) -> int:
    """보존 기한 초과 또는 논리 삭제 여행의 알림을 제거한다."""
    deleted_trip_ids = select(Trip.trip_id).where(Trip.deleted_at.is_not(None))
    result = await session.execute(delete(Notification).where(or_(Notification.created_at < (now or datetime.now(UTC)) - timedelta(days=retention_days), Notification.trip_id.in_(deleted_trip_ids))))
    return result.rowcount or 0


async def run_notification_cleanup(session_factory: async_sessionmaker[AsyncSession], *, retention_days: int = 90, interval_seconds: int = 3600) -> None:
    """cleanup 실패를 다음 실행과 격리해 반복한다."""
    while True:
        try:
            async with session_factory() as session:
                async with session.begin():
                    await cleanup_notifications(session, retention_days=retention_days)
        except Exception:
            logger.exception("알림 cleanup 실행 실패")
        await asyncio.sleep(interval_seconds)
