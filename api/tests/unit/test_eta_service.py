"""F006 ETA 계산 규칙을 검증한다."""

import uuid
from datetime import UTC, datetime, timedelta

from app.models.itinerary import ItineraryItem
from app.services.eta import recalculate_eta


def _item(status: str, *, stay: int = 30) -> ItineraryItem:
    return ItineraryItem(
        item_id=uuid.uuid4(),
        trip_day_id=uuid.uuid4(),
        place_id=uuid.uuid4(),
        sequence=1,
        status=status,
        planned_stay_minutes=stay,
        stay_source="RECOMMENDED",
    )


def test_eta_uses_start_location_and_planned_route_then_accumulates_stay() -> None:
    started = datetime(2026, 9, 8, 9, tzinfo=UTC)
    first, second = _item("EN_ROUTE"), _item("PLANNED", stay=60)
    first.sequence, second.sequence = 1, 2
    durations = {(None, first.item_id): 600, (first.item_id, second.item_id): 900}

    recalculate_eta(
        [first, second],
        actual_started_at=started,
        route_durations=durations,
        progress_durations={},
        has_start_location=True,
    )

    assert first.estimated_arrival_at == started + timedelta(seconds=600)
    assert first.estimated_departure_at == first.estimated_arrival_at + timedelta(minutes=30)
    assert second.estimated_arrival_at == first.estimated_arrival_at + timedelta(minutes=30, seconds=900)


def test_eta_uses_zero_for_first_segment_without_start_location() -> None:
    started = datetime(2026, 9, 8, 9, tzinfo=UTC)
    item = _item("PLANNED")

    recalculate_eta(
        [item],
        actual_started_at=started,
        route_durations={},
        progress_durations={},
        has_start_location=False,
    )

    assert item.estimated_arrival_at == started


def test_eta_falls_back_to_progress_segment_and_propagates_unknown() -> None:
    started = datetime(2026, 9, 8, 9, tzinfo=UTC)
    first, second, third = (_item("EN_ROUTE"), _item("PLANNED"), _item("PLANNED"))
    for sequence, item in enumerate((first, second, third), start=1):
        item.sequence = sequence
    durations = {(None, first.item_id): 0}
    progress = {(first.item_id, second.item_id): 300}

    recalculate_eta(
        [first, second, third],
        actual_started_at=started,
        route_durations=durations,
        progress_durations=progress,
        has_start_location=False,
    )

    assert second.estimated_arrival_at == started + timedelta(minutes=30, seconds=300)
    assert third.estimated_arrival_at is None
    assert third.estimated_departure_at is None


def test_eta_uses_arrival_and_departure_actual_times_and_preserves_finished_items() -> None:
    started = datetime(2026, 9, 8, 9, tzinfo=UTC)
    completed, arrived, next_item, skipped = (
        _item("COMPLETED"), _item("ARRIVED"), _item("PLANNED"), _item("SKIPPED")
    )
    completed.sequence, arrived.sequence, next_item.sequence, skipped.sequence = 1, 2, 3, 4
    completed.actual_departed_at = started + timedelta(hours=1)
    arrived.actual_arrived_at = started + timedelta(hours=2)
    arrived.estimated_arrival_at = started + timedelta(hours=1, minutes=50)
    arrived.estimated_departure_at = started + timedelta(hours=2)
    skipped.estimated_arrival_at = started + timedelta(hours=4)
    skipped.estimated_departure_at = started + timedelta(hours=5)
    route = {(arrived.item_id, next_item.item_id): 600}

    recalculate_eta(
        [completed, arrived, next_item, skipped],
        actual_started_at=started,
        route_durations=route,
        progress_durations={},
        has_start_location=False,
    )

    assert arrived.estimated_arrival_at == started + timedelta(hours=1, minutes=50)
    assert next_item.estimated_arrival_at == started + timedelta(hours=2, minutes=40)
    assert skipped.estimated_arrival_at == started + timedelta(hours=4)
