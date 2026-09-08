"""F006 수동 진행 전환·상태 수정의 파생 규칙과 불변식을 검증한다."""

import uuid
from datetime import UTC, date, datetime, timedelta, timezone
from unittest.mock import AsyncMock

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError
from app.models.itinerary import ItineraryItem, TripDay
from app.schemas.progress import StartDayProgressRequest
from app.services.progress import ProgressService, apply_manual_transition


def _item(sequence: int, status: str) -> ItineraryItem:
    return ItineraryItem(
        item_id=uuid.uuid4(), trip_day_id=uuid.uuid4(), place_id=uuid.uuid4(),
        sequence=sequence, status=status, planned_stay_minutes=30,
        stay_source="RECOMMENDED", transport_mode_to_next="WALK",
    )


def _day(items: list[ItineraryItem]) -> TripDay:
    return TripDay(
        trip_day_id=items[0].trip_day_id, trip_id=uuid.uuid4(),
        visit_date=date(2026, 9, 8), day_number=1, status="IN_PROGRESS",
        schedule_version=1, progress_version=1, detection_active=True,
        items=items,
    )


@pytest.mark.asyncio
async def test_get_unstored_day_returns_empty_not_started_progress() -> None:
    trip_id = uuid.uuid4()
    visit_date = date(2026, 9, 8)
    session = AsyncMock(spec=AsyncSession)
    session.scalar.return_value = None

    result = await ProgressService(session).get_day(
        trip_id=trip_id,
        visit_date=visit_date,
    )

    assert result.trip_id == trip_id
    assert result.date == visit_date
    assert result.day_status == "NOT_STARTED"
    assert result.progress_version == 0
    assert result.schedule_version == 0
    assert result.items == []


@pytest.mark.asyncio
async def test_start_unstored_day_is_rejected_as_empty() -> None:
    session = AsyncMock(spec=AsyncSession)
    session.scalar.return_value = None
    today = datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date()

    with pytest.raises(AppError) as error:
        await ProgressService(session).start_day(
            trip_id=uuid.uuid4(),
            visit_date=today,
            payload=StartDayProgressRequest.model_validate({"progressVersion": 0}),
            idempotency_key=uuid.uuid4(),
        )

    assert error.value.status_code == 422
    assert error.value.code == "DAY_EMPTY"


def test_arrive_records_actual_time_and_completes_last_remaining_item() -> None:
    now = datetime(2026, 9, 8, 1, tzinfo=UTC)
    item = _item(1, "EN_ROUTE")
    day = _day([item])

    affected, transition_type = apply_manual_transition(day, item, "ARRIVED", now)

    assert item.status == "ARRIVED"
    assert item.actual_arrived_at == now
    assert day.status == "COMPLETED"
    assert day.completed_at == now
    assert day.detection_active is False
    assert transition_type == "ARRIVE"
    assert affected == [
        {"itemId": str(item.item_id), "beforeStatus": "EN_ROUTE", "afterStatus": "ARRIVED"},
        {"dayStatusBefore": "IN_PROGRESS", "dayStatusAfter": "COMPLETED"},
    ]


def test_depart_completes_arrived_item_and_starts_next_planned_item() -> None:
    now = datetime(2026, 9, 8, 2, tzinfo=UTC)
    arrived, planned = _item(1, "ARRIVED"), _item(2, "PLANNED")
    planned.trip_day_id = arrived.trip_day_id
    day = _day([arrived, planned])

    affected, transition_type = apply_manual_transition(day, arrived, "COMPLETED", now)

    assert arrived.status == "COMPLETED"
    assert arrived.actual_departed_at == arrived.completed_at == now
    assert planned.status == "EN_ROUTE"
    assert day.status == "IN_PROGRESS"
    assert transition_type == "DEPART"
    assert len(affected) == 2


def test_skip_en_route_starts_next_and_can_complete_day() -> None:
    now = datetime(2026, 9, 8, 3, tzinfo=UTC)
    current, next_item = _item(1, "EN_ROUTE"), _item(2, "PLANNED")
    next_item.trip_day_id = current.trip_day_id
    day = _day([current, next_item])

    _, transition_type = apply_manual_transition(day, current, "SKIPPED", now)

    assert current.status == "SKIPPED"
    assert next_item.status == "EN_ROUTE"
    assert day.status == "IN_PROGRESS"
    assert transition_type == "SKIP"

    apply_manual_transition(day, next_item, "SKIPPED", now)
    assert day.status == "COMPLETED"
    assert day.completed_at == now


