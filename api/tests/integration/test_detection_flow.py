"""F007 위치 이벤트 검증·저장·멱등 처리 통합 테스트."""

import os
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import pytest
from geoalchemy2 import WKTElement
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import selectinload

from app.api.errors import AppError
from app.db import transaction_session
from app.models.auth import User
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.progress import ProgressEvent, ProgressTransition
from app.models.trip import Trip
from app.schemas.progress import ProgressEventRequest
from app.services.detection import (
    REPROMPT_DELAY_MINUTES,
    UNDO_WINDOW_MINUTES,
    DetectionService,
)
from app.services.progress import ProgressService


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
    event_type: str = "DWELL",
) -> ProgressEventRequest:
    default_kind = "ARRIVAL" if event_type == "DWELL" else "DEPARTURE"
    return ProgressEventRequest.model_validate({
        "eventId": str(event_id or uuid.uuid4()),
        "eventType": event_type,
        "itemId": str(item_id),
        "geofenceId": geofence_id or f"{item_id}:{default_kind}",
        "occurredAt": (occurred_at or now).isoformat(),
        "location": {
            "latitude": 37.5,
            "longitude": 127.0,
            "accuracyMeters": accuracy,
        },
    })


async def _register(
    factory: async_sessionmaker[AsyncSession],
    *,
    trip_id: uuid.UUID,
    item_id: uuid.UUID,
    user_id: uuid.UUID,
    now: datetime,
    event_type: str = "DWELL",
    event_id: uuid.UUID | None = None,
):
    async with transaction_session(factory) as session:
        return await DetectionService(session).register_event(
            user_id=user_id,
            trip_id=trip_id,
            visit_date=now.date(),
            payload=_payload(
                item_id, now, event_type=event_type, event_id=event_id
            ),
            received_at=now,
        )


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
async def test_progress_reports_latest_event_rejection_and_clears_after_acceptance(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        rejected = await DetectionService(session).register_event(
            user_id=user_id,
            trip_id=trip_id,
            visit_date=now.date(),
            payload=_payload(item_id, now, accuracy=101),
            received_at=now,
        )
    async with session_factory() as session:
        after_rejection = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )

    assert rejected.rejection_reason == "LOW_ACCURACY"
    assert after_rejection.items[0].event_rejection_reason == "LOW_ACCURACY"

    async with transaction_session(session_factory) as session:
        accepted = await DetectionService(session).register_event(
            user_id=user_id,
            trip_id=trip_id,
            visit_date=now.date(),
            payload=_payload(item_id, now),
            received_at=now + timedelta(seconds=1),
        )
    async with session_factory() as session:
        after_acceptance = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )

    assert accepted.accepted is True
    assert after_acceptance.items[0].event_rejection_reason is None


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


@pytest.mark.asyncio
async def test_dwell_creates_arrival_candidate_without_incrementing_progress_version(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)

    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).register_event(
            user_id=user_id,
            trip_id=trip_id,
            visit_date=now.date(),
            payload=_payload(item_id, now),
            received_at=now,
        )

    async with session_factory() as session:
        day = await session.scalar(
            select(TripDay).where(
                TripDay.trip_id == trip_id,
                TripDay.visit_date == now.date(),
            )
        )
        transition = await session.scalar(
            select(ProgressTransition).where(
                ProgressTransition.primary_item_id == item_id,
                ProgressTransition.status == "PENDING_CONFIRMATION",
            )
        )

    assert result.accepted is True
    assert result.candidate is not None
    assert result.candidate.type == "ARRIVAL"
    assert result.candidate.status == "PENDING_CONFIRMATION"
    assert result.candidate.allowed_decisions == ["CONFIRM", "NOT_ARRIVED"]
    assert result.candidate.auto_finalize_at == now + timedelta(minutes=5)
    assert result.candidate.evidence.occurred_at == now
    assert result.candidate.evidence.accuracy_meters == 20
    assert transition is not None
    assert transition.transition_type == "ARRIVAL"
    assert transition.source == "GEOFENCE_DWELL"
    assert transition.auto_finalize_at == now + timedelta(minutes=5)
    assert day is not None and day.progress_version == 1


