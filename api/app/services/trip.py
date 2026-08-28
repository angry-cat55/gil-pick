"""여행 생성 규칙과 transaction 내부 DB 변경을 처리한다."""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import uuid
from datetime import date, datetime, timedelta, timezone

from sqlalchemy import and_, case, func, select, tuple_
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError, FORBIDDEN, INVALID_TRIP_PERIOD, TRIP_NOT_FOUND
from app.models.trip import Trip as TripModel
from app.schemas.trip import CreateTripRequest, Trip, TripStatus

KST = timezone(timedelta(hours=9))
EPOCH_DATE = date(1970, 1, 1)


class TripService:
    """인증된 사용자의 여행 생성과 목록 조회 규칙을 담당한다."""

    def __init__(self, session: AsyncSession, *, cursor_secret: str) -> None:
        self.session = session
        self.cursor_key = hmac.new(
            cursor_secret.encode(),
            b"gilpick-trip-cursor-v1",
            hashlib.sha256,
        ).digest()

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

    async def list_trips(
        self,
        *,
        user_id: uuid.UUID,
        query: str | None,
        status: TripStatus | None,
        cursor: str | None,
        limit: int,
    ) -> tuple[list[Trip], str | None, bool]:
        """사용자 소유 여행을 검색·상태 필터와 cursor로 조회한다.

        Args:
            user_id: 인증된 여행 소유자 식별자.
            query: 여행명에 적용할 대소문자 무시 부분 검색어.
            status: 조회할 파생 여행 상태. ``None``이면 전체 상태를 조회한다.
            cursor: 이전 응답이 반환한 불투명한 다음 페이지 식별자.
            limit: 한 페이지에 반환할 최대 여행 수.

        Returns:
            정렬된 여행 목록, 다음 cursor, 다음 페이지 존재 여부.

        Raises:
            AppError: cursor가 서버가 발급한 형식이 아닌 경우.
        """
        normalized_query = query.strip().lower() if query else ""
        cursor_position = _decode_cursor(
            cursor,
            user_id=user_id,
            query=normalized_query,
            status=status,
            signing_key=self.cursor_key,
        )
        today = cursor_position[0] if cursor_position else datetime.now(KST).date()
        status_rank = case(
            (and_(TripModel.start_date <= today, TripModel.end_date >= today), 0),
            (TripModel.start_date > today, 1),
            else_=2,
        )
        date_sort_key = case(
            (TripModel.end_date < today, -(TripModel.end_date - EPOCH_DATE)),
            else_=TripModel.start_date - EPOCH_DATE,
        )
        statement = select(TripModel).where(
            TripModel.user_id == user_id,
            TripModel.deleted_at.is_(None),
        )

        if normalized_query:
            statement = statement.where(
                func.lower(TripModel.name).contains(normalized_query, autoescape=True)
            )
        if status == TripStatus.UPCOMING:
            statement = statement.where(TripModel.start_date > today)
        elif status == TripStatus.IN_PROGRESS:
            statement = statement.where(
                TripModel.start_date <= today,
                TripModel.end_date >= today,
            )
        elif status == TripStatus.COMPLETED:
            statement = statement.where(TripModel.end_date < today)

        if cursor_position:
            _, cursor_rank, cursor_date_key, cursor_trip_id = cursor_position
            statement = statement.where(
                tuple_(status_rank, date_sort_key, TripModel.trip_id)
                > tuple_(cursor_rank, cursor_date_key, cursor_trip_id)
            )

        statement = statement.order_by(
            status_rank,
            date_sort_key,
            TripModel.trip_id,
        ).limit(limit + 1)
        rows = list((await self.session.scalars(statement)).all())
        has_next = len(rows) > limit
        items = [_to_schema(trip, today=today) for trip in rows[:limit]]
        next_cursor = (
            _encode_cursor(
                items[-1],
                user_id=user_id,
                today=today,
                query=normalized_query,
                status=status,
                signing_key=self.cursor_key,
            )
            if has_next
            else None
        )
        return items, next_cursor, has_next

    async def get_trip(self, *, user_id: uuid.UUID, trip_id: uuid.UUID) -> Trip:
        """소유한 활성 여행 하나를 조회한다.

        Args:
            user_id: 인증된 사용자 식별자.
            trip_id: 조회할 여행 식별자.

        Returns:
            현재 KST 날짜로 상태를 계산한 여행 상세 정보.

        Raises:
            AppError: 여행이 없거나 삭제됐거나 요청 사용자가 소유하지 않은 경우.
        """
        trip = await self.session.scalar(
            select(TripModel).where(TripModel.trip_id == trip_id)
        )
        if trip is None or trip.deleted_at is not None:
            raise AppError(404, TRIP_NOT_FOUND, "여행을 찾을 수 없습니다.")
        if trip.user_id != user_id:
            raise AppError(403, FORBIDDEN, "다른 사용자의 여행은 조회할 수 없습니다.")
        return _to_schema(trip)


