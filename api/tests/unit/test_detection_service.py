"""위치 감지 후보 생성의 파생 판정 규칙 단위 테스트."""

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.models.itinerary import ItineraryItem, TripDay
from app.models.progress import ProgressTransition
from app.schemas.progress import ProgressEventRequest
from app.services import detection


NOW = datetime(2026, 9, 8, 3, 0, tzinfo=UTC)


def _transition(
    *,
    status: str = "CANCELLED",
    decision: str | None = None,
    cancelled_at: datetime | None = None,
    undone_at: datetime | None = None,
    transition_type: str = "ARRIVAL",
) -> ProgressTransition:
    return ProgressTransition(
        transition_type=transition_type,
        status=status,
        decision=decision,
        cancelled_at=cancelled_at,
        undone_at=undone_at,
    )


def _arrival_payload(item_id: uuid.UUID) -> ProgressEventRequest:
    return ProgressEventRequest.model_validate(
        {
            "eventId": str(uuid.uuid4()),
            "eventType": "DWELL",
            "itemId": str(item_id),
            "geofenceId": f"{item_id}:ARRIVAL",
            "occurredAt": NOW.isoformat(),
            "location": {
                "latitude": 37.5,
                "longitude": 127.0,
                "accuracyMeters": 20,
            },
        }
    )


def test_arrival_prompt_limit_is_two() -> None:
    transitions = [_transition(), _transition(status="CONFIRMED")]

    assert detection.MAX_ARRIVAL_PROMPTS == 2
    assert (
        detection.DetectionService._arrival_block_reason(transitions, NOW)
        == "PROMPT_LIMIT_REACHED"
    )


def test_existing_pending_candidate_blocks_duplicate() -> None:
    transitions = [_transition(status="PENDING_CONFIRMATION")]

    assert (
        detection.DetectionService._arrival_block_reason(transitions, NOW)
        == "DETECTION_PAUSED"
    )


def test_not_arrived_pauses_reprompt_before_ten_minutes() -> None:
    transitions = [
        _transition(
            decision="NOT_ARRIVED",
            cancelled_at=NOW - timedelta(minutes=10) + timedelta(microseconds=1),
        )
    ]

    assert detection.REPROMPT_DELAY_MINUTES == 10
    assert (
        detection.DetectionService._arrival_block_reason(transitions, NOW)
        == "DETECTION_PAUSED"
    )


def test_not_arrived_allows_reprompt_at_exact_boundary() -> None:
    transitions = [
        _transition(
            decision="NOT_ARRIVED",
            cancelled_at=NOW - timedelta(minutes=10),
        )
    ]

    assert detection.DetectionService._arrival_block_reason(transitions, NOW) is None


def _departure(**kwargs) -> ProgressTransition:
    kwargs.setdefault("transition_type", "DEPARTURE")
    return _transition(**kwargs)


def test_departure_pending_blocks_duplicate() -> None:
    transitions = [_departure(status="PENDING_CONFIRMATION")]

    assert (
        detection.DetectionService._departure_block_reason(transitions, NOW)
        == "DETECTION_PAUSED"
    )


def test_departure_still_here_stops_for_the_day() -> None:
    transitions = [_departure(decision="STILL_HERE")]

    assert (
        detection.DetectionService._departure_block_reason(transitions, NOW)
        == "DEPARTURE_DETECTION_STOPPED"
    )


def test_departure_two_undos_stop_for_the_day() -> None:
    transitions = [
        _departure(status="UNDONE", undone_at=NOW - timedelta(hours=2)),
        _departure(status="UNDONE", undone_at=NOW - timedelta(hours=1)),
    ]

    assert detection.DEPARTURE_UNDO_STOP_COUNT == 2
    assert (
        detection.DetectionService._departure_block_reason(transitions, NOW)
        == "DEPARTURE_DETECTION_STOPPED"
    )


def test_departure_single_undo_pauses_until_resume_then_allows() -> None:
    before = [_departure(status="UNDONE", undone_at=NOW - timedelta(minutes=9))]
    after = [_departure(status="UNDONE", undone_at=NOW - timedelta(minutes=10))]

    assert (
        detection.DetectionService._departure_block_reason(before, NOW)
        == "DETECTION_PAUSED"
    )
    assert detection.DetectionService._departure_block_reason(after, NOW) is None


@pytest.mark.parametrize("item_status", ["PLANNED", "ARRIVED", "COMPLETED", "SKIPPED"])
def test_arrival_event_only_accepts_en_route_item(item_status: str) -> None:
    item_id = uuid.uuid4()
    day_id = uuid.uuid4()
    day = TripDay(
        trip_day_id=day_id,
        status="IN_PROGRESS",
        detection_active=True,
    )
    item = ItineraryItem(
        item_id=item_id,
        trip_day_id=day_id,
        status=item_status,
    )

    reason = detection.DetectionService._rejection_reason(
        day, item, _arrival_payload(item_id), NOW
    )

    assert reason == "ITEM_NOT_ELIGIBLE"


def test_arrival_event_accepts_en_route_item_at_validation_boundaries() -> None:
    item_id = uuid.uuid4()
    day_id = uuid.uuid4()
    day = TripDay(
        trip_day_id=day_id,
        status="IN_PROGRESS",
        detection_active=True,
    )
    item = ItineraryItem(
        item_id=item_id,
        trip_day_id=day_id,
        status="EN_ROUTE",
    )
    payload = _arrival_payload(item_id)
    payload.location.accuracy_meters = detection.MAX_ACCURACY_METERS
    payload.occurred_at = NOW - timedelta(seconds=detection.MAX_EVENT_AGE_SECONDS)

    assert detection.DetectionService._rejection_reason(day, item, payload, NOW) is None
