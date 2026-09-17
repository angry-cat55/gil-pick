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


def test_processed_last_item_allows_filling_null_transport() -> None:
    """처리된 마지막 장소 뒤에 새 장소가 붙어 null이던 이동 수단이 채워지는 것은 허용한다(#582)."""
    item_id = uuid.uuid4()
    stored = ItineraryItem(
        item_id=item_id,
        trip_day_id=uuid.uuid4(),
        place_id=uuid.uuid4(),
        sequence=1,
        status="COMPLETED",
        planned_stay_minutes=60,
        stay_source="RECOMMENDED",
        transport_mode_to_next=None,
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
    incoming = _payload(
        [
            {
                **_new_item(1, "WALK"),
                "itemId": str(item_id),
                "placeId": "tourapi:12345",
                "place": None,
            }
        ]
    ).items

    _validate_locked_items(day, incoming)  # 예외 없이 통과해야 한다.


def test_processed_item_still_rejects_changing_existing_transport() -> None:
    """이미 있던 이동 수단을 다른 값으로 바꾸는 것은 여전히 막는다(#582)."""
    item_id = uuid.uuid4()
    trip_day_id = uuid.uuid4()
    stored = ItineraryItem(
        item_id=item_id,
        trip_day_id=trip_day_id,
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
    planned = ItineraryItem(
        item_id=uuid.uuid4(),
        trip_day_id=trip_day_id,
        place_id=uuid.uuid4(),
        sequence=2,
        status="PLANNED",
        planned_stay_minutes=60,
        stay_source="RECOMMENDED",
        transport_mode_to_next=None,
    )
    day = TripDay(
        trip_day_id=stored.trip_day_id,
        trip_id=uuid.uuid4(),
        visit_date=date(2026, 9, 1),
        day_number=1,
        schedule_version=1,
        items=[stored, planned],
    )
    incoming = _payload(
        [
            {
                **_new_item(1, "TRANSIT"),
                "itemId": str(item_id),
                "placeId": "tourapi:12345",
                "place": None,
            }
        ]
    ).items

    with pytest.raises(AppError) as error:
        _validate_locked_items(day, incoming)

    assert error.value.code == "ITINERARY_ITEM_LOCKED"


@pytest.mark.parametrize("locked_status", ["EN_ROUTE", "ARRIVED", "COMPLETED", "SKIPPED"])
def test_locked_item_allows_clearing_transport_when_planned_successor_is_removed(
    locked_status: str,
) -> None:
    """마지막 예정 장소 삭제로 잠긴 장소가 새 마지막이 되면 이동 수단을 비울 수 있다(#688)."""
    completed_id = uuid.uuid4()
    en_route_id = uuid.uuid4()
    planned_id = uuid.uuid4()
    trip_day_id = uuid.uuid4()
    completed = ItineraryItem(
        item_id=completed_id,
        trip_day_id=trip_day_id,
        place_id=uuid.uuid4(),
        sequence=1,
        status="COMPLETED",
        planned_stay_minutes=60,
        stay_source="RECOMMENDED",
        transport_mode_to_next="WALK",
    )
    completed.place = Place(
        place_id=completed.place_id,
        tour_content_id="11111",
        name="완료 장소",
        category="OTHER",
        location="POINT(127 37)",
    )
    en_route = ItineraryItem(
        item_id=en_route_id,
        trip_day_id=trip_day_id,
        place_id=uuid.uuid4(),
        sequence=2,
        status=locked_status,
        planned_stay_minutes=60,
        stay_source="RECOMMENDED",
        transport_mode_to_next="TRANSIT",
    )
    en_route.place = Place(
        place_id=en_route.place_id,
        tour_content_id="12345",
        name="이동 중 장소",
        category="OTHER",
        location="POINT(127 37)",
    )
    planned = ItineraryItem(
        item_id=planned_id,
        trip_day_id=trip_day_id,
        place_id=uuid.uuid4(),
        sequence=3,
        status="PLANNED",
        planned_stay_minutes=60,
        stay_source="RECOMMENDED",
        transport_mode_to_next=None,
    )
    day = TripDay(
        trip_day_id=trip_day_id,
        trip_id=uuid.uuid4(),
        visit_date=date(2026, 9, 17),
        day_number=1,
        schedule_version=1,
        items=[completed, en_route, planned],
    )
    incoming = _payload(
        [
            {
                **_new_item(1, "WALK"),
                "itemId": str(completed_id),
                "placeId": "tourapi:11111",
                "place": None,
            },
            {
                **_new_item(2),
                "itemId": str(en_route_id),
                "placeId": "tourapi:12345",
                "place": None,
            }
        ]
    ).items

    _validate_locked_items(day, incoming)  # 예외 없이 통과해야 한다.
