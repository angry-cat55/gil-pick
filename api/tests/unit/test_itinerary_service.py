"""일정 저장 service의 도메인 규칙 단위 테스트."""

import uuid

import pytest

from app.api.errors import AppError
from app.models.itinerary import ItineraryItem
from app.schemas.itinerary import SaveDayItineraryRequest
from app.services.itinerary import _desired_item, _validate_items


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

    first = _desired_item(item, day_id, key, place_id)
    second = _desired_item(item, day_id, key, place_id)

    assert isinstance(first, ItineraryItem)
    assert first.item_id == second.item_id == uuid.uuid5(day_id, f"{key}:1")
