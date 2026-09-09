"""진행 중인 당일 일정의 변수 위험을 주기적으로 평가한다."""

from __future__ import annotations

import asyncio
import logging
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.clients.fcm import FcmClient
from app.core.config import Settings, get_settings
from app.models.notification import Notification
from app.services.detection.evaluator import evaluate_all_active
from app.services.notification.dispatch import NotificationDispatchService

logger = logging.getLogger("gilpick.detection.job")


async def run_variable_detection(
    session_factory: async_sessionmaker[AsyncSession],
    *,
    settings: Settings | None = None,
    interval_seconds: int = 600,
) -> None:
    """평가 실패를 다음 주기와 격리해 반복 실행한다."""
    client = FcmClient(settings or get_settings())
    try:
        while True:
            started_at = asyncio.get_running_loop().time()
            try:
                async with session_factory() as session:
                    async with session.begin():
                        _, created_detection_ids = await evaluate_all_active(session)
                    await _dispatch_place_change(session, created_detection_ids, client)
            except Exception:
                logger.exception("여행 변수 감지 실행 실패")
            elapsed = asyncio.get_running_loop().time() - started_at
            await asyncio.sleep(max(0, interval_seconds - elapsed))
    finally:
        await client.aclose()


async def _dispatch_place_change(
    session: AsyncSession,
    detection_ids: list[uuid.UUID],
    client: FcmClient,
) -> None:
    """감지 cycle 커밋 직후 새 장소 변경 제안 알림을 한 번 즉시 발송한다.

    발송은 감지 transaction 커밋 이후 별도 transaction에서 하고, FCM 실패는
    `send_one` 안에서 흡수되므로 감지 결과를 롤백하지 않는다. 실패·누락 건은
    다음 dispatch tick이 `sent_at IS NULL` 큐에서 다시 집는다.
    """
    if not detection_ids:
        return
    try:
        async with session.begin():
            notifications = list(
                (
                    await session.scalars(
                        select(Notification).where(
                            Notification.detection_id.in_(detection_ids),
                            Notification.sent_at.is_(None),
                        )
                    )
                ).all()
            )
            dispatch = NotificationDispatchService(session, client=client)
            for notification in notifications:
                await dispatch.send_one(notification)
    except Exception:
        logger.exception("장소 변경 제안 알림 즉시 발송 실패")
