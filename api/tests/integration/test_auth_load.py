"""Authentication endpoint concurrency and performance smoke tests."""

import asyncio
import os
import time
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import httpx2
import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import Settings, get_settings
from app.core.security import create_opaque_token, parse_opaque_token
from app.db import get_session, transaction_session
from app.main import app
from app.models.auth import DeviceSession, User


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
    """Return deterministic non-secret load-test settings."""
    return Settings(
        _env_file=None,
        database_url="postgresql+asyncpg://user:password@localhost/gilpick",
        jwt_signing_secret="load-test-signing-secret-at-least-32-bytes",
        jwt_issuer="https://api.gilpick.example",
        jwt_audience="gilpick-android",
        kakao_rest_api_key="test-rest-key",
        kakao_client_secret="test-client-secret",
        kakao_redirect_uri="https://api.gilpick.example/api/v1/auth/kakao/callback",
        android_app_link_base_url="https://app.gilpick.example/auth/kakao/complete",
        android_app_link_host="app.gilpick.example",
    )


async def seed_sessions(
    factory: async_sessionmaker[AsyncSession], count: int
) -> list[tuple[str, uuid.UUID]]:
    """Seed independent active sessions and return their request credentials."""
    credentials: list[tuple[str, uuid.UUID]] = []
    async with transaction_session(factory) as session:
        for _ in range(count):
            device_id = uuid.uuid4()
            refresh = create_opaque_token()
            user = User(
                social_provider="KAKAO",
                social_subject=f"load-{uuid.uuid4()}",
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
                    refresh_expires_at=datetime.now(UTC) + timedelta(days=1),
                )
            )
            credentials.append((refresh.encoded, device_id))
    return credentials


@pytest.mark.asyncio
async def test_100_concurrent_independent_refresh_requests_succeed(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    """Verify 100 concurrent JSON requests preserve session data."""
    credentials = await seed_sessions(session_factory, 100)

    async def request_session() -> AsyncIterator[AsyncSession]:
        async with transaction_session(session_factory) as session:
            yield session

    app.dependency_overrides[get_session] = request_session
    app.dependency_overrides[get_settings] = lambda: settings
    try:
        async with httpx2.AsyncClient(
            transport=httpx2.ASGITransport(app=app), base_url="http://test"
        ) as client:
            async def refresh(token: str, device_id: uuid.UUID) -> int:
                response = await client.post(
                    "/api/v1/auth/token/refresh",
                    json={"refreshToken": token, "deviceId": str(device_id)},
                )
                return response.status_code

            results = await asyncio.gather(
                *(refresh(token, device_id) for token, device_id in credentials)
            )
    finally:
        app.dependency_overrides.clear()

    assert set(results) == {200}

    async with session_factory() as session:
        selectors = [parse_opaque_token(token).selector for token, _ in credentials]
        assert await session.scalar(
            select(func.count(DeviceSession.session_id)).where(
                DeviceSession.session_id.in_(selectors)
            )
        ) == 100


@pytest.mark.asyncio
async def test_refresh_endpoint_p95_is_under_500ms(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    """Measure steady-state JSON endpoint latency across 100 valid requests."""
    credentials = await seed_sessions(session_factory, 100)

    async def request_session() -> AsyncIterator[AsyncSession]:
        async with transaction_session(session_factory) as session:
            yield session

    app.dependency_overrides[get_session] = request_session
    app.dependency_overrides[get_settings] = lambda: settings
    latencies: list[float] = []
    try:
        async with httpx2.AsyncClient(
            transport=httpx2.ASGITransport(app=app), base_url="http://test"
        ) as client:
            for token, device_id in credentials:
                started = time.perf_counter()
                response = await client.post(
                    "/api/v1/auth/token/refresh",
                    json={"refreshToken": token, "deviceId": str(device_id)},
                )
                latencies.append(time.perf_counter() - started)
                assert response.status_code == 200
    finally:
        app.dependency_overrides.clear()

    assert sorted(latencies)[94] < 0.5


@pytest.mark.asyncio
async def test_100_competing_refresh_requests_update_exactly_once(
    session_factory: async_sessionmaker[AsyncSession], settings: Settings
) -> None:
    """Verify one shared Refresh Token can be rotated by only one contender."""
    token, device_id = (await seed_sessions(session_factory, 1))[0]

    async def request_session() -> AsyncIterator[AsyncSession]:
        async with transaction_session(session_factory) as session:
            yield session

    app.dependency_overrides[get_session] = request_session
    app.dependency_overrides[get_settings] = lambda: settings
    try:
        async with httpx2.AsyncClient(
            transport=httpx2.ASGITransport(app=app), base_url="http://test"
        ) as client:
            responses = await asyncio.gather(
                *(
                    client.post(
                        "/api/v1/auth/token/refresh",
                        json={"refreshToken": token, "deviceId": str(device_id)},
                    )
                    for _ in range(100)
                )
            )
    finally:
        app.dependency_overrides.clear()

    assert sum(response.status_code == 200 for response in responses) == 1
    assert sum(response.status_code == 401 for response in responses) == 99