@pytest.mark.asyncio
async def test_duplicate_dwell_returns_same_candidate_and_creates_one_transition(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    event_id = uuid.uuid4()
    payload = _payload(item_id, now, event_id=event_id)

    async with transaction_session(session_factory) as session:
        first = await DetectionService(session).register_event(
            user_id=user_id,
            trip_id=trip_id,
            visit_date=now.date(),
            payload=payload,
            received_at=now,
        )
    async with transaction_session(session_factory) as session:
        retried = await DetectionService(session).register_event(
            user_id=user_id,
            trip_id=trip_id,
            visit_date=now.date(),
            payload=payload,
            received_at=now,
        )
    async with session_factory() as session:
        transition_count = await session.scalar(
            select(func.count())
            .select_from(ProgressTransition)
            .where(ProgressTransition.primary_item_id == item_id)
        )

    assert retried == first
    assert first.candidate is not None
    assert transition_count == 1


async def _register_arrival(
    factory: async_sessionmaker[AsyncSession],
    *,
    trip_id: uuid.UUID,
    item_id: uuid.UUID,
    user_id: uuid.UUID,
    now: datetime,
):
    async with transaction_session(factory) as session:
        return await DetectionService(session).register_event(
            user_id=user_id,
            trip_id=trip_id,
            visit_date=now.date(),
            payload=_payload(item_id, now),
            received_at=now,
        )


@pytest.mark.asyncio
async def test_not_arrived_pauses_once_then_stops_after_second_prompt(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    first = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
    )
    assert first.candidate is not None

    async with transaction_session(session_factory) as session:
        cancelled = await DetectionService(session).decide_transition(
            user_id=user_id,
            transition_id=first.candidate.transition_id,
            decision="NOT_ARRIVED",
            idempotency_key=uuid.uuid4(),
            decided_at=now,
        )

    assert cancelled.status == "CANCELLED"
    assert cancelled.affected_items == []
    assert cancelled.next_prompt_at == now + timedelta(minutes=10)

    paused = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now + timedelta(minutes=9, seconds=59),
    )
    assert paused.accepted is False
    assert paused.rejection_reason == "DETECTION_PAUSED"
    assert paused.candidate is None

    second = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now + timedelta(minutes=10),
    )
    assert second.candidate is not None
    async with transaction_session(session_factory) as session:
        await DetectionService(session).decide_transition(
            user_id=user_id,
            transition_id=second.candidate.transition_id,
            decision="NOT_ARRIVED",
            idempotency_key=uuid.uuid4(),
            decided_at=now + timedelta(minutes=10),
        )

    limited = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now + timedelta(minutes=20),
    )
    assert limited.accepted is False
    assert limited.rejection_reason == "PROMPT_LIMIT_REACHED"
    assert limited.candidate is None


@pytest.mark.asyncio
async def test_confirm_arrival_changes_item_and_has_no_undo_deadline(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    event = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
    )
    assert event.candidate is not None

    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).decide_transition(
            user_id=user_id,
            transition_id=event.candidate.transition_id,
            decision="CONFIRM",
            idempotency_key=uuid.uuid4(),
            decided_at=now,
        )
    async with session_factory() as session:
        item = await session.get(ItineraryItem, item_id)
        day = await session.scalar(select(TripDay).where(TripDay.trip_id == trip_id))
        progress = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )

    assert result.status == "CONFIRMED"
    assert result.undo_deadline is None
    assert result.progress_version == 2
    assert item is not None and item.status == "ARRIVED"
    assert day is not None and day.progress_version == 2
    assert progress.items[0].processing_source == "MANUAL"


