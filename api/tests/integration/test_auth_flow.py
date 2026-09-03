"""Login ticket exchange integration tests against PostgreSQL."""

import asyncio
import logging
import os
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import event, func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import Settings
from app.core.security import create_opaque_token, parse_opaque_token
from app.core.logging import request_id_context
from app.db import transaction_session
from app.models.auth import AuthLoginTransaction, DeviceSession, User
from app.services.auth import (
    AuthServiceError,
    InvalidLoginTicketError,
    exchange_login_ticket,
    logout_device_session,
    rotate_refresh_token,
)


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


async def verified_ticket(
    factory: async_sessionmaker[AsyncSession],
    *,
    social_subject: str,
    device_id: uuid.UUID,
    nickname: str | None = "길픽 사용자",
) -> str:
    """Seed one verified, unconsumed login ticket."""
    ticket = create_opaque_token()
    async with transaction_session(factory) as session:
        session.add(
            AuthLoginTransaction(
                transaction_id=ticket.selector,
                state_hash=uuid.uuid4().hex + uuid.uuid4().hex,
                client_device_id=str(device_id),
                platform="ANDROID",
                status="VERIFIED",
                login_ticket_hash=ticket.secret_hash,
                social_subject=social_subject,
                nickname=nickname,
                profile_image_url="https://example.com/profile.png",
                expires_at=datetime.now(UTC) + timedelta(minutes=10),
                ticket_expires_at=datetime.now(UTC) + timedelta(minutes=2),
            )
        )
    return ticket.encoded


async def active_session(
    factory: async_sessionmaker[AsyncSession],
    *,
    device_id: uuid.UUID,
    expires_at: datetime | None = None,
    user_id: uuid.UUID | None = None,
) -> tuple[str, uuid.UUID]:
    """Seed one active device session and return its Refresh Token."""
    refresh = create_opaque_token()
    async with transaction_session(factory) as session:
        user = await session.get(User, user_id) if user_id else None
        if user is None:
            user = User(
                social_provider="KAKAO",
                social_subject=f"session-{uuid.uuid4()}",
            )
            session.add(user)
            await session.flush()
        session.add(
            DeviceSession(
                session_id=refresh.selector,
                user_id=user.user_id,
                client_device_id=str(device_id),
                platform="ANDROID",
                refresh_token_hash=refresh.secret_hash,
                refresh_expires_at=expires_at
                or datetime.now(UTC) + timedelta(days=1),
            )
        )
    return refresh.encoded, user.user_id


