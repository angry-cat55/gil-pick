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
from app.models.progress import ProgressSegment, ProgressTransition
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
    def __init__(self) -> None:
        self.calls = 0

    async def calculate_single_segment(self, **kwargs):
        self.calls += 1
        raise RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=True)


class _BlockingCalculator(_Calculator):
    def __init__(self, entered: asyncio.Event, release: asyncio.Event) -> None:
        super().__init__()
        self.entered = entered
        self.release = release

    async def calculate_single_segment(self, **kwargs):
        self.calls += 1
        self.entered.set()
        await self.release.wait()
        return SingleSegmentResult(
            duration_seconds=600, distance_meters=800, provider=Provider.TMAP
        )


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


async def _seed_three(
    factory: async_sessionmaker[AsyncSession],
    item_count: int = 3,
) -> tuple[uuid.UUID, uuid.UUID, uuid.UUID]:
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"transition-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        trip = Trip(user_id=user.user_id, name="전환 테스트", start_date=today, end_date=today)
        session.add(trip)
        await session.flush()
        day = TripDay(trip_id=trip.trip_id, visit_date=today, day_number=1, schedule_version=1)
        session.add(day)
        await session.flush()
        for sequence in range(1, item_count + 1):
            place = Place(
                tour_content_id=f"transition-{uuid.uuid4()}", name=f"장소 {sequence}",
                category="OTHER",
                location=WKTElement(f"POINT({127 + sequence / 100} 37.5)", srid=4326),
            )
            session.add(place)
            await session.flush()
            session.add(ItineraryItem(
                trip_day_id=day.trip_day_id, place_id=place.place_id,
                sequence=sequence, planned_stay_minutes=30,
                stay_source="RECOMMENDED",
                transport_mode_to_next="WALK" if sequence < item_count else None,
            ))
        return trip.trip_id, day.trip_day_id, user.user_id


async def _start_and_complete_first(
    factory: async_sessionmaker[AsyncSession],
    trip_id: uuid.UUID,
    user_id: uuid.UUID,
) -> tuple[uuid.UUID, uuid.UUID, uuid.UUID]:
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id,
            visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    first, second, third = [item.item_id for item in started.items]
    for version, status in enumerate(("ARRIVED", "COMPLETED"), start=1):
        async with factory() as session:
            await ProgressService(session).update_item_status(
                user_id=user_id,
                item_id=first,
                target_status=status,
                progress_version=version,
                idempotency_key=uuid.uuid4(),
            )
    return first, second, third


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
    assert first.items[0].processing_source == "MANUAL"
    assert first.items[0].event_rejection_reason is None
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
    assert "126.98" not in message


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


@pytest.mark.asyncio
async def test_arrive_depart_and_skip_update_versions_and_complete_day(
    session_factory: async_sessionmaker[AsyncSession],
    caplog: pytest.LogCaptureFixture,
) -> None:
    trip_id, day_id, user_id = await _seed_three(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    caplog.set_level(logging.INFO, logger="gilpick.progress")
    idempotency_keys: list[uuid.UUID] = []
    async with session_factory() as session:
        start_key = uuid.uuid4()
        idempotency_keys.append(start_key)
        started = await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=start_key,
        )
    first, second, third = [item.item_id for item in started.items]

    async with session_factory() as session:
        arrive_key = uuid.uuid4()
        idempotency_keys.append(arrive_key)
        arrived = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=first, target_status="ARRIVED",
            progress_version=1, idempotency_key=arrive_key,
        )
    async with session_factory() as session:
        depart_key = uuid.uuid4()
        idempotency_keys.append(depart_key)
        departed = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=first, target_status="COMPLETED",
            progress_version=2, idempotency_key=depart_key,
        )
    async with session_factory() as session:
        skip_key = uuid.uuid4()
        idempotency_keys.append(skip_key)
        skipped = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=second, target_status="SKIPPED",
            progress_version=3, idempotency_key=skip_key,
        )
    async with session_factory() as session:
        complete_key = uuid.uuid4()
        idempotency_keys.append(complete_key)
        completed = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=third, target_status="ARRIVED",
            progress_version=4, idempotency_key=complete_key,
        )
        transitions = await session.scalar(select(func.count()).select_from(ProgressTransition).where(ProgressTransition.trip_day_id == day_id))

    assert arrived.progress_version == 2
    assert departed.next_item_id == second
    assert skipped.next_item_id == third
    assert completed.day_status == "COMPLETED"
    assert completed.progress_version == 5
    assert transitions == 5
    message = " ".join(record.getMessage() for record in caplog.records)
    assert all(str(key) not in message for key in idempotency_keys)