@pytest.mark.asyncio
async def test_confirm_next_arrival_records_composite_transition(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, first_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    async with transaction_session(session_factory) as session:
        day = await session.scalar(select(TripDay).where(TripDay.trip_id == trip_id))
        place = Place(
            tour_content_id=f"detection-{uuid.uuid4()}",
            name="다음 장소",
            category="OTHER",
            location=WKTElement("POINT(127.01 37.51)", srid=4326),
        )
        session.add(place)
        await session.flush()
        next_item = ItineraryItem(
            trip_day_id=day.trip_day_id,
            place_id=place.place_id,
            sequence=2,
            status="EN_ROUTE",
            planned_stay_minutes=30,
            stay_source="USER_ADJUSTED",
        )
        session.add(next_item)
        await session.flush()
        next_id = next_item.item_id

    event = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=next_id,
        user_id=user_id,
        now=now,
    )
    assert event.candidate is not None
    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).decide_transition(
            user_id=user_id,
            transition_id=event.candidate.transition_id,
            decision="CONFIRM",
            idempotency_key=uuid.uuid4(),
            decided_at=now,
        )
    async with session_factory() as session:
        transition = await session.get(
            ProgressTransition, event.candidate.transition_id
        )
        progress = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )

    assert transition is not None and transition.transition_type == "COMPOSITE"
    assert [(item.item_id, item.before_status, item.after_status) for item in result.affected_items] == [
        (first_id, "ARRIVED", "COMPLETED"),
        (next_id, "EN_ROUTE", "ARRIVED"),
    ]
    assert {item.item_id: item.processing_source for item in progress.items} == {
        first_id: "MANUAL",
        next_id: "MANUAL",
    }


@pytest.mark.asyncio
async def test_build_state_returns_detection_targets_and_pending_candidate(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    async with transaction_session(session_factory) as session:
        day = await session.scalar(
            select(TripDay)
            .options(selectinload(TripDay.items))
            .where(TripDay.trip_id == trip_id)
        )
        targets, pending = await DetectionService(session).build_state(day)

    assert pending is None
    assert len(targets) == 1
    target = targets[0]
    assert target.item_id == item_id
    assert target.kind == "ARRIVAL"
    assert target.geofence_id == f"{item_id}:ARRIVAL"
    assert target.radius_meters == 300
    assert target.dwell_minutes == 5

    event = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
    )
    assert event.candidate is not None

    async with transaction_session(session_factory) as session:
        day = await session.scalar(
            select(TripDay)
            .options(selectinload(TripDay.items))
            .where(TripDay.trip_id == trip_id)
        )
        targets, pending = await DetectionService(session).build_state(day)

    assert pending is not None
    assert pending.model_dump(mode="json") == event.candidate.model_dump(mode="json")
    assert targets == []


# --- US2: 무응답 자동 확정과 되돌리기 (T020) ---


async def _add_item(
    factory: async_sessionmaker[AsyncSession],
    trip_id: uuid.UUID,
    *,
    sequence: int,
    status: str,
    longitude: float = 127.01,
    latitude: float = 37.51,
) -> uuid.UUID:
    async with transaction_session(factory) as session:
        day = await session.scalar(
            select(TripDay).where(TripDay.trip_id == trip_id)
        )
        place = Place(
            tour_content_id=f"detection-{uuid.uuid4()}",
            name="추가 장소",
            category="OTHER",
            location=WKTElement(f"POINT({longitude} {latitude})", srid=4326),
        )
        session.add(place)
        await session.flush()
        item = ItineraryItem(
            trip_day_id=day.trip_day_id,
            place_id=place.place_id,
            sequence=sequence,
            status=status,
            planned_stay_minutes=30,
            stay_source="USER_ADJUSTED",
        )
        session.add(item)
        await session.flush()
        return item.item_id


async def _finalize(
    factory: async_sessionmaker[AsyncSession],
    trip_id: uuid.UUID,
    *,
    now: datetime,
) -> None:
    async with transaction_session(factory) as session:
        day = await session.scalar(
            select(TripDay)
            .options(selectinload(TripDay.items))
            .where(TripDay.trip_id == trip_id)
        )
        await DetectionService(session).finalize_due_candidates(day, now=now)


async def _auto_confirm_arrival(
    factory: async_sessionmaker[AsyncSession],
    *,
    trip_id: uuid.UUID,
    item_id: uuid.UUID,
    user_id: uuid.UUID,
    now: datetime,
) -> tuple[uuid.UUID, datetime]:
    created = await _register_arrival(
        factory, trip_id=trip_id, item_id=item_id, user_id=user_id, now=now
    )
    assert created.candidate is not None
    finalize_at = created.candidate.auto_finalize_at
    await _finalize(factory, trip_id, now=finalize_at + timedelta(seconds=1))
    return created.candidate.transition_id, finalize_at


