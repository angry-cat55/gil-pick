"""F004 일정 DTO와 공개 계약의 단위 테스트."""

import uuid

import pytest
from pydantic import ValidationError

from app.schemas.itinerary import SaveDayItineraryRequest


def _item(**changes: object) -> dict[str, object]:
    item: dict[str, object] = {
        "itemId": None,
        "placeId": "tourapi:12345",
        "place": {
            "name": "경복궁",
            "category": "HISTORY_CULTURE",
            "tourApiCategory": {"large": "A02", "middle": None, "small": None},
            "address": "서울특별시 종로구",
            "latitude": 37.5796,
            "longitude": 126.977,
            "imageUrl": None,
        },
        "sequence": 1,
        "plannedStayMinutes": 90,
        "staySource": "RECOMMENDED",
        "transportModeToNext": None,
    }
    item.update(changes)
    return item


def test_save_request_accepts_contract_fields() -> None:
    request = SaveDayItineraryRequest.model_validate({"version": 0, "items": [_item()]})

    assert request.version == 0
    assert request.items[0].place_id == "tourapi:12345"
    assert request.items[0].planned_stay_minutes == 90


@pytest.mark.parametrize("minutes", [29, 45, 361])
def test_save_request_rejects_invalid_stay_minutes(minutes: int) -> None:
    with pytest.raises(ValidationError):
        SaveDayItineraryRequest.model_validate(
            {"version": 0, "items": [_item(plannedStayMinutes=minutes)]}
        )


@pytest.mark.parametrize("place_id", ["123", "kakao:123", "tourapi:"])
def test_save_request_rejects_invalid_place_id(place_id: str) -> None:
    with pytest.raises(ValidationError):
        SaveDayItineraryRequest.model_validate(
            {"version": 0, "items": [_item(placeId=place_id)]}
        )


def test_save_request_rejects_more_than_ten_items() -> None:
    items = [_item(sequence=index) for index in range(1, 11)]
    items.append(_item(sequence=10, itemId=str(uuid.uuid4())))

    with pytest.raises(ValidationError):
        SaveDayItineraryRequest.model_validate({"version": 0, "items": items})
