"""F012 계정 탈퇴 API가 데이터를 숨기고 재가입을 허용하는지 검증하는 통합 테스트."""

import os
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import Settings
from app.core.security import create_opaque_token
from app.db import transaction_session
from app.models.auth import AuthLoginTransaction, DeviceSession, User
from app.models.trip import Trip
from app.services.auth import AuthServiceError, delete_account, exchange_login_ticket, rotate_refresh_token


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    """Provide sessions connected to the migrated integration database."""
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다")

    engine = create_async_engine(database_url)
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


@pytest.fixture
def settings() -> Settings:
    """Return deterministic non-secret integration settings."""
    return Settings(
        _env_file=None,
        database_url="postgresql+asyncpg://user:password@localhost/gilpick",
        jwt_signing_secret="integration-test-signing-secret-32-bytes",
        jwt_issuer="https://api.gilpick.example",
        jwt_audience="gilpick-android",
        kakao_rest_api_key="test-rest-key",
        kakao_client_secret="test-client-secret",
        kakao_redirect_uri="https://api.gilpick.example/api/v1/auth/kakao/callback",
        android_app_link_base_url="https://app.gilpick.example/auth/kakao/complete",
        android_app_link_host="app.gilpick.example",
        tour_api_service_key="test-tour-api-key",
        google_places_api_key="test-google-places-key",
    )


async def _seed_account(
    factory: async_sessionmaker[AsyncSession], *, social_subject: str
) -> tuple[uuid.UUID, uuid.UUID, uuid.UUID, uuid.UUID, str, str]:
    """탈퇴 대상 사용자, 여행 하나, 두 기기의 활성 device session을 만든다."""
    first_device, second_device = uuid.uuid4(), uuid.uuid4()
    first_refresh, second_refresh = create_opaque_token(), create_opaque_token()
    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=social_subject)
        session.add(user)
        await session.flush()
        trip = Trip(
            user_id=user.user_id,
            name="탈퇴 전 여행",
            start_date=date.today(),
            end_date=date.today() + timedelta(days=1),
        )
        session.add(trip)
        session.add_all(
            [
                DeviceSession(
                    session_id=first_refresh.selector,
                    user_id=user.user_id,
                    client_device_id=str(first_device),
                    platform="ANDROID",
                    refresh_token_hash=first_refresh.secret_hash,
                    refresh_expires_at=datetime.now(UTC) + timedelta(days=1),
                    fcm_token=f"fcm-{uuid.uuid4()}",
                ),
                DeviceSession(
                    session_id=second_refresh.selector,
                    user_id=user.user_id,
                    client_device_id=str(second_device),
                    platform="ANDROID",
                    refresh_token_hash=second_refresh.secret_hash,
                    refresh_expires_at=datetime.now(UTC) + timedelta(days=1),
                    fcm_token=f"fcm-{uuid.uuid4()}",
                ),
            ]
        )
        await session.flush()
        user_id, trip_id = user.user_id, trip.trip_id
    return user_id, trip_id, first_device, second_device, first_refresh.encoded, second_refresh.encoded


@pytest.mark.asyncio
async def test_delete_account_hides_trip_and_revokes_every_device_session(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    """탈퇴 처리는 여행을 숨기고 모든 기기의 Refresh Token을 즉시 무효화한다."""
    subject = f"delete-{uuid.uuid4()}"
    user_id, trip_id, _first_device, second_device, _first_refresh, second_refresh = await _seed_account(
        session_factory, social_subject=subject
    )

    async with transaction_session(session_factory) as session:
        await delete_account(session, user_id)

    async with session_factory() as session:
        user = await session.get(User, user_id)
        trip = await session.get(Trip, trip_id)
        sessions = (
            await session.scalars(select(DeviceSession).where(DeviceSession.user_id == user_id))
        ).all()

    assert user.deleted_at is not None
    assert trip.deleted_at is not None
    assert len(sessions) == 2
    assert all(item.revoked_at is not None and item.fcm_token is None for item in sessions)

    with pytest.raises(AuthServiceError, match="INVALID_REFRESH_TOKEN"):
        async with transaction_session(session_factory) as session:
            await rotate_refresh_token(session, second_refresh, second_device, settings)


@pytest.mark.asyncio
async def test_delete_account_is_idempotent_for_repeated_requests(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """이미 탈퇴한 계정에 다시 요청해도 오류 없이 상태가 그대로 유지된다."""
    subject = f"delete-repeat-{uuid.uuid4()}"
    user_id, _trip_id, _fd, _sd, _fr, _sr = await _seed_account(session_factory, social_subject=subject)

    async with transaction_session(session_factory) as session:
        await delete_account(session, user_id)
    async with session_factory() as session:
        first_deleted_at = (await session.get(User, user_id)).deleted_at

    async with transaction_session(session_factory) as session:
        await delete_account(session, user_id)
    async with session_factory() as session:
        second_deleted_at = (await session.get(User, user_id)).deleted_at

    assert first_deleted_at == second_deleted_at


@pytest.mark.asyncio
async def test_resignup_after_deletion_reuses_user_without_restoring_old_trip(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    """탈퇴한 카카오 계정으로 재가입하면 같은 user_id를 재사용하되 이전 여행은 조회되지 않는다."""
    subject = f"resignup-{uuid.uuid4()}"
    user_id, _trip_id, _fd, _sd, _fr, _sr = await _seed_account(session_factory, social_subject=subject)

    async with transaction_session(session_factory) as session:
        await delete_account(session, user_id)

    new_device = uuid.uuid4()
    ticket = create_opaque_token()
    async with transaction_session(session_factory) as session:
        session.add(
            AuthLoginTransaction(
                transaction_id=ticket.selector,
                state_hash=uuid.uuid4().hex + uuid.uuid4().hex,
                client_device_id=str(new_device),
                platform="ANDROID",
                status="VERIFIED",
                login_ticket_hash=ticket.secret_hash,
                social_subject=subject,
                nickname="재가입 사용자",
                expires_at=datetime.now(UTC) + timedelta(minutes=10),
                ticket_expires_at=datetime.now(UTC) + timedelta(minutes=2),
            )
        )

    async with transaction_session(session_factory) as session:
        result = await exchange_login_ticket(
            session, ticket.encoded, new_device, settings, now=datetime.now(UTC)
        )

    async with session_factory() as session:
        user = await session.get(User, user_id)
        visible_trips = (
            await session.scalars(
                select(Trip).where(Trip.user_id == user_id, Trip.deleted_at.is_(None))
            )
        ).all()

    assert result.is_new_user is False
    assert result.user.user_id == user_id
    assert user.deleted_at is None
    assert visible_trips == []


@pytest.mark.asyncio
async def test_delete_account_rolls_back_completely_on_failure(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """탈퇴 처리 중 하나라도 실패하면 계정과 데이터가 그대로 유지된다."""
    subject = f"delete-rollback-{uuid.uuid4()}"
    user_id, trip_id, *_ = await _seed_account(session_factory, social_subject=subject)

    with pytest.raises(RuntimeError, match="forced failure"):
        async with transaction_session(session_factory) as session:
            await delete_account(session, user_id)
            raise RuntimeError("forced failure")

    async with session_factory() as session:
        user = await session.get(User, user_id)
        trip = await session.get(Trip, trip_id)

    assert user.deleted_at is None
    assert trip.deleted_at is None