@pytest.mark.asyncio
async def test_lazy_finalize_auto_confirms_expired_candidate_by_stored_time(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    created = await _register_arrival(
        session_factory, trip_id=trip_id, item_id=item_id, user_id=user_id, now=now
    )
    assert created.candidate is not None
    finalize_at = created.candidate.auto_finalize_at

    # 실행 시각을 확정 예정보다 한참 뒤로 둬도 확정·되돌리기 시각은 저장된 값 기준이다(SC-007).
    await _finalize(session_factory, trip_id, now=finalize_at + timedelta(hours=3))

    async with session_factory() as session:
        item = await session.get(ItineraryItem, item_id)
        day = await session.scalar(
            select(TripDay).where(TripDay.trip_id == trip_id)
        )
        transition = await session.get(
            ProgressTransition, created.candidate.transition_id
        )

    assert item is not None and item.status == "ARRIVED"
    assert day is not None and day.progress_version == 2
    assert transition is not None
    assert transition.status == "AUTO_CONFIRMED"
    assert transition.source == "GEOFENCE_AUTO"
    assert transition.confirmed_at == finalize_at
    assert transition.undo_deadline == finalize_at + timedelta(
        minutes=UNDO_WINDOW_MINUTES
    )

    async with transaction_session(session_factory) as session:
        stored = await session.get(
            ProgressTransition, created.candidate.transition_id
        )
        stored.undo_deadline = datetime.now(UTC) - timedelta(seconds=1)
    async with session_factory() as session:
        progress = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )

    assert progress.undoable is None
    assert progress.items[0].processing_source == "AUTO"


@pytest.mark.asyncio
async def test_lazy_finalize_skips_candidate_when_item_already_handled(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    created = await _register_arrival(
        session_factory, trip_id=trip_id, item_id=item_id, user_id=user_id, now=now
    )
    assert created.candidate is not None
    async with transaction_session(session_factory) as session:
        item = await session.get(ItineraryItem, item_id)
        item.status = "ARRIVED"

    await _finalize(
        session_factory,
        trip_id,
        now=created.candidate.auto_finalize_at + timedelta(minutes=1),
    )

    async with session_factory() as session:
        transition = await session.get(
            ProgressTransition, created.candidate.transition_id
        )
    assert transition is not None and transition.status == "CANCELLED"


@pytest.mark.asyncio
async def test_undo_restores_item_and_reports_resume_time(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    transition_id, finalize_at = await _auto_confirm_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
    )
    undone_at = finalize_at + timedelta(minutes=2)

    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).undo_transition(
            user_id=user_id,
            transition_id=transition_id,
            idempotency_key=uuid.uuid4(),
            undone_at=undone_at,
        )
    async with session_factory() as session:
        item = await session.get(ItineraryItem, item_id)
        day = await session.scalar(
            select(TripDay).where(TripDay.trip_id == trip_id)
        )
        transition = await session.get(ProgressTransition, transition_id)

    assert result.status == "UNDONE"
    assert result.day_status is None
    assert result.detection_resume_at == undone_at + timedelta(
        minutes=REPROMPT_DELAY_MINUTES
    )
    assert [
        (i.item_id, i.before_status, i.after_status) for i in result.restored_items
    ] == [(item_id, "ARRIVED", "EN_ROUTE")]
    assert item is not None and item.status == "EN_ROUTE"
    assert item.actual_arrived_at is None
    assert day is not None and day.progress_version == 3
    assert transition is not None
    assert transition.status == "UNDONE"
    assert transition.undone_at == undone_at


