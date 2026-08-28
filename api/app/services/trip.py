"""여행 생성 규칙과 transaction 내부 DB 변경을 처리한다."""

from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError, INVALID_TRIP_PERIOD
from app.models.trip import Trip as TripModel
from app.schemas.trip import CreateTripRequest, Trip, TripStatus

KST = timezone(timedelta(hours=9))


class TripService:
    """인증된 사용자의 여행 생성 규칙과 원자적 저장을 담당한다."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def create_trip(
        self,
        *,
        user_id: uuid.UUID,
        payload: CreateTripRequest,
        idempotency_key: str,
    ) -> Trip:
        """검증된 여행을 생성하고 같은 멱등 키에는 최초 결과를 반환한다.

        Args:
            user_id: 인증된 여행 소유자 식별자.
            payload: 여행명과 시작일·종료일을 담은 생성 요청.
            idempotency_key: 같은 생성 요청의 재전송을 식별하는 client key.

        Returns:
            생성되었거나 같은 멱등 키로 이미 생성된 여행.

        Raises:
            AppError: 여행명·기간 또는 멱등 키가 공개 계약을 위반한 경우.

        Notes:
            이 메서드는 호출자가 소유한 transaction 안에서 한 행만 생성한다.
        """
        name = payload.name.strip()
        if not 2 <= len(name) <= 30:
            raise AppError(
                422,
                "VALIDATION_ERROR",
                "여행명은 앞뒤 공백을 제외하고 2자 이상 30자 이하여야 합니다.",
            )

        duration = (payload.end_date - payload.start_date).days
        if not 0 <= duration <= 6:
            raise AppError(
                422,
                INVALID_TRIP_PERIOD,
                "여행 기간은 시작일부터 종료일까지 최대 7일이어야 합니다.",
            )

        key = idempotency_key.strip()
        if not key:
            raise AppError(400, "INVALID_REQUEST", "Idempotency-Key가 필요합니다.")

        trip_id = uuid.uuid5(user_id, key)
        result = await self.session.execute(
            pg_insert(TripModel)
            .values(
                trip_id=trip_id,
                user_id=user_id,
                name=name,
                start_date=payload.start_date,
                end_date=payload.end_date,
            )
            .on_conflict_do_nothing(index_elements=[TripModel.trip_id])
            .returning(TripModel)
        )
        trip = result.scalar_one_or_none()
        if trip is None:
            trip = await self.session.scalar(
                select(TripModel).where(
                    TripModel.trip_id == trip_id,
                    TripModel.user_id == user_id,
                )
            )
        if trip is None:
            raise RuntimeError("멱등 생성 결과를 조회할 수 없습니다.")
        return _to_schema(trip)


def _to_schema(trip: TripModel, *, today: date | None = None) -> Trip:
    """저장된 여행을 파생 상태와 일수를 포함한 공개 schema로 변환한다."""
    current_date = today or datetime.now(KST).date()
    if current_date < trip.start_date:
        status = TripStatus.UPCOMING
    elif current_date <= trip.end_date:
        status = TripStatus.IN_PROGRESS
    else:
        status = TripStatus.COMPLETED
    return Trip(
        trip_id=trip.trip_id,
        name=trip.name,
        start_date=trip.start_date,
        end_date=trip.end_date,
        status=status,
        day_count=(trip.end_date - trip.start_date).days + 1,
        version=trip.version,
        created_at=trip.created_at,
    )
