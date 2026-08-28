"""PostgreSQL을 사용하는 여행 생성 통합 테스트."""

import os
import uuid
from collections.abc import AsyncIterator
from datetime import date

import pytest
from sqlalchemy import func, inspect, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.errors import AppError
from app.db import transaction_session
from app.models.auth import User
from app.models.trip import Trip
from app.schemas.trip import CreateTripRequest
from app.services.trip import TripService


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    """migration이 적용된 PostgreSQL session factory를 제공한다."""
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다")

    engine = create_async_engine(database_url)
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


async def create_user(factory: async_sessionmaker[AsyncSession]) -> uuid.UUID:
    """여행 소유권 검증에 사용할 사용자를 생성한다."""
    async with transaction_session(factory) as session:
        user = User(
            social_provider="KAKAO",
            social_subject=f"trip-{uuid.uuid4()}",
        )
        session.add(user)
        await session.flush()
        return user.user_id


async def create_trip(
    factory: async_sessionmaker[AsyncSession],
    user_id: uuid.UUID,
    *,
    name: str = "서울 여행",
    start_date: date = date(2026, 9, 1),
    end_date: date = date(2026, 9, 3),
    idempotency_key: str,
):
    """별도 transaction에서 여행 생성을 실행한다."""
    async with transaction_session(factory) as session:
        return await TripService(session).create_trip(
            user_id=user_id,
            payload=CreateTripRequest(
                name=name,
                startDate=start_date,
                endDate=end_date,
            ),
            idempotency_key=idempotency_key,
        )


@pytest.mark.asyncio
async def test_same_idempotency_key_creates_one_trip(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """같은 사용자의 동일 멱등 키 재전송이 한 행과 동일 응답을 만드는지 확인한다."""
    user_id = await create_user(session_factory)
    key = str(uuid.uuid4())

    first = await create_trip(session_factory, user_id, idempotency_key=key)
    second = await create_trip(session_factory, user_id, idempotency_key=key)

    async with session_factory() as session:
        count = await session.scalar(select(func.count()).select_from(Trip).where(Trip.user_id == user_id))

    assert first.trip_id == second.trip_id
    assert count == 1


@pytest.mark.asyncio
async def test_duplicate_names_with_different_keys_create_two_trips(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """동일 사용자의 같은 여행명에 unique 제약이 적용되지 않는지 확인한다."""
    user_id = await create_user(session_factory)

    first = await create_trip(session_factory, user_id, idempotency_key=str(uuid.uuid4()))
    second = await create_trip(session_factory, user_id, idempotency_key=str(uuid.uuid4()))

    assert first.trip_id != second.trip_id


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("name", "start_date", "end_date", "code"),
    [
        ("   ", date(2026, 9, 1), date(2026, 9, 3), "VALIDATION_ERROR"),
        ("서울 여행", date(2026, 9, 4), date(2026, 9, 3), "INVALID_TRIP_PERIOD"),
        ("서울 여행", date(2026, 9, 1), date(2026, 9, 8), "INVALID_TRIP_PERIOD"),
    ],
)
async def test_create_trip_rejects_invalid_name_and_period(
    session_factory: async_sessionmaker[AsyncSession],
    name: str,
    start_date: date,
    end_date: date,
    code: str,
) -> None:
    """trim 후 이름과 최대 7일 기간 규칙을 DB 변경 전에 검증한다."""
    user_id = await create_user(session_factory)

    with pytest.raises(AppError) as error:
        await create_trip(
            session_factory,
            user_id,
            name=name,
            start_date=start_date,
            end_date=end_date,
            idempotency_key=str(uuid.uuid4()),
        )

    assert error.value.status_code == 422
    assert error.value.code == code


@pytest.mark.asyncio
async def test_trip_schema_constraints_and_indexes_exist(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """T010 migration이 데이터 모델의 제약과 인덱스를 생성했는지 확인한다."""
    engine = session_factory.kw["bind"]

    def schema_snapshot(connection) -> tuple[set[str], set[str], set[str]]:
        inspector = inspect(connection)
        constraints = {
            item["name"] for item in inspector.get_check_constraints("trips")
        }
        indexes = {item["name"] for item in inspector.get_indexes("trips")}
        return set(inspector.get_table_names()), constraints, indexes

    async with engine.connect() as connection:
        tables, constraints, indexes = await connection.run_sync(schema_snapshot)

    assert "trips" in tables
    assert {"ck_trips_name_length", "ck_trips_date_range"} <= constraints
    assert {"ix_trips_user_deleted_at", "ix_trips_user_lower_name_active"} <= indexes
