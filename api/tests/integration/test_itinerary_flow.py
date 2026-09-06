"""PostgreSQL에서 일정 저장의 version·멱등·장소 재사용을 검증한다."""

import os
import time
import uuid
from collections.abc import AsyncIterator
from datetime import date, timedelta

import pytest
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.errors import AppError
from app.db import transaction_session
from app.models.auth import User
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.trip import Trip
from app.schemas.itinerary import SaveDayItineraryRequest, StaySource
from app.services.itinerary import ItineraryService


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다")
    engine = create_async_engine(database_url)
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


async def _seed(
    factory: async_sessionmaker[AsyncSession],
    *,
    end_date: date = date(2026, 9, 3),
) -> tuple[uuid.UUID, date]:
    visit_date = date(2026, 9, 1)
    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"itinerary-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        trip = Trip(
            user_id=user.user_id,
            name="일정 저장 여행",
            start_date=visit_date,
            end_date=date(2026, 9, 3),
        )
        session.add(trip)
        await session.flush()
        return trip.trip_id, visit_date


def _create_payload(*, version: int = 0) -> SaveDayItineraryRequest:
    return SaveDayItineraryRequest(
        version=version,
        items=[_place_item(1, None)],
    )


def _place_item(sequence: int, transport: str | None) -> dict[str, object]:
    return {
        "itemId": None,
        "placeId": "tourapi:126508",
        "place": {
            "name": "경복궁",
            "category": "HISTORY_CULTURE",
            "tourApiCategory": {"large": "A02", "middle": None, "small": None},
            "address": "서울 종로구",
            "latitude": 37.5796,
            "longitude": 126.977,
            "imageUrl": None,
        },
        "sequence": sequence,
        "plannedStayMinutes": 90,
        "staySource": "RECOMMENDED",
        "transportModeToNext": transport,
    }


@pytest.mark.asyncio
async def test_first_save_and_same_key_retry_are_idempotent(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    key = uuid.uuid4()

    async with transaction_session(session_factory) as session:
        empty = await ItineraryService(session).get_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date
        )
    async with transaction_session(session_factory) as session:
        first, created = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=_create_payload(),
            idempotency_key=key,
        )
    async with transaction_session(session_factory) as session:
        retry, retry_created = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=_create_payload(),
            idempotency_key=key,
        )

    assert empty.version == 0 and empty.items == []
    assert created is True and first.version == 1
    assert retry_created is False and retry.version == 1
    assert retry.items[0].item_id == first.items[0].item_id
    async with session_factory() as session:
        assert await session.scalar(select(func.count()).select_from(TripDay).where(TripDay.trip_id == trip_id)) == 1
        assert await session.scalar(select(func.count()).select_from(ItineraryItem).join(TripDay).where(TripDay.trip_id == trip_id)) == 1
        assert await session.scalar(select(func.count()).select_from(Place).where(Place.tour_content_id == "126508")) == 1


@pytest.mark.asyncio
async def test_stale_version_conflicts_but_identical_current_state_is_noop(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        saved, _ = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=_create_payload(), idempotency_key=uuid.uuid4(),
        )

    existing = _create_payload(version=saved.version)
    existing.items[0].item_id = saved.items[0].item_id
    existing.items[0].place = None
    async with transaction_session(session_factory) as session:
        unchanged, created = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=existing, idempotency_key=uuid.uuid4(),
        )
    assert created is False and unchanged.version == saved.version

    with pytest.raises(AppError) as conflict:
        async with transaction_session(session_factory) as session:
            await ItineraryService(session).save_day(
                trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
                payload=_create_payload(), idempotency_key=uuid.uuid4(),
            )
    assert conflict.value.status_code == 409
    assert conflict.value.code == "VERSION_CONFLICT"


@pytest.mark.asyncio
async def test_changed_existing_item_keeps_id_and_increments_version(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        saved, _ = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=_create_payload(), idempotency_key=uuid.uuid4(),
        )
    changed = _create_payload(version=saved.version)
    changed.items[0].item_id = saved.items[0].item_id
    changed.items[0].place = None
    changed.items[0].planned_stay_minutes = 120
    changed.items[0].stay_source = StaySource.USER_ADJUSTED

    async with transaction_session(session_factory) as session:
        updated, created = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=changed, idempotency_key=uuid.uuid4(),
        )

    assert created is False
    assert updated.version == saved.version + 1
    assert updated.items[0].item_id == saved.items[0].item_id
    assert updated.items[0].planned_stay_minutes == 120


