"""ETA 변경 직후 날짜 단위 재평가 계약을 검증한다."""

import uuid
from unittest.mock import AsyncMock

import pytest

from app.services.detection import evaluator


@pytest.mark.asyncio
async def test_reevaluate_day_evaluates_only_requested_day(monkeypatch) -> None:
    day_id = uuid.uuid4()
    session = AsyncMock()

    class Context:
        async def __aenter__(self):
            return session

        async def __aexit__(self, *args):
            return None

    rows = [object()]
    load_rows = AsyncMock(return_value=rows)
    evaluate_rows = AsyncMock(return_value=1)
    invalidate = AsyncMock()
    monkeypatch.setattr(evaluator, "_invalidate_ineligible", invalidate)
    monkeypatch.setattr(evaluator, "_load_eligible_rows", load_rows)
    monkeypatch.setattr(evaluator, "_evaluate_rows", evaluate_rows)

    assert await evaluator.reevaluate_day(lambda: Context(), day_id) == 1
    invalidate.assert_awaited_once_with(session, trip_day_id=day_id)
    load_rows.assert_awaited_once_with(session, trip_day_id=day_id)
    evaluate_rows.assert_awaited_once_with(session, rows)
    session.commit.assert_awaited_once()


@pytest.mark.asyncio
async def test_reevaluate_day_swallows_failure(monkeypatch) -> None:
    class Context:
        async def __aenter__(self):
            raise RuntimeError("database unavailable")

        async def __aexit__(self, *args):
            return None

    monkeypatch.setattr(evaluator.logger, "exception", lambda *args, **kwargs: None)

    assert await evaluator.reevaluate_day(lambda: Context(), uuid.uuid4()) == 0