@pytest.mark.asyncio
async def test_exchange_creates_new_user_and_device_session(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    device_id = uuid.uuid4()
    subject = f"new-{uuid.uuid4()}"
    ticket = await verified_ticket(
        session_factory, social_subject=subject, device_id=device_id
    )

    async with transaction_session(session_factory) as session:
        result = await exchange_login_ticket(
            session, ticket, device_id, settings, now=datetime.now(UTC)
        )

    async with session_factory() as session:
        user = await session.scalar(select(User).where(User.social_subject == subject))
        device_session = await session.scalar(
            select(DeviceSession).where(DeviceSession.user_id == user.user_id)
        )
        transaction = await session.get(
            AuthLoginTransaction, parse_opaque_token(ticket).selector
        )

    assert result.is_new_user is True
    assert device_session.client_device_id == str(device_id)
    assert device_session.refresh_token_hash == parse_opaque_token(result.refresh_token).secret_hash
    assert transaction.status == "CONSUMED"
    assert transaction.login_ticket_hash is None
    assert transaction.social_subject is None


@pytest.mark.asyncio
async def test_exchange_reuses_existing_user_without_erasing_nullable_profile(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    device_id = uuid.uuid4()
    subject = f"existing-{uuid.uuid4()}"
    async with transaction_session(session_factory) as session:
        existing = User(
            social_provider="KAKAO",
            social_subject=subject,
            nickname="기존 닉네임",
            profile_image_url="https://example.com/existing.png",
        )
        session.add(existing)
        await session.flush()
        existing_user_id = existing.user_id
    ticket = await verified_ticket(
        session_factory, social_subject=subject, device_id=device_id, nickname=None
    )

    async with transaction_session(session_factory) as session:
        result = await exchange_login_ticket(
            session, ticket, device_id, settings, now=datetime.now(UTC)
        )

    async with session_factory() as session:
        users = (
            await session.scalars(select(User).where(User.social_subject == subject))
        ).all()

    assert result.is_new_user is False
    assert [user.user_id for user in users] == [existing_user_id]
    assert users[0].nickname == "기존 닉네임"


@pytest.mark.asyncio
async def test_exchange_adds_second_device_without_replacing_first(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    subject = f"multi-device-{uuid.uuid4()}"
    first_device, second_device = uuid.uuid4(), uuid.uuid4()

    for device_id in (first_device, second_device):
        ticket = await verified_ticket(
            session_factory, social_subject=subject, device_id=device_id
        )
        async with transaction_session(session_factory) as session:
            await exchange_login_ticket(
                session, ticket, device_id, settings, now=datetime.now(UTC)
            )

    async with session_factory() as session:
        sessions = (
            await session.scalars(
                select(DeviceSession)
                .join(User)
                .where(User.social_subject == subject)
            )
        ).all()

    assert {item.client_device_id for item in sessions} == {
        str(first_device),
        str(second_device),
    }
    assert all(item.revoked_at is None for item in sessions)


@pytest.mark.asyncio
async def test_ticket_is_consumed_by_exactly_one_concurrent_exchange(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    device_id = uuid.uuid4()
    subject = f"single-consume-{uuid.uuid4()}"
    ticket = await verified_ticket(
        session_factory, social_subject=subject, device_id=device_id
    )

    async def exchange() -> object:
        async with transaction_session(session_factory) as session:
            return await exchange_login_ticket(
                session, ticket, device_id, settings, now=datetime.now(UTC)
            )

    outcomes = await asyncio.gather(exchange(), exchange(), return_exceptions=True)

    assert sum(not isinstance(item, Exception) for item in outcomes) == 1
    assert sum(isinstance(item, InvalidLoginTicketError) for item in outcomes) == 1
    async with session_factory() as session:
        assert await session.scalar(
            select(func.count(DeviceSession.session_id))
            .join(User)
            .where(User.social_subject == subject)
        ) == 1


@pytest.mark.asyncio
async def test_concurrent_first_login_upserts_one_user_and_two_sessions(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    subject = f"concurrent-user-{uuid.uuid4()}"
    device_ids = (uuid.uuid4(), uuid.uuid4())
    tickets = await asyncio.gather(
        *(verified_ticket(session_factory, social_subject=subject, device_id=device_id) for device_id in device_ids)
    )

    async def exchange(ticket: str, device_id: uuid.UUID) -> object:
        async with transaction_session(session_factory) as session:
            return await exchange_login_ticket(session, ticket, device_id, settings)

    results = await asyncio.gather(
        *(exchange(ticket, device_id) for ticket, device_id in zip(tickets, device_ids))
    )

    async with session_factory() as session:
        user_count = await session.scalar(select(func.count(User.user_id)).where(User.social_subject == subject))
        session_count = await session.scalar(
            select(func.count(DeviceSession.session_id)).join(User).where(User.social_subject == subject)
        )

    assert user_count == 1
    assert session_count == 2
    assert sorted(result.is_new_user for result in results) == [False, True]


@pytest.mark.asyncio
async def test_exchange_rolls_back_user_session_and_ticket_on_partial_failure(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    device_id = uuid.uuid4()
    subject = f"rollback-{uuid.uuid4()}"
    ticket = await verified_ticket(
        session_factory, social_subject=subject, device_id=device_id
    )

    def fail_session_insert(
        _connection: object,
        _cursor: object,
        statement: str,
        _parameters: object,
        _context: object,
        _executemany: bool,
    ) -> None:
        if "INSERT INTO device_sessions" in statement:
            raise RuntimeError("forced session insert failure")

    engine = session_factory.kw["bind"].sync_engine
    event.listen(engine, "before_cursor_execute", fail_session_insert)
    try:
        with pytest.raises(RuntimeError, match="forced session insert failure"):
            async with transaction_session(session_factory) as session:
                await exchange_login_ticket(
                    session, ticket, device_id, settings, now=datetime.now(UTC)
                )
    finally:
        event.remove(engine, "before_cursor_execute", fail_session_insert)

    async with session_factory() as session:
        transaction = await session.get(
            AuthLoginTransaction, parse_opaque_token(ticket).selector
        )
        user_count = await session.scalar(
            select(func.count(User.user_id)).where(User.social_subject == subject)
        )
        session_count = await session.scalar(
            select(func.count(DeviceSession.session_id))
            .join(User)
            .where(User.social_subject == subject)
        )

    assert user_count == 0
    assert session_count == 0
    assert transaction.status == "VERIFIED"
    assert transaction.login_ticket_hash == parse_opaque_token(ticket).secret_hash
    assert transaction.consumed_at is None


@pytest.mark.asyncio
async def test_refresh_rotates_hash_and_sets_new_sliding_expiry(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    device_id = uuid.uuid4()
    old_token, _ = await active_session(session_factory, device_id=device_id)
    clock = datetime.now(UTC)

    async with transaction_session(session_factory) as session:
        result = await rotate_refresh_token(
            session, old_token, device_id, settings, now=clock
        )

    async with session_factory() as session:
        stored = await session.get(
            DeviceSession, parse_opaque_token(old_token).selector
        )

    assert result.refresh_token != old_token
    assert stored.refresh_token_hash == parse_opaque_token(
        result.refresh_token
    ).secret_hash
    assert stored.refresh_expires_at == clock + timedelta(days=30)


@pytest.mark.asyncio
async def test_same_refresh_token_concurrency_has_exactly_one_success(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    device_id = uuid.uuid4()
    old_token, _ = await active_session(session_factory, device_id=device_id)

    async def rotate() -> object:
        async with transaction_session(session_factory) as session:
            return await rotate_refresh_token(session, old_token, device_id, settings)

    outcomes = await asyncio.gather(rotate(), rotate(), return_exceptions=True)

    assert sum(not isinstance(item, Exception) for item in outcomes) == 1
    assert sum(
        isinstance(item, AuthServiceError)
        and item.code == "INVALID_REFRESH_TOKEN"
        for item in outcomes
    ) == 1


@pytest.mark.asyncio
async def test_refresh_rejects_expired_and_wrong_device_without_mutation(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    device_id = uuid.uuid4()
    expired, _ = await active_session(
        session_factory,
        device_id=device_id,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    valid, _ = await active_session(session_factory, device_id=device_id)

    async with session_factory() as session:
        before = {
            parsed.selector: (
                (await session.get(DeviceSession, parsed.selector)).refresh_token_hash,
                (await session.get(DeviceSession, parsed.selector)).refresh_expires_at,
                (await session.get(DeviceSession, parsed.selector)).last_seen_at,
            )
            for parsed in map(parse_opaque_token, (expired, valid))
        }

    for token, request_device, code in (
        (expired, device_id, "TOKEN_EXPIRED"),
        (valid, uuid.uuid4(), "DEVICE_MISMATCH"),
    ):
        with pytest.raises(AuthServiceError, match=code) as caught:
            async with transaction_session(session_factory) as session:
                await rotate_refresh_token(
                    session, token, request_device, settings
                )
        assert caught.value.code == code

    async with session_factory() as session:
        for token in (expired, valid):
            parsed = parse_opaque_token(token)
            stored = await session.get(DeviceSession, parsed.selector)
            assert (
                stored.refresh_token_hash,
                stored.refresh_expires_at,
                stored.last_seen_at,
            ) == before[parsed.selector]


@pytest.mark.asyncio
async def test_lost_refresh_response_keeps_new_hash_and_rejects_old_token(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    device_id = uuid.uuid4()
    old_token, _ = await active_session(session_factory, device_id=device_id)
    async with transaction_session(session_factory) as session:
        issued = await rotate_refresh_token(session, old_token, device_id, settings)

    with pytest.raises(AuthServiceError) as caught:
        async with transaction_session(session_factory) as session:
            await rotate_refresh_token(session, old_token, device_id, settings)
    assert caught.value.code == "INVALID_REFRESH_TOKEN"

    async with session_factory() as session:
        stored = await session.get(DeviceSession, parse_opaque_token(old_token).selector)
    assert stored.refresh_token_hash == parse_opaque_token(issued.refresh_token).secret_hash


@pytest.mark.asyncio
async def test_logout_revokes_only_current_device_and_is_idempotent(
    session_factory: async_sessionmaker[AsyncSession]
) -> None:
    first_device, second_device = uuid.uuid4(), uuid.uuid4()
    first_token, user_id = await active_session(
        session_factory, device_id=first_device
    )
    second_token, _ = await active_session(
        session_factory, device_id=second_device, user_id=user_id
    )

    for _ in range(2):
        async with transaction_session(session_factory) as session:
            await logout_device_session(session, first_token, first_device)

    async with session_factory() as session:
        first = await session.get(
            DeviceSession, parse_opaque_token(first_token).selector
        )
        second = await session.get(
            DeviceSession, parse_opaque_token(second_token).selector
        )

    assert first.revoked_at is not None
    assert second.revoked_at is None


@pytest.mark.asyncio
async def test_logout_rejects_wrong_device_and_hash_without_revoking(
    session_factory: async_sessionmaker[AsyncSession]
) -> None:
    device_id = uuid.uuid4()
    token, _ = await active_session(session_factory, device_id=device_id)
    parsed = parse_opaque_token(token)
    wrong_hash_token = create_opaque_token(parsed.selector).encoded

    for candidate, request_device, code in (
        (token, uuid.uuid4(), "DEVICE_MISMATCH"),
        (wrong_hash_token, device_id, "INVALID_REFRESH_TOKEN"),
    ):
        with pytest.raises(AuthServiceError) as caught:
            async with transaction_session(session_factory) as session:
                await logout_device_session(session, candidate, request_device)
        assert caught.value.code == code

    async with session_factory() as session:
        stored = await session.get(DeviceSession, parsed.selector)
    assert stored.revoked_at is None


@pytest.mark.asyncio
async def test_logout_rejects_expired_token_without_mutation(
    session_factory: async_sessionmaker[AsyncSession]
) -> None:
    device_id = uuid.uuid4()
    token, _ = await active_session(
        session_factory,
        device_id=device_id,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )

    with pytest.raises(AuthServiceError) as caught:
        async with transaction_session(session_factory) as session:
            await logout_device_session(session, token, device_id)
    assert caught.value.code == "INVALID_REFRESH_TOKEN"

    async with session_factory() as session:
        stored = await session.get(DeviceSession, parse_opaque_token(token).selector)
    assert stored.revoked_at is None


@pytest.mark.asyncio
async def test_refresh_and_logout_logs_correlate_without_raw_tokens(
    session_factory: async_sessionmaker[AsyncSession],
    settings: Settings,
    caplog,
) -> None:
    device_id = uuid.uuid4()
    old_token, _ = await active_session(session_factory, device_id=device_id)
    request_id = str(uuid.uuid4())
    context_token = request_id_context.set(request_id)
    try:
        with caplog.at_level(logging.INFO, logger="gilpick.auth"):
            async with transaction_session(session_factory) as session:
                rotated = await rotate_refresh_token(
                    session, old_token, device_id, settings
                )
            async with transaction_session(session_factory) as session:
                await logout_device_session(
                    session, rotated.refresh_token, device_id
                )
    finally:
        request_id_context.reset(context_token)

    messages = "\n".join(record.getMessage() for record in caplog.records)
    assert request_id in messages
    assert "REFRESH_TOKEN" in messages
    assert "LOGOUT" in messages
    assert old_token not in messages
    assert rotated.refresh_token not in messages
