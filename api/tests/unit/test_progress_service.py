"""F006 수동 진행 전환·상태 수정의 파생 규칙과 불변식을 검증한다."""

import uuid
from datetime import UTC, date, datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError
from app.models.itinerary import ItineraryItem, TripDay
from app.schemas.progress import StartDayProgressRequest
from app.schemas.progress import StartMode
from app.services.progress import (
    ProgressService,
    apply_manual_transition,
    apply_start_transition,
    apply_trip_end_close_out,
    close_out_ended_trip,
    reopen_completed_day,
)


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


def test_at_first_place_start_arrives_first_item() -> None:
    now = datetime(2026, 9, 8, 1, tzinfo=UTC)
    first, second = _item(1, "PLANNED"), _item(2, "PLANNED")
    second.trip_day_id = first.trip_day_id
    day = _day([first, second])

    affected = apply_start_transition(day, first, StartMode.AT_FIRST_PLACE, now)

    assert first.status == "ARRIVED"
    assert first.actual_arrived_at == now
    assert day.status == "IN_PROGRESS"
    assert day.completed_at is None
    assert affected[0]["afterStatus"] == "ARRIVED"


def test_at_first_place_start_completes_single_item_day() -> None:
    now = datetime(2026, 9, 8, 1, tzinfo=UTC)
    first = _item(1, "PLANNED")
    day = _day([first])

    affected = apply_start_transition(day, first, StartMode.AT_FIRST_PLACE, now)

    assert first.status == "ARRIVED"
    assert day.status == "COMPLETED"
    assert day.completed_at == now
    assert day.detection_active is False
    assert affected[-1] == {
        "dayStatusBefore": "NOT_STARTED",
        "dayStatusAfter": "COMPLETED",
    }


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


def test_trip_end_close_out_skips_unvisited_items_and_completes_day() -> None:
    """#711: 종료된 여행은 가지 못한 장소만 건너뛰기로 바꾸고 방문 기록은 그대로 둔다."""
    done, arrived, en_route, planned, skipped = (
        _item(1, "COMPLETED"), _item(2, "ARRIVED"), _item(3, "EN_ROUTE"),
        _item(4, "PLANNED"), _item(5, "SKIPPED"),
    )
    day = _day([done, arrived, en_route, planned, skipped])
    now = datetime(2026, 9, 10, tzinfo=UTC)

    affected = apply_trip_end_close_out(day, now)

    assert [item.status for item in day.items] == [
        "COMPLETED", "ARRIVED", "SKIPPED", "SKIPPED", "SKIPPED",
    ]
    assert (day.status, day.completed_at, day.detection_active) == ("COMPLETED", now, False)
    assert affected == [
        {"itemId": str(en_route.item_id), "beforeStatus": "EN_ROUTE", "afterStatus": "SKIPPED"},
        {"itemId": str(planned.item_id), "beforeStatus": "PLANNED", "afterStatus": "SKIPPED"},
        {"dayStatusBefore": "IN_PROGRESS", "dayStatusAfter": "COMPLETED"},
    ]


@pytest.mark.asyncio
async def test_close_out_does_nothing_until_the_last_day_has_passed_in_kst() -> None:
    """#711: 마지막 날(KST) 23:59까지는 여행 중이라 조회조차 하지 않는다."""
    session = AsyncMock(spec=AsyncSession)

    await close_out_ended_trip(
        session, trip_id=uuid.uuid4(), end_date=date(2026, 9, 9),
        now=datetime(2026, 9, 9, 14, 59, tzinfo=UTC),  # KST 9/9 23:59
    )

    session.scalars.assert_not_called()


def _completed_day(items: list[ItineraryItem]) -> TripDay:
    day = _day(items)
    day.status = "COMPLETED"
    day.completed_at = datetime(2026, 9, 8, 9, tzinfo=UTC)
    day.detection_active = False
    return day


def test_reopen_keeps_arrived_last_place_so_user_can_depart_to_added_place() -> None:
    """#724: 마지막 장소에 도착해 완료된 날짜에 장소를 추가하면 `다음 장소로 출발`로 이어진다."""
    arrived, added = _item(1, "ARRIVED"), _item(2, "PLANNED")
    day = _completed_day([arrived, added])
    session = MagicMock(spec=AsyncSession)

    assert reopen_completed_day(session, day, datetime(2026, 9, 8, 10, tzinfo=UTC)) is True

    assert [item.status for item in day.items] == ["ARRIVED", "PLANNED"]
    assert (day.status, day.completed_at, day.detection_active) == ("IN_PROGRESS", None, True)
    assert day.progress_version == 2
    transition = session.add.call_args.args[0]
    assert (transition.transition_type, transition.source) == ("REOPEN_DAY", "ITINERARY_EDIT")
    assert transition.progress_version_after == 2
    assert transition.affected_items == [
        {"dayStatusBefore": "COMPLETED", "dayStatusAfter": "IN_PROGRESS"},
    ]
    # 다시 연 뒤에는 기존 규칙대로 출발하면 추가한 장소가 이동 중이 된다.
    apply_manual_transition(day, arrived, "COMPLETED", datetime(2026, 9, 8, 11, tzinfo=UTC))
    assert [item.status for item in day.items] == ["COMPLETED", "EN_ROUTE"]


def test_reopen_derives_en_route_when_last_place_was_skipped() -> None:
    """#724: 도착해 있는 장소가 없으면(마지막을 건너뜀) 추가한 장소가 바로 이동 중이 된다."""
    added = _item(3, "PLANNED")
    day = _completed_day([_item(1, "COMPLETED"), _item(2, "SKIPPED"), added])

    assert reopen_completed_day(MagicMock(spec=AsyncSession), day, datetime(2026, 9, 8, 10, tzinfo=UTC)) is True

    assert [item.status for item in day.items] == ["COMPLETED", "SKIPPED", "EN_ROUTE"]
    assert day.status == "IN_PROGRESS"


@pytest.mark.parametrize(
    ("day_status", "item_status"),
    [("COMPLETED", "ARRIVED"), ("IN_PROGRESS", "PLANNED"), ("NOT_STARTED", "PLANNED")],
)
def test_reopen_leaves_other_days_untouched(day_status: str, item_status: str) -> None:
    """#724: 장소 추가 없는 완료 날짜와 완료가 아닌 날짜는 바꾸지 않는다."""
    day = _completed_day([_item(1, item_status)])
    day.status = day_status
    session = MagicMock(spec=AsyncSession)

    assert reopen_completed_day(session, day, datetime(2026, 9, 8, 10, tzinfo=UTC)) is False

    assert (day.status, day.progress_version) == (day_status, 1)
    session.add.assert_not_called()