@pytest.mark.parametrize(
    ("current", "target"),
    [("PLANNED", "COMPLETED"), ("ARRIVED", "ARRIVED"), ("COMPLETED", "SKIPPED")],
)
def test_invalid_us2_transition_is_rejected(current: str, target: str) -> None:
    item = _item(1, current)
    day = _day([item])

    with pytest.raises(AppError) as error:
        apply_manual_transition(day, item, target, datetime.now(UTC))

    assert error.value.code == "INVALID_STATUS_TRANSITION"


def test_completed_day_rejects_departure_from_last_arrived_item() -> None:
    item = _item(1, "ARRIVED")
    day = _day([item])
    day.status = "COMPLETED"

    with pytest.raises(AppError) as error:
        apply_manual_transition(day, item, "COMPLETED", datetime.now(UTC))

    assert error.value.code == "INVALID_STATUS_TRANSITION"


def test_undo_complete_restores_day_and_resets_following_items() -> None:
    now = datetime(2026, 9, 8, 4, tzinfo=UTC)
    completed, following = _item(1, "COMPLETED"), _item(2, "SKIPPED")
    following.trip_day_id = completed.trip_day_id
    completed.actual_arrived_at = datetime(2026, 9, 8, 2, tzinfo=UTC)
    completed.actual_departed_at = completed.completed_at = datetime(
        2026, 9, 8, 3, tzinfo=UTC
    )
    following.actual_arrived_at = following.actual_departed_at = now
    following.completed_at = now
    day = _day([completed, following])
    day.status = "COMPLETED"
    day.completed_at = now
    day.detection_active = False

    affected, transition_type = apply_manual_transition(
        day, completed, "ARRIVED", now
    )

    assert transition_type == "UNDO_COMPLETE"
    assert completed.status == "ARRIVED"
    assert completed.actual_arrived_at == datetime(2026, 9, 8, 2, tzinfo=UTC)
    assert completed.actual_departed_at is None
    assert completed.completed_at is None
    assert following.status == "PLANNED"
    assert following.actual_arrived_at is None
    assert following.actual_departed_at is None
    assert following.completed_at is None
    assert day.status == "IN_PROGRESS"
    assert day.completed_at is None
    assert day.detection_active is True
    assert len(affected) == 3


def test_undo_skip_starts_first_planned_item_and_restores_day() -> None:
    now = datetime(2026, 9, 8, 5, tzinfo=UTC)
    skipped, planned = _item(1, "SKIPPED"), _item(2, "PLANNED")
    planned.trip_day_id = skipped.trip_day_id
    day = _day([skipped, planned])
    day.status = "COMPLETED"
    day.completed_at = now
    day.detection_active = False

    affected, transition_type = apply_manual_transition(
        day, skipped, "PLANNED", now
    )

    assert transition_type == "UNDO_SKIP"
    assert skipped.status == "EN_ROUTE"
    assert planned.status == "PLANNED"
    assert day.status == "IN_PROGRESS"
    assert day.completed_at is None
    assert day.detection_active is True
    assert affected[0]["beforeStatus"] == "SKIPPED"
    assert affected[-1] == {
        "dayStatusBefore": "COMPLETED",
        "dayStatusAfter": "IN_PROGRESS",
    }


def test_change_planned_to_arrived_reconciles_all_other_progress() -> None:
    now = datetime(2026, 9, 8, 6, tzinfo=UTC)
    arrived = _item(1, "ARRIVED")
    en_route = _item(2, "EN_ROUTE")
    target = _item(3, "PLANNED")
    following = _item(4, "COMPLETED")
    for item in (en_route, target, following):
        item.trip_day_id = arrived.trip_day_id
    arrived.actual_arrived_at = datetime(2026, 9, 8, 3, tzinfo=UTC)
    following.actual_arrived_at = following.actual_departed_at = now
    following.completed_at = now
    day = _day([arrived, en_route, target, following])

    affected, transition_type = apply_manual_transition(
        day, target, "ARRIVED", now
    )

    assert transition_type == "ARRIVE"
    assert arrived.status == "COMPLETED"
    assert arrived.actual_departed_at == arrived.completed_at == now
    assert en_route.status == "PLANNED"
    assert target.status == "ARRIVED"
    assert target.actual_arrived_at == now
    assert following.status == "PLANNED"
    assert following.actual_arrived_at is None
    assert following.actual_departed_at is None
    assert following.completed_at is None
    assert sum(item.status == "ARRIVED" for item in day.items) == 1
    assert sum(item.status == "EN_ROUTE" for item in day.items) == 0
