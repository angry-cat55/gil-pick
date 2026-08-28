"""PostgreSQL을 사용하는 여행 관리 통합 테스트."""

import base64
import os
import uuid
from collections.abc import AsyncIterator
from datetime import date, datetime, timedelta

import pytest
from sqlalchemy import func, inspect, select, update as sql_update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.errors import AppError
from app.db import transaction_session
from app.models.auth import User
from app.models.trip import Trip
from app.schemas.trip import CreateTripRequest, TripStatus, UpdateTripRequest
from app.services.trip import KST, TripService

CURSOR_SECRET = "integration-test-trip-cursor-secret"


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
        return await TripService(session, cursor_secret=CURSOR_SECRET).create_trip(
            user_id=user_id,
            payload=CreateTripRequest(
                name=name,
                startDate=start_date,
                endDate=end_date,
            ),
            idempotency_key=idempotency_key,
        )


async def list_trips(
    factory: async_sessionmaker[AsyncSession],
    user_id: uuid.UUID,
    *,
    query: str | None = None,
    status: TripStatus | None = None,
    cursor: str | None = None,
    limit: int = 20,
):
    """별도 transaction에서 사용자 여행 목록을 조회한다."""
    async with transaction_session(factory) as session:
        return await TripService(session, cursor_secret=CURSOR_SECRET).list_trips(
            user_id=user_id,
            query=query,
            status=status,
            cursor=cursor,
            limit=limit,
        )


async def get_trip(
    factory: async_sessionmaker[AsyncSession],
    user_id: uuid.UUID,
    trip_id: uuid.UUID,
):
    """별도 transaction에서 여행 상세를 조회한다."""
    async with transaction_session(factory) as session:
        return await TripService(session, cursor_secret=CURSOR_SECRET).get_trip(
            user_id=user_id,
            trip_id=trip_id,
        )


async def update_trip(
    factory: async_sessionmaker[AsyncSession],
    user_id: uuid.UUID,
    trip_id: uuid.UUID,
    payload: UpdateTripRequest,
):
    """별도 transaction에서 여행 수정을 실행한다."""
    async with transaction_session(factory) as session:
        return await TripService(session, cursor_secret=CURSOR_SECRET).update_trip(
            user_id=user_id,
            trip_id=trip_id,
            payload=payload,
        )