@pytest.mark.asyncio
async def test_skip_computes_missing_previous_to_next_segment(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _, user_id = await _seed_three(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    first, second, third = [item.item_id for item in started.items]
    version = 1
    for item_id, status in ((first, "ARRIVED"), (first, "COMPLETED")):
        async with session_factory() as session:
            await ProgressService(session).update_item_status(
                user_id=user_id, item_id=item_id, target_status=status,
                progress_version=version, idempotency_key=uuid.uuid4(),
            )
        version += 1
    calculator = _Calculator()
    async with session_factory() as session:
        first_response = await ProgressService(
            session, calculator=calculator, session_factory=session_factory
        ).update_item_status(
            user_id=user_id, item_id=second, target_status="SKIPPED",
            progress_version=version, idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        result = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=today
        )

    third_item = next(item for item in result.items if item.item_id == third)
    assert calculator.calls == 1
    assert next(item for item in first_response.items if item.item_id == third).inbound_travel is not None
    assert third_item.inbound_travel is not None
    assert third_item.inbound_travel.from_item_id == first
    assert third_item.inbound_travel.source == "COMPUTED"


@pytest.mark.asyncio
async def test_same_key_retry_waits_for_final_skip_snapshot(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _, user_id = await _seed_three(session_factory)
    first, second, third = await _start_and_complete_first(
        session_factory, trip_id, user_id
    )
    entered, release = asyncio.Event(), asyncio.Event()
    calculator = _BlockingCalculator(entered, release)
    key = uuid.uuid4()

    async def request() -> object:
        async with session_factory() as session:
            return await ProgressService(
                session, calculator=calculator, session_factory=session_factory
            ).update_item_status(
                user_id=user_id, item_id=second, target_status="SKIPPED",
                progress_version=3, idempotency_key=key,
            )

    original = asyncio.create_task(request())
    await asyncio.wait_for(entered.wait(), timeout=2)
    retry = asyncio.create_task(request())
    await asyncio.sleep(0.1)
    release.set()
    original_result, retry_result = await asyncio.gather(original, retry)

    assert original_result == retry_result
    third_item = next(item for item in original_result.items if item.item_id == third)
    assert third_item.inbound_travel is not None
    assert third_item.inbound_travel.from_item_id == first
    assert calculator.calls == 1


@pytest.mark.asyncio
async def test_skip_planned_item_uses_immediate_en_route_previous_item(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _, user_id = await _seed_three(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id,
            visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    first, second, third = [item.item_id for item in started.items]
    calculator = _Calculator()
    async with session_factory() as session:
        await ProgressService(
            session, calculator=calculator, session_factory=session_factory
        ).update_item_status(
            user_id=user_id, item_id=second, target_status="SKIPPED",
            progress_version=1, idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        result = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=today
        )

    third_item = next(item for item in result.items if item.item_id == third)
    assert calculator.calls == 1
    assert third_item.inbound_travel is not None
    assert third_item.inbound_travel.from_item_id == first


@pytest.mark.asyncio
async def test_skip_future_planned_item_uses_its_immediate_planned_previous_item(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _, user_id = await _seed_three(session_factory, item_count=4)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id,
            visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    _, second, third, fourth = [item.item_id for item in started.items]
    calculator = _Calculator()
    async with session_factory() as session:
        await ProgressService(
            session, calculator=calculator, session_factory=session_factory
        ).update_item_status(
            user_id=user_id, item_id=third, target_status="SKIPPED",
            progress_version=1, idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        result = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=today
        )

    fourth_item = next(item for item in result.items if item.item_id == fourth)
    assert calculator.calls == 1
    assert fourth_item.inbound_travel is not None
    assert fourth_item.inbound_travel.from_item_id == second


@pytest.mark.asyncio
async def test_skip_reuses_existing_progress_segment_without_provider_call(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, user_id = await _seed_three(session_factory)
    first, second, third = await _start_and_complete_first(
        session_factory, trip_id, user_id
    )
    async with transaction_session(session_factory) as session:
        session.add(ProgressSegment(
            trip_day_id=day_id,
            from_item_id=first,
            to_item_id=third,
            transport_mode="WALK",
            provider="TMAP",
            duration_seconds=420,
            distance_meters=550,
            computed_at=datetime.now(UTC),
        ))
    calculator = _Calculator()
    async with session_factory() as session:
        result = await ProgressService(
            session, calculator=calculator, session_factory=session_factory
        ).update_item_status(
            user_id=user_id,
            item_id=second,
            target_status="SKIPPED",
            progress_version=3,
            idempotency_key=uuid.uuid4(),
        )

    third_item = next(item for item in result.items if item.item_id == third)
    assert calculator.calls == 0
    assert third_item.inbound_travel is not None
    assert third_item.inbound_travel.duration_seconds == 420
    assert third_item.inbound_travel.source == "COMPUTED"


@pytest.mark.asyncio
async def test_skip_provider_failure_keeps_transition_and_unknown_eta(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, user_id = await _seed_three(session_factory)
    _, second, third = await _start_and_complete_first(
        session_factory, trip_id, user_id
    )
    calculator = _FailingCalculator()
    async with session_factory() as session:
        result = await ProgressService(
            session, calculator=calculator, session_factory=session_factory
        ).update_item_status(
            user_id=user_id,
            item_id=second,
            target_status="SKIPPED",
            progress_version=3,
            idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        segment_count = await session.scalar(
            select(func.count()).select_from(ProgressSegment).where(
                ProgressSegment.trip_day_id == day_id,
                ProgressSegment.to_item_id == third,
            )
        )

    second_item = next(item for item in result.items if item.item_id == second)
    third_item = next(item for item in result.items if item.item_id == third)
    assert calculator.calls == 1
    assert result.progress_version == 4
    assert second_item.status == "SKIPPED"
    assert third_item.inbound_travel is None
    assert third_item.estimated_arrival_at is None
    assert segment_count == 0


@pytest.mark.asyncio
async def test_transition_enforces_ownership_and_records_audit_fields(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, user_id = await _seed_three(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id,
            visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    first = started.items[0].item_id

    async with session_factory() as session:
        with pytest.raises(AppError) as forbidden:
            await ProgressService(session).update_item_status(
                user_id=uuid.uuid4(), item_id=first, target_status="ARRIVED",
                progress_version=1, idempotency_key=uuid.uuid4(),
            )
        with pytest.raises(AppError) as missing:
            await ProgressService(session).update_item_status(
                user_id=user_id, item_id=uuid.uuid4(), target_status="ARRIVED",
                progress_version=1, idempotency_key=uuid.uuid4(),
            )
    assert forbidden.value.code == "TRIP_FORBIDDEN"
    assert missing.value.code == "ITINERARY_ITEM_NOT_FOUND"

    async with session_factory() as session:
        await ProgressService(session).update_item_status(
            user_id=user_id, item_id=first, target_status="ARRIVED",
            progress_version=1, idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        transition = await session.scalar(
            select(ProgressTransition)
            .where(ProgressTransition.trip_day_id == day_id)
            .order_by(ProgressTransition.progress_version_after.desc())
        )

    assert transition is not None
    assert transition.transition_type == "ARRIVE"
    assert transition.status == "CONFIRMED"
    assert transition.source == "MANUAL"
    assert transition.confirmed_at == transition.detected_at
    assert transition.progress_version_after == 2
    assert transition.affected_items == [{
        "itemId": str(first),
        "beforeStatus": "EN_ROUTE",
        "afterStatus": "ARRIVED",
    }]


@pytest.mark.asyncio
async def test_transition_rejects_not_started_and_stale_version_and_is_idempotent(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _, user_id = await _seed_three(session_factory)
    async with session_factory() as session:
        item_id = await session.scalar(
            select(ItineraryItem.item_id)
            .join(TripDay, TripDay.trip_day_id == ItineraryItem.trip_day_id)
            .where(TripDay.trip_id == trip_id, ItineraryItem.sequence == 1)
        )
        with pytest.raises(AppError) as not_started:
            await ProgressService(session).update_item_status(
                user_id=user_id, item_id=item_id, target_status="ARRIVED",
                progress_version=0, idempotency_key=uuid.uuid4(),
            )
    assert not_started.value.code == "DAY_NOT_STARTED"

    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        with pytest.raises(AppError) as conflict:
            await ProgressService(session).update_item_status(
                user_id=user_id, item_id=item_id, target_status="ARRIVED",
                progress_version=0, idempotency_key=uuid.uuid4(),
            )
    assert conflict.value.code == "VERSION_CONFLICT"

    key = uuid.uuid4()
    async with session_factory() as session:
        first = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=item_id, target_status="ARRIVED",
            progress_version=1, idempotency_key=key,
        )
    async with session_factory() as session:
        retried = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=item_id, target_status="ARRIVED",
            progress_version=1, idempotency_key=key,
        )
    assert retried == first

    async with session_factory() as session:
        await ProgressService(session).update_item_status(
            user_id=user_id, item_id=item_id, target_status="COMPLETED",
            progress_version=2, idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        historical_retry = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=item_id, target_status="ARRIVED",
            progress_version=1, idempotency_key=key,
        )
        with pytest.raises(AppError) as reused:
            await ProgressService(session).update_item_status(
                user_id=user_id, item_id=item_id, target_status="COMPLETED",
                progress_version=2, idempotency_key=key,
            )

    assert historical_retry == first
    assert historical_retry.progress_version == 2
    assert reused.value.code == "IDEMPOTENCY_KEY_CONFLICT"


@pytest.mark.asyncio
async def test_undo_complete_restores_completed_day_and_recalculates_eta(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, user_id = await _seed_three(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    first, second, third = [item.item_id for item in started.items]
    old_time = datetime.now(UTC) - timedelta(minutes=10)
    async with transaction_session(session_factory) as session:
        items = (await session.scalars(
            select(ItineraryItem)
            .where(ItineraryItem.trip_day_id == day_id)
            .order_by(ItineraryItem.sequence)
        )).all()
        for item, status in zip(items, ("COMPLETED", "SKIPPED", "ARRIVED"), strict=True):
            item.status = status
            item.actual_arrived_at = old_time
            item.actual_departed_at = old_time
            item.completed_at = old_time
        day = await session.get(TripDay, day_id)
        day.status = "COMPLETED"
        day.completed_at = old_time
        day.detection_active = False
        day.progress_version = 4
        session.add_all([
            ProgressSegment(
                trip_day_id=day_id, from_item_id=first, to_item_id=second,
                transport_mode="WALK", provider="TMAP", duration_seconds=300,
                distance_meters=400, computed_at=old_time,
            ),
            ProgressSegment(
                trip_day_id=day_id, from_item_id=second, to_item_id=third,
                transport_mode="WALK", provider="TMAP", duration_seconds=300,
                distance_meters=400, computed_at=old_time,
            ),
        ])

    async with session_factory() as session:
        result = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=first, target_status="ARRIVED",
            progress_version=4, idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        day = await session.get(TripDay, day_id)
        rows = (await session.scalars(
            select(ItineraryItem)
            .where(ItineraryItem.trip_day_id == day_id)
            .order_by(ItineraryItem.sequence)
        )).all()
        transition = await session.scalar(
            select(ProgressTransition)
            .where(ProgressTransition.trip_day_id == day_id)
            .order_by(ProgressTransition.progress_version_after.desc())
        )

    assert result.day_status == "IN_PROGRESS"
    assert result.progress_version == 5
    assert [item.status for item in result.items] == ["ARRIVED", "PLANNED", "PLANNED"]
    assert result.items[1].estimated_arrival_at is not None
    assert day.completed_at is None
    assert day.detection_active is True
    assert rows[0].actual_departed_at is None and rows[0].completed_at is None
    assert all(item.actual_arrived_at is None for item in rows[1:])
    assert transition.transition_type == "UNDO_COMPLETE"
    assert len(transition.affected_items) == 4


@pytest.mark.asyncio
async def test_undo_skip_starts_first_planned_item_and_restores_day(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, user_id = await _seed_three(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    _, second, _ = [item.item_id for item in started.items]
    async with transaction_session(session_factory) as session:
        await session.execute(
            update(ItineraryItem)
            .where(ItineraryItem.trip_day_id == day_id)
            .values(status="SKIPPED")
        )
        await session.execute(
            update(TripDay).where(TripDay.trip_day_id == day_id).values(
                status="COMPLETED", completed_at=datetime.now(UTC),
                detection_active=False, progress_version=4,
            )
        )

    async with session_factory() as session:
        result = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=second, target_status="PLANNED",
            progress_version=4, idempotency_key=uuid.uuid4(),
        )

    assert result.day_status == "IN_PROGRESS"
    assert result.completed_at is None
    assert [item.status for item in result.items] == ["SKIPPED", "EN_ROUTE", "SKIPPED"]


@pytest.mark.asyncio
async def test_change_planned_to_arrived_resets_en_route_and_recalculates_eta(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, user_id = await _seed_three(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    first, second, third = [item.item_id for item in started.items]
    async with transaction_session(session_factory) as session:
        session.add(ProgressSegment(
            trip_day_id=day_id, from_item_id=second, to_item_id=third,
            transport_mode="WALK", provider="TMAP", duration_seconds=300,
            distance_meters=400, computed_at=datetime.now(UTC),
        ))

    async with session_factory() as session:
        result = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=second, target_status="ARRIVED",
            progress_version=1, idempotency_key=uuid.uuid4(),
        )

    assert [item.status for item in result.items] == ["PLANNED", "ARRIVED", "PLANNED"]
    assert result.current_item_id == second
    assert result.items[0].actual_arrived_at is None
    assert result.items[2].estimated_arrival_at is not None
    assert result.items[2].estimated_arrival_at > result.items[1].actual_arrived_at
    assert result.next_item_id == first


@pytest.mark.asyncio
async def test_change_later_planned_to_arrived_completes_previous_arrived(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, user_id = await _seed_three(session_factory)
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()
    async with session_factory() as session:
        started = await ProgressService(session).start_day(
            trip_id=trip_id, visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )
    first, second, third = [item.item_id for item in started.items]
    async with session_factory() as session:
        await ProgressService(session).update_item_status(
            user_id=user_id, item_id=first, target_status="ARRIVED",
            progress_version=1, idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        result = await ProgressService(session).update_item_status(
            user_id=user_id, item_id=third, target_status="ARRIVED",
            progress_version=2, idempotency_key=uuid.uuid4(),
        )
    async with session_factory() as session:
        previous = await session.get(ItineraryItem, first)
        transition = await session.scalar(
            select(ProgressTransition)
            .where(ProgressTransition.trip_day_id == day_id)
            .order_by(ProgressTransition.progress_version_after.desc())
        )

    assert [item.status for item in result.items] == ["COMPLETED", "PLANNED", "ARRIVED"]
    assert previous.actual_departed_at is not None
    assert previous.completed_at == previous.actual_departed_at
    assert transition.transition_type == "ARRIVE"
    assert {entry.get("itemId") for entry in transition.affected_items} == {
        str(first), str(third)
    }
