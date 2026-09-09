"""진행 중인 당일 일정의 변수 위험을 주기적으로 평가한다."""

from __future__ import annotations

import asyncio
import logging

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.services.detection.evaluator import evaluate_all_active

logger = logging.getLogger("gilpick.detection.job")


async def run_variable_detection(
    session_factory: async_sessionmaker[AsyncSession], *, interval_seconds: int = 600
) -> None:
    """평가 실패를 다음 주기와 격리해 반복 실행한다."""
    while True:
        started_at = asyncio.get_running_loop().time()
        try:
            async with session_factory() as session:
                async with session.begin():
                    await evaluate_all_active(session)
        except Exception:
            logger.exception("여행 변수 감지 실행 실패")
        elapsed = asyncio.get_running_loop().time() - started_at
        await asyncio.sleep(max(0, interval_seconds - elapsed))
