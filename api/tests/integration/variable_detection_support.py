"""F008 PostgreSQL 통합 테스트용 최소 일정 seed."""

import os
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from geoalchemy2 import WKTElement
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db import transaction_session
from app.models.auth import User
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.trip import Trip

KST = timezone(timedelta(hours=9))


async def factory():
    url = os.getenv("TEST_DATABASE_URL")
    if not url:
        pytest.skip("격리된 TEST_DATABASE_URL이 필요합니다.")
    engine = create_async_engine(url)
    return engine, async_sessionmaker(engine, expire_on_commit=False)


async def seed(
    session_factory,
    *,
    category: str = "OTHER",
    day_status: str = "IN_PROGRESS",
    visit_offset_days: int = 0,
    item_status: str = "PLANNED",
    with_eta: bool = True,
):
    now = datetime.now(KST)
    async with transaction_session(session_factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"variable-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        trip = Trip(user_id=user.user_id, name="변수 감지", start_date=now.date(), end_date=now.date())
        place = Place(
            google_place_id=f"places/{uuid.uuid4()}",
            name="감지 대상",
            category=category,
            location=WKTElement("POINT(126.9768 37.5752)", srid=4326),
        )
        session.add_all([trip, place])
        await session.flush()
        day = TripDay(
            trip_id=trip.trip_id,
            visit_date=(now + timedelta(days=visit_offset_days)).date(),
            day_number=1,
            status=day_status,
            detection_active=day_status == "IN_PROGRESS",
        )
        session.add(day)
        await session.flush()
        item = ItineraryItem(
            trip_day_id=day.trip_day_id,
            place_id=place.place_id,
            sequence=1,
            status=item_status,
            planned_stay_minutes=60,
            stay_source="RECOMMENDED",
            estimated_arrival_at=now + timedelta(hours=1) if with_eta else None,
        )
        session.add(item)
        await session.flush()
        return trip.trip_id, item.item_id, user.user_id


class Provider:
    async def aclose(self): pass


def providers(monkeypatch, evaluator):
    monkeypatch.setattr(evaluator, "KmaClient", lambda settings: Provider())
    monkeypatch.setattr(evaluator, "SeoulCityDataClient", lambda settings: Provider())
    monkeypatch.setattr(evaluator, "OperatingHoursSource", lambda settings: Provider())
    monkeypatch.setattr(evaluator, "get_settings", lambda: object())