def _decode_cursor(
    cursor: str | None,
    *,
    user_id: uuid.UUID,
    query: str,
    status: TripStatus | None,
    signing_key: bytes,
) -> tuple[date, int, int, uuid.UUID] | None:
    """URL-safe cursor에서 기준일과 마지막 정렬 키를 복원한다."""
    if cursor is None:
        return None
    if len(cursor) > 256:
        raise AppError(400, "INVALID_REQUEST", "cursor가 올바르지 않습니다.")
    try:
        padding = "=" * (-len(cursor) % 4)
        value = base64.b64decode(
            cursor + padding,
            altchars=b"-_",
            validate=True,
        ).decode("ascii")
        (
            raw_date,
            raw_rank,
            raw_date_key,
            raw_trip_id,
            raw_user_id,
            fingerprint,
            signature,
        ) = value.split("|")
        payload = value.rsplit("|", 1)[0].encode("ascii")
        expected_signature = hmac.new(signing_key, payload, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected_signature):
            raise ValueError
        cursor_date = date.fromisoformat(raw_date)
        rank = int(raw_rank)
        date_key = int(raw_date_key)
        trip_id = uuid.UUID(raw_trip_id)
        cursor_user_id = uuid.UUID(raw_user_id)
        if (
            rank not in (0, 1, 2)
            or cursor_user_id != user_id
            or fingerprint != _filter_fingerprint(query, status)
        ):
            raise ValueError
        return cursor_date, rank, date_key, trip_id
    except (binascii.Error, UnicodeDecodeError, ValueError) as exc:
        raise AppError(400, "INVALID_REQUEST", "cursor가 올바르지 않습니다.") from exc


def _encode_cursor(
    trip: Trip,
    *,
    user_id: uuid.UUID,
    today: date,
    query: str,
    status: TripStatus | None,
    signing_key: bytes,
) -> str:
    """마지막 여행의 정렬 키를 URL-safe 불투명 cursor로 변환한다."""
    rank = {
        TripStatus.IN_PROGRESS: 0,
        TripStatus.UPCOMING: 1,
        TripStatus.COMPLETED: 2,
    }[trip.status]
    date_key = (
        -(trip.end_date - EPOCH_DATE).days
        if trip.status == TripStatus.COMPLETED
        else (trip.start_date - EPOCH_DATE).days
    )
    payload = "|".join(
        (
            today.isoformat(),
            str(rank),
            str(date_key),
            str(trip.trip_id),
            str(user_id),
            _filter_fingerprint(query, status),
        )
    )
    signature = hmac.new(signing_key, payload.encode("ascii"), hashlib.sha256).hexdigest()
    value = f"{payload}|{signature}"
    return base64.urlsafe_b64encode(value.encode("ascii")).decode("ascii").rstrip("=")


def _filter_fingerprint(query: str, status: TripStatus | None) -> str:
    """cursor를 최초 요청의 검색·상태 조건에 결합한다."""
    status_value = status.value if status else ""
    value = f"{query}\0{status_value}".encode()
    return hashlib.sha256(value).hexdigest()[:16]


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
