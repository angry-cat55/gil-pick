"""Authentication retention cleanup."""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, delete, or_
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.models.auth import AuthLoginTransaction, DeviceSession

logger = logging.getLogger("gilpick.auth.cleanup")


async def cleanup_auth_records(session: AsyncSession, *, now: datetime | None = None) -> tuple[int, int]:
    """보존 기한이 지난 transaction과 비활성 session만 삭제한다."""
    clock = now or datetime.now(UTC)
    transactions = await session.execute(
        delete(AuthLoginTransaction).where(
            AuthLoginTransaction.updated_at < clock - timedelta(hours=24),
            or_(
                AuthLoginTransaction.status.in_(("CONSUMED", "FAILED", "EXPIRED")),
                AuthLoginTransaction.expires_at <= clock,
            ),
        )
    )
    sessions = await session.execute(
        delete(DeviceSession).where(
            DeviceSession.updated_at < clock - timedelta(days=30),
            or_(DeviceSession.revoked_at.is_not(None), DeviceSession.refresh_expires_at <= clock),
        )
    )
    return transactions.rowcount or 0, sessions.rowcount or 0


async def run_auth_cleanup(session_factory: async_sessionmaker[AsyncSession], *, interval_seconds: int = 3600) -> None:
    """Application lifespan 동안 cleanup을 반복하고 실패는 다음 실행으로 넘긴다."""
    while True:
        try:
            async with session_factory() as session:
                async with session.begin():
                    await cleanup_auth_records(session)
        except Exception:
            logger.exception("인증 cleanup 실행 실패")
        await asyncio.sleep(interval_seconds)