async def delete_trip(
    factory: async_sessionmaker[AsyncSession],
    user_id: uuid.UUID,
    trip_id: uuid.UUID,
) -> None:
    """별도 transaction에서 여행 삭제를 실행한다."""
    async with transaction_session(factory) as session:
        await TripService(session, cursor_secret=CURSOR_SECRET).delete_trip(
            user_id=user_id,
            trip_id=trip_id,
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


@pytest.mark.asyncio
async def test_list_trips_only_returns_owner_in_status_group_order(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """다른 사용자 여행을 제외하고 여행 중·예정·완료 그룹 순으로 반환한다."""
    user_id = await create_user(session_factory)
    other_user_id = await create_user(session_factory)
    today = datetime.now(KST).date()

    await create_trip(
        session_factory,
        user_id,
        name="오래된 완료 여행",
        start_date=today - timedelta(days=365),
        end_date=today - timedelta(days=365),
        idempotency_key=str(uuid.uuid4()),
    )
    await create_trip(
        session_factory,
        user_id,
        name="먼 예정 여행",
        start_date=today + timedelta(days=365),
        end_date=today + timedelta(days=365),
        idempotency_key=str(uuid.uuid4()),
    )
    await create_trip(
        session_factory,
        user_id,
        name="최근 완료 여행",
        start_date=today - timedelta(days=30),
        end_date=today - timedelta(days=30),
        idempotency_key=str(uuid.uuid4()),
    )
    await create_trip(
        session_factory,
        user_id,
        name="가까운 예정 여행",
        start_date=today + timedelta(days=30),
        end_date=today + timedelta(days=30),
        idempotency_key=str(uuid.uuid4()),
    )
    await create_trip(
        session_factory,
        user_id,
        name="진행 중 여행",
        start_date=today,
        end_date=today,
        idempotency_key=str(uuid.uuid4()),
    )
    await create_trip(
        session_factory,
        other_user_id,
        name="다른 사용자 여행",
        start_date=today,
        end_date=today,
        idempotency_key=str(uuid.uuid4()),
    )

    items, next_cursor, has_next = await list_trips(session_factory, user_id)

    assert [item.name for item in items] == [
        "진행 중 여행",
        "가까운 예정 여행",
        "먼 예정 여행",
        "최근 완료 여행",
        "오래된 완료 여행",
    ]
    assert [item.status for item in items] == [
        TripStatus.IN_PROGRESS,
        TripStatus.UPCOMING,
        TripStatus.UPCOMING,
        TripStatus.COMPLETED,
        TripStatus.COMPLETED,
    ]
    assert next_cursor is None
    assert has_next is False


@pytest.mark.asyncio
async def test_list_trips_applies_search_status_and_cursor_pagination(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """검색어와 상태 필터 결과를 cursor로 중복 없이 이어서 조회한다."""
    user_id = await create_user(session_factory)
    today = datetime.now(KST).date()
    for name, days_until_start in (
        ("서울 봄 여행", 30),
        ("부산 여행", 40),
        ("서울 가을 여행", 60),
    ):
        upcoming = today + timedelta(days=days_until_start)
        await create_trip(
            session_factory,
            user_id,
            name=name,
            start_date=upcoming,
            end_date=upcoming,
            idempotency_key=str(uuid.uuid4()),
        )

    first_items, cursor, first_has_next = await list_trips(
        session_factory,
        user_id,
        query="서울",
        status=TripStatus.UPCOMING,
        limit=1,
    )
    await create_trip(
        session_factory,
        user_id,
        name="서울 새 여행",
        start_date=today + timedelta(days=10),
        end_date=today + timedelta(days=10),
        idempotency_key=str(uuid.uuid4()),
    )
    second_items, final_cursor, second_has_next = await list_trips(
        session_factory,
        user_id,
        query="서울",
        status=TripStatus.UPCOMING,
        cursor=cursor,
        limit=1,
    )

    assert len(first_items) == len(second_items) == 1
    assert first_items[0].trip_id != second_items[0].trip_id
    assert first_items[0].name == "서울 봄 여행"
    assert second_items[0].name == "서울 가을 여행"
    assert first_has_next is True
    assert cursor is not None
    assert final_cursor is None
    assert second_has_next is False

    with pytest.raises(AppError) as error:
        await list_trips(session_factory, user_id, cursor="invalid-cursor")
    assert error.value.status_code == 400
    assert error.value.code == "INVALID_REQUEST"

    other_user_id = await create_user(session_factory)
    with pytest.raises(AppError) as error:
        await list_trips(
            session_factory,
            other_user_id,
            query="서울",
            status=TripStatus.UPCOMING,
            cursor=cursor,
            limit=1,
        )
    assert error.value.code == "INVALID_REQUEST"

    padding = "=" * (-len(cursor) % 4)
    decoded = base64.urlsafe_b64decode(cursor + padding).decode("ascii")
    tampered = decoded.replace(str(today), str(today - timedelta(days=1)), 1)
    tampered_cursor = base64.urlsafe_b64encode(tampered.encode("ascii")).decode("ascii").rstrip("=")
    with pytest.raises(AppError) as error:
        await list_trips(
            session_factory,
            user_id,
            query="서울",
            status=TripStatus.UPCOMING,
            cursor=tampered_cursor,
            limit=1,
        )
    assert error.value.code == "INVALID_REQUEST"


@pytest.mark.asyncio
async def test_get_trip_distinguishes_owner_forbidden_missing_and_deleted(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """상세 조회가 소유권과 활성 상태에 따라 성공·403·404를 구분하는지 확인한다."""
    user_id = await create_user(session_factory)
    other_user_id = await create_user(session_factory)
    created = await create_trip(
        session_factory,
        user_id,
        idempotency_key=str(uuid.uuid4()),
    )

    found = await get_trip(session_factory, user_id, created.trip_id)

    assert found == created

    with pytest.raises(AppError) as forbidden:
        await get_trip(session_factory, other_user_id, created.trip_id)
    assert forbidden.value.status_code == 403

    with pytest.raises(AppError) as missing:
        await get_trip(session_factory, user_id, uuid.uuid4())
    assert missing.value.status_code == 404
    assert missing.value.code == "TRIP_NOT_FOUND"

    async with transaction_session(session_factory) as session:
        trip = await session.get(Trip, created.trip_id)
        assert trip is not None
        trip.deleted_at = datetime.now(KST)

    with pytest.raises(AppError) as deleted:
        await get_trip(session_factory, user_id, created.trip_id)
    assert deleted.value.status_code == 404
    assert deleted.value.code == "TRIP_NOT_FOUND"

    with pytest.raises(AppError) as other_user_deleted:
        await get_trip(session_factory, other_user_id, created.trip_id)
    assert other_user_deleted.value.status_code == 404
    assert other_user_deleted.value.code == "TRIP_NOT_FOUND"


@pytest.mark.asyncio
async def test_update_trip_requires_confirmation_and_rejects_stale_version(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """기간 축소는 확인 전 보존하고 확인 후 version을 올려 원자적으로 반영한다."""
    user_id = await create_user(session_factory)
    today = datetime.now(KST).date()
    created = await create_trip(
        session_factory,
        user_id,
        start_date=today + timedelta(days=10),
        end_date=today + timedelta(days=12),
        idempotency_key=str(uuid.uuid4()),
    )
    shortened_end = today + timedelta(days=11)

    with pytest.raises(AppError) as confirmation:
        await update_trip(
            session_factory,
            user_id,
            created.trip_id,
            UpdateTripRequest(endDate=shortened_end, version=created.version),
        )
    assert confirmation.value.status_code == 409
    assert confirmation.value.code == "CONFIRMATION_REQUIRED"
    assert confirmation.value.details == {"deletedItemCount": 0}

    unchanged = await get_trip(session_factory, user_id, created.trip_id)
    assert unchanged.end_date == created.end_date
    assert unchanged.version == created.version

    updated = await update_trip(
        session_factory,
        user_id,
        created.trip_id,
        UpdateTripRequest(
            endDate=shortened_end,
            version=created.version,
            confirmDeleteOutOfRangeItems=True,
        ),
    )
    assert updated.end_date == shortened_end
    assert updated.version == created.version + 1

    with pytest.raises(AppError) as stale:
        await update_trip(
            session_factory,
            user_id,
            created.trip_id,
            UpdateTripRequest(name="오래된 수정", version=created.version),
        )
    assert stale.value.status_code == 409
    assert stale.value.code == "VERSION_CONFLICT"


@pytest.mark.asyncio
async def test_update_trip_rejects_other_owner_missing_and_deleted(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """수정 대상의 소유권·존재·활성 상태를 실제 DB 행으로 검증한다."""
    user_id = await create_user(session_factory)
    other_user_id = await create_user(session_factory)
    created = await create_trip(
        session_factory,
        user_id,
        idempotency_key=str(uuid.uuid4()),
    )
    payload = UpdateTripRequest(name="수정 여행", version=created.version)

    with pytest.raises(AppError) as forbidden:
        await update_trip(session_factory, other_user_id, created.trip_id, payload)
    assert forbidden.value.status_code == 403
    assert forbidden.value.code == "FORBIDDEN"

    with pytest.raises(AppError) as missing:
        await update_trip(session_factory, user_id, uuid.uuid4(), payload)
    assert missing.value.status_code == 404
    assert missing.value.code == "TRIP_NOT_FOUND"

    async with transaction_session(session_factory) as session:
        trip = await session.get(Trip, created.trip_id)
        assert trip is not None
        trip.deleted_at = datetime.now(KST)

    with pytest.raises(AppError) as deleted:
        await update_trip(session_factory, user_id, created.trip_id, payload)
    assert deleted.value.status_code == 404
    assert deleted.value.code == "TRIP_NOT_FOUND"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("concurrent_change", "expected_code"),
    [("version", "VERSION_CONFLICT"), ("delete", "TRIP_NOT_FOUND")],
)
async def test_update_trip_maps_concurrent_change_after_stale_read(
    session_factory: async_sessionmaker[AsyncSession],
    concurrent_change: str,
    expected_code: str,
) -> None:
    """stale ORM 객체 뒤의 동시 수정·삭제도 500 없이 공개 오류로 변환한다."""
    user_id = await create_user(session_factory)
    created = await create_trip(
        session_factory,
        user_id,
        idempotency_key=str(uuid.uuid4()),
    )

    async with session_factory() as stale_session:
        async with stale_session.begin():
            cached = await stale_session.get(Trip, created.trip_id)
            assert cached is not None

            async with transaction_session(session_factory) as concurrent_session:
                values = (
                    {"name": "동시 수정", "version": created.version + 1}
                    if concurrent_change == "version"
                    else {"deleted_at": datetime.now(KST)}
                )
                await concurrent_session.execute(
                    sql_update(Trip)
                    .where(Trip.trip_id == created.trip_id)
                    .values(**values)
                )

            with pytest.raises(AppError) as error:
                await TripService(
                    stale_session,
                    cursor_secret=CURSOR_SECRET,
                ).update_trip(
                    user_id=user_id,
                    trip_id=created.trip_id,
                    payload=UpdateTripRequest(name="내 수정", version=created.version),
                )

    assert error.value.code == expected_code
    assert error.value.status_code == (409 if concurrent_change == "version" else 404)


@pytest.mark.asyncio
async def test_delete_trip_soft_deletes_and_excludes_all_reads_idempotently(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """삭제된 여행은 행을 보존하면서 목록·검색·상세에서 빠지고 반복 삭제도 유지된다."""
    user_id = await create_user(session_factory)
    other_user_id = await create_user(session_factory)
    today = datetime.now(KST).date()
    created = await create_trip(
        session_factory,
        user_id,
        name="삭제할 서울 여행",
        start_date=today + timedelta(days=1),
        end_date=today + timedelta(days=2),
        idempotency_key=str(uuid.uuid4()),
    )

    await delete_trip(session_factory, user_id, created.trip_id)
    with pytest.raises(AppError) as other_user_deleted:
        await delete_trip(session_factory, other_user_id, created.trip_id)
    assert other_user_deleted.value.status_code == 404
    assert other_user_deleted.value.code == "TRIP_NOT_FOUND"

    async with session_factory() as session:
        stored = await session.get(Trip, created.trip_id)
        assert stored is not None
        assert stored.deleted_at is not None
        first_deleted_at = stored.deleted_at

    all_items, _, _ = await list_trips(session_factory, user_id)
    searched_items, _, _ = await list_trips(session_factory, user_id, query="서울")
    assert all_items == []
    assert searched_items == []

    with pytest.raises(AppError) as deleted:
        await get_trip(session_factory, user_id, created.trip_id)
    assert deleted.value.status_code == 404
    assert deleted.value.code == "TRIP_NOT_FOUND"

    await delete_trip(session_factory, user_id, created.trip_id)
    async with session_factory() as session:
        stored = await session.get(Trip, created.trip_id)
        assert stored is not None
        assert stored.deleted_at == first_deleted_at


@pytest.mark.asyncio
async def test_delete_trip_rejects_completed_other_owner_and_missing(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """완료 여행·비소유 여행·미존재 여행의 삭제 오류를 구분한다."""
    user_id = await create_user(session_factory)
    other_user_id = await create_user(session_factory)
    today = datetime.now(KST).date()
    completed = await create_trip(
        session_factory,
        user_id,
        start_date=today - timedelta(days=2),
        end_date=today - timedelta(days=1),
        idempotency_key=str(uuid.uuid4()),
    )
    active = await create_trip(
        session_factory,
        user_id,
        start_date=today,
        end_date=today + timedelta(days=1),
        idempotency_key=str(uuid.uuid4()),
    )

    with pytest.raises(AppError) as locked:
        await delete_trip(session_factory, user_id, completed.trip_id)
    assert locked.value.status_code == 409
    assert locked.value.code == "TRIP_LOCKED"

    with pytest.raises(AppError) as forbidden:
        await delete_trip(session_factory, other_user_id, active.trip_id)
    assert forbidden.value.status_code == 403
    assert forbidden.value.code == "FORBIDDEN"

    with pytest.raises(AppError) as missing:
        await delete_trip(session_factory, user_id, uuid.uuid4())
    assert missing.value.status_code == 404
    assert missing.value.code == "TRIP_NOT_FOUND"

    async with session_factory() as session:
        completed_row = await session.get(Trip, completed.trip_id)
        active_row = await session.get(Trip, active.trip_id)
        assert completed_row is not None and completed_row.deleted_at is None
        assert active_row is not None and active_row.deleted_at is None
