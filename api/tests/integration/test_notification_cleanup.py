"""알림 보존 cleanup 통합 테스트."""

import os
import uuid
from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db import transaction_session
from app.jobs.notification_cleanup import cleanup_notifications
from app.models.auth import User
from app.models.notification import Notification
from app.models.trip import Trip


@pytest.mark.asyncio
async def test_cleanup_removes_expired_and_deleted_trip_notifications() -> None:
    """90일 초과와 논리 삭제 여행 알림만 제거한다."""
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    engine = create_async_engine(database_url)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    now = datetime.now(UTC)
    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"notification-cleanup-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        active_trip = Trip(user_id=user.user_id, name="활성 여행", start_date=date(2026, 9, 1), end_date=date(2026, 9, 1))
        deleted_trip = Trip(user_id=user.user_id, name="삭제 여행", start_date=date(2026, 9, 1), end_date=date(2026, 9, 1), deleted_at=now)
        session.add_all((active_trip, deleted_trip))
        await session.flush()
        expired = Notification(user_id=user.user_id, trip_id=active_trip.trip_id, type="ARRIVAL_CHECK", title="만료", body="만료", created_at=now - timedelta(days=91))
        recent = Notification(user_id=user.user_id, trip_id=active_trip.trip_id, type="ARRIVAL_CHECK", title="유지", body="유지", created_at=now - timedelta(days=89))
        deleted = Notification(user_id=user.user_id, trip_id=deleted_trip.trip_id, type="ARRIVAL_CHECK", title="삭제", body="삭제", created_at=now)
        session.add_all((expired, recent, deleted))
        await session.flush()
        expired_id, recent_id, deleted_id = expired.notification_id, recent.notification_id, deleted.notification_id

    async with transaction_session(factory) as session:
        assert await cleanup_notifications(session, now=now) == 2

    async with factory() as session:
        assert await session.get(Notification, expired_id) is None
        assert await session.get(Notification, deleted_id) is None
        assert await session.get(Notification, recent_id) is not None
    await engine.dispose()
