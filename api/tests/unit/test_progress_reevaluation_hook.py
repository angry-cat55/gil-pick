"""진행 API의 commit 이후 재평가 연결을 검증한다."""

import uuid
from datetime import date
from unittest.mock import AsyncMock

import pytest

from app.api.v1 import progress


@pytest.mark.asyncio
async def test_reevaluation_hook_passes_resolved_day_id(monkeypatch) -> None:
    day_id = uuid.uuid4()
    session = AsyncMock()
    session.scalar.return_value = day_id

    class Factory:
        def __call__(self):
            return self

        async def __aenter__(self):
            return session

        async def __aexit__(self, *args):
            return None

    factory = Factory()
    reevaluate = AsyncMock()
    monkeypatch.setattr(progress, "reevaluate_day", reevaluate)

    await progress._reevaluate_progress_day(
        factory, trip_id=uuid.uuid4(), visit_date=date(2026, 9, 9)
    )

    reevaluate.assert_awaited_once_with(factory, day_id)


@pytest.mark.asyncio
async def test_reevaluation_hook_does_not_break_progress_response(monkeypatch) -> None:
    class Factory:
        def __call__(self):
            return self

        async def __aenter__(self):
            raise RuntimeError("database unavailable")

        async def __aexit__(self, *args):
            return None

    monkeypatch.setattr(progress.logger, "exception", lambda *args, **kwargs: None)

    await progress._reevaluate_progress_day(
        Factory(), trip_id=uuid.uuid4(), visit_date=date(2026, 9, 9)
    )
