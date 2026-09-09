"""감지 종료 로직이 두 평가 진입점에 연결되는지 검증한다."""

import uuid
from unittest.mock import AsyncMock

import pytest

from app.services.detection import evaluator


@pytest.mark.asyncio
async def test_periodic_evaluation_invalidates_before_loading(monkeypatch) -> None:
    session = AsyncMock()
    calls = []

    async def invalidate(*args, **kwargs):
        calls.append("invalidate")

    async def load(*args, **kwargs):
        calls.append("load")
        return []

    monkeypatch.setattr(evaluator, "_invalidate_ineligible", invalidate)
    monkeypatch.setattr(evaluator, "_load_eligible_rows", load)
    monkeypatch.setattr(evaluator, "_evaluate_rows", AsyncMock(return_value=0))

    await evaluator.evaluate_all_active(session)

    assert calls == ["invalidate", "load"]


@pytest.mark.asyncio
async def test_day_reevaluation_invalidates_requested_day(monkeypatch) -> None:
    day_id = uuid.uuid4()
    session = AsyncMock()

    class Context:
        async def __aenter__(self): return session
        async def __aexit__(self, *args): return None

    invalidate = AsyncMock()
    monkeypatch.setattr(evaluator, "_invalidate_ineligible", invalidate)
    monkeypatch.setattr(evaluator, "_load_eligible_rows", AsyncMock(return_value=[]))
    monkeypatch.setattr(evaluator, "_evaluate_rows", AsyncMock(return_value=0))

    await evaluator.reevaluate_day(lambda: Context(), day_id)

    invalidate.assert_awaited_once_with(session, trip_day_id=day_id)
