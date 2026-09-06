"""일정 저장 service의 도메인 규칙 단위 테스트."""

import uuid
from datetime import date

import pytest

from app.api.errors import AppError
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.schemas.itinerary import SaveDayItineraryRequest
from app.services.itinerary import _desired_item, _validate_items, _validate_locked_items


def _payload(items: list[dict]) -> SaveDayItineraryRequest:
    return SaveDayItineraryRequest(version=0, items=items)


def _new_item(sequence: int, transport: str | None = None) -> dict:
    return {
        "itemId": None,
        "placeId": f"tourapi:{sequence}",
        "place": {
            "name": f"장소 {sequence}",
            "category": "OTHER",
            "tourApiCategory": None,
            "address": None,
            "latitude": 37.5,
            "longitude": 127.0,
            "imageUrl": None,
        },
        "sequence": sequence,
        "plannedStayMinutes": 60,
        "staySource": "RECOMMENDED",
        "transportModeToNext": transport,
    }


def test_validate_items_accepts_contiguous_sequence_and_transport_boundary() -> None:
    items = _payload([_new_item(1, "WALK"), _new_item(2)]).items

    _validate_items(items)


@pytest.mark.parametrize(
    "items",
    [
        [_new_item(2)],
        [_new_item(1, "WALK")],
        [_new_item(1), _new_item(2)],
    ],
)
def test_validate_items_returns_typed_violations(items: list[dict]) -> None:
    with pytest.raises(AppError) as error:
        _validate_items(_payload(items).items)

    assert error.value.status_code == 422
    assert error.value.code == "INVALID_ITINERARY"
    assert error.value.details["violations"]


def test_new_item_id_is_stable_per_key_and_sequence() -> None:
    item = _payload([_new_item(1)]).items[0]
    day_id = uuid.uuid4()
    key = uuid.uuid4()
    place_id = uuid.uuid4()

    first = _desired_item(item, day_id, key, place_id, status="PLANNED")
    second = _desired_item(item, day_id, key, place_id, status="PLANNED")

    assert isinstance(first, ItineraryItem)
    assert first.item_id == second.item_id == uuid.uuid5(day_id, f"{key}:1")


@pytest.mark.parametrize(
    ("place_id", "transport", "sequence", "included"),
    [
        ("tourapi:54321", "WALK", 1, True),
        ("tourapi:12345", None, 1, True),
        ("tourapi:12345", "WALK", 2, True),
        ("tourapi:12345", "WALK", 1, False),
    ],
)
def test_processed_item_rejects_locked_changes(
    place_id: str, transport: str | None, sequence: int, included: bool
) -> None:
    item_id = uuid.uuid4()
    stored = ItineraryItem(
        item_id=item_id,
        trip_day_id=uuid.uuid4(),
        place_id=uuid.uuid4(),
        sequence=1,
        status="COMPLETED",
        planned_stay_minutes=60,
        stay_source="RECOMMENDED",
        transport_mode_to_next="WALK",
    )
    stored.place = Place(
        place_id=stored.place_id,
        tour_content_id="12345",
        name="처리된 장소",
        category="OTHER",
        location="POINT(127 37)",
    )
    day = TripDay(
        trip_day_id=stored.trip_day_id,
        trip_id=uuid.uuid4(),
        visit_date=date(2026, 9, 1),
        day_number=1,
        schedule_version=1,
        items=[stored],
    )
    incoming = (
        _payload(
            [
                {
                    **_new_item(sequence, transport),
                    "itemId": str(item_id),
                    "placeId": place_id,
                    "place": None,
                }
            ]
        ).items
        if included
        else []
    )

    with pytest.raises(AppError) as error:
        _validate_locked_items(day, incoming)

    assert error.value.code == "ITINERARY_ITEM_LOCKED"
    assert error.value.details == {"itemId": str(item_id)}
