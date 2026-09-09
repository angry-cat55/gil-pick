"""알림 dispatch 반복 job."""

from __future__ import annotations

import asyncio
import logging

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.clients.fcm import FcmClient
from app.core.config import Settings
from app.services.notification.dispatch import NotificationDispatchService

logger = logging.getLogger("gilpick.notification.job")


async def run_notification_dispatch(session_factory: async_sessionmaker[AsyncSession], *, settings: Settings, interval_seconds: int = 30) -> None:
    """cycle 실패를 다음 실행과 격리하며 알림을 전달한다."""
    client = FcmClient(settings)
    try:
        while True:
            try:
                async with session_factory() as session:
                    async with session.begin():
                        await NotificationDispatchService(session, client=client).tick()
            except Exception:
                logger.exception("알림 dispatch 실행 실패")
            await asyncio.sleep(interval_seconds)
    finally:
        await client.aclose()