@pytest.mark.asyncio
async def test_undo_restores_composite_transition_as_one_unit(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, first_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    second_id = await _add_item(
        session_factory, trip_id, sequence=2, status="EN_ROUTE"
    )
    transition_id, finalize_at = await _auto_confirm_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=second_id,
        user_id=user_id,
        now=now,
    )
    async with session_factory() as session:
        transition = await session.get(ProgressTransition, transition_id)
        confirmed_progress = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )
    assert transition is not None and transition.transition_type == "COMPOSITE"
    assert {
        item.item_id: item.processing_source for item in confirmed_progress.items
    } == {first_id: "AUTO", second_id: "AUTO"}

    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).undo_transition(
            user_id=user_id,
            transition_id=transition_id,
            idempotency_key=uuid.uuid4(),
            undone_at=finalize_at + timedelta(minutes=1),
        )
    async with session_factory() as session:
        first = await session.get(ItineraryItem, first_id)
        second = await session.get(ItineraryItem, second_id)
        restored_progress = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )

    assert first is not None and first.status == "ARRIVED"
    assert second is not None and second.status == "EN_ROUTE"
    assert {(i.item_id, i.after_status) for i in result.restored_items} == {
        (first_id, "ARRIVED"),
        (second_id, "EN_ROUTE"),
    }
    assert all(
        item.processing_source is None for item in restored_progress.items
    )


@pytest.mark.asyncio
async def test_undo_releases_day_completion(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    transition_id, finalize_at = await _auto_confirm_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
    )
    async with session_factory() as session:
        completed = await session.scalar(
            select(TripDay).where(TripDay.trip_id == trip_id)
        )
    assert completed is not None and completed.status == "COMPLETED"
    assert completed.detection_active is False

    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).undo_transition(
            user_id=user_id,
            transition_id=transition_id,
            idempotency_key=uuid.uuid4(),
            undone_at=finalize_at + timedelta(minutes=1),
        )
    async with session_factory() as session:
        day = await session.scalar(
            select(TripDay).where(TripDay.trip_id == trip_id)
        )
        item = await session.get(ItineraryItem, item_id)

    assert result.day_status == "IN_PROGRESS"
    assert day is not None and day.status == "IN_PROGRESS"
    assert day.detection_active is True
    assert day.completed_at is None
    assert item is not None and item.status == "EN_ROUTE"


@pytest.mark.asyncio
async def test_undo_after_deadline_returns_conflict(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    transition_id, finalize_at = await _auto_confirm_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
    )

    async with transaction_session(session_factory) as session:
        with pytest.raises(AppError) as expired:
            await DetectionService(session).undo_transition(
                user_id=user_id,
                transition_id=transition_id,
                idempotency_key=uuid.uuid4(),
                undone_at=finalize_at + timedelta(minutes=UNDO_WINDOW_MINUTES),
            )

    assert expired.value.status_code == 409
    assert expired.value.code == "UNDO_WINDOW_EXPIRED"


