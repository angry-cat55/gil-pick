"""F007 위치 이벤트 검증·저장·멱등 처리 통합 테스트."""

import os
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import pytest
from geoalchemy2 import WKTElement
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.errors import AppError
from app.db import transaction_session
from app.models.auth import User
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.progress import ProgressEvent
from app.models.trip import Trip
from app.schemas.progress import ProgressEventRequest
from app.services.detection import DetectionService


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    engine = create_async_engine(database_url)
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


async def _seed(
    factory: async_sessionmaker[AsyncSession],
    *,
    day_status: str = "IN_PROGRESS",
    item_status: str = "EN_ROUTE",
) -> tuple[uuid.UUID, uuid.UUID, uuid.UUID, datetime]:
    now = datetime.now(UTC)
    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"detection-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        trip = Trip(
            user_id=user.user_id,
            name="감지 테스트",
            start_date=now.date(),
            end_date=now.date(),
        )
        place = Place(
            tour_content_id=f"detection-{uuid.uuid4()}",
            name="테스트 장소",
            category="OTHER",
            location=WKTElement("POINT(127.0 37.5)", srid=4326),
        )
        session.add_all([trip, place])
        await session.flush()
        day = TripDay(
            trip_id=trip.trip_id,
            visit_date=now.date(),
            day_number=1,
            status=day_status,
            schedule_version=1,
            progress_version=1,
            detection_active=day_status == "IN_PROGRESS",
        )
        session.add(day)
        await session.flush()
        item = ItineraryItem(
            trip_day_id=day.trip_day_id,
            place_id=place.place_id,
            sequence=1,
            status=item_status,
            planned_stay_minutes=30,
            stay_source="USER_ADJUSTED",
        )
        session.add(item)
        await session.flush()
        return trip.trip_id, item.item_id, user.user_id, now


def _payload(
    item_id: uuid.UUID,
    now: datetime,
    *,
    event_id: uuid.UUID | None = None,
    accuracy: float = 20,
    occurred_at: datetime | None = None,
    geofence_id: str | None = None,
) -> ProgressEventRequest:
    return ProgressEventRequest.model_validate({
        "eventId": str(event_id or uuid.uuid4()),
        "eventType": "DWELL",
        "itemId": str(item_id),
        "geofenceId": geofence_id or f"{item_id}:ARRIVAL",
        "occurredAt": (occurred_at or now).isoformat(),
        "location": {
            "latitude": 37.5,
            "longitude": 127.0,
            "accuracyMeters": accuracy,
        },
    })


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("day_status", "accuracy", "age_seconds", "wrong_geofence", "reason"),
    [
        ("IN_PROGRESS", 101, 0, False, "LOW_ACCURACY"),
        ("IN_PROGRESS", 20, 121, False, "STALE"),
        ("COMPLETED", 20, 0, False, "DAY_NOT_IN_PROGRESS"),
        ("IN_PROGRESS", 20, 0, True, "ITEM_NOT_ELIGIBLE"),
    ],
)
async def test_rejected_event_is_still_saved(
    session_factory: async_sessionmaker[AsyncSession],
    day_status: str,
    accuracy: float,
    age_seconds: int,
    wrong_geofence: bool,
    reason: str,
) -> None:
    trip_id, item_id, user_id, now = await _seed(
        session_factory, day_status=day_status
    )
    payload = _payload(
        item_id,
        now,
        accuracy=accuracy,
        occurred_at=now - timedelta(seconds=age_seconds),
        geofence_id="wrong" if wrong_geofence else None,
    )

    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).register_event(
            user_id=user_id,
            trip_id=trip_id,
            visit_date=now.date(),
            payload=payload,
            received_at=now,
        )
    async with session_factory() as session:
        saved = await session.scalar(
            select(ProgressEvent).where(
                ProgressEvent.client_event_id == payload.event_id
            )
        )

    assert result.accepted is False
    assert result.rejection_reason == reason
    assert saved is not None and saved.rejection_reason == reason


@pytest.mark.asyncio
async def test_duplicate_event_returns_first_result_without_second_row(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    event_id = uuid.uuid4()
    first_payload = _payload(item_id, now, event_id=event_id)
    retry_payload = _payload(item_id, now, event_id=event_id, accuracy=999)

    async with transaction_session(session_factory) as session:
        first = await DetectionService(session).register_event(
            user_id=user_id, trip_id=trip_id, visit_date=now.date(),
            payload=first_payload, received_at=now,
        )
    async with transaction_session(session_factory) as session:
        retried = await DetectionService(session).register_event(
            user_id=user_id, trip_id=trip_id, visit_date=now.date(),
            payload=retry_payload, received_at=now,
        )
    async with session_factory() as session:
        count = await session.scalar(
            select(func.count()).select_from(ProgressEvent).where(
                ProgressEvent.client_event_id == event_id
            )
        )

    assert first == retried
    assert first.accepted is True
    assert count == 1


@pytest.mark.asyncio
async def test_event_rejects_other_trip_owner(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, _, now = await _seed(session_factory)

    async with transaction_session(session_factory) as session:
        with pytest.raises(AppError) as forbidden:
            await DetectionService(session).register_event(
                user_id=uuid.uuid4(),
                trip_id=trip_id,
                visit_date=now.date(),
                payload=_payload(item_id, now),
                received_at=now,
            )

    assert forbidden.value.status_code == 403
    assert forbidden.value.code == "TRIP_FORBIDDEN"
