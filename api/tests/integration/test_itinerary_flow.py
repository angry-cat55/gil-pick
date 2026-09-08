"""PostgreSQL에서 일정 저장의 version·멱등·장소 재사용을 검증한다."""

import os
import logging
import time
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.errors import AppError
from app.db import transaction_session
from app.models.auth import User
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.route import Route as RouteModel
from app.models.trip import Trip
from app.schemas.itinerary import SaveDayItineraryRequest, StaySource
from app.schemas.trip import UpdateTripRequest
from app.services.itinerary import ItineraryService
from app.services.trip import TripService
from app.services.route import RouteCalculationService, RouteService
from app.clients.route_provider import Provider


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
    start_date: date = date(2026, 9, 1),
    end_date: date = date(2026, 9, 3),
) -> tuple[uuid.UUID, date]:
    visit_date = start_date
    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"itinerary-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        trip = Trip(
            user_id=user.user_id,
            name="일정 저장 여행",
            start_date=visit_date,
            end_date=end_date,
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
async def test_trip_period_shrink_counts_and_deletes_only_out_of_range_itinerary(
    session_factory: async_sessionmaker[AsyncSession],
    caplog: pytest.LogCaptureFixture,
) -> None:
    """기간 밖 장소는 확인 전 보존하고 확인 후 날짜 단위로 삭제한다."""
    start_date = date.today() + timedelta(days=10)
    trip_id, start_date = await _seed(
        session_factory,
        start_date=start_date,
        end_date=start_date + timedelta(days=2),
    )
    for visit_date in (start_date, start_date + timedelta(days=2)):
        async with transaction_session(session_factory) as session:
            await ItineraryService(session).save_day(
                trip_id=trip_id,
                visit_date=visit_date,
                start_date=start_date,
                payload=_create_payload(),
                idempotency_key=uuid.uuid4(),
            )

    async with session_factory() as session:
        trip = await session.get(Trip, trip_id)
        assert trip is not None
        user_id = trip.user_id

    payload = UpdateTripRequest(endDate=start_date + timedelta(days=1), version=1)
    with pytest.raises(AppError) as confirmation:
        async with transaction_session(session_factory) as session:
            await TripService(session, cursor_secret="test-secret").update_trip(
                user_id=user_id,
                trip_id=trip_id,
                payload=payload,
            )
    assert confirmation.value.code == "CONFIRMATION_REQUIRED"
    assert confirmation.value.details == {"deletedItemCount": 1}

    async with session_factory() as session:
        assert await session.scalar(
            select(func.count()).select_from(TripDay).where(TripDay.trip_id == trip_id)
        ) == 2

    confirmed = UpdateTripRequest(
        endDate=start_date + timedelta(days=1),
        version=1,
        confirmDeleteOutOfRangeItems=True,
    )
    with caplog.at_level(logging.INFO, logger="app.services.trip"):
        async with transaction_session(session_factory) as session:
            updated = await TripService(session, cursor_secret="test-secret").update_trip(
                user_id=user_id,
                trip_id=trip_id,
                payload=confirmed,
            )

    assert updated.end_date == start_date + timedelta(days=1)
    assert updated.version == 2
    async with session_factory() as session:
        remaining_dates = list(
            await session.scalars(
                select(TripDay.visit_date)
                .where(TripDay.trip_id == trip_id)
                .order_by(TripDay.visit_date)
            )
        )
        remaining_items = await session.scalar(
            select(func.count())
            .select_from(ItineraryItem)
            .join(TripDay)
            .where(TripDay.trip_id == trip_id)
        )
    assert remaining_dates == [start_date]
    assert remaining_items == 1
    assert "deleted_day_count=1" in caplog.text
    assert "deleted_item_count=1" in caplog.text


