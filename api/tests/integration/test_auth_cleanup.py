"""Authentication retention cleanup integration tests."""

import os
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db import transaction_session
from app.jobs.auth_cleanup import cleanup_auth_records
from app.models.auth import AuthLoginTransaction, DeviceSession, User


@pytest.mark.asyncio
async def test_cleanup_deletes_only_records_past_retention() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다")
    engine = create_async_engine(database_url)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    now = datetime.now(UTC)
    old = now - timedelta(days=31)

    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"cleanup-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        expired = DeviceSession(
            user_id=user.user_id,
            client_device_id=str(uuid.uuid4()),
            platform="ANDROID",
            refresh_token_hash="a" * 64,
            refresh_expires_at=old,
            updated_at=old,
        )
        active = DeviceSession(
            user_id=user.user_id,
            client_device_id=str(uuid.uuid4()),
            platform="ANDROID",
            refresh_token_hash="b" * 64,
            refresh_expires_at=now + timedelta(days=1),
            updated_at=old,
        )
        terminal = AuthLoginTransaction(
            state_hash=uuid.uuid4().hex + uuid.uuid4().hex,
            client_device_id=str(uuid.uuid4()),
            platform="ANDROID",
            status="FAILED",
            expires_at=old,
            updated_at=now - timedelta(hours=25),
        )
        recent = AuthLoginTransaction(
            state_hash=uuid.uuid4().hex + uuid.uuid4().hex,
            client_device_id=str(uuid.uuid4()),
            platform="ANDROID",
            status="PENDING",
            expires_at=now + timedelta(minutes=1),
            updated_at=now,
        )
        session.add_all((expired, active, terminal, recent))
        await session.flush()
        expired_id, active_id = expired.session_id, active.session_id
        terminal_id, recent_id = terminal.transaction_id, recent.transaction_id

    async with transaction_session(factory) as session:
        assert await cleanup_auth_records(session, now=now) == (1, 1)

    async with factory() as session:
        assert await session.get(DeviceSession, expired_id) is None
        assert await session.get(AuthLoginTransaction, terminal_id) is None
        assert await session.get(DeviceSession, active_id) is not None
        assert await session.get(AuthLoginTransaction, recent_id) is not None
    await engine.dispose()
