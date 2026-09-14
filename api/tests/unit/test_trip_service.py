"""여행 service의 수정 규칙 단위 테스트."""

import uuid
from datetime import date, datetime, timedelta, UTC
from unittest.mock import AsyncMock, MagicMock

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError
from app.models.trip import Trip as TripModel
from app.schemas.trip import CreateTripRequest, UpdateTripRequest
from app.services.trip import KST, TripService


def make_trip(*, completed: bool = False) -> TripModel:
    """수정 규칙 검증에 사용할 여행 모델을 만든다."""
    today = datetime.now(KST).date()
    start_date = today - timedelta(days=3) if completed else today + timedelta(days=3)
    end_date = start_date + timedelta(days=2)
    return TripModel(
        trip_id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        name="서울 여행",
        start_date=start_date,
        end_date=end_date,
        version=1,
        created_at=datetime.now(UTC),
    )


def make_service(trip: TripModel) -> tuple[TripService, AsyncMock]:
    """첫 조회에서 지정한 여행을 반환하는 service와 session을 만든다."""
    session = AsyncMock(spec=AsyncSession)
    session.scalar.return_value = trip
    return TripService(session, cursor_secret="unit-test-secret"), session


@pytest.mark.asyncio
async def test_create_trip_rejects_overlapping_active_trip() -> None:
    """같은 사용자의 포함 경계 기간과 겹치는 생성은 충돌 여행 정보를 반환한다."""
    conflict = make_trip()
    service, _ = make_service(conflict)

    with pytest.raises(AppError) as error:
        await service.create_trip(
            user_id=conflict.user_id,
            payload=CreateTripRequest(
                name="새 여행",
                startDate=conflict.end_date,
                endDate=conflict.end_date,
            ),
            idempotency_key=str(uuid.uuid4()),
        )

    assert error.value.status_code == 409
    assert error.value.code == "TRIP_PERIOD_CONFLICT"
    assert error.value.details == {
        "tripId": str(conflict.trip_id),
        "name": conflict.name,
    }


@pytest.mark.asyncio
async def test_overlap_check_excludes_updated_trip_itself() -> None:
    """여행 수정의 중복 검사는 수정 대상 자체를 제외한다."""
    trip = make_trip()
    service, session = make_service(trip)
    session.scalar.return_value = None

    await service._reject_overlapping_period(
        user_id=trip.user_id,
        start_date=trip.start_date,
        end_date=trip.end_date,
        exclude_trip_id=trip.trip_id,
    )

    statement = session.scalar.await_args.args[0]
    assert "trips.trip_id !=" in str(statement)


@pytest.mark.asyncio
async def test_completed_trip_allows_trimmed_name_only_update() -> None:
    """완료 여행도 이름만 수정하면 trim하고 version을 증가시킨다."""
    trip = make_trip(completed=True)
    service, session = make_service(trip)
    updated = make_trip(completed=True)
    updated.trip_id = trip.trip_id
    updated.user_id = trip.user_id
    updated.name = "부산 여행"
    updated.version = 2
    result = MagicMock()
    result.scalar_one_or_none.return_value = updated
    session.execute.return_value = result

    response = await service.update_trip(
        user_id=trip.user_id,
        trip_id=trip.trip_id,
        payload=UpdateTripRequest(name="  부산 여행  ", version=1),
    )

    assert response.name == "부산 여행"
    assert response.version == 2


@pytest.mark.asyncio
async def test_completed_trip_rejects_period_fields() -> None:
    """완료 여행은 같은 값이라도 기간 필드가 포함되면 잠금 오류를 반환한다."""
    trip = make_trip(completed=True)
    service, session = make_service(trip)

    with pytest.raises(AppError) as error:
        await service.update_trip(
            user_id=trip.user_id,
            trip_id=trip.trip_id,
            payload=UpdateTripRequest(startDate=trip.start_date, version=1),
        )

    assert error.value.status_code == 409
    assert error.value.code == "TRIP_LOCKED"
    session.execute.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("payload", "code"),
    [
        (UpdateTripRequest(name="   ", version=1), "VALIDATION_ERROR"),
        (UpdateTripRequest(name="가" * 31, version=1), "VALIDATION_ERROR"),
        (
            UpdateTripRequest(
                startDate=date(2026, 9, 4),
                endDate=date(2026, 9, 3),
                version=1,
            ),
            "INVALID_TRIP_PERIOD",
        ),
        (
            UpdateTripRequest(
                startDate=date(2026, 9, 1),
                endDate=date(2026, 9, 8),
                version=1,
            ),
            "INVALID_TRIP_PERIOD",
        ),
    ],
)
async def test_update_trip_revalidates_name_and_period(
    payload: UpdateTripRequest,
    code: str,
) -> None:
    """수정 후 전체 값에 생성과 같은 이름·기간 규칙을 적용한다."""
    trip = make_trip()
    service, session = make_service(trip)

    with pytest.raises(AppError) as error:
        await service.update_trip(
            user_id=trip.user_id,
            trip_id=trip.trip_id,
            payload=payload,
        )

    assert error.value.status_code == 422
    assert error.value.code == code
    session.execute.assert_not_awaited()