@pytest.mark.asyncio
async def test_trip_period_shrink_without_out_of_range_items_needs_no_confirmation(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """범위 밖 장소가 없으면 확인 없이 기간을 줄인다."""
    start_date = date.today() + timedelta(days=10)
    trip_id, start_date = await _seed(
        session_factory,
        start_date=start_date,
        end_date=start_date + timedelta(days=2),
    )
    async with session_factory() as session:
        trip = await session.get(Trip, trip_id)
        assert trip is not None
        user_id = trip.user_id

    async with transaction_session(session_factory) as session:
        updated = await TripService(session, cursor_secret="test-secret").update_trip(
            user_id=user_id,
            trip_id=trip_id,
            payload=UpdateTripRequest(
                endDate=start_date + timedelta(days=1),
                version=1,
            ),
        )

    assert updated.end_date == start_date + timedelta(days=1)


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
        first, created, route_changed = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=_create_payload(),
            idempotency_key=key,
        )
    async with transaction_session(session_factory) as session:
        retry, retry_created, retry_route_changed = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=_create_payload(),
            idempotency_key=key,
        )

    assert empty.version == 0 and empty.items == []
    assert created is True and first.version == 1
    assert route_changed is True
    assert retry_created is False and retry.version == 1
    assert retry_route_changed is False
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
        saved, _, _ = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=_create_payload(), idempotency_key=uuid.uuid4(),
        )

    existing = _create_payload(version=saved.version)
    existing.items[0].item_id = saved.items[0].item_id
    existing.items[0].place = None
    async with transaction_session(session_factory) as session:
        unchanged, created, route_changed = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=existing, idempotency_key=uuid.uuid4(),
        )
    assert created is False and unchanged.version == saved.version
    assert route_changed is False

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
        saved, _, _ = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=_create_payload(), idempotency_key=uuid.uuid4(),
        )
    changed = _create_payload(version=saved.version)
    changed.items[0].item_id = saved.items[0].item_id
    changed.items[0].place = None
    changed.items[0].planned_stay_minutes = 120
    changed.items[0].stay_source = StaySource.USER_ADJUSTED

    async with transaction_session(session_factory) as session:
        updated, created, route_changed = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=changed, idempotency_key=uuid.uuid4(),
        )

    assert created is False
    assert updated.version == saved.version + 1
    assert updated.items[0].item_id == saved.items[0].item_id
    assert updated.items[0].planned_stay_minutes == 120
    assert route_changed is False


@pytest.mark.asyncio
async def test_processed_item_allows_stay_change_but_rejects_delete(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        saved, _, _ = await ItineraryService(session).save_day(
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
        changed, _, _ = await ItineraryService(session).save_day(
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
        first_day, _, _ = await ItineraryService(session).save_day(
            trip_id=trip_id, visit_date=visit_date, start_date=visit_date,
            payload=duplicate_payload, idempotency_key=uuid.uuid4(),
        )
    async with transaction_session(session_factory) as session:
        second_day, _, _ = await ItineraryService(session).save_day(
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

class _UnusedProvider:
    async def calculate(self, *args, **kwargs):  # type: ignore[no-untyped-def]
        raise AssertionError("한 장소 일정은 provider를 호출하면 안 됩니다.")


@pytest.mark.asyncio
async def test_stay_only_change_carries_active_route_to_new_schedule_version(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        saved, _, _ = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=_create_payload(),
            idempotency_key=uuid.uuid4(),
        )
    route_service = RouteService(
        session_factory,
        RouteCalculationService(
            tmap=_UnusedProvider(),
            odsay=_UnusedProvider(),
            concurrency=3,
            deadline_seconds=10,
        ),
    )
    await route_service.calculate_current(trip_id=trip_id, visit_date=visit_date)

    started = datetime(2026, 9, 1, 1, tzinfo=UTC)
    async with transaction_session(session_factory) as session:
        day = await session.scalar(select(TripDay).where(TripDay.trip_id == trip_id))
        day.status = "IN_PROGRESS"
        day.actual_started_at = started
        item = await session.scalar(select(ItineraryItem).where(ItineraryItem.trip_day_id == day.trip_day_id))
        item.status = "EN_ROUTE"

    changed = _create_payload(version=saved.version)
    changed.items[0].item_id = saved.items[0].item_id
    changed.items[0].place = None
    changed.items[0].planned_stay_minutes = 120
    async with transaction_session(session_factory) as session:
        updated, _, route_changed = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=changed,
            idempotency_key=uuid.uuid4(),
        )

    assert route_changed is False
    async with session_factory() as session:
        active = await session.scalar(
            select(RouteModel)
            .join(TripDay, TripDay.trip_day_id == RouteModel.trip_day_id)
            .where(
                TripDay.trip_id == trip_id,
                RouteModel.is_active.is_(True),
            )
        )
        assert active is not None
        assert active.schedule_version == updated.version
        assert active.status == "READY"
        item = await session.scalar(select(ItineraryItem).where(ItineraryItem.trip_day_id == active.trip_day_id))
        assert item.estimated_arrival_at == started
        assert item.estimated_departure_at == started + timedelta(minutes=120)


@pytest.mark.asyncio
async def test_coordinate_change_is_route_input_change_even_for_same_place(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        saved, _, _ = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=_create_payload(),
            idempotency_key=uuid.uuid4(),
        )

    changed = _create_payload(version=saved.version)
    changed.items[0].item_id = saved.items[0].item_id
    assert changed.items[0].place is not None
    changed.items[0].place.latitude = 37.58
    async with transaction_session(session_factory) as session:
        updated, _, route_changed = await ItineraryService(session).save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=changed,
            idempotency_key=uuid.uuid4(),
        )

    assert updated.version == saved.version + 1
    assert route_changed is True


@pytest.mark.asyncio
async def test_explicit_itinerary_commit_closes_request_transaction_cleanly(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, visit_date = await _seed(session_factory)

    async with transaction_session(session_factory) as session:
        service = ItineraryService(session)
        await service.save_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
            payload=_create_payload(),
            idempotency_key=uuid.uuid4(),
        )
        await service.commit()
