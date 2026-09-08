"""여행 진행 시작·조회 흐름의 PostgreSQL 통합 테스트."""

import os
import logging
import asyncio
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta, timezone

import pytest
from geoalchemy2 import WKTElement
from sqlalchemy import delete, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.db import transaction_session
from app.models.auth import User
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.progress import ProgressTransition
from app.models.trip import Trip
from app.schemas.progress import StartDayProgressRequest
from app.services.progress import ProgressService
from app.api.errors import AppError
from app.services.route import SingleSegmentResult
from app.clients.route_provider import Provider, RouteProviderError


class _Calculator:
    def __init__(self) -> None:
        self.calls = 0

    async def calculate_single_segment(self, **kwargs):
        self.calls += 1
        return SingleSegmentResult(duration_seconds=600, distance_meters=800, provider=Provider.TMAP)


class _FailingCalculator:
    async def calculate_single_segment(self, **kwargs):
        raise RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=True)


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    engine = create_async_engine(database_url)
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


async def _seed(factory: async_sessionmaker[AsyncSession]) -> tuple[uuid.UUID, uuid.UUID]:
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"progress-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        trip = Trip(user_id=user.user_id, name="진행 테스트", start_date=today, end_date=today)
        place = Place(tour_content_id=f"progress-{uuid.uuid4()}", name="경복궁", category="HISTORY_CULTURE", location=WKTElement("POINT(126.977 37.5796)", srid=4326))
        session.add_all([trip, place])
        await session.flush()
        day = TripDay(trip_id=trip.trip_id, visit_date=today, day_number=1, schedule_version=1)
        session.add(day)
        await session.flush()
        item = ItineraryItem(trip_day_id=day.trip_day_id, place_id=place.place_id, sequence=1, planned_stay_minutes=60, stay_source="RECOMMENDED")
        session.add(item)
        await session.flush()
        return trip.trip_id, day.trip_day_id


@pytest.mark.asyncio
async def test_start_commits_once_and_same_key_returns_stored_result(
    session_factory: async_sessionmaker[AsyncSession],
    caplog: pytest.LogCaptureFixture,
) -> None:
    trip_id, day_id = await _seed(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    key = uuid.uuid4()
    caplog.set_level(logging.INFO, logger="gilpick.progress")

    async with session_factory() as session:
        first = await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=key,
        )
    async with session_factory() as session:
        second = await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=key,
        )
        transition = await session.scalar(select(ProgressTransition).where(ProgressTransition.trip_day_id == day_id))

    assert first.actual_started_at == second.actual_started_at
    assert second.progress_version == 1
    assert transition is not None
    assert transition.source == "MANUAL"
    assert transition.confirmed_at == transition.detected_at
    assert transition.schedule_version_after == transition.schedule_version_before
    assert transition.affected_items[0]["beforeStatus"] == "PLANNED"
    assert transition.affected_items[0]["afterStatus"] == "EN_ROUTE"
    message = " ".join(record.getMessage() for record in caplog.records)
    assert "START_DAY_PROGRESS" in message
    assert str(trip_id) in message
    assert str(key) not in message