@pytest.mark.asyncio
async def test_processed_item_allows_stay_change_but_rejects_delete(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        saved, _ = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=_create_payload(), idempotency_key=uuid.uuid4(),
        )
        await session.execute(
            update(ItineraryItem)
            .where(ItineraryItem.item_id == saved.items[0].item_id)
            .values(status="COMPLETED")
        )

    allowed = _create_payload(version=saved.version)
    allowed.items[0].item_id = saved.items[0].item_id
    allowed.items[0].place = None
    allowed.items[0].planned_stay_minutes = 120
    allowed.items[0].stay_source = StaySource.USER_ADJUSTED
    async with transaction_session(session_factory) as session:
        changed, _ = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=allowed, idempotency_key=uuid.uuid4(),
        )
    assert changed.items[0].status == "COMPLETED"
    assert changed.items[0].planned_stay_minutes == 120

    with pytest.raises(AppError) as locked:
        async with transaction_session(session_factory) as session:
            await ItineraryService(session).save_day(
                trip_id=trip_id,
                visit_date=visit_date,
                start_date=visit_date,
                payload=SaveDayItineraryRequest(version=changed.version, items=[]),
                idempotency_key=uuid.uuid4(),
            )
    assert locked.value.status_code == 409
    assert locked.value.code == "ITINERARY_ITEM_LOCKED"
    assert locked.value.details == {"itemId": str(saved.items[0].item_id)}


@pytest.mark.asyncio
async def test_overview_includes_all_dates_and_unsaved_days(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=_create_payload(), idempotency_key=uuid.uuid4(),
        )
    async with transaction_session(session_factory) as session:
        overview = await ItineraryService(session).get_overview(
            trip_id=trip_id,
            start_date=visit_date,
            end_date=date(2026, 9, 3),
        )

    assert [day.date for day in overview.days] == [
        date(2026, 9, 1), date(2026, 9, 2), date(2026, 9, 3)
    ]
    assert [day.version for day in overview.days] == [1, 0, 0]
    assert [len(day.items) for day in overview.days] == [1, 0, 0]


@pytest.mark.asyncio
async def test_same_place_is_reused_across_duplicate_visits_and_dates(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    duplicate_payload = SaveDayItineraryRequest(
        version=0,
        items=[_place_item(1, "WALK"), _place_item(2, None)],
    )
    async with transaction_session(session_factory) as session:
        first_day, _ = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=duplicate_payload, idempotency_key=uuid.uuid4(),
        )
    async with transaction_session(session_factory) as session:
        second_day, _ = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=date(2026, 9, 2),
            start_date=visit_date,
            payload=_create_payload(),
            idempotency_key=uuid.uuid4(),
        )

    assert len(first_day.items) == 2
    assert len({item.item_id for item in first_day.items}) == 2
    assert len(second_day.items) == 1
    async with session_factory() as session:
        assert await session.scalar(
            select(func.count()).select_from(Place).where(Place.tour_content_id == "126508")
        ) == 1


@pytest.mark.asyncio
async def test_seven_day_overview_with_ten_items_each_finishes_within_three_seconds(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    end_date = date(2026, 9, 7)
    trip_id, start_date = await _seed(session_factory, end_date=end_date)
    items = [_place_item(index, "WALK" if index < 10 else None) for index in range(1, 11)]
    for offset in range(7):
        async with transaction_session(session_factory) as session:
            await ItineraryService(session).save_day(
                trip_id=trip_id,
                visit_date=start_date + timedelta(days=offset),
                start_date=start_date,
                payload=SaveDayItineraryRequest(version=0, items=items),
                idempotency_key=uuid.uuid4(),
            )

    started = time.perf_counter()
    async with transaction_session(session_factory) as session:
        overview = await ItineraryService(session).get_overview(
            trip_id=trip_id,
            start_date=start_date,
            end_date=end_date,
        )
    elapsed = time.perf_counter() - started

    assert len(overview.days) == 7
    assert all(len(day.items) == 10 for day in overview.days)
    assert elapsed < 3
