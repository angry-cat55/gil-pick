"""F006 수동 진행 전환의 파생 규칙과 불변식을 검증한다."""

import uuid
from datetime import UTC, date, datetime

import pytest

from app.api.errors import AppError
from app.models.itinerary import ItineraryItem, TripDay
from app.services.progress import apply_manual_transition


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