@pytest.mark.asyncio
async def test_valid_start_location_persists_computed_segment_and_eta(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _ = await _seed(session_factory)
    now = datetime.now(UTC)
    today = now.astimezone(timezone(timedelta(hours=9))).date()
    payload = StartDayProgressRequest.model_validate({
        "progressVersion": 0,
        "currentLocation": {"latitude": 37.57, "longitude": 126.98, "accuracyMeters": 20, "occurredAt": now.isoformat()},
    })
    key = uuid.uuid4()

    async with transaction_session(session_factory) as session:
        result = await ProgressService(
            session, calculator=_Calculator(), session_factory=session_factory
        ).start_day(
            trip_id=trip_id, visit_date=today, payload=payload, idempotency_key=key
        )
    async with session_factory() as session:
        retried = await ProgressService(session, calculator=_Calculator()).start_day(
            trip_id=trip_id, visit_date=today, payload=payload, idempotency_key=key
        )

    assert result.start_location is not None
    assert retried.start_location == result.start_location
    assert result.items[0].inbound_travel is not None
    assert result.items[0].inbound_travel.source == "COMPUTED"
    assert result.items[0].estimated_arrival_at == result.actual_started_at + timedelta(seconds=600)


@pytest.mark.asyncio
async def test_provider_failure_keeps_start_success_and_unknown_eta(
    session_factory: async_sessionmaker[AsyncSession],
    caplog: pytest.LogCaptureFixture,
) -> None:
    trip_id, _ = await _seed(session_factory)
    now = datetime.now(UTC)
    today = now.astimezone(timezone(timedelta(hours=9))).date()
    payload = StartDayProgressRequest.model_validate({
        "progressVersion": 0,
        "currentLocation": {"latitude": 37.57, "longitude": 126.98, "accuracyMeters": 20, "occurredAt": now.isoformat()},
    })
    caplog.set_level(logging.INFO, logger="gilpick.progress")
    async with session_factory() as session:
        result = await ProgressService(session, calculator=_FailingCalculator()).start_day(
            trip_id=trip_id, visit_date=today, payload=payload, idempotency_key=uuid.uuid4()
        )

    assert result.day_status == "IN_PROGRESS"
    assert result.items[0].estimated_arrival_at is None
    assert result.items[0].inbound_travel is None
    message = " ".join(record.getMessage() for record in caplog.records)
    assert "ROUTE_PROVIDER_UNAVAILABLE" in message
    assert "37.57" not in message


@pytest.mark.asyncio
async def test_concurrent_start_requests_apply_transition_once(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id = await _seed(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    payload = StartDayProgressRequest.model_validate({"progressVersion": 0})

    async def start(key: uuid.UUID):
        async with session_factory() as session:
            return await ProgressService(session).start_day(
                trip_id=trip_id, visit_date=today, payload=payload, idempotency_key=key
            )

    first, second = await asyncio.gather(start(uuid.uuid4()), start(uuid.uuid4()))
    async with session_factory() as session:
        count = await session.scalar(select(func.count()).select_from(ProgressTransition).where(ProgressTransition.trip_day_id == day_id))

    assert first.actual_started_at == second.actual_started_at
    assert first.progress_version == second.progress_version == 1
    assert count == 1


@pytest.mark.asyncio
async def test_start_rejects_non_today_and_empty_day(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id = await _seed(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    yesterday = today - timedelta(days=1)
    async with transaction_session(session_factory) as session:
        await session.execute(update(TripDay).where(TripDay.trip_day_id == day_id).values(visit_date=yesterday))
        await session.execute(update(Trip).where(Trip.trip_id == trip_id).values(start_date=yesterday, end_date=yesterday))
    async with session_factory() as session:
        with pytest.raises(AppError) as not_today:
            await ProgressService(session).start_day(
                trip_id=trip_id, visit_date=yesterday,
                payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
                idempotency_key=uuid.uuid4(),
            )
    assert not_today.value.code == "DAY_NOT_TODAY"

    trip_id, day_id = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        await session.execute(delete(ItineraryItem).where(ItineraryItem.trip_day_id == day_id))
    async with session_factory() as session:
        with pytest.raises(AppError) as empty:
            await ProgressService(session).start_day(
                trip_id=trip_id, visit_date=today,
                payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
                idempotency_key=uuid.uuid4(),
            )
    assert empty.value.code == "DAY_EMPTY"


@pytest.mark.asyncio
@pytest.mark.parametrize("accuracy,age_seconds", [(101, 0), (20, 121)])
async def test_invalid_location_is_ignored_without_provider_call(
    session_factory: async_sessionmaker[AsyncSession], accuracy: float, age_seconds: int,
) -> None:
    trip_id, _ = await _seed(session_factory)
    now = datetime.now(UTC)
    today = now.astimezone(timezone(timedelta(hours=9))).date()
    calculator = _Calculator()
    payload = StartDayProgressRequest.model_validate({
        "progressVersion": 0,
        "currentLocation": {"latitude": 37.57, "longitude": 126.98, "accuracyMeters": accuracy, "occurredAt": (now - timedelta(seconds=age_seconds)).isoformat()},
    })
    async with session_factory() as session:
        result = await ProgressService(session, calculator=calculator).start_day(
            trip_id=trip_id, visit_date=today, payload=payload, idempotency_key=uuid.uuid4()
        )

    assert calculator.calls == 0
    assert result.start_location is None
    assert result.items[0].estimated_arrival_at == result.actual_started_at