@pytest.mark.asyncio
async def test_undo_rejects_user_confirmed_transition(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    created = await _register_arrival(
        session_factory, trip_id=trip_id, item_id=item_id, user_id=user_id, now=now
    )
    assert created.candidate is not None
    async with transaction_session(session_factory) as session:
        await DetectionService(session).decide_transition(
            user_id=user_id,
            transition_id=created.candidate.transition_id,
            decision="CONFIRM",
            idempotency_key=uuid.uuid4(),
            decided_at=now,
        )

    async with transaction_session(session_factory) as session:
        with pytest.raises(AppError) as not_undoable:
            await DetectionService(session).undo_transition(
                user_id=user_id,
                transition_id=created.candidate.transition_id,
                idempotency_key=uuid.uuid4(),
                undone_at=now + timedelta(minutes=1),
            )

    assert not_undoable.value.status_code == 409
    assert not_undoable.value.code == "TRANSITION_NOT_UNDOABLE"


@pytest.mark.asyncio
async def test_undo_is_idempotent_per_key(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    transition_id, finalize_at = await _auto_confirm_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
    )
    key = uuid.uuid4()
    undone_at = finalize_at + timedelta(minutes=1)

    async with transaction_session(session_factory) as session:
        first = await DetectionService(session).undo_transition(
            user_id=user_id,
            transition_id=transition_id,
            idempotency_key=key,
            undone_at=undone_at,
        )
    async with transaction_session(session_factory) as session:
        replay = await DetectionService(session).undo_transition(
            user_id=user_id,
            transition_id=transition_id,
            idempotency_key=key,
            undone_at=undone_at + timedelta(minutes=1),
        )
    assert replay == first

    async with transaction_session(session_factory) as session:
        with pytest.raises(AppError) as other_key:
            await DetectionService(session).undo_transition(
                user_id=user_id,
                transition_id=transition_id,
                idempotency_key=uuid.uuid4(),
                undone_at=undone_at,
            )
    assert other_key.value.code == "TRANSITION_NOT_UNDOABLE"


@pytest.mark.asyncio
async def test_detection_pauses_until_resume_then_counts_toward_limit(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    transition_id, finalize_at = await _auto_confirm_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
    )
    undone_at = finalize_at + timedelta(minutes=1)
    async with transaction_session(session_factory) as session:
        await DetectionService(session).undo_transition(
            user_id=user_id,
            transition_id=transition_id,
            idempotency_key=uuid.uuid4(),
            undone_at=undone_at,
        )

    paused = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=undone_at + timedelta(minutes=9, seconds=59),
    )
    assert paused.accepted is False
    assert paused.rejection_reason == "DETECTION_PAUSED"
    assert paused.candidate is None

    resumed = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=undone_at + timedelta(minutes=10),
    )
    assert resumed.candidate is not None

    # 자동 확정 1회 + 재개 후 질문 1회 = 장소별 상한(2회) 도달 (FR-017a·SC-011).
    async with transaction_session(session_factory) as session:
        await DetectionService(session).decide_transition(
            user_id=user_id,
            transition_id=resumed.candidate.transition_id,
            decision="NOT_ARRIVED",
            idempotency_key=uuid.uuid4(),
            decided_at=undone_at + timedelta(minutes=10),
        )
    limited = await _register_arrival(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=undone_at + timedelta(minutes=30),
    )
    assert limited.accepted is False
    assert limited.rejection_reason == "PROMPT_LIMIT_REACHED"


@pytest.mark.asyncio
async def test_get_day_lazy_finalizes_and_reports_undoable(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    created = await _register_arrival(
        session_factory, trip_id=trip_id, item_id=item_id, user_id=user_id, now=now
    )
    assert created.candidate is not None
    async with transaction_session(session_factory) as session:
        transition = await session.get(
            ProgressTransition, created.candidate.transition_id
        )
        transition.auto_finalize_at = datetime.now(UTC) - timedelta(minutes=1)

    async with transaction_session(session_factory) as session:
        data = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )

    assert data.pending_candidate is None
    assert data.undoable is not None
    assert data.undoable.transition_id == created.candidate.transition_id
    assert data.undoable.type == "ARRIVAL"
    arrived = next(i for i in data.items if i.item_id == item_id)
    assert arrived.status == "ARRIVED"

    async with transaction_session(session_factory) as session:
        transition = await session.get(
            ProgressTransition, created.candidate.transition_id
        )
        transition.undo_deadline = datetime.now(UTC) - timedelta(seconds=1)
    async with transaction_session(session_factory) as session:
        later = await ProgressService(session).get_day(
            trip_id=trip_id, visit_date=now.date()
        )
    assert later.undoable is None


# --- US3: 출발 감지와 오판 대응 (T026) ---


async def _auto_confirm_departure(
    factory: async_sessionmaker[AsyncSession],
    *,
    trip_id: uuid.UUID,
    item_id: uuid.UUID,
    user_id: uuid.UUID,
    now: datetime,
) -> tuple[uuid.UUID, datetime]:
    created = await _register(
        factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
        event_type="EXIT",
    )
    assert created.candidate is not None
    finalize_at = created.candidate.auto_finalize_at
    await _finalize(factory, trip_id, now=finalize_at + timedelta(seconds=1))
    return created.candidate.transition_id, finalize_at


@pytest.mark.asyncio
async def test_exit_creates_departure_candidate(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")

    created = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
        event_type="EXIT",
    )

    assert created.accepted is True
    assert created.candidate is not None
    assert created.candidate.type == "DEPARTURE"
    assert created.candidate.allowed_decisions == ["CONFIRM", "STILL_HERE"]
    assert created.candidate.evidence.dwell_minutes is None
    assert created.candidate.auto_finalize_at == now + timedelta(minutes=5)

    async with session_factory() as session:
        transition = await session.get(
            ProgressTransition, created.candidate.transition_id
        )
    assert transition is not None
    assert transition.transition_type == "DEPARTURE"
    assert transition.source == "GEOFENCE_EXIT"


@pytest.mark.asyncio
async def test_reenter_cancels_pending_departure_candidate(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    created = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
        event_type="EXIT",
    )
    assert created.candidate is not None

    reentered = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now + timedelta(seconds=30),
        event_type="REENTER",
    )

    assert reentered.accepted is True
    assert reentered.cancelled_transition_id == created.candidate.transition_id
    async with session_factory() as session:
        transition = await session.get(
            ProgressTransition, created.candidate.transition_id
        )
        item = await session.get(ItineraryItem, item_id)
    assert transition is not None and transition.status == "CANCELLED"
    assert item is not None and item.status == "ARRIVED"


@pytest.mark.asyncio
async def test_still_here_stops_departure_detection_for_the_day(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")
    created = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now,
        event_type="EXIT",
    )
    assert created.candidate is not None

    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).decide_transition(
            user_id=user_id,
            transition_id=created.candidate.transition_id,
            decision="STILL_HERE",
            idempotency_key=uuid.uuid4(),
            decided_at=now,
        )
    assert result.status == "CANCELLED"
    assert result.affected_items == []
    assert result.next_prompt_at is None

    async with session_factory() as session:
        item = await session.get(ItineraryItem, item_id)
    assert item is not None and item.status == "ARRIVED"

    blocked = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now + timedelta(hours=1),
        event_type="EXIT",
    )
    assert blocked.accepted is False
    assert blocked.rejection_reason == "DEPARTURE_DETECTION_STOPPED"
    assert blocked.candidate is None

    async with transaction_session(session_factory) as session:
        day = await session.scalar(
            select(TripDay)
            .options(selectinload(TripDay.items))
            .where(TripDay.trip_id == trip_id)
        )
        targets, _ = await DetectionService(session).build_state(day)
    assert all(t.item_id != item_id for t in targets)


@pytest.mark.asyncio
async def test_departure_confirm_completes_and_advances(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, first_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    second_id = await _add_item(
        session_factory, trip_id, sequence=2, status="PLANNED"
    )
    created = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=first_id,
        user_id=user_id,
        now=now,
        event_type="EXIT",
    )
    assert created.candidate is not None

    async with transaction_session(session_factory) as session:
        result = await DetectionService(session).decide_transition(
            user_id=user_id,
            transition_id=created.candidate.transition_id,
            decision="CONFIRM",
            idempotency_key=uuid.uuid4(),
            decided_at=now,
        )
    async with session_factory() as session:
        first = await session.get(ItineraryItem, first_id)
        second = await session.get(ItineraryItem, second_id)

    assert result.status == "CONFIRMED"
    assert result.undo_deadline is None
    assert first is not None and first.status == "COMPLETED"
    assert second is not None and second.status == "EN_ROUTE"


@pytest.mark.asyncio
async def test_no_response_auto_departure_advances_and_is_undoable(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, first_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    second_id = await _add_item(
        session_factory, trip_id, sequence=2, status="PLANNED"
    )
    transition_id, finalize_at = await _auto_confirm_departure(
        session_factory,
        trip_id=trip_id,
        item_id=first_id,
        user_id=user_id,
        now=now,
    )

    async with session_factory() as session:
        first = await session.get(ItineraryItem, first_id)
        second = await session.get(ItineraryItem, second_id)
        transition = await session.get(ProgressTransition, transition_id)

    assert first is not None and first.status == "COMPLETED"
    assert second is not None and second.status == "EN_ROUTE"
    assert transition is not None
    assert transition.status == "AUTO_CONFIRMED"
    assert transition.source == "GEOFENCE_AUTO"
    assert transition.undo_deadline == finalize_at + timedelta(
        minutes=UNDO_WINDOW_MINUTES
    )


@pytest.mark.asyncio
async def test_second_departure_undo_stops_detection_for_the_day(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, first_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")

    transition_id, finalize_at = await _auto_confirm_departure(
        session_factory,
        trip_id=trip_id,
        item_id=first_id,
        user_id=user_id,
        now=now,
    )
    undo_one = finalize_at + timedelta(minutes=1)
    async with transaction_session(session_factory) as session:
        await DetectionService(session).undo_transition(
            user_id=user_id,
            transition_id=transition_id,
            idempotency_key=uuid.uuid4(),
            undone_at=undo_one,
        )

    paused = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=first_id,
        user_id=user_id,
        now=undo_one + timedelta(minutes=5),
        event_type="EXIT",
    )
    assert paused.rejection_reason == "DETECTION_PAUSED"

    resume = undo_one + timedelta(minutes=REPROMPT_DELAY_MINUTES)
    second = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=first_id,
        user_id=user_id,
        now=resume,
        event_type="EXIT",
    )
    assert second.candidate is not None
    await _finalize(session_factory, trip_id, now=resume + timedelta(minutes=6))
    async with transaction_session(session_factory) as session:
        await DetectionService(session).undo_transition(
            user_id=user_id,
            transition_id=second.candidate.transition_id,
            idempotency_key=uuid.uuid4(),
            undone_at=resume + timedelta(minutes=7),
        )

    stopped = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=first_id,
        user_id=user_id,
        now=resume + timedelta(hours=1),
        event_type="EXIT",
    )
    assert stopped.accepted is False
    assert stopped.rejection_reason == "DEPARTURE_DETECTION_STOPPED"
    assert stopped.candidate is None


@pytest.mark.asyncio
async def test_manual_departure_then_late_exit_creates_no_candidate(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(
        session_factory, item_status="ARRIVED"
    )
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")

    async with transaction_session(session_factory) as session:
        await ProgressService(session).update_item_status(
            user_id=user_id,
            item_id=item_id,
            target_status="COMPLETED",
            progress_version=1,
            idempotency_key=uuid.uuid4(),
        )

    late = await _register(
        session_factory,
        trip_id=trip_id,
        item_id=item_id,
        user_id=user_id,
        now=now + timedelta(minutes=1),
        event_type="EXIT",
    )
    assert late.accepted is False
    assert late.rejection_reason == "ITEM_NOT_ELIGIBLE"
    assert late.candidate is None
    async with session_factory() as session:
        pending = await session.scalar(
            select(func.count())
            .select_from(ProgressTransition)
            .where(
                ProgressTransition.primary_item_id == item_id,
                ProgressTransition.status == "PENDING_CONFIRMATION",
            )
        )
    assert pending == 0


# --- US4: 정확도가 계속 미달이면 후보가 없고 수동은 정상 (T032) ---


@pytest.mark.asyncio
async def test_persistent_low_accuracy_never_creates_candidate_manual_still_works(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, item_id, user_id, now = await _seed(session_factory)
    await _add_item(session_factory, trip_id, sequence=2, status="PLANNED")

    for minute in range(4):
        async with transaction_session(session_factory) as session:
            result = await DetectionService(session).register_event(
                user_id=user_id,
                trip_id=trip_id,
                visit_date=now.date(),
                payload=_payload(
                    item_id, now + timedelta(minutes=minute), accuracy=250
                ),
                received_at=now + timedelta(minutes=minute),
            )
        assert result.accepted is False
        assert result.rejection_reason == "LOW_ACCURACY"
        assert result.candidate is None

    async with session_factory() as session:
        transitions = await session.scalar(
            select(func.count())
            .select_from(ProgressTransition)
            .where(ProgressTransition.primary_item_id == item_id)
        )
    assert transitions == 0

    async with transaction_session(session_factory) as session:
        data = await ProgressService(session).update_item_status(
            user_id=user_id,
            item_id=item_id,
            target_status="ARRIVED",
            progress_version=1,
            idempotency_key=uuid.uuid4(),
        )
    assert data.progress_version == 2
    async with session_factory() as session:
        item = await session.get(ItineraryItem, item_id)
    assert item is not None and item.status == "ARRIVED"
